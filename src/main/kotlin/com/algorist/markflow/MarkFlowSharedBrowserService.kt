package com.algorist.markflow

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.sun.net.httpserver.HttpServer
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest
import java.io.IOException
import java.net.InetSocketAddress
import java.net.JarURLConnection
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarFile
import javax.swing.KeyStroke
import javax.swing.JPanel

@Service(Service.Level.PROJECT)
class MarkFlowSharedBrowserService(@Suppress("UNUSED_PARAMETER") _project: Project) : Disposable {

    private val gson = Gson()
    private val browser = JBCefBrowser()
    private val jsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val debugQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val openEditors = mutableSetOf<MarkFlowEditor>()

    private var sharedResourcesAcquired = false
    private var activeEditor: MarkFlowEditor? = null
    private var attachedHost: JPanel? = null
    private var webViewLoaded = false
    private var pendingRuntimeSettingsPush = false
    private var pendingRuntimeSettingsForceReload = false
    private var pendingForceRerender = false
    private var intelliJToWebPushSequence = 0
    @Volatile
    private var disposed = false

    init {
        synchronized(serviceLock) {
            activeServices.add(this)
        }

        setupQueries()
        setupJcefHandlers()
        try {
            acquireSharedWebviewPort()
        } catch (ex: Throwable) {
            LOG.error("MARKFLOW_UI failed to acquire shared web resources: ${ex.message}", ex)
        }
    }

    fun preWarm() {
        if (disposed || webViewLoaded) return
        val currentUrl = browser.cefBrowser.url
        if (!currentUrl.isNullOrBlank() && currentUrl != "about:blank") return

        val bootstrapUrl = loadWebviewIndexUrl()
        if (bootstrapUrl != null) {
            LOG.info("MARKFLOW_UI pre-warm shared browser: $bootstrapUrl")
            browser.loadURL(bootstrapUrl)
        } else {
            browser.loadHTML("<html><body><h1>MarkFlow UI Resource Not Found</h1></body></html>")
        }
    }

    fun registerEditor(editor: MarkFlowEditor) {
        synchronized(openEditors) {
            openEditors.add(editor)
        }
    }

    fun unregisterEditor(editor: MarkFlowEditor) {
        synchronized(openEditors) {
            openEditors.remove(editor)
        }
        if (activeEditor === editor) {
            detach(editor, attachedHost)
        }
    }

    fun attach(editor: MarkFlowEditor, host: JPanel) {
        if (disposed || editor.isDisposedEditor()) return

        if (activeEditor !== editor) {
            executeSetActiveFlag(false)
        }

        val oldHost = attachedHost
        if (oldHost != null && oldHost !== host) {
            oldHost.remove(browser.component)
            oldHost.revalidate()
            oldHost.repaint()
        }

        if (browser.component.parent !== host) {
            host.remove(browser.component)
            host.add(browser.component)
            host.revalidate()
            host.repaint()
        }

        attachedHost = host
        activeEditor = editor
        preWarm()

        if (webViewLoaded) {
            syncActiveEditorToWebview(pushSettings = true)
        }
    }

    fun detach(editor: MarkFlowEditor, host: JPanel?) {
        if (disposed || activeEditor !== editor) return

        executeSetActiveFlag(false)
        val currentHost = host ?: attachedHost
        currentHost?.remove(browser.component)
        currentHost?.revalidate()
        currentHost?.repaint()

        activeEditor = null
        attachedHost = null
    }

