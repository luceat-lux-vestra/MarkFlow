package com.algorist.markflow

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.concurrency.AppExecutorUtil
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
import java.util.LinkedHashSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarFile
import javax.swing.KeyStroke
import javax.swing.JPanel

@Service(Service.Level.PROJECT)
class MarkFlowSharedBrowserService(@Suppress("UNUSED_PARAMETER") _project: Project) : Disposable {

    private val gson = Gson()
    private val lifecycleLock = Any()
    private val leaseById = linkedMapOf<Int, BrowserLease>()
    private val editorToLeaseId = mutableMapOf<MarkFlowEditor, Int>()
    private val idleLeaseIds = LinkedHashSet<Int>()
    private val openEditors = mutableSetOf<MarkFlowEditor>()

    private var sharedResourcesAcquired = false
    private val leaseSequence = AtomicInteger(0)
    private val sessionSequence = AtomicInteger(0)
    private val evictionTask: ScheduledFuture<*>

    @Volatile
    private var disposed = false

    init {
        synchronized(serviceLock) {
            activeServices.add(this)
        }

        try {
            acquireSharedWebviewPort()
        } catch (ex: Throwable) {
            LOG.error("MARKFLOW_UI failed to acquire shared web resources: ${ex.message}", ex)
        }

        evictionTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            {
                val app = ApplicationManager.getApplication()
                app.invokeLater {
                    if (!disposed) {
                        evictIdleLeases()
                    }
                }
            },
            EVICTION_PERIOD_MS,
            EVICTION_PERIOD_MS,
            TimeUnit.MILLISECONDS
        )
    }

    fun preWarm() {
        if (disposed) return
        val lease = synchronized(lifecycleLock) {
            idleLeaseIds.firstOrNull()?.let { leaseById[it] } ?: createLeaseLocked().also { created ->
                idleLeaseIds.add(created.id)
            }
        }

        ensureLeaseLoaded(lease)
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
        detach(editor, null)
    }

    fun attach(editor: MarkFlowEditor, host: JPanel) {
        if (disposed || editor.isDisposedEditor()) return

        val hadExistingLease = synchronized(lifecycleLock) {
            editorToLeaseId.containsKey(editor)
        }

        val lease = synchronized(lifecycleLock) {
            val existing = editorToLeaseId[editor]?.let { leaseById[it] }
            existing ?: acquireLeaseLocked(editor)
        }
        if (lease == null) {
            LOG.warn("MARKFLOW_UI attach skipped: lease pool reached max size for ${editor.getFile().path}")
            return
        }

        logLeaseEvent(
            event = if (hadExistingLease) "lease_attach_reuse" else "lease_attach_acquire",
            lease = lease,
            editor = editor,
            note = "hostHash=${host.hashCode()}"
        )

        // Single browser component cannot belong to multiple containers, so we reattach on focus changes.
        lease.attachedHost?.takeIf { it !== host }?.let { oldHost ->
            oldHost.remove(lease.browser.component)
            oldHost.revalidate()
            oldHost.repaint()
            logLeaseEvent(
                event = "lease_host_reattach",
                lease = lease,
                editor = editor,
                note = "oldHostHash=${oldHost.hashCode()}, newHostHash=${host.hashCode()}"
            )
        }

        if (lease.browser.component.parent !== host) {
            host.remove(lease.browser.component)
            host.add(lease.browser.component)
            host.revalidate()
            host.repaint()
        }

        lease.attachedEditor = editor
        lease.attachedHost = host
        lease.isEditorActive = false
        lease.sessionId = nextSessionId(lease.id)
        lease.lastUsedAtMs = System.currentTimeMillis()
        logLeaseEvent(event = "lease_attached", lease = lease, editor = editor)

        ensureLeaseLoaded(lease)
        if (lease.webViewLoaded) {
            // Re-inject bridge for reused lease — onLoadEnd won't fire again
            injectBridgeAndBootstrap(lease)
            syncLeaseWithEditor(lease, pushSettings = true)
        }
    }

    fun detach(editor: MarkFlowEditor, host: JPanel?) {
        if (disposed) return
        val lease = synchronized(lifecycleLock) {
            val leaseId = editorToLeaseId.remove(editor) ?: return
            idleLeaseIds.add(leaseId)
            leaseById[leaseId]
        } ?: return

        logLeaseEvent(
            event = "lease_detach",
            lease = lease,
            editor = editor,
            note = "hostHash=${(host ?: lease.attachedHost)?.hashCode() ?: -1}"
        )

        executeSetActiveFlag(lease, false)
        val currentHost = host ?: lease.attachedHost
        currentHost?.remove(lease.browser.component)
        currentHost?.revalidate()
        currentHost?.repaint()

        lease.attachedEditor = null
        lease.attachedHost = null
        lease.lastUsedAtMs = System.currentTimeMillis()

        // Clean up recovery lease when editor detaches to avoid stale recovery state
        synchronized(recoveryLock) {
            val filePath = editor.getFile().path
            val current = recoveryLeasesByFile[filePath]
            if (current?.leader === editor && current.leaseId == lease.id) {
                recoveryLeasesByFile.remove(filePath)
                LOG.info("MARKFLOW_UI recovery:detach cleaned filePath=$filePath leaseId=${lease.id}")
            }
        }
    }

    fun pushMarkdownFromEditor(editor: MarkFlowEditor, markdown: String) {
        val lease = leaseForEditor(editor) ?: return
        if (!lease.webViewLoaded) return

        val seq = ++lease.intelliJToWebPushSequence
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
        lease.browser.cefBrowser.executeJavaScript(script, lease.browser.cefBrowser.url, 0)
    }

    fun executeForEditor(editor: MarkFlowEditor, script: String): Boolean {
        val lease = leaseForEditor(editor) ?: return false
        if (!lease.webViewLoaded) return false

        lease.browser.cefBrowser.executeJavaScript(script, lease.browser.cefBrowser.url, 0)
        return true
    }

    fun forceRerender(editor: MarkFlowEditor) {
        val lease = leaseForEditor(editor) ?: return
        if (!lease.webViewLoaded) {
            lease.pendingForceRerender = true
            return
        }

        lease.browser.cefBrowser.executeJavaScript(
            "window.dispatchEvent(new CustomEvent('markflowForceRerender'));",
            lease.browser.cefBrowser.url,
            0
        )
    }

    fun reapplyRuntimeSettingsForEditor(editor: MarkFlowEditor, forceReload: Boolean) {
        val lease = leaseForEditor(editor) ?: return
        applyRuntimeSettingsToLease(lease, forceReload)
    }

    fun setEditorActive(editor: MarkFlowEditor, active: Boolean) {
        val lease = leaseForEditor(editor) ?: return
        lease.isEditorActive = active
        executeSetActiveFlag(lease, active)
    }

    private fun leaseForEditor(editor: MarkFlowEditor): BrowserLease? {
        if (disposed) return null
        return synchronized(lifecycleLock) {
            val leaseId = editorToLeaseId[editor] ?: return@synchronized null
            leaseById[leaseId]?.takeIf { it.attachedEditor === editor }
        }
    }

    fun getCurrentMarkdown(editor: MarkFlowEditor): String? {
        val lease = leaseForEditor(editor) ?: return null
        if (!lease.webViewLoaded) return null
        val flushQuery = JBCefJSQuery.create(lease.browser as JBCefBrowserBase)
        try {
            val latch = CountDownLatch(1)
            var result: String? = null
            flushQuery.addHandler { request: String ->
                result = request
                latch.countDown()
                JBCefJSQuery.Response("ok")
            }
            val flushCallSnippet = flushQuery.inject("md")
            val script = """
                (function() {
                    var md = (typeof window.getMarkdown === 'function') ? window.getMarkdown() : "";
                    $flushCallSnippet
                })();
            """.trimIndent()
            lease.browser.cefBrowser.executeJavaScript(script, lease.browser.cefBrowser.url, 0)
            val completed = latch.await(2000, TimeUnit.MILLISECONDS)
            if (!completed) {
                LOG.warn("MARKFLOW_SAVE getCurrentMarkdown: timeout for ${editor.file.path}")
                return null
            }
            val markdown = result?.takeIf { it.isNotEmpty() && it != "undefined" && it != "null" }
            return markdown
        } catch (e: Exception) {
            LOG.error("MARKFLOW_SAVE getCurrentMarkdown: failed for ${editor.file.path}: ${e.message}", e)
            return null
        } finally {
            flushQuery.dispose()
        }
    }

    private fun acquireLeaseLocked(editor: MarkFlowEditor): BrowserLease? {
        val idleId = idleLeaseIds.firstOrNull()
        val lease = when {
            idleId != null -> {
                idleLeaseIds.remove(idleId)
                leaseById[idleId] ?: createLeaseLocked()
            }

            leaseById.size >= currentMaxPoolSize() -> {
                null
            }

            else -> {
                createLeaseLocked()
            }
        }
        if (lease == null) {
            return null
        }
        editorToLeaseId[editor] = lease.id
        logLeaseEvent(
            event = if (idleId != null) "lease_borrow_idle" else "lease_borrow_new",
            lease = lease,
            editor = editor
        )
        return lease
    }

    private fun createLeaseLocked(): BrowserLease {
        val browser = JBCefBrowser()
        val jsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        val debugQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        val lease = BrowserLease(
            id = leaseSequence.incrementAndGet(),
            browser = browser,
            jsQuery = jsQuery,
            debugQuery = debugQuery,
            lastUsedAtMs = System.currentTimeMillis()
        )
        setupQueries(lease)
        setupJcefHandlers(lease)
        leaseById[lease.id] = lease
        logLeaseEvent(event = "lease_created", lease = lease)
        return lease
    }

    private fun setupQueries(lease: BrowserLease) {
        lease.jsQuery.addHandler { request: String ->
            try {
                val normalized = request.trim()
                if (normalized.isEmpty() || normalized == "undefined" || normalized == "null") {
                    LOG.warn("MARKFLOW_SAVE setupQueries:DROPPED empty request, lease=${lease.id}")
                    return@addHandler ignoredResponse()
                }

                val parsed = JsonParser.parseString(normalized)
                if (!parsed.isJsonObject) {
                    LOG.warn("MARKFLOW_SAVE setupQueries:DROPPED not JSON, lease=${lease.id}")
                    return@addHandler ignoredResponse()
                }

                val json = parsed.asJsonObject
                val requestSession = json["sessionId"]?.takeIf { it.isJsonPrimitive }?.asString
                if (!requestSession.isNullOrBlank() && requestSession != lease.sessionId) {
                    LOG.warn("MARKFLOW_SAVE setupQueries:DROPPED session mismatch request=$requestSession lease=${lease.sessionId}, lease=${lease.id}")
                    return@addHandler ignoredResponse()
                }

                val action = json["action"]?.takeIf { it.isJsonPrimitive }?.asString
                LOG.info("MARKFLOW_SAVE setupQueries:received action=$action lease=${lease.id} sessionId=$requestSession")
                when (action) {
                    "update" -> {
                        val targetEditor = lease.attachedEditor ?: run {
                            LOG.warn("MARKFLOW_SAVE setupQueries:DROPPED no attachedEditor, lease=${lease.id}")
                            return@addHandler ignoredResponse()
                        }
                        val content = json["content"]?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                        LOG.info("MARKFLOW_SAVE setupQueries:DISPATCHING to applyWebUpdate editor=${targetEditor.file.path} contentLen=${content.length}")
                        targetEditor.applyWebUpdate(
                            content = content,
                            scrollTop = readJsonInt(json, "scrollTop", 0),
                            cursorOffset = readJsonInt(json, "cursorOffset", -1),
                            selectionStart = readJsonInt(json, "selectionStart", -1),
                            selectionEnd = readJsonInt(json, "selectionEnd", -1)
                        )
                        successResponse()
                    }

                    "recovery:request" -> {
                        val targetEditor = lease.attachedEditor ?: return@addHandler ignoredResponse()
                        val reason = json["reason"]?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                        val response = claimRecoveryLease(targetEditor, lease.id, reason)
                        logLeaseEvent(
                            event = "recovery_request",
                            lease = lease,
                            editor = targetEditor,
                            note = "role=${response.role} epoch=${response.epoch}"
                        )
                        jsonResponse(response)
                    }

                    "recovery:complete" -> {
                        val targetEditor = lease.attachedEditor ?: return@addHandler ignoredResponse()
                        val epoch = readJsonInt(json, "epoch", -1)
                        val response = completeRecoveryLease(targetEditor, lease.id, epoch, success = true)
                        logLeaseEvent(
                            event = "recovery_complete",
                            lease = lease,
                            editor = targetEditor,
                            note = "epoch=$epoch status=${response.role}"
                        )
                        jsonResponse(response)
                    }

                    "recovery:failed" -> {
                        val targetEditor = lease.attachedEditor ?: return@addHandler ignoredResponse()
                        val epoch = readJsonInt(json, "epoch", -1)
                        val response = completeRecoveryLease(targetEditor, lease.id, epoch, success = false)
                        logLeaseEvent(
                            event = "recovery_failed",
                            lease = lease,
                            editor = targetEditor,
                            note = "epoch=$epoch status=${response.role}"
                        )
                        jsonResponse(response)
                    }

                    else -> ignoredResponse()
                }
            } catch (ex: Exception) {
                LOG.warn("MARKFLOW_UI JS bridge parse failed: ${ex.message}", ex)
                errorResponse()
            }
        }

        lease.debugQuery.addHandler { request: String ->
            val normalized = request.trim()
            if (normalized.isNotEmpty() && normalized != "undefined") {
                LOG.warn("MARKFLOW_DIAG JS bridge: $normalized")
            }
            okResponse()
        }
    }

    private fun setupJcefHandlers(lease: BrowserLease) {
        lease.browser.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
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
                    if (safeMessage.contains("MARKFLOW_UI") || safeMessage.contains("MARKFLOW_DIAG") || safeMessage.contains("MARKFLOW_SAVE")) {
                        LOG.warn("MARKFLOW_DIAG JS console[$level] $safeSource:$line $safeMessage")
                    } else {
                        LOG.debug("MARKFLOW_UI JS console[$level] $safeSource:$line $safeMessage")
                    }
                }
                return false
            }
        }, lease.browser.cefBrowser)

        lease.browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadStart(cefBrowser: CefBrowser?, frame: CefFrame?, transitionType: CefRequest.TransitionType?) {
                LOG.debug("MARKFLOW_UI lease=${lease.id} JCEF onLoadStart: url=${cefBrowser?.url ?: lease.browser.cefBrowser.url}")
            }

            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                LOG.warn("MARKFLOW_SAVE onLoadEnd: lease=${lease.id} frame=${frame?.isMain} url=${cefBrowser?.url}")
                if (frame != null && !frame.isMain) {
                    LOG.warn("MARKFLOW_SAVE onLoadEnd: SKIPPED subframe, lease=${lease.id}")
                    return
                }
                lease.webViewLoaded = true
                LOG.warn("MARKFLOW_SAVE onLoadEnd: calling injectBridgeAndBootstrap, lease=${lease.id}")
                injectBridgeAndBootstrap(lease)
                lease.attachedEditor?.onActivatedInSharedBrowser()
                applyPendingRuntimeSettings(lease)
                flushPendingForceRerender(lease)
            }

            override fun onLoadError(
                cefBrowser: CefBrowser?,
                frame: CefFrame?,
                errorCode: CefLoadHandler.ErrorCode?,
                errorText: String?,
                failedUrl: String?
            ) {
                LOG.error("MARKFLOW_UI lease=${lease.id} JCEF onLoadError: url=$failedUrl code=$errorCode text=$errorText")
            }
        }, lease.browser.cefBrowser)
    }

    private fun injectBridgeAndBootstrap(lease: BrowserLease) {
        LOG.warn("MARKFLOW_SAVE injectBridgeAndBootstrap: START lease=${lease.id} editor=${lease.attachedEditor?.file?.path}")
        val editor = lease.attachedEditor
        val markdownLiteral = gson.toJson(editor?.currentMarkdownText().orEmpty())
        val runtimeSettingsJson = buildRuntimeSettingsJsonWithConflict(detectShortcutConflict())
        val initialMarkdownSeq = ++lease.intelliJToWebPushSequence
        val initialSettingsSeq = settingsPushSequence.incrementAndGet()
        val activeLiteral = if (lease.isEditorActive) "true" else "false"
        val sessionLiteral = gson.toJson(lease.sessionId)
        val cefQueryBridgeCall = lease.jsQuery.inject(
            "window.__markflowBridgeRequest",
            "window.__markflowBridgeOnSuccess",
            "window.__markflowBridgeOnFailure"
        )
        val debugBridgeCall = lease.debugQuery.inject("window.__markflowDebugRequest")

        val injectJs = """
            window.intelliJ_initialMarkdown = $markdownLiteral;
            window.intelliJ_markFlowSettings = $runtimeSettingsJson;
            window.__markflowSessionId = $sessionLiteral;
            window.cefQuery = function(payload) {
                var source = payload || {};
                window.__markflowBridgeRequest = String(source.request || "");
                window.__markflowBridgeOnSuccess = typeof source.onSuccess === 'function' ? source.onSuccess : function() {};
                window.__markflowBridgeOnFailure = typeof source.onFailure === 'function' ? source.onFailure : function() {};
                $cefQueryBridgeCall
            };
            window.markflowLog = function(message) {
                window.__markflowDebugRequest = String(message || "");
                $debugBridgeCall
            };
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

        lease.browser.cefBrowser.executeJavaScript(injectJs, lease.browser.cefBrowser.url, 0)
    }

    private fun syncLeaseWithEditor(lease: BrowserLease, pushSettings: Boolean) {
        val editor = lease.attachedEditor ?: return
        if (!lease.webViewLoaded) return

        pushMarkdownFromEditor(editor, editor.currentMarkdownText())
        editor.applyPendingStateIfPossible()
        executeSetActiveFlag(lease, lease.isEditorActive)
        if (pushSettings) {
            applyRuntimeSettingsToLease(lease, forceReload = false)
        }
    }

    private fun executeSetActiveFlag(lease: BrowserLease, active: Boolean) {
        lease.isEditorActive = active
        if (!lease.webViewLoaded) return
        val value = if (active) "true" else "false"
        lease.browser.cefBrowser.executeJavaScript(
            "if (window.setMarkFlowEditorActive) { window.setMarkFlowEditorActive($value); }",
            lease.browser.cefBrowser.url,
            0
        )
    }

    private fun applyRuntimeSettingsToLease(lease: BrowserLease, forceReload: Boolean) {
        if (disposed) return
        if (!lease.webViewLoaded || lease.attachedEditor == null) {
            lease.pendingRuntimeSettingsPush = true
            if (forceReload) {
                lease.pendingRuntimeSettingsForceReload = true
            }
            return
        }

        if (forceReload) {
            lease.pendingRuntimeSettingsPush = true
            lease.pendingRuntimeSettingsForceReload = false
            lease.webViewLoaded = false
            try {
                lease.browser.cefBrowser.reload()
            } catch (_: Exception) {
                val fallback = loadWebviewIndexUrl()
                if (fallback != null) {
                    lease.browser.loadURL(fallback)
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
        lease.browser.cefBrowser.executeJavaScript(script, lease.browser.cefBrowser.url, 0)
    }

    private fun applyPendingRuntimeSettings(lease: BrowserLease) {
        if (!lease.pendingRuntimeSettingsPush || !lease.webViewLoaded) return
        val forceReload = lease.pendingRuntimeSettingsForceReload
        lease.pendingRuntimeSettingsPush = false
        lease.pendingRuntimeSettingsForceReload = false
        applyRuntimeSettingsToLease(lease, forceReload)
    }

    private fun flushPendingForceRerender(lease: BrowserLease) {
        if (!lease.pendingForceRerender || !lease.webViewLoaded) return
        lease.pendingForceRerender = false
        lease.browser.cefBrowser.executeJavaScript(
            "window.dispatchEvent(new CustomEvent('markflowForceRerender'));",
            lease.browser.cefBrowser.url,
            0
        )
    }

    private fun ensureLeaseLoaded(lease: BrowserLease) {
        if (lease.webViewLoaded) return
        val currentUrl = lease.browser.cefBrowser.url
        if (!currentUrl.isNullOrBlank() && currentUrl != "about:blank") return

        val bootstrapUrl = loadWebviewIndexUrl()
        if (bootstrapUrl != null) {
            lease.browser.loadURL(bootstrapUrl)
            return
        }
        lease.browser.loadHTML("<html><body><h1>MarkFlow UI Resource Not Found</h1></body></html>")
    }

    private fun nextSessionId(leaseId: Int): String {
        return "lease-${leaseId}-session-${sessionSequence.incrementAndGet()}"
    }

    private fun logLeaseEvent(event: String, lease: BrowserLease, editor: MarkFlowEditor? = null, note: String = "") {
        val editorPath = editor?.getFile()?.path ?: "<none>"
        val session = lease.sessionId.ifBlank { "<none>" }
        val suffix = if (note.isBlank()) "" else " note=$note"
        LOG.info("MARKFLOW_DIAG $event lease=${lease.id} session=$session editor=$editorPath ${poolStats()}$suffix")
    }

    private fun poolStats(): String {
        val totals = synchronized(lifecycleLock) {
            Triple(leaseById.size, idleLeaseIds.size, editorToLeaseId.size)
        }
        val openEditorCount = synchronized(openEditors) { openEditors.size }
        return "pool[total=${totals.first} idle=${totals.second} attached=${totals.third} editorsOpen=$openEditorCount]"
    }

    private fun currentMaxPoolSize(): Int {
        return MarkFlowSettingsService.getInstance().state.maxPoolSize.coerceAtLeast(1)
    }

    private fun currentIdleEvictAfterMs(): Long {
        return MarkFlowSettingsService.getInstance().state.idleEvictAfterMs.coerceAtLeast(1).toLong()
    }

    private fun evictIdleLeases() {
        if (disposed) return

        val now = System.currentTimeMillis()
        val evictable = synchronized(lifecycleLock) {
            val idleLeases = idleLeaseIds.mapNotNull { idleId -> leaseById[idleId] }
            if (idleLeases.size <= MIN_IDLE_LEASE_COUNT) {
                emptyList()
            } else {
                val keepIds = idleLeases
                    .sortedByDescending { it.lastUsedAtMs }
                    .take(MIN_IDLE_LEASE_COUNT)
                    .map { it.id }
                    .toSet()

                idleLeases.filter { lease ->
                    lease.id !in keepIds && now - lease.lastUsedAtMs >= currentIdleEvictAfterMs()
                }
            }
        }

        evictable.forEach { lease ->
            logLeaseEvent(event = "lease_idle_evict", lease = lease)
            disposeLease(lease)
        }
    }

    private fun disposeLease(lease: BrowserLease) {
        logLeaseEvent(event = "lease_dispose", lease = lease, editor = lease.attachedEditor)
        synchronized(lifecycleLock) {
            leaseById.remove(lease.id)
            idleLeaseIds.remove(lease.id)
            lease.attachedEditor?.let { editorToLeaseId.remove(it) }
        }
        lease.attachedHost?.remove(lease.browser.component)
        lease.attachedHost?.revalidate()
        lease.attachedHost?.repaint()
        lease.debugQuery.dispose()
        lease.jsQuery.dispose()
        lease.browser.dispose()

        // Clean up recovery lease on disposal
        lease.attachedEditor?.let { editor ->
            synchronized(recoveryLock) {
                val filePath = editor.getFile().path
                val current = recoveryLeasesByFile[filePath]
                if (current?.leader === editor && current.leaseId == lease.id) {
                    recoveryLeasesByFile.remove(filePath)
                    LOG.info("MARKFLOW_UI recovery:dispose cleaned filePath=$filePath leaseId=${lease.id}")
                }
            }
        }
    }

    private fun reapplyRuntimeSettingsForAllAttachedLeases(forceReload: Boolean) {
        val snapshot = synchronized(lifecycleLock) {
            leaseById.values.filter { it.attachedEditor != null }.toList()
        }
        snapshot.forEach { lease ->
            applyRuntimeSettingsToLease(lease, forceReload)
        }
    }

    private fun claimRecoveryLease(editor: MarkFlowEditor, leaseId: Int, reason: String): RecoveryBridgeResponse {
        synchronized(recoveryLock) {
            val filePath = editor.getFile().path
            val current = recoveryLeasesByFile[filePath]
            val currentLeaderValid = current?.leader?.isDisposedEditor() == false

            if (!currentLeaderValid) {
                val nextEpoch = (current?.epoch ?: 0) + 1
                val updated = RecoveryLease(epoch = nextEpoch, leader = editor, leaseId = leaseId)
                recoveryLeasesByFile[filePath] = updated
                LOG.info("MARKFLOW_UI recovery:claim leader role=$leaseId epoch=$nextEpoch reason=$reason")
                return RecoveryBridgeResponse(role = "leader", epoch = nextEpoch, reason = reason)
            }

            val active = current
            if (active.leader === editor || active.leaseId == leaseId) {
                val nextEpoch = active.epoch
                val updated = RecoveryLease(epoch = nextEpoch, leader = editor, leaseId = leaseId)
                recoveryLeasesByFile[filePath] = updated
                LOG.info("MARKFLOW_UI recovery:claim leader role=$leaseId epoch=$nextEpoch reason=$reason (reused)")
                return RecoveryBridgeResponse(role = "leader", epoch = nextEpoch, reason = reason)
            }

            LOG.info("MARKFLOW_UI recovery:claim follower role=$leaseId epoch=${active.epoch} reason=$reason")
            return RecoveryBridgeResponse(role = "follower", epoch = active.epoch, reason = reason)
        }
    }

    private fun completeRecoveryLease(
        editor: MarkFlowEditor,
        leaseId: Int,
        epoch: Int,
        success: Boolean
    ): RecoveryBridgeResponse {
        val reason = if (success) "complete" else "failed"
        synchronized(recoveryLock) {
            val filePath = editor.getFile().path
            val current = recoveryLeasesByFile[filePath]
            if (current?.leader === editor && current.leaseId == leaseId && current.epoch == epoch) {
                recoveryLeasesByFile.remove(filePath)
                LOG.info("MARKFLOW_UI recovery:complete leaseId=$leaseId epoch=$epoch success=$success")
                return RecoveryBridgeResponse(role = reason, epoch = epoch, reason = reason)
            }

            // Log ignored recovery completions for debugging race conditions
            val logStatus = when {
                current == null -> "noActiveLease"
                current.leader !== editor -> "editorMismatch"
                current.leaseId != leaseId -> "leaseMismatch"
                current.epoch != epoch -> "epochMismatch"
                else -> "unknown"
            }
            LOG.info("MARKFLOW_UI recovery:complete ignored leaseId=$leaseId epoch=$epoch status=$logStatus")
        }
        return RecoveryBridgeResponse(role = "ignored", epoch = epoch, reason = reason)
    }

    private fun ignoredResponse(): JBCefJSQuery.Response = JBCefJSQuery.Response("Ignored")
    private fun successResponse(): JBCefJSQuery.Response = JBCefJSQuery.Response("Success")
    private fun okResponse(): JBCefJSQuery.Response = JBCefJSQuery.Response("OK")
    private fun errorResponse(): JBCefJSQuery.Response = JBCefJSQuery.Response(null, BRIDGE_ERROR_STATUS, "Error parsing request")
    private fun jsonResponse(value: Any): JBCefJSQuery.Response = JBCefJSQuery.Response(gson.toJson(value))

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
        evictionTask.cancel(false)
        LOG.info("MARKFLOW_DIAG pool_dispose_start ${poolStats()}")

        val leases = synchronized(lifecycleLock) {
            val snapshot = leaseById.values.toList()
            leaseById.clear()
            editorToLeaseId.clear()
            idleLeaseIds.clear()
            snapshot
        }
        leases.forEach { lease ->
            lease.attachedHost?.remove(lease.browser.component)
            lease.debugQuery.dispose()
            lease.jsQuery.dispose()
            lease.browser.dispose()
        }
        LOG.info("MARKFLOW_DIAG pool_dispose_done leases=${leases.size}")

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
        private val recoveryLock = Any()
        private val settingsPushSequence = AtomicInteger(0)
        private val activeServices = mutableSetOf<MarkFlowSharedBrowserService>()
        private val recoveryLeasesByFile = mutableMapOf<String, RecoveryLease>()

        private const val BRIDGE_ERROR_STATUS = 500
        private const val MIN_IDLE_LEASE_COUNT = 1
        private const val EVICTION_PERIOD_MS = 30_000L

        private data class BrowserLease(
            val id: Int,
            val browser: JBCefBrowser,
            val jsQuery: JBCefJSQuery,
            val debugQuery: JBCefJSQuery,
            var attachedEditor: MarkFlowEditor? = null,
            var attachedHost: JPanel? = null,
            var webViewLoaded: Boolean = false,
            var pendingRuntimeSettingsPush: Boolean = false,
            var pendingRuntimeSettingsForceReload: Boolean = false,
            var pendingForceRerender: Boolean = false,
            var intelliJToWebPushSequence: Int = 0,
            var isEditorActive: Boolean = false,
            var sessionId: String = "",
            var lastUsedAtMs: Long
        )

        private data class RecoveryLease(
            val epoch: Int,
            val leader: MarkFlowEditor,
            val leaseId: Int
        )

        private data class RecoveryBridgeResponse(
            val role: String,
            val epoch: Int,
            val reason: String
        )

        fun notifyRuntimeSettingsChanged(forceReload: Boolean = false) {
            val app = ApplicationManager.getApplication()
            val snapshot = synchronized(serviceLock) { activeServices.toList() }
            if (snapshot.isEmpty()) return

            val action = {
                snapshot.forEach { service ->
                    if (!service.disposed) {
                        service.reapplyRuntimeSettingsForAllAttachedLeases(forceReload)
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

