package com.algorist.markflow

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest
import java.beans.PropertyChangeListener
import java.io.IOException
import java.net.InetSocketAddress
import java.net.JarURLConnection
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarFile
import javax.swing.JComponent
import javax.swing.KeyStroke
import com.sun.net.httpserver.HttpServer

class MarkFlowEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    private val browser = JBCefBrowser()
    private val gson = Gson()
    private val document: Document? = FileDocumentManager.getInstance().getDocument(file)
    private val jsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val debugQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    // Prevent feedback loops while applying updates received from webview.
    private var isUpdatingFromWeb = false

    // Editor state snapshot fields (Option C: scroll + cursor/selection + versioned state object)
    private var webViewLoaded = false
    private var pendingState: MarkFlowEditorState? = null
    private var lastKnownScrollTop = 0
    private var lastKnownCursorOffset = -1
    private var lastKnownSelectionStart = -1
    private var lastKnownSelectionEnd = -1
    private var sharedResourcesAcquired = false
    private var disposed = false
    private var isEditorActive = true
    private var pendingRuntimeSettingsPush = false
    private var pendingRuntimeSettingsForceReload = false
    private var pendingForceRerender = false
    private var lastActivationSettingsPushAtMs = 0L
    private var pendingWebToDocumentContent: String? = null
    private var webToDocumentApplyScheduled = false
    private var intelliJToWebPushSequence = 0

    init {
        LOG.info("MARKFLOW_UI editor init: ${file.path}")
        registerOpenEditor()

        // 1. [Web -> IntelliJ] 웹 에디터의 변경사항을 IntelliJ 파일에 적용
        jsQuery.addHandler { request: String ->
            try {
                val normalizedRequest = request.trim()
                if (normalizedRequest.isEmpty() || normalizedRequest == "undefined" || normalizedRequest == "null") {
                    return@addHandler JBCefJSQuery.Response("Ignored")
                }

                val parsed = JsonParser.parseString(normalizedRequest)
                if (!parsed.isJsonObject) {
                    LOG.debug("Ignored non-object JS bridge payload for ${file.path}: $normalizedRequest")
                    return@addHandler JBCefJSQuery.Response("Ignored")
                }

                val json = parsed.asJsonObject
                val action = json["action"]?.takeIf { it.isJsonPrimitive }?.asString
                when (action) {
                    "update" -> {
                        val newContent = json["content"]?.takeIf { it.isJsonPrimitive }?.asString ?: ""

                        // Keep optional UI state from web payload when available.
                        lastKnownScrollTop = readJsonInt(json, "scrollTop", lastKnownScrollTop)
                        lastKnownCursorOffset = readJsonInt(json, "cursorOffset", lastKnownCursorOffset)
                        lastKnownSelectionStart = readJsonInt(json, "selectionStart", lastKnownSelectionStart)
                        lastKnownSelectionEnd = readJsonInt(json, "selectionEnd", lastKnownSelectionEnd)

                        scheduleWebToDocumentApply(newContent)
                        JBCefJSQuery.Response("Success")
                    }

                    "recovery:request" -> {
                        val reason = json["reason"]?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                        val response = claimRecoveryLease(this, reason)
                        JBCefJSQuery.Response(gson.toJson(response))
                    }

                    "recovery:complete" -> {
                        val epoch = json["epoch"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                            ?.asInt ?: -1
                        val response = completeRecoveryLease(this, epoch, success = true)
                        JBCefJSQuery.Response(gson.toJson(response))
                    }

                    "recovery:failed" -> {
                        val epoch = json["epoch"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                            ?.asInt ?: -1
                        val response = completeRecoveryLease(this, epoch, success = false)
                        JBCefJSQuery.Response(gson.toJson(response))
                    }

                    else -> JBCefJSQuery.Response("Ignored")
                }
             } catch (ex: Exception) {
                 LOG.warn("Failed to parse JS bridge request for ${file.path}: ${ex.message}", ex)
                 JBCefJSQuery.Response(null, 500, "Error parsing request")
             }
        }

        // 2. [IntelliJ -> Web] 외부(Git, 타 에디터)에서 파일이 변경되었을 때 웹 에디터 리렌더링
        document?.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                // 웹에서 쏜 데이터로 인해 변경된 것이 아닐 때만 웹으로 다시 쏨
                if (!isUpdatingFromWeb) {
                    val newText = event.document.text
                    pushMarkdownToWebview(newText)
                }
            }
        }, this) // FileEditor(this)가 Dispose 될 때 리스너 자동 해제

        debugQuery.addHandler { request: String ->
            val normalized = request.trim()
            if (normalized.isNotEmpty() && normalized != "undefined") {
                LOG.warn("MARKFLOW_DIAG JS bridge: $normalized")
            }
            JBCefJSQuery.Response("OK")
        }

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
                    if (safeMessage.contains("MARKFLOW_UI")) {
                        LOG.warn("MARKFLOW_DIAG JS console[$level] $safeSource:$line $safeMessage")
                    } else {
                        LOG.debug("MARKFLOW_UI JS console[$level] $safeSource:$line $safeMessage")
                    }
                }
                return false
            }
        }, browser.cefBrowser)

        // 3. JCEF 로드 완료 시 JS 브릿지 주입 및 초기 마크다운 설정
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadStart(cefBrowser: CefBrowser?, frame: CefFrame?, transitionType: CefRequest.TransitionType?) {
                LOG.debug("MARKFLOW_UI JCEF onLoadStart: url=${cefBrowser?.url ?: browser.cefBrowser.url}")
            }

            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame != null && !frame.isMain) return
                LOG.debug("MARKFLOW_UI JCEF onLoadEnd: url=${cefBrowser?.url ?: browser.cefBrowser.url}, status=$httpStatusCode")
                val currentText = readInitialMarkdownText()
                val currentTextLiteral = toJsStringLiteral(currentText)
                val conflictDetected = detectShortcutConflict()
                val runtimeSettingsJson = buildRuntimeSettingsJsonWithConflict(conflictDetected)
                val initialMarkdownSeq = ++intelliJToWebPushSequence
                val initialSettingsSeq = settingsPushSequence.incrementAndGet()
                LOG.warn("MARKFLOW_DIAG bridge:inject runtimeSettings=${runtimeSettingsJson.take(240)}")

                val injectJs = """
                    window.intelliJ_initialMarkdown = $currentTextLiteral;
                    window.intelliJ_markFlowSettings = $runtimeSettingsJson;
                    ${jsQuery.inject("window.cefQuery")}
                    ${debugQuery.inject("window.markflowLog")}
                    (function() {
                        if (window.__markflowGlobalErrorBridgeInstalled) {
                            return;
                        }
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
                        if (typeof window.markflowLog === 'function') {
                            window.markflowLog('bridge:injected');
                        }
                        (function syncInitialMarkdown(seq, payload) {
                            window.__markflowIntelliJUpdateSeq = Math.max(window.__markflowIntelliJUpdateSeq || 0, seq);
                            (function applyMarkdown(attempt) {
                                if ((window.__markflowIntelliJUpdateSeq || 0) !== seq) {
                                    if (typeof window.markflowLog === 'function') {
                                        window.markflowLog('bridge:initialMarkdown:dropped:' + seq);
                                    }
                                    return;
                                }
                                if (typeof window.updateFromIntelliJ === 'function') {
                                    window.updateFromIntelliJ(payload);
                                    if (typeof window.markflowLog === 'function') {
                                        window.markflowLog('bridge:initialMarkdown:applied:' + seq);
                                    }
                                    return;
                                }
                                if (attempt < 20) {
                                    setTimeout(function() {
                                        applyMarkdown(attempt + 1);
                                    }, 50);
                                    return;
                                }
                                if (typeof window.markflowLog === 'function') {
                                    window.markflowLog('bridge:initialMarkdown:timeout:' + seq);
                                }
                            })(0);
                        })($initialMarkdownSeq, $currentTextLiteral);
                        (function syncInitialSettings(seq, payload) {
                            window.__markflowRuntimeSettingsSeq = Math.max(window.__markflowRuntimeSettingsSeq || 0, seq);
                            (function applySettings(attempt) {
                                if ((window.__markflowRuntimeSettingsSeq || 0) !== seq) {
                                    if (typeof window.markflowLog === 'function') {
                                        window.markflowLog('bridge:initialSettings:dropped:' + seq);
                                    }
                                    return;
                                }
                                window.intelliJ_markFlowSettings = payload;
                                if (typeof window.applyMarkFlowSettingsFromIntelliJ === 'function') {
                                    window.applyMarkFlowSettingsFromIntelliJ(window.intelliJ_markFlowSettings || {});
                                    if (typeof window.markflowLog === 'function') {
                                        window.markflowLog('bridge:initialSettings:applied:' + seq);
                                    }
                                    return;
                                }
                                if (attempt < 20) {
                                    setTimeout(function() {
                                        applySettings(attempt + 1);
                                    }, 50);
                                    return;
                                }
                                if (typeof window.markflowLog === 'function') {
                                    window.markflowLog('bridge:initialSettings:timeout:' + seq);
                                }
                            })(0);
                        })($initialSettingsSeq, $runtimeSettingsJson);
                        if (typeof window.setMarkFlowEditorActive === 'function') {
                          window.setMarkFlowEditorActive(${if (isEditorActive) "true" else "false"});
                        }
                    })();
                """.trimIndent()

                cefBrowser?.executeJavaScript(injectJs, browser.cefBrowser.url, 0)
                webViewLoaded = true
                LOG.info("MARKFLOW_UI webViewLoaded=true for ${file.path}")
                applyPendingState()
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
                LOG.error("MARKFLOW_UI JCEF onLoadError: url=$failedUrl code=$errorCode text=$errorText")
            }
        }, browser.cefBrowser)

        try {
            val webviewIndexUrl = loadWebviewIndexUrl()
            if (webviewIndexUrl != null) {
                LOG.info("Loading MarkFlow webview from extracted file URL: $webviewIndexUrl")
                browser.loadURL(webviewIndexUrl)
            } else {
                LOG.error("Could not resolve a loadable MarkFlow webview index.html")
                browser.loadHTML("<html><body><h1>MarkFlow UI Resource Not Found</h1></body></html>")
            }
        } catch (ex: Throwable) {
            rollbackSharedResourcesOnInitFailure(ex)
            throw ex
        }
    }

    private fun buildRuntimeSettingsJsonWithConflict(conflictDetected: Boolean): String {
        return try {
            val settings = MarkFlowSettingsService.getInstance().runtimeSettings(conflictDetected)
            gson.toJson(settings)
        } catch (ex: Exception) {
            LOG.warn("Failed to serialize runtime settings to JSON: ${ex.message}", ex)
            "{}"
        }
    }

    private fun detectShortcutConflict(): Boolean {
        return try {
            val settings = MarkFlowSettingsService.getInstance().state
            if (!settings.forceRerenderShortcutEnabled) {
                return false
            }

            val keymapManager = KeymapManager.getInstance()
            val activeKeymap = keymapManager.activeKeymap
            val ctrlShiftR = KeyStroke.getKeyStroke("ctrl shift R")
            val metaShiftR = KeyStroke.getKeyStroke("meta shift R")
            val candidates = buildList {
                if (ctrlShiftR != null) add(ctrlShiftR)
                if (SystemInfo.isMac && metaShiftR != null) add(metaShiftR)
            }

            candidates.any { shortcut ->
                activeKeymap
                    .getActionIds(shortcut)
                    .any { actionId -> actionId != MarkFlowForceRerenderAction.ACTION_ID }
            }
        } catch (ex: Exception) {
            LOG.warn("Failed to detect shortcut conflict: ${ex.message}", ex)
            false
        }
    }


    private fun readInitialMarkdownText(): String {
        document?.text?.let { return it }

        return try {
            val bytes = file.contentsToByteArray()
            String(bytes, file.charset)
        } catch (ex: Exception) {
            LOG.warn("MARKFLOW_UI failed to read initial markdown from VFS for ${file.path}: ${ex.message}")
            ""
        }
    }

    private fun toJsStringLiteral(value: String): String = gson.toJson(value)

    private fun loadWebviewIndexUrl(): String? {
        return try {
            LOG.debug("MARKFLOW_UI loadWebviewIndexUrl: start for ${file.path}")
            val port = acquireSharedWebviewPort() ?: return null
            val indexUrl = "http://127.0.0.1:$port/index.html"
            LOG.debug("MARKFLOW_UI loadWebviewIndexUrl: resolved=$indexUrl")
            indexUrl
        } catch (ex: Exception) {
            LOG.error("MARKFLOW_UI loadWebviewIndexUrl failed: ${ex.message}", ex)
            rollbackSharedResourcesOnInitFailure(ex)
            null
        }
    }

    private fun rollbackSharedResourcesOnInitFailure(cause: Throwable) {
        if (!sharedResourcesAcquired) return
        LOG.warn("MARKFLOW_UI init failed; rolling back shared resources for ${file.path}: ${cause.message}")
        releaseSharedWebviewResources()
    }

    private fun acquireSharedWebviewPort(): Int? {
        return synchronized(sharedLifecycleLock) {
            val extractedRoot = ensureExtractedWebviewRootLocked() ?: return null
            val port = ensureWebviewHttpServerLocked(extractedRoot) ?: return null
            sharedWebviewRefCount++
            sharedResourcesAcquired = true
            LOG.info("MARKFLOW_UI shared webview acquired refs=$sharedWebviewRefCount port=$port")
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
                    val contentLength = Files.size(target)
                    exchange.responseHeaders["Content-Type"] = contentType
                    exchange.responseHeaders["Cache-Control"] = "no-cache"
                    exchange.sendResponseHeaders(200, contentLength)
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
            LOG.error("MARKFLOW_UI failed to start webview HTTP server: ${ex.message}", ex)
            null
        }
    }

    private fun ensureExtractedWebviewRootLocked(): Path? {
        extractedWebviewRoot?.let { return it }

        val resource = MarkFlowEditor::class.java.classLoader.getResource(WEBVIEW_ENTRY_RESOURCE)
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
                LOG.info("MARKFLOW_UI using classpath webview root: $root")
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

        val tempRoot = Files.createTempDirectory("markflow-webview-")
        LOG.info("MARKFLOW_UI extractWebviewRoot: jar=$pluginJarPath")
        JarFile(pluginJarPath.toFile()).use { jar ->
            val entries = jar.entries()
            var copied = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.startsWith("webview/")) continue

                val target = tempRoot.resolve(entry.name.removePrefix("webview/"))
                target.parent?.let(Files::createDirectories)
                jar.getInputStream(entry).use { input ->
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                    copied++
                }
            }
            LOG.info("MARKFLOW_UI extractWebviewRoot: extracted=$copied to $tempRoot")
        }

        extractedWebviewRoot = tempRoot
        extractedWebviewRootIsTemp = true
        return tempRoot
    }

    private fun releaseSharedWebviewResources() {
        if (!sharedResourcesAcquired) {
            LOG.debug("MARKFLOW_UI release skipped (not acquired): ${file.path}")
            return
        }

        synchronized(sharedLifecycleLock) {
            decrementSharedRefCountLocked()

            LOG.info("MARKFLOW_UI shared webview released refs=$sharedWebviewRefCount")
            if (sharedWebviewRefCount > 0) {
                sharedResourcesAcquired = false
                return
            }

            LOG.info("MARKFLOW_UI shared webview reached zero refs; cleaning global resources")

            stopWebviewServerLocked()

            webviewHttpServer = null
            webviewServerPort = null

            cleanupExtractedWebviewRootLocked()

            extractedWebviewRoot = null
            extractedWebviewRootIsTemp = false
            sharedResourcesAcquired = false
        }
    }

    private fun decrementSharedRefCountLocked() {
        if (sharedWebviewRefCount > 0) {
            sharedWebviewRefCount--
            return
        }
        LOG.warn("MARKFLOW_UI shared refcount underflow guard hit for ${file.path}")
    }

    private fun stopWebviewServerLocked() {
        webviewHttpServer?.let { server ->
            try {
                server.stop(0)
                LOG.info("MARKFLOW_UI webview server stopped")
            } catch (ex: Exception) {
                LOG.warn("MARKFLOW_UI failed to stop webview HTTP server: ${ex.message}", ex)
            }
        }
    }

    private fun cleanupExtractedWebviewRootLocked() {
        if (!extractedWebviewRootIsTemp) {
            LOG.debug("MARKFLOW_UI skip cleanup for non-temp webview root: $extractedWebviewRoot")
            return
        }

        extractedWebviewRoot?.let { root ->
            try {
                val deleted = root.toFile().deleteRecursively()
                if (!deleted && Files.exists(root)) {
                    LOG.warn("MARKFLOW_UI failed to cleanup extracted webview root: $root")
                } else {
                    LOG.info("MARKFLOW_UI cleaned extracted webview root: $root")
                }
            } catch (ex: Exception) {
                LOG.warn("MARKFLOW_UI failed to cleanup extracted webview root $root: ${ex.message}", ex)
            }
        }

        extractedWebviewRootIsTemp = false
    }

    private fun applyRuntimeSettingsToWebview(forceReload: Boolean) {
        LOG.warn(
            "MARKFLOW_DIAG settings:applyAttempt file=${file.path} loaded=$webViewLoaded disposed=$disposed forceReload=$forceReload"
        )
        if (disposed) {
            LOG.warn("MARKFLOW_DIAG settings:skip disposed file=${file.path}")
            return
        }
        if (!webViewLoaded) {
            pendingRuntimeSettingsPush = true
            if (forceReload) {
                pendingRuntimeSettingsForceReload = true
            }
            LOG.warn(
                "MARKFLOW_DIAG settings:queued file=${file.path} loaded=$webViewLoaded forceReload=$forceReload"
            )

            // If this editor is active but webview is not loaded yet, kick off load immediately.
            if (isEditorActive) {
                val currentUrl = browser.cefBrowser.url
                if (currentUrl.isNullOrBlank() || currentUrl == "about:blank") {
                    val bootstrapUrl = loadWebviewIndexUrl()
                    if (bootstrapUrl != null) {
                        LOG.warn("MARKFLOW_DIAG settings:bootstrapLoad file=${file.path} url=$bootstrapUrl")
                        browser.loadURL(bootstrapUrl)
                    }
                } else {
                    LOG.warn("MARKFLOW_DIAG settings:bootstrapReload file=${file.path} url=$currentUrl")
                    browser.loadURL(currentUrl)
                }
            }
            return
        }

        val pushId = settingsPushSequence.incrementAndGet()
        val shouldReloadNow = forceReload && webViewLoaded && isEditorActive
        if (forceReload && !shouldReloadNow) {
            LOG.warn(
                "MARKFLOW_DIAG settings:skipReload file=${file.path} loaded=$webViewLoaded active=$isEditorActive"
            )
        }

        if (shouldReloadNow) {
            LOG.warn("MARKFLOW_DIAG settings:forceReload id=$pushId file=${file.path}")
            // Ensure a normal settings push runs after reload completes.
            pendingRuntimeSettingsPush = true
            pendingRuntimeSettingsForceReload = false
            webViewLoaded = false
            try {
                browser.cefBrowser.reload()
                LOG.warn("MARKFLOW_DIAG settings:forceReloadTriggered id=$pushId file=${file.path}")
            } catch (_: Exception) {
                LOG.warn("MARKFLOW_DIAG settings:forceReload failed id=$pushId file=${file.path}")
                val bootstrapUrl = loadWebviewIndexUrl()
                if (bootstrapUrl != null) {
                    LOG.warn("MARKFLOW_DIAG settings:forceReloadFallback id=$pushId url=$bootstrapUrl")
                    browser.loadURL(bootstrapUrl)
                } else {
                    LOG.warn("MARKFLOW_DIAG settings:forceReloadFallbackFailed id=$pushId")
                }
            }
            return
        }
        val conflictDetected = detectShortcutConflict()
        val runtimeSettingsJson = buildRuntimeSettingsJsonWithConflict(conflictDetected)
        LOG.warn("MARKFLOW_DIAG settings:push id=$pushId file=${file.path} payload=$runtimeSettingsJson")
        val script = """
            (function syncRuntimeSettingsPush(seq, payload) {
                window.__markflowRuntimeSettingsSeq = Math.max(window.__markflowRuntimeSettingsSeq || 0, seq);
                (function syncRuntimeSettings(attempt) {
                    if ((window.__markflowRuntimeSettingsSeq || 0) !== seq) {
                        if (typeof window.markflowLog === 'function') {
                            window.markflowLog('bridge:runtimeSettings:dropped:' + seq);
                        }
                        return;
                    }

                    window.intelliJ_markFlowSettings = payload;
                    if (typeof window.applyMarkFlowSettingsFromIntelliJ === 'function') {
                        window.applyMarkFlowSettingsFromIntelliJ(window.intelliJ_markFlowSettings);
                        if (typeof window.markflowLog === 'function') {
                            window.markflowLog('bridge:runtimeSettings:applied:' + seq);
                        }
                        return;
                    }

                    if (attempt < 20) {
                        setTimeout(function() {
                            syncRuntimeSettings(attempt + 1);
                        }, 50);
                        return;
                    }

                    if (typeof window.markflowLog === 'function') {
                        window.markflowLog('bridge:runtimeSettings:timeout:' + seq);
                    }
                })(0);
            })($pushId, $runtimeSettingsJson);
        """.trimIndent()
        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
    }

    private fun scheduleWebToDocumentApply(newContent: String) {
        pendingWebToDocumentContent = newContent
        if (webToDocumentApplyScheduled) {
            return
        }

        webToDocumentApplyScheduled = true
        ApplicationManager.getApplication().invokeLater {
            webToDocumentApplyScheduled = false
            if (disposed) {
                return@invokeLater
            }

            val target = pendingWebToDocumentContent ?: return@invokeLater
            pendingWebToDocumentContent = null

            WriteCommandAction.runWriteCommandAction(project) {
                if (document != null && document.text != target) {
                    isUpdatingFromWeb = true
                    try {
                        document.setText(target)
                    } finally {
                        isUpdatingFromWeb = false
                    }
                }
            }
        }
    }

    private fun pushMarkdownToWebview(markdown: String) {
        if (disposed) {
            return
        }

        val seq = ++intelliJToWebPushSequence
        val markdownLiteral = toJsStringLiteral(markdown)
        val script = """
            (function syncIntelliJMarkdown(seq, payload) {
                window.__markflowIntelliJUpdateSeq = Math.max(window.__markflowIntelliJUpdateSeq || 0, seq);
                (function applyMarkdown(attempt) {
                    if ((window.__markflowIntelliJUpdateSeq || 0) !== seq) {
                        if (typeof window.markflowLog === 'function') {
                            window.markflowLog('bridge:updateFromIntelliJ:dropped:' + seq);
                        }
                        return;
                    }

                    if (typeof window.updateFromIntelliJ === 'function') {
                        window.updateFromIntelliJ(payload);
                        if (typeof window.markflowLog === 'function') {
                            window.markflowLog('bridge:updateFromIntelliJ:applied:' + seq);
                        }
                        return;
                    }

                    if (attempt < 20) {
                        setTimeout(function() {
                            applyMarkdown(attempt + 1);
                        }, 25);
                        return;
                    }

                    if (typeof window.markflowLog === 'function') {
                        window.markflowLog('bridge:updateFromIntelliJ:timeout:' + seq);
                    }
                })(0);
            })($seq, $markdownLiteral);
        """.trimIndent()

        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
        LOG.debug("MARKFLOW_DIAG bridge:updateFromIntelliJ queued seq=$seq file=${file.path}")
    }

    private fun applyPendingRuntimeSettings() {
        if (!webViewLoaded || !pendingRuntimeSettingsPush) return

        val forceReload = pendingRuntimeSettingsForceReload
        pendingRuntimeSettingsPush = false
        pendingRuntimeSettingsForceReload = false
        LOG.warn("MARKFLOW_DIAG settings:flushPending file=${file.path} forceReload=$forceReload")
        applyRuntimeSettingsToWebview(forceReload)
    }

    private fun resyncAfterRecovery(epoch: Int) {
        if (disposed) {
            return
        }

        val markdown = document?.text ?: ""
        LOG.warn("MARKFLOW_DIAG recovery:resync file=${file.path} epoch=$epoch loaded=$webViewLoaded")
        pushMarkdownToWebview(markdown)
        applyPendingState()
    }

    private fun readJsonInt(json: com.google.gson.JsonObject, key: String, fallback: Int): Int {
        val element = json[key]
        if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            return fallback
        }
        return element.asInt
    }

    private fun registerOpenEditor() {
        synchronized(openEditorsLock) {
            openEditors.add(this)
        }
    }

    private fun unregisterOpenEditor() {
        synchronized(openEditorsLock) {
            openEditors.remove(this)
        }
    }

    override fun getComponent(): JComponent = browser.component
    override fun getPreferredFocusedComponent(): JComponent = browser.component
    override fun getName(): String = "MarkFlow Editor"
    override fun getFile(): VirtualFile = file
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = true

    override fun getState(level: FileEditorStateLevel): FileEditorState {
        val viewTop = browser.component.visibleRect.y.coerceAtLeast(0)
        val scrollTop = maxOf(lastKnownScrollTop, viewTop)
        return MarkFlowEditorState(
            version = MarkFlowEditorState.CURRENT_VERSION,
            scrollTop = scrollTop,
            cursorOffset = lastKnownCursorOffset,
            selectionStart = lastKnownSelectionStart,
            selectionEnd = lastKnownSelectionEnd
        )
    }

    override fun setState(state: FileEditorState) {
        val incoming = state as? MarkFlowEditorState ?: return
        if (incoming.version != MarkFlowEditorState.CURRENT_VERSION) {
            LOG.warn(
                "State version mismatch for ${file.path}. expected=${MarkFlowEditorState.CURRENT_VERSION}, actual=${incoming.version}"
            )
        }
        pendingState = incoming
        applyPendingState()
    }

    private fun applyPendingState() {
        val state = pendingState ?: return
        if (!webViewLoaded) {
            return
        }

        val safeScrollTop = state.scrollTop.coerceAtLeast(0)
        val safeCursorOffset = state.cursorOffset.coerceAtLeast(-1)
        val safeSelectionStart = state.selectionStart.coerceAtLeast(-1)
        val safeSelectionEnd = state.selectionEnd.coerceAtLeast(-1)

        val script = """
            (function() {
              var state = {
                version: ${state.version},
                scrollTop: $safeScrollTop,
                cursorOffset: $safeCursorOffset,
                selectionStart: $safeSelectionStart,
                selectionEnd: $safeSelectionEnd
              };
              if (typeof window.applyEditorStateFromIntelliJ === 'function') {
                window.applyEditorStateFromIntelliJ(state);
              } else {
                window.scrollTo(0, state.scrollTop || 0);
              }
            })();
        """.trimIndent()

        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
        pendingState = null
    }

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        // State change events are not emitted yet; editor is currently pull-based.
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        // State change events are not emitted yet; editor is currently pull-based.
    }

    override fun selectNotify() {
        isEditorActive = true
        if (webViewLoaded) {
            browser.cefBrowser.executeJavaScript(
                "if (window.setMarkFlowEditorActive) { window.setMarkFlowEditorActive(true); }",
                browser.cefBrowser.url,
                0
            )
        }

        val now = System.currentTimeMillis()
        if (now - lastActivationSettingsPushAtMs >= ACTIVATION_SETTINGS_REAPPLY_THROTTLE_MS) {
            lastActivationSettingsPushAtMs = now
            LOG.warn("MARKFLOW_DIAG settings:reapplyOnSelect file=${file.path} loaded=$webViewLoaded")
            applyRuntimeSettingsToWebview(forceReload = false)
        }
    }

    override fun deselectNotify() {
        isEditorActive = false
        if (!webViewLoaded) return
        browser.cefBrowser.executeJavaScript(
            "if (window.setMarkFlowEditorActive) { window.setMarkFlowEditorActive(false); }",
            browser.cefBrowser.url,
            0
        )
    }


    fun forceRerenderPreviews() {
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

    private fun flushPendingForceRerender() {
        if (!pendingForceRerender || !webViewLoaded) return
        pendingForceRerender = false
        forceRerenderPreviews()
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        LOG.info("MARKFLOW_UI dispose start: ${file.path}")

        try {
            debugQuery.dispose()
            jsQuery.dispose()
            browser.dispose()
        } finally {
            unregisterOpenEditor()
            releaseSharedWebviewResources()
            LOG.info("MARKFLOW_UI dispose end: ${file.path}")
        }
    }


    companion object {
        private val LOG = Logger.getInstance(MarkFlowEditor::class.java)
        private const val WEBVIEW_ENTRY_RESOURCE = "webview/index.html"
        @Volatile
        private var extractedWebviewRoot: Path? = null
        @Volatile
        private var extractedWebviewRootIsTemp: Boolean = false
        @Volatile
        private var webviewHttpServer: HttpServer? = null
        @Volatile
        private var webviewServerPort: Int? = null
        @Volatile
        private var sharedWebviewRefCount: Int = 0
        private val sharedLifecycleLock = Any()
        private val openEditorsLock = Any()
        private val openEditors = mutableSetOf<MarkFlowEditor>()
        private val recoveryLock = Any()
        private val recoveryLeasesByFile = mutableMapOf<String, RecoveryLease>()

        fun notifyRuntimeSettingsChanged(forceReload: Boolean = false) {
            val snapshot = synchronized(openEditorsLock) { openEditors.toList() }
            if (snapshot.isEmpty()) return
            LOG.warn("MARKFLOW_DIAG settings:notify editors=${snapshot.size}, forceReload=$forceReload")

            val app = ApplicationManager.getApplication()
            val applyAll = {
                snapshot.forEach { editor ->
                    editor.applyRuntimeSettingsToWebview(forceReload)
                }
            }

            if (app.isDispatchThread) {
                applyAll()
                return
            }

            app.invokeLater(applyAll)
        }

        private fun claimRecoveryLease(editor: MarkFlowEditor, reason: String): RecoveryBridgeResponse {
            synchronized(recoveryLock) {
                val current = recoveryLeasesByFile[editor.file.path]
                val currentLeader = current?.leader
                val currentLeaderValid = currentLeader != null && !currentLeader.disposed

                if (!currentLeaderValid || currentLeader === editor) {
                    val nextEpoch = if (currentLeaderValid) current.epoch else (current?.epoch ?: 0) + 1
                    val lease = RecoveryLease(epoch = nextEpoch, leader = editor)
                    recoveryLeasesByFile[editor.file.path] = lease
                    LOG.warn(
                        "MARKFLOW_DIAG recovery:claim leader file=${editor.file.path} " +
                            "epoch=$nextEpoch reason=$reason"
                    )
                    return RecoveryBridgeResponse(
                        role = "leader",
                        epoch = nextEpoch,
                        filePath = editor.file.path,
                        reason = reason
                    )
                }

                LOG.warn(
                    "MARKFLOW_DIAG recovery:claim follower file=${editor.file.path} " +
                        "epoch=${current.epoch} leader=${currentLeader.file.path} reason=$reason"
                )
                return RecoveryBridgeResponse(
                    role = "follower",
                    epoch = current.epoch,
                    filePath = editor.file.path,
                    reason = reason
                )
            }
        }

        @Suppress("ReturnCount")
        private fun completeRecoveryLease(
            editor: MarkFlowEditor,
            epoch: Int,
            success: Boolean
        ): RecoveryBridgeResponse {
            val reason = if (success) "complete" else "failed"
            var response = RecoveryBridgeResponse(
                role = "ignored",
                epoch = epoch,
                filePath = editor.file.path,
                reason = reason
            )
            var followers: List<MarkFlowEditor> = emptyList()

            synchronized(recoveryLock) {
                val current = recoveryLeasesByFile[editor.file.path]
                if (current?.leader === editor && current.epoch == epoch) {
                    recoveryLeasesByFile.remove(editor.file.path)
                    followers = synchronized(openEditorsLock) {
                        openEditors
                            .asSequence()
                            .filter { it.file.path == editor.file.path && it !== editor && !it.disposed }
                            .toList()
                    }
                    LOG.warn(
                        "MARKFLOW_DIAG recovery:release file=${editor.file.path} " +
                            "epoch=$epoch success=$success followers=${followers.size}"
                    )
                    response = RecoveryBridgeResponse(
                        role = reason,
                        epoch = epoch,
                        filePath = editor.file.path,
                        reason = reason
                    )
                }
            }

            if (success && followers.isNotEmpty()) {
                val app = ApplicationManager.getApplication()
                app.invokeLater {
                    followers.forEach { follower ->
                        follower.resyncAfterRecovery(epoch)
                    }
                }
            }

            return response
        }

        private val settingsPushSequence = AtomicInteger(0)
        private const val ACTIVATION_SETTINGS_REAPPLY_THROTTLE_MS = 300L

        private data class RecoveryLease(
            val epoch: Int,
            val leader: MarkFlowEditor
        )

        private data class RecoveryBridgeResponse(
            val role: String,
            val epoch: Int,
            val filePath: String,
            val reason: String
        )
    }
}