    fun pushMarkdownFromEditor(editor: MarkFlowEditor, markdown: String) {
        if (disposed || activeEditor !== editor || !webViewLoaded) return

        val seq = ++intelliJToWebPushSequence
        val markdownLiteral = gson.toJson(markdown)
        val script = """
            (function syncIntelliJMarkdown(seq, payload) {
                window.__markflowIntelliJUpdateSeq = Math.max(window.__markflowIntelliJUpdateSeq || 0, seq);
                (function applyMarkdown(attempt) {
                    if ((window.__markflowIntelliJUpdateSeq || 0) !== seq) {
                        return;
                    }
                    if (typeof window.updateFromIntelliJ === 'function') {
                        window.updateFromIntelliJ(payload);
                        return;
                    }
                    if (attempt < 20) {
                        setTimeout(function() { applyMarkdown(attempt + 1); }, 25);
                    }
                })(0);
            })($seq, $markdownLiteral);
        """.trimIndent()
        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
    }

    fun executeForEditor(editor: MarkFlowEditor, script: String): Boolean {
        if (disposed || activeEditor !== editor || !webViewLoaded) return false
        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
        return true
    }

    fun forceRerender(editor: MarkFlowEditor) {
        if (disposed || activeEditor !== editor) return
        if (!webViewLoaded) {
            pendingForceRerender = true
            return
        }

        browser.cefBrowser.executeJavaScript(
            "window.dispatchEvent(new CustomEvent('markflowForceRerender'));",
            browser.cefBrowser.url,
            0
        )
    }

    fun reapplyRuntimeSettingsForActiveEditor(forceReload: Boolean) {
        applyRuntimeSettingsToWebview(forceReload)
    }

    private fun setupQueries() {
        jsQuery.addHandler { request: String ->
            try {
                val normalized = request.trim()
                if (normalized.isEmpty() || normalized == "undefined" || normalized == "null") {
                    return@addHandler JBCefJSQuery.Response("Ignored")
                }

                val parsed = JsonParser.parseString(normalized)
                if (!parsed.isJsonObject) {
                    return@addHandler JBCefJSQuery.Response("Ignored")
                }

                val json = parsed.asJsonObject
                val action = json["action"]?.takeIf { it.isJsonPrimitive }?.asString
                when (action) {
                    "update" -> {
                        val targetEditor = activeEditor ?: return@addHandler JBCefJSQuery.Response("Ignored")
                        val content = json["content"]?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                        targetEditor.applyWebUpdate(
                            content = content,
                            scrollTop = readJsonInt(json, "scrollTop", 0),
                            cursorOffset = readJsonInt(json, "cursorOffset", -1),
                            selectionStart = readJsonInt(json, "selectionStart", -1),
                            selectionEnd = readJsonInt(json, "selectionEnd", -1)
                        )
                        JBCefJSQuery.Response("Success")
                    }

                    else -> JBCefJSQuery.Response("Ignored")
                }
            } catch (ex: Exception) {
                LOG.warn("MARKFLOW_UI JS bridge parse failed: ${ex.message}", ex)
                JBCefJSQuery.Response(null, 500, "Error parsing request")
            }
        }

        debugQuery.addHandler { request: String ->
            val normalized = request.trim()
            if (normalized.isNotEmpty() && normalized != "undefined") {
                LOG.warn("MARKFLOW_DIAG JS bridge: $normalized")
            }
            JBCefJSQuery.Response("OK")
        }
    }

