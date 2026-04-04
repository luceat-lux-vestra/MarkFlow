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
import com.sun.net.httpserver.HttpServer

class MarkFlowEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    private val browser = JBCefBrowser()
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
    private var lastActivationSettingsPushAtMs = 0L

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
                if (action != "update") {
                    return@addHandler JBCefJSQuery.Response("Ignored")
                }

                val newContent = json["content"]?.takeIf { it.isJsonPrimitive }?.asString ?: ""

                // Keep optional UI state from web payload when available.
                lastKnownScrollTop = json["scrollTop"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                    ?.asInt ?: lastKnownScrollTop
                lastKnownCursorOffset = json["cursorOffset"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                    ?.asInt ?: lastKnownCursorOffset
                lastKnownSelectionStart = json["selectionStart"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                    ?.asInt ?: lastKnownSelectionStart
                lastKnownSelectionEnd = json["selectionEnd"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                    ?.asInt ?: lastKnownSelectionEnd

                ApplicationManager.getApplication().invokeLater {
                    WriteCommandAction.runWriteCommandAction(project) {
                        if (document != null && document.text != newContent) {
                            isUpdatingFromWeb = true
                            try {
                                document.setText(newContent)
                            } finally {
                                isUpdatingFromWeb = false
                            }
                         }
                     }
                 }
                 JBCefJSQuery.Response("Success")
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
                    // JS 문자열 이스케이프 (중요: 줄바꿈, 따옴표 처리)
                    val escapedText = newText.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "")

                    val script = "if (window.updateFromIntelliJ) { window.updateFromIntelliJ(\"$escapedText\"); }"
                    browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
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
                val currentText = document?.text?.replace("\\", "\\\\")
                    ?.replace("\"", "\\\"")
                    ?.replace("\n", "\\n")
                    ?.replace("\r", "") ?: ""
                val runtimeSettingsJson = buildRuntimeSettingsJson()
                LOG.warn("MARKFLOW_DIAG bridge:inject runtimeSettings=${runtimeSettingsJson.take(240)}")

                val injectJs = """
                    window.intelliJ_initialMarkdown = "$currentText";
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
                        (function syncInitialMarkdown(attempt) {
                            if (typeof window.updateFromIntelliJ === 'function') {
                                window.updateFromIntelliJ("$currentText");
                                if (typeof window.markflowLog === 'function') {
                                    window.markflowLog('bridge:initialMarkdown:applied');
                                }
                                return;
                            }
                            if (attempt < 20) {
                                setTimeout(function() {
                                    syncInitialMarkdown(attempt + 1);
                                }, 50);
                                return;
                            }
                            if (typeof window.markflowLog === 'function') {
                                window.markflowLog('bridge:initialMarkdown:timeout');
                            }
                        })(0);
                        (function syncInitialSettings(attempt) {
                            if (typeof window.applyMarkFlowSettingsFromIntelliJ === 'function') {
                                window.applyMarkFlowSettingsFromIntelliJ(window.intelliJ_markFlowSettings || {});
                                if (typeof window.markflowLog === 'function') {
                                    window.markflowLog('bridge:initialSettings:applied');
                                }
                                return;
                            }
                            if (attempt < 20) {
                                setTimeout(function() {
                                    syncInitialSettings(attempt + 1);
                                }, 50);
                                return;
                            }
                            if (typeof window.markflowLog === 'function') {
                                window.markflowLog('bridge:initialSettings:timeout');
                            }
                        })(0);
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

    private fun buildRuntimeSettingsJson(): String {
        return try {
            val settings = MarkFlowSettingsService.getInstance().runtimeSettings()
            Gson().toJson(settings)
        } catch (ex: Exception) {
            LOG.warn("Failed to serialize runtime settings to JSON: ${ex.message}", ex)
            "{}"
        }
    }

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
                    val bytes = Files.readAllBytes(target)
                    val contentType = Files.probeContentType(target)
                        ?: URLConnection.guessContentTypeFromName(target.fileName.toString())
                        ?: "application/octet-stream"
                    exchange.responseHeaders["Content-Type"] = contentType
                    exchange.responseHeaders["Cache-Control"] = "no-cache"
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { output -> output.write(bytes) }
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
            } catch (ex: Exception) {
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
        val runtimeSettingsJson = buildRuntimeSettingsJson()
        LOG.warn("MARKFLOW_DIAG settings:push id=$pushId file=${file.path} payload=$runtimeSettingsJson")
        val script = """
            (function syncRuntimeSettings(attempt) {
                window.intelliJ_markFlowSettings = $runtimeSettingsJson;
                if (typeof window.applyMarkFlowSettingsFromIntelliJ === 'function') {
                    window.applyMarkFlowSettingsFromIntelliJ(window.intelliJ_markFlowSettings);
                    if (typeof window.markflowLog === 'function') {
                        window.markflowLog('bridge:runtimeSettings:applied:$pushId');
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
                    window.markflowLog('bridge:runtimeSettings:timeout:$pushId');
                }
            })(0);
        """.trimIndent()
        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
    }

    private fun applyPendingRuntimeSettings() {
        if (!webViewLoaded || !pendingRuntimeSettingsPush) return

        val forceReload = pendingRuntimeSettingsForceReload
        pendingRuntimeSettingsPush = false
        pendingRuntimeSettingsForceReload = false
        LOG.warn("MARKFLOW_DIAG settings:flushPending file=${file.path} forceReload=$forceReload")
        applyRuntimeSettingsToWebview(forceReload)
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

    private data class MarkFlowEditorState(
        val version: Int = CURRENT_VERSION,
        val scrollTop: Int = 0,
        val cursorOffset: Int = -1,
        val selectionStart: Int = -1,
        val selectionEnd: Int = -1
    ) : FileEditorState {
        override fun canBeMergedWith(otherState: FileEditorState, level: FileEditorStateLevel): Boolean {
            if (otherState !is MarkFlowEditorState) return false
            if (otherState.version != version) return false

            return when (level) {
                FileEditorStateLevel.NAVIGATION -> true
                else -> otherState == this
            }
        }

        companion object {
            const val CURRENT_VERSION = 1
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

        private val settingsPushSequence = AtomicInteger(0)
        private const val ACTIVATION_SETTINGS_REAPPLY_THROTTLE_MS = 300L
    }
}
