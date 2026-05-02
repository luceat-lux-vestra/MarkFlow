package com.algorist.markflow.browser

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.algorist.markflow.MarkFlowDiagnostics
import com.algorist.markflow.editor.MarkFlowEditor
import com.algorist.markflow.settings.MarkFlowSettingsService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.concurrency.AppExecutorUtil
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest
import java.util.LinkedHashSet
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPanel

internal class MarkFlowBrowserLeasePool {
    private val gson = Gson()
    private val lifecycleLock = Any()
    private val leaseById = linkedMapOf<Int, BrowserLease>()
    private val editorToLeaseId = mutableMapOf<MarkFlowEditor, Int>()
    private val idleLeaseIds = LinkedHashSet<Int>()
    private val openEditors = mutableSetOf<MarkFlowEditor>()

    private val leaseSequence = AtomicInteger(0)
    private val sessionSequence = AtomicInteger(0)
    private val evictionTask: ScheduledFuture<*>

    @Volatile
    private var disposed = false

    init {
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

    fun attach(editor: MarkFlowEditor, host: JPanel): Boolean {
        if (disposed || editor.isDisposedEditor()) return false

        val hadExistingLease = synchronized(lifecycleLock) {
            editorToLeaseId.containsKey(editor)
        }

        val attachmentSnapshot = synchronized(lifecycleLock) {
            val existing = editorToLeaseId[editor]?.let { leaseById[it] }
            val lease = existing ?: acquireLeaseLocked(editor)
            lease.let {
                val previousHost = it.attachedHost
                it.attachedEditor = editor
                it.attachedHost = host
                it.sessionId = nextSessionId(it.id)
                it.lastUsedAtMs = System.currentTimeMillis()
                LeaseAttachmentSnapshot(it, previousHost, it.sessionId)
            }
        }
        val lease = attachmentSnapshot.lease
        val sessionId = attachmentSnapshot.sessionId
        val previousHost = attachmentSnapshot.previousHost

        logLeaseEvent(
            event = if (hadExistingLease) "lease_attach_reuse" else "lease_attach_acquire",
            lease = lease,
            editor = editor,
            note = "hostHash=${host.hashCode()}"
        )

        previousHost?.takeIf { it !== host }?.let { oldHost ->
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

        if (!isLeaseSessionCurrent(lease, sessionId)) {
            return false
        }

        if (lease.browser.component.parent !== host) {
            host.remove(lease.browser.component)
            host.add(lease.browser.component)
            host.revalidate()
            host.repaint()
        }

        logLeaseEvent(event = "lease_attached", lease = lease, editor = editor)

        ensureLeaseLoaded(lease)
        if (lease.webViewLoaded && isLeaseSessionCurrent(lease, sessionId)) {
            injectBridgeAndBootstrap(lease)
            syncLeaseWithEditor(lease, pushSettings = true)
        }
        return true
    }

    fun detach(editor: MarkFlowEditor, host: JPanel?) {
        if (disposed) return
        val detachSnapshot = synchronized(lifecycleLock) {
            val leaseId = editorToLeaseId.remove(editor) ?: return
            idleLeaseIds.add(leaseId)
            val lease = leaseById[leaseId] ?: return
            DetachSnapshot(lease, lease.sessionId, host ?: lease.attachedHost)
        }
        val lease = detachSnapshot.lease
        val sessionId = detachSnapshot.sessionId

        logLeaseEvent(
            event = "lease_detach",
            lease = lease,
            editor = editor,
            note = "hostHash=${detachSnapshot.host?.hashCode() ?: -1}"
        )

        executeSetActiveFlag(lease, false, sessionId)
        detachSnapshot.host?.remove(lease.browser.component)
        detachSnapshot.host?.revalidate()
        detachSnapshot.host?.repaint()

        clearLeaseAttachmentIfCurrent(lease, sessionId, editor)

        if (isLeaseSessionCurrent(lease, sessionId)) {
            MarkFlowRecoveryCoordinator.clearRecoveryLease(editor, lease.id)
        }
    }

    fun pushMarkdownFromEditor(editor: MarkFlowEditor, markdown: String) {
        val lease = leaseForEditor(editor) ?: return
        if (!lease.webViewLoaded) return

        val seq = ++lease.intelliJToWebPushSequence
        val markdownLiteral = gson.toJson(markdown)
        val sessionLiteral = gson.toJson(lease.sessionId)
        val script = """
            (function syncIntelliJMarkdown(seq, payload) {
                if (window.__markflowSessionId !== $sessionLiteral) {
                    return;
                }
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

        lease.browser.cefBrowser.executeJavaScript(
            wrapWithSessionGuard(lease.sessionId, script),
            lease.browser.cefBrowser.url,
            0
        )
        return true
    }

    fun reapplyRuntimeSettingsForEditor(editor: MarkFlowEditor, forceReload: Boolean) {
        val lease = leaseForEditor(editor) ?: return
        applyRuntimeSettingsToLease(lease, forceReload)
    }

    fun setEditorActive(editor: MarkFlowEditor, active: Boolean) {
        val lease = leaseForEditor(editor) ?: return
        executeSetActiveFlag(lease, active, lease.sessionId)
    }

    fun reapplyRuntimeSettingsForAllAttachedLeases(forceReload: Boolean) {
        val snapshot = synchronized(lifecycleLock) {
            leaseById.values.filter { it.attachedEditor != null }.toList()
        }
        snapshot.forEach { lease ->
            applyRuntimeSettingsToLease(lease, forceReload)
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        evictionTask.cancel(false)
        if (MarkFlowDiagnostics.enabled) {
            LOG.info("MARKFLOW_DIAG pool_dispose_start ${poolStats()}")
        }

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
        if (MarkFlowDiagnostics.enabled) {
            LOG.info("MARKFLOW_DIAG pool_dispose_done leases=${leases.size}")
        }
    }

    private fun leaseForEditor(editor: MarkFlowEditor): BrowserLease? {
        if (disposed) return null
        return synchronized(lifecycleLock) {
            val leaseId = editorToLeaseId[editor] ?: return@synchronized null
            leaseById[leaseId]?.takeIf { it.attachedEditor === editor }
        }
    }

    fun hasLease(editor: MarkFlowEditor): Boolean {
        return leaseForEditor(editor) != null
    }

    private fun acquireLeaseLocked(editor: MarkFlowEditor): BrowserLease {
        val idleId = idleLeaseIds.firstOrNull()
        val lease = if (idleId != null) {
            idleLeaseIds.remove(idleId)
            leaseById[idleId] ?: createLeaseLocked()
        } else {
            createLeaseLocked()
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
                    if (MarkFlowDiagnostics.enabled) {
                        LOG.warn("MARKFLOW_SAVE setupQueries:DROPPED empty request, lease=${lease.id}")
                    }
                    return@addHandler ignoredResponse()
                }

                val parsed = JsonParser.parseString(normalized)
                if (!parsed.isJsonObject) {
                    if (MarkFlowDiagnostics.enabled) {
                        LOG.warn("MARKFLOW_SAVE setupQueries:DROPPED not JSON, lease=${lease.id}")
                    }
                    return@addHandler ignoredResponse()
                }

                val json = parsed.asJsonObject
                val requestSession = json["sessionId"]?.takeIf { it.isJsonPrimitive }?.asString
                if (!requestSession.isNullOrBlank() && requestSession != lease.sessionId) {
                    if (MarkFlowDiagnostics.enabled) {
                        LOG.warn("MARKFLOW_SAVE setupQueries:DROPPED session mismatch request=$requestSession lease=${lease.sessionId}, lease=${lease.id}")
                    }
                    return@addHandler ignoredResponse()
                }

                val action = json["action"]?.takeIf { it.isJsonPrimitive }?.asString
                if (MarkFlowDiagnostics.enabled) {
                    LOG.debug("MARKFLOW_SAVE setupQueries:received action=$action lease=${lease.id} sessionId=$requestSession")
                }
                when (action) {
                    "update" -> {
                        val targetEditor = lease.attachedEditor ?: run {
                            if (MarkFlowDiagnostics.enabled) {
                                LOG.warn("MARKFLOW_SAVE setupQueries:DROPPED no attachedEditor, lease=${lease.id}")
                            }
                            return@addHandler ignoredResponse()
                        }
                        val content = json["content"]?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                        if (MarkFlowDiagnostics.enabled) {
                            LOG.debug("MARKFLOW_SAVE setupQueries:DISPATCHING to applyWebUpdate editor=${targetEditor.file.path} contentLen=${content.length}")
                        }
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
                        val response = MarkFlowRecoveryCoordinator.claimRecoveryLease(targetEditor, lease.id, reason)
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
                        val response = MarkFlowRecoveryCoordinator.completeRecoveryLease(targetEditor, lease.id, epoch, success = true)
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
                        val response = MarkFlowRecoveryCoordinator.completeRecoveryLease(targetEditor, lease.id, epoch, success = false)
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
                if (MarkFlowDiagnostics.enabled) {
                    LOG.warn("MARKFLOW_DIAG JS bridge: $normalized")
                }
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
                    if (MarkFlowDiagnostics.enabled || MarkFlowDiagnostics.shouldEmitCriticalBridgeMessage(safeMessage)) {
                        if (safeMessage.contains("MARKFLOW_UI") || safeMessage.contains("MARKFLOW_DIAG") || safeMessage.contains("MARKFLOW_SAVE")) {
                            LOG.warn("MARKFLOW_DIAG JS console[$level] $safeSource:$line $safeMessage")
                        } else {
                            LOG.debug("MARKFLOW_UI JS console[$level] $safeSource:$line $safeMessage")
                        }
                    }
                }
                return false
            }
        }, lease.browser.cefBrowser)

        lease.browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadStart(cefBrowser: CefBrowser?, frame: CefFrame?, transitionType: CefRequest.TransitionType?) {
                if (MarkFlowDiagnostics.enabled) {
                    LOG.debug("MARKFLOW_UI lease=${lease.id} JCEF onLoadStart: url=${cefBrowser?.url ?: lease.browser.cefBrowser.url}")
                }
            }

            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (MarkFlowDiagnostics.enabled) {
                    LOG.warn("MARKFLOW_SAVE onLoadEnd: lease=${lease.id} frame=${frame?.isMain} url=${cefBrowser?.url}")
                }
                if (frame != null && !frame.isMain) {
                    if (MarkFlowDiagnostics.enabled) {
                        LOG.warn("MARKFLOW_SAVE onLoadEnd: SKIPPED subframe, lease=${lease.id}")
                    }
                    return
                }
                lease.webViewLoaded = true
                if (MarkFlowDiagnostics.enabled) {
                    LOG.warn("MARKFLOW_SAVE onLoadEnd: calling injectBridgeAndBootstrap, lease=${lease.id}")
                }
                injectBridgeAndBootstrap(lease)
                lease.attachedEditor?.onActivatedInSharedBrowser()
                applyPendingRuntimeSettings(lease)
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
        if (MarkFlowDiagnostics.enabled) {
            LOG.warn("MARKFLOW_SAVE injectBridgeAndBootstrap: START lease=${lease.id} editor=${lease.attachedEditor?.file?.path}")
        }
        val editor = lease.attachedEditor
        val markdownLiteral = gson.toJson(editor?.currentMarkdownText().orEmpty())
        val runtimeSettingsJson = buildRuntimeSettingsJson()
        val initialMarkdownSeq = ++lease.intelliJToWebPushSequence
        val initialSettingsSeq = settingsPushSequence.incrementAndGet()
        val diagnosticsEnabledLiteral = if (MarkFlowDiagnostics.enabled) "true" else "false"
        val activeLiteral = if (lease.isEditorActive) "true" else "false"
        val sessionLiteral = gson.toJson(lease.sessionId)
        val cefQueryBridgeCall = lease.jsQuery.inject(
            "window.__markflowBridgeRequest",
            "window.__markflowBridgeOnSuccess",
            "window.__markflowBridgeOnFailure"
        )
        val debugBridgeCall = lease.debugQuery.inject("window.__markflowDebugRequest")

        val injectJs = """
            window.__markflowDiagnosticsEnabled = $diagnosticsEnabledLiteral;
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
                var normalized = String(message || "");
                if (!window.__markflowDiagnosticsEnabled) {
                    var critical = /bootError|window:error|window:unhandledrejection|failed|error|missing/i.test(normalized);
                    if (!critical) {
                        return;
                    }
                }
                window.__markflowDebugRequest = normalized;
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

        editor.applyPendingStateIfPossible()
        executeSetActiveFlag(lease, lease.isEditorActive, lease.sessionId)
        if (pushSettings) {
            applyRuntimeSettingsToLease(lease, forceReload = false)
        }
    }

    private fun executeSetActiveFlag(lease: BrowserLease, active: Boolean, expectedSessionId: String) {
        if (!isLeaseSessionCurrent(lease, expectedSessionId)) return
        lease.isEditorActive = active
        if (!lease.webViewLoaded) return
        val value = if (active) "true" else "false"
        val sessionLiteral = gson.toJson(expectedSessionId)
        lease.browser.cefBrowser.executeJavaScript(
            """
                (function(expectedSessionId, activeValue) {
                    if (window.__markflowSessionId !== expectedSessionId) {
                        return;
                    }
                    if (window.setMarkFlowEditorActive) {
                        window.setMarkFlowEditorActive(activeValue);
                    }
                })($sessionLiteral, $value);
            """.trimIndent(),
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
                val fallback = MarkFlowWebviewResourceManager.loadWebviewIndexUrl()
                if (fallback != null) {
                    lease.browser.loadURL(fallback)
                }
            }
            return
        }

        val pushId = settingsPushSequence.incrementAndGet()
        val runtimeSettingsJson = buildRuntimeSettingsJson()
        val sessionLiteral = gson.toJson(lease.sessionId)
        val script = """
            (function syncRuntimeSettingsPush(seq, payload) {
                if (window.__markflowSessionId !== $sessionLiteral) {
                    return;
                }
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

    private fun ensureLeaseLoaded(lease: BrowserLease) {
        if (lease.webViewLoaded) return
        val currentUrl = lease.browser.cefBrowser.url
        if (!currentUrl.isNullOrBlank() && currentUrl != "about:blank") return

        val bootstrapUrl = MarkFlowWebviewResourceManager.loadWebviewIndexUrl()
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
        if (!MarkFlowDiagnostics.enabled) return
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
                }.onEach { lease ->
                    leaseById.remove(lease.id)
                    idleLeaseIds.remove(lease.id)
                    lease.attachedEditor?.let { editorToLeaseId.remove(it) }
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

        lease.attachedEditor?.let { editor ->
            MarkFlowRecoveryCoordinator.clearRecoveryLease(editor, lease.id)
        }
    }

    private fun isLeaseSessionCurrent(lease: BrowserLease, expectedSessionId: String): Boolean {
        synchronized(lifecycleLock) {
            return lease.sessionId == expectedSessionId
        }
    }

    private fun clearLeaseAttachmentIfCurrent(lease: BrowserLease, expectedSessionId: String, editor: MarkFlowEditor) {
        synchronized(lifecycleLock) {
            if (lease.sessionId != expectedSessionId || lease.attachedEditor !== editor) {
                return
            }
            lease.attachedEditor = null
            lease.attachedHost = null
            lease.lastUsedAtMs = System.currentTimeMillis()
        }
    }

    private fun wrapWithSessionGuard(expectedSessionId: String, script: String): String {
        val sessionLiteral = gson.toJson(expectedSessionId)
        return """
            (function(expectedSessionId) {
                if (window.__markflowSessionId !== expectedSessionId) {
                    return;
                }
                $script
            })($sessionLiteral);
        """.trimIndent()
    }

    private fun ignoredResponse(): JBCefJSQuery.Response = JBCefJSQuery.Response("Ignored")
    private fun successResponse(): JBCefJSQuery.Response = JBCefJSQuery.Response("Success")
    private fun okResponse(): JBCefJSQuery.Response = JBCefJSQuery.Response("OK")
    private fun errorResponse(): JBCefJSQuery.Response = JBCefJSQuery.Response(null, BRIDGE_ERROR_STATUS, "Error parsing request")
    private fun jsonResponse(value: Any): JBCefJSQuery.Response = JBCefJSQuery.Response(gson.toJson(value))

    private fun buildRuntimeSettingsJson(): String {
        return try {
            val settings = MarkFlowSettingsService.getInstance().runtimeSettings()
            gson.toJson(settings)
        } catch (ex: Exception) {
            LOG.warn("MARKFLOW_UI failed to serialize runtime settings: ${ex.message}", ex)
            "{}"
        }
    }

    private fun readJsonInt(json: JsonObject, key: String, fallback: Int): Int {
        val element = json[key]
        if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            return fallback
        }
        return element.asInt
    }

    private companion object {
        private val LOG = Logger.getInstance(MarkFlowBrowserLeasePool::class.java)
        private val settingsPushSequence = AtomicInteger(0)

        private const val BRIDGE_ERROR_STATUS = 500
        private const val MIN_IDLE_LEASE_COUNT = 0
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
            var intelliJToWebPushSequence: Int = 0,
            var isEditorActive: Boolean = false,
            var sessionId: String = "",
            var lastUsedAtMs: Long
        )

        private data class LeaseAttachmentSnapshot(
            val lease: BrowserLease,
            val previousHost: JPanel?,
            val sessionId: String
        )

        private data class DetachSnapshot(
            val lease: BrowserLease,
            val sessionId: String,
            val host: JPanel?
        )
    }
}