    private fun setupJcefHandlers() {
        browser.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onConsoleMessage(
                cefBrowser: CefBrowser?,
                level: CefSettings.LogSeverity?,
                message: String?,
                source: String?,
                line: Int
            ): Boolean {
                val safeMessage = message?.trim().orEmpty()
                if (safeMessage.isNotEmpty()) {
                    val safeSource = source ?: "<unknown>"
                    if (safeMessage.contains("MARKFLOW_UI") || safeMessage.contains("MARKFLOW_DIAG")) {
                        LOG.warn("MARKFLOW_DIAG JS console[$level] $safeSource:$line $safeMessage")
                    } else {
                        LOG.debug("MARKFLOW_UI JS console[$level] $safeSource:$line $safeMessage")
                    }
                }
                return false
            }
        }, browser.cefBrowser)

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadStart(cefBrowser: CefBrowser?, frame: CefFrame?, transitionType: CefRequest.TransitionType?) {
                LOG.debug("MARKFLOW_UI shared JCEF onLoadStart: url=${cefBrowser?.url ?: browser.cefBrowser.url}")
            }

            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame != null && !frame.isMain) return
                LOG.debug("MARKFLOW_UI shared JCEF onLoadEnd: status=$httpStatusCode")
                webViewLoaded = true
                injectBridgeAndBootstrap()
                activeEditor?.onActivatedInSharedBrowser()
                applyPendingRuntimeSettings()
                flushPendingForceRerender()
            }

            override fun onLoadError(
                cefBrowser: CefBrowser?,
                frame: CefFrame?,
                errorCode: CefLoadHandler.ErrorCode?,
                errorText: String?,
                failedUrl: String?
            ) {
                LOG.error("MARKFLOW_UI shared JCEF onLoadError: url=$failedUrl code=$errorCode text=$errorText")
            }
        }, browser.cefBrowser)
    }

    private fun injectBridgeAndBootstrap() {
        val editor = activeEditor
        val markdownLiteral = gson.toJson(editor?.currentMarkdownText().orEmpty())
        val runtimeSettingsJson = buildRuntimeSettingsJsonWithConflict(detectShortcutConflict())
        val initialMarkdownSeq = ++intelliJToWebPushSequence
        val initialSettingsSeq = settingsPushSequence.incrementAndGet()
        val activeLiteral = if (editor != null) "true" else "false"

        val injectJs = """
            window.intelliJ_initialMarkdown = $markdownLiteral;
            window.intelliJ_markFlowSettings = $runtimeSettingsJson;
            ${jsQuery.inject("window.cefQuery")}
            ${debugQuery.inject("window.markflowLog")}
            (function() {
                if (!window.__markflowGlobalErrorBridgeInstalled) {
                    window.__markflowGlobalErrorBridgeInstalled = true;
                    window.addEventListener('error', function(event) {
                        if (typeof window.markflowLog === 'function') {
                            window.markflowLog('window:error:' + (event.message || 'unknown'));
                        }
                    });
                    window.addEventListener('unhandledrejection', function(event) {
                        var reason = event && event.reason ? String(event.reason) : 'unknown';
                        if (typeof window.markflowLog === 'function') {
                            window.markflowLog('window:unhandledrejection:' + reason);
                        }
                    });
                }
                (function syncInitialMarkdown(seq, payload) {
                    window.__markflowIntelliJUpdateSeq = Math.max(window.__markflowIntelliJUpdateSeq || 0, seq);
                    (function applyMarkdown(attempt) {
                        if ((window.__markflowIntelliJUpdateSeq || 0) !== seq) {
                            return;
                        }
                        if (typeof window.updateFromIntelliJ === 'function') {
                            window.updateFromIntelliJ(payload);
                            return;
                        }
                        if (attempt < 20) {
                            setTimeout(function() { applyMarkdown(attempt + 1); }, 50);
                        }
                    })(0);
                })($initialMarkdownSeq, $markdownLiteral);
                (function syncInitialSettings(seq, payload) {
                    window.__markflowRuntimeSettingsSeq = Math.max(window.__markflowRuntimeSettingsSeq || 0, seq);
                    (function applySettings(attempt) {
                        if ((window.__markflowRuntimeSettingsSeq || 0) !== seq) {
                            return;
                        }
                        window.intelliJ_markFlowSettings = payload;
                        if (typeof window.applyMarkFlowSettingsFromIntelliJ === 'function') {
                            window.applyMarkFlowSettingsFromIntelliJ(payload || {});
                            return;
                        }
                        if (attempt < 20) {
                            setTimeout(function() { applySettings(attempt + 1); }, 50);
                        }
                    })(0);
                })($initialSettingsSeq, $runtimeSettingsJson);
                if (typeof window.setMarkFlowEditorActive === 'function') {
                    window.setMarkFlowEditorActive($activeLiteral);
                }
            })();
        """.trimIndent()

        browser.cefBrowser.executeJavaScript(injectJs, browser.cefBrowser.url, 0)
    }

    private fun syncActiveEditorToWebview(pushSettings: Boolean) {
        val editor = activeEditor ?: return
        if (!webViewLoaded) return

        pushMarkdownFromEditor(editor, editor.currentMarkdownText())
        editor.applyPendingStateIfPossible()
        executeSetActiveFlag(true)
        if (pushSettings) {
            applyRuntimeSettingsToWebview(forceReload = false)
        }
    }

    private fun executeSetActiveFlag(active: Boolean) {
        if (!webViewLoaded) return
        val value = if (active) "true" else "false"
        browser.cefBrowser.executeJavaScript(
            "if (window.setMarkFlowEditorActive) { window.setMarkFlowEditorActive($value); }",
            browser.cefBrowser.url,
            0
        )
    }

    private fun applyRuntimeSettingsToWebview(forceReload: Boolean) {
        if (disposed) return
        if (!webViewLoaded || activeEditor == null) {
            pendingRuntimeSettingsPush = true
            if (forceReload) {
                pendingRuntimeSettingsForceReload = true
            }
            return
        }

        if (forceReload) {
            pendingRuntimeSettingsPush = true
            pendingRuntimeSettingsForceReload = false
            webViewLoaded = false
            try {
                browser.cefBrowser.reload()
            } catch (_: Exception) {
                val fallback = loadWebviewIndexUrl()
                if (fallback != null) {
                    browser.loadURL(fallback)
                }
            }
            return
        }

        val pushId = settingsPushSequence.incrementAndGet()
        val runtimeSettingsJson = buildRuntimeSettingsJsonWithConflict(detectShortcutConflict())
        val script = """
            (function syncRuntimeSettingsPush(seq, payload) {
                window.__markflowRuntimeSettingsSeq = Math.max(window.__markflowRuntimeSettingsSeq || 0, seq);
                (function syncRuntimeSettings(attempt) {
                    if ((window.__markflowRuntimeSettingsSeq || 0) !== seq) {
                        return;
                    }
                    window.intelliJ_markFlowSettings = payload;
                    if (typeof window.applyMarkFlowSettingsFromIntelliJ === 'function') {
                        window.applyMarkFlowSettingsFromIntelliJ(window.intelliJ_markFlowSettings);
                        return;
                    }
                    if (attempt < 20) {
                        setTimeout(function() { syncRuntimeSettings(attempt + 1); }, 50);
                    }
                })(0);
            })($pushId, $runtimeSettingsJson);
        """.trimIndent()
        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
    }

    private fun applyPendingRuntimeSettings() {
        if (!pendingRuntimeSettingsPush || !webViewLoaded) return
        val forceReload = pendingRuntimeSettingsForceReload
        pendingRuntimeSettingsPush = false
        pendingRuntimeSettingsForceReload = false
        applyRuntimeSettingsToWebview(forceReload)
    }

    private fun flushPendingForceRerender() {
        if (!pendingForceRerender || !webViewLoaded) return
        pendingForceRerender = false
        browser.cefBrowser.executeJavaScript(
            "window.dispatchEvent(new CustomEvent('markflowForceRerender'));",
            browser.cefBrowser.url,
            0
        )
    }

    private fun buildRuntimeSettingsJsonWithConflict(conflictDetected: Boolean): String {
        return try {
            val settings = MarkFlowSettingsService.getInstance().runtimeSettings(conflictDetected)
            gson.toJson(settings)
        } catch (ex: Exception) {
            LOG.warn("MARKFLOW_UI failed to serialize runtime settings: ${ex.message}", ex)
            "{}"
        }
    }

    private fun detectShortcutConflict(): Boolean {
        return try {
            val settings = MarkFlowSettingsService.getInstance().state
            if (!settings.forceRerenderShortcutEnabled) {
                return false
            }

            val activeKeymap = KeymapManager.getInstance().activeKeymap
            val ctrlAltShiftR = KeyStroke.getKeyStroke("ctrl alt shift R")
            val metaAltShiftR = KeyStroke.getKeyStroke("meta alt shift R")
            val candidates = buildList {
                if (SystemInfo.isMac) {
                    if (metaAltShiftR != null) add(metaAltShiftR)
                } else {
                    if (ctrlAltShiftR != null) add(ctrlAltShiftR)
                }
            }

            candidates.any { shortcut ->
                activeKeymap
                    .getActionIds(shortcut)
                    .any { actionId -> actionId != MarkFlowForceRerenderAction.ACTION_ID }
            }
        } catch (ex: Exception) {
            LOG.warn("MARKFLOW_UI failed to detect shortcut conflict: ${ex.message}", ex)
            false
        }
    }

    private fun readJsonInt(json: JsonObject, key: String, fallback: Int): Int {
        val element = json[key]
        if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            return fallback
        }
        return element.asInt
    }

    private fun loadWebviewIndexUrl(): String? {
        val port = acquireSharedWebviewPort() ?: return null
        return "http://127.0.0.1:$port/index.html"
    }

    private fun acquireSharedWebviewPort(): Int? {
        return synchronized(sharedLifecycleLock) {
            val extractedRoot = ensureExtractedWebviewRootLocked() ?: return null
            val port = ensureWebviewHttpServerLocked(extractedRoot) ?: return null
            if (!sharedResourcesAcquired) {
                sharedWebviewOwnerCount++
                sharedResourcesAcquired = true
            }
            port
        }
    }

    private fun ensureWebviewHttpServerLocked(root: Path): Int? {
        webviewServerPort?.let { return it }

        return try {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                val requestPath = exchange.requestURI?.path.orEmpty()
                if (requestPath.isEmpty()) {
                    exchange.sendResponseHeaders(404, -1)
                    exchange.close()
                    return@createContext
                }

                val normalized = if (requestPath == "/") "index.html" else requestPath.removePrefix("/")
                val target = root.resolve(normalized).normalize()
                if (!target.startsWith(root) || !Files.exists(target) || Files.isDirectory(target)) {
                    exchange.sendResponseHeaders(404, -1)
                    exchange.close()
                    return@createContext
                }

                try {
                    val contentType = Files.probeContentType(target)
                        ?: URLConnection.guessContentTypeFromName(target.fileName.toString())
                        ?: "application/octet-stream"
                    exchange.responseHeaders["Content-Type"] = contentType
                    exchange.responseHeaders["Cache-Control"] = "no-cache"
                    exchange.sendResponseHeaders(200, Files.size(target))
                    Files.newInputStream(target).use { input ->
                        exchange.responseBody.use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (ioe: IOException) {
                    LOG.warn("MARKFLOW_UI webview server read failed for $target: ${ioe.message}")
                    exchange.sendResponseHeaders(500, -1)
                    exchange.close()
                }
            }
            server.executor = null
            server.start()
            webviewHttpServer = server
            webviewServerPort = server.address.port
            LOG.info("MARKFLOW_UI webview server started on 127.0.0.1:${server.address.port}")
            server.address.port
        } catch (ex: Exception) {
            LOG.error("MARKFLOW_UI failed to start webview server: ${ex.message}", ex)
            null
        }
    }

    private fun ensureExtractedWebviewRootLocked(): Path? {
        extractedWebviewRoot?.let { return it }

        val resource = MarkFlowSharedBrowserService::class.java.classLoader.getResource(WEBVIEW_ENTRY_RESOURCE)
        if (resource == null) {
            LOG.error("MARKFLOW_UI webview resource not found: $WEBVIEW_ENTRY_RESOURCE")
            return null
        }

        if (resource.protocol == "file") {
            return try {
                val indexPath = Path.of(resource.toURI())
                val root = indexPath.parent ?: return null
                extractedWebviewRoot = root
                extractedWebviewRootIsTemp = false
                root
            } catch (ex: Exception) {
                LOG.error("MARKFLOW_UI failed to resolve file webview resource: ${ex.message}", ex)
                null
            }
        }

        if (resource.protocol != "jar") {
            LOG.error("MARKFLOW_UI unsupported webview resource protocol: ${resource.protocol}")
            return null
        }

        val connection = resource.openConnection() as? JarURLConnection
        if (connection == null) {
            LOG.error("MARKFLOW_UI failed to open jar connection for resource: $resource")
            return null
        }

        val pluginJarPath = try {
            Path.of(connection.jarFileURL.toURI())
        } catch (ex: Exception) {
            LOG.error("MARKFLOW_UI failed to resolve jar path for webview resource: ${ex.message}", ex)
            return null
        }

        return try {
            val tempRoot = Files.createTempDirectory("markflow-webview-")
            JarFile(pluginJarPath.toFile()).use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.startsWith("webview/")) continue

                    val target = tempRoot.resolve(entry.name.removePrefix("webview/"))
                    target.parent?.let(Files::createDirectories)
                    jar.getInputStream(entry).use { input ->
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
            extractedWebviewRoot = tempRoot
            extractedWebviewRootIsTemp = true
            tempRoot
        } catch (ex: Exception) {
            LOG.error("MARKFLOW_UI failed to extract webview resources: ${ex.message}", ex)
            null
        }
    }

    private fun releaseSharedWebviewResources() {
        if (!sharedResourcesAcquired) return

        synchronized(sharedLifecycleLock) {
            if (sharedWebviewOwnerCount > 0) {
                sharedWebviewOwnerCount--
            }
            sharedResourcesAcquired = false

            if (sharedWebviewOwnerCount > 0) {
                return
            }

            webviewHttpServer?.let { server ->
                try {
                    server.stop(0)
                } catch (ex: Exception) {
                    LOG.warn("MARKFLOW_UI failed to stop webview server: ${ex.message}", ex)
                }
            }
            webviewHttpServer = null
            webviewServerPort = null

            if (extractedWebviewRootIsTemp) {
                extractedWebviewRoot?.toFile()?.deleteRecursively()
            }
            extractedWebviewRoot = null
            extractedWebviewRootIsTemp = false
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true

        activeEditor?.let { detach(it, attachedHost) }
        debugQuery.dispose()
        jsQuery.dispose()
        browser.dispose()
        releaseSharedWebviewResources()

        synchronized(serviceLock) {
            activeServices.remove(this)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(MarkFlowSharedBrowserService::class.java)
        private const val WEBVIEW_ENTRY_RESOURCE = "webview/index.html"

        @Volatile
        private var extractedWebviewRoot: Path? = null
        @Volatile
        private var extractedWebviewRootIsTemp = false
        @Volatile
        private var webviewHttpServer: HttpServer? = null
        @Volatile
        private var webviewServerPort: Int? = null
        @Volatile
        private var sharedWebviewOwnerCount = 0

        private val serviceLock = Any()
        private val sharedLifecycleLock = Any()
        private val settingsPushSequence = AtomicInteger(0)
        private val activeServices = mutableSetOf<MarkFlowSharedBrowserService>()

        fun notifyRuntimeSettingsChanged(forceReload: Boolean = false) {
            val app = ApplicationManager.getApplication()
            val snapshot = synchronized(serviceLock) { activeServices.toList() }
            if (snapshot.isEmpty()) return

            val action = {
                snapshot.forEach { service ->
                    if (!service.disposed) {
                        service.reapplyRuntimeSettingsForActiveEditor(forceReload)
                    }
                }
            }

            if (app.isDispatchThread) {
                action()
            } else {
                app.invokeLater(action)
            }
        }
    }
}

