package com.algorist.markflow.renderer.jcef

import com.algorist.markflow.browser.MarkFlowWebviewResourceManager
import com.algorist.markflow.renderer.DerivedRendererKind
import com.algorist.markflow.renderer.DerivedRendererPresentationArtifact
import com.algorist.markflow.renderer.DerivedRendererRuntime
import com.algorist.markflow.renderer.DerivedRendererRuntimeRequest
import com.algorist.markflow.renderer.DerivedRendererRuntimeResult
import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.registry.Registry
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Timer

/**
 * JCEF execution adapter for #144. This realm hosts only derived rendering; it has no editor,
 * Document, source-sync, local-file, or external-navigation authority.
 */
class JcefDerivedRendererRuntime internal constructor(
    private val startupPolicy: JcefStartupPolicy = JcefStartupPolicy.fromRegistry(),
) : DerivedRendererRuntime {
    private val gson = Gson()
    private val browser: JBCefBrowser
    private val messageQuery: JBCefJSQuery
    private val entryUrl: String
    private val callbacks = LinkedHashMap<String, (DerivedRendererRuntimeResult) -> Unit>()
    private val activeRequests = LinkedHashMap<String, DerivedRendererRuntimeRequest>()
    private val startupQueue = JcefStartupRequestQueue<DerivedRendererRuntimeRequest>(MAX_PENDING_REQUESTS)

    @Volatile
    private var disposed = false

    @Volatile
    private var ready = false

    // Startup has two independent bounded phases. Cold entry loading must not consume the
    // much shorter host<->page ready-handshake budget.
    private var startupDeadline: Timer? = null
    private var armedStartupPhase: JcefStartupRequestQueue.Phase? = null
    private var requestHandler: CefRequestHandlerAdapter? = null
    private val blockedNavigationCounter = AtomicInteger(0)

    private val blockingResourceRequestHandler = object : CefResourceRequestHandlerAdapter() {
        override fun onBeforeResourceLoad(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): Boolean = true

        override fun onProtocolExecution(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest?,
            allowOsExecution: BoolRef?,
        ) {
            allowOsExecution?.set(false)
        }
    }

    init {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val port = MarkFlowWebviewResourceManager.acquire()
            ?: error("derived renderer webview resources unavailable")
        entryUrl = "http://127.0.0.1:$port/derived-renderer.html"

        val createdBrowser = try {
            if (failBrowserConstructionOnceForDiagnostics.getAndSet(false)) {
                error("diagnostic browser construction failure")
            }
            JBCefBrowser()
        } catch (failure: Throwable) {
            MarkFlowWebviewResourceManager.release()
            throw failure
        }
        val createdQuery = try {
            JBCefJSQuery.create(createdBrowser as JBCefBrowserBase)
        } catch (failure: Throwable) {
            runCatching { createdBrowser.dispose() }.onFailure { failure.addSuppressed(it) }
            MarkFlowWebviewResourceManager.release()
            throw failure
        }
        browser = createdBrowser
        messageQuery = createdQuery
        try {
            installRequestPolicy(entryUrl)
            installMessageHandler()
            installLoadHandler()
            // This renderer browser is intentionally offscreen and is never made displayable by
            // a Swing component. Force native browser creation before loading so production uses
            // the same lifecycle that the real-JCEF diagnostic probes previously required.
            browser.createImmediately()
            browser.loadURL(entryUrl)
            liveInstances.incrementAndGet()
        } catch (failure: Throwable) {
            runCatching { createdQuery.dispose() }.onFailure { failure.addSuppressed(it) }
            runCatching { createdBrowser.dispose() }.onFailure { failure.addSuppressed(it) }
            MarkFlowWebviewResourceManager.release()
            throw failure
        }
    }

    override fun render(
        request: DerivedRendererRuntimeRequest,
        callback: (DerivedRendererRuntimeResult) -> Unit,
    ) {
        onEdt {
            if (disposed) {
                callback(disposedResult(request))
                return@onEdt
            }
            validate(request)?.let { invalid ->
                callback(failureResult(request, "INVALID_REQUEST", retryable = false, invalid))
                return@onEdt
            }
            if (activeRequests.containsKey(request.requestId)) {
                callback(
                    failureResult(
                        request,
                        "INVALID_REQUEST",
                        retryable = false,
                        "renderer request id is already active",
                    )
                )
                return@onEdt
            }

            activeRequests[request.requestId] = request
            callbacks[request.requestId] = callback
            if (!ready) {
                when (startupQueue.offer(request.requestId, request)) {
                    JcefStartupRequestQueue.OfferResult.ACCEPTED -> {
                        armStartupDeadlineIfNeeded()
                    }
                    JcefStartupRequestQueue.OfferResult.FULL -> {
                        callbacks.remove(request.requestId)
                        activeRequests.remove(request.requestId)
                        callback(
                            failureResult(
                                request,
                                "BACKEND_UNAVAILABLE",
                                retryable = true,
                                "renderer startup queue reached its configured bound",
                            )
                        )
                    }
                    JcefStartupRequestQueue.OfferResult.TERMINAL_FAILURE -> {
                        callbacks.remove(request.requestId)
                        activeRequests.remove(request.requestId)
                        callback(
                            failureResult(
                                request,
                                "BACKEND_UNAVAILABLE",
                                retryable = false,
                                requireNotNull(startupQueue.terminalFailureMessage),
                            )
                        )
                    }
                }
                return@onEdt
            }
            sendRequest(request)
        }
    }

    override fun cancel(requestId: String) {
        onEdt {
            val request = activeRequests.remove(requestId) ?: return@onEdt
            startupQueue.remove(requestId)
            if (!ready && startupQueue.isEmpty) stopStartupDeadline()
            callbacks.remove(requestId)?.invoke(
                failureResult(request, "CANCELLED", retryable = false, "renderer request was cancelled")
            )
            if (!disposed && ready) {
                browser.cefBrowser.executeJavaScript(
                    "window.__markflowDerivedRendererCancel?.(${quoteJs(requestId)});",
                    entryUrl,
                    0,
                )
            }
        }
    }

    override fun dispose() {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            disposeOnEdt()
        } else {
            application.invokeAndWait { disposeOnEdt() }
        }
    }

    internal fun probeBlockedNavigationForDiagnostics() {
        onEdt {
            if (!disposed && ready) {
                browser.cefBrowser.executeJavaScript(
                    "window.location.href = 'https://example.invalid/markflow-derived-renderer-probe';",
                    entryUrl,
                    0,
                )
            }
        }
    }

    internal val blockedNavigationCountForDiagnostics: Int
        get() = blockedNavigationCounter.get()

    private fun installMessageHandler() {
        messageQuery.addHandler { raw ->
            if (!disposed) {
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed) handleMessage(raw)
                }
            }
            JBCefJSQuery.Response("ok")
        }
    }

    private fun installLoadHandler() {
        browser.jbCefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    if (disposed || frame == null || !frame.isMain) return
                    ApplicationManager.getApplication().invokeLater {
                        if (disposed || ready || startupQueue.terminallyFailed) return@invokeLater
                        if (httpStatusCode >= 400) {
                            failStartupTerminal("renderer entry returned HTTP $httpStatusCode")
                        } else if (startupQueue.markEntryLoaded()) {
                            stopStartupDeadline()
                            installBridgeGlue()
                            armStartupDeadlineIfNeeded()
                        }
                    }
                }

                override fun onLoadError(
                    browser: CefBrowser,
                    frame: CefFrame,
                    errorCode: CefLoadHandler.ErrorCode,
                    errorText: String,
                    failedUrl: String,
                ) {
                    if (disposed || !frame.isMain) return
                    val detail = buildString {
                        append("renderer entry failed to load: ").append(errorCode)
                        errorText.takeIf(String::isNotBlank)?.let { append(" (").append(it).append(')') }
                    }
                    ApplicationManager.getApplication().invokeLater {
                        if (!disposed && !ready) {
                            failStartupTerminal(detail)
                        }
                    }
                }
            },
            browser.cefBrowser,
        )
    }

    private fun installBridgeGlue() {
        val sendSnippet = messageQuery.inject(
            "window.__markflowDerivedRendererMessage",
            "window.__markflowDerivedRendererOnSuccess",
            "window.__markflowDerivedRendererOnFailure",
        )
        val script = """
            window.__markflowDerivedRendererSendMessage = function(raw) {
                window.__markflowDerivedRendererMessage = raw;
                window.__markflowDerivedRendererOnSuccess = function() {};
                window.__markflowDerivedRendererOnFailure = function() {};
                $sendSnippet
            };
            window.__markflowDerivedRendererResult = function(raw) {
                window.__markflowDerivedRendererSendMessage(raw);
            };
            window.__markflowDerivedRendererReady = function() {
                window.__markflowDerivedRendererSendMessage(JSON.stringify({type: 'ready'}));
            };
            if (typeof window.__markflowDerivedRendererHostReady === 'function') {
                window.__markflowDerivedRendererHostReady();
            }
        """.trimIndent()
        browser.cefBrowser.executeJavaScript(script, entryUrl, 0)
    }

    private fun handleMessage(raw: String) {
        val envelope = runCatching { gson.fromJson(raw, WireEnvelope::class.java) }.getOrNull() ?: return
        when (envelope.type) {
            "ready" -> {
                if (ready || !startupQueue.markReady()) return
                stopStartupDeadline()
                ready = true
                val pending = startupQueue.drain()
                pending.forEach(::sendRequest)
            }

            "result", "failure" -> handleResult(envelope)
        }
    }

    private fun armStartupDeadlineIfNeeded() {
        val phase = startupQueue.deadlinePhaseOrNull ?: return
        if (startupDeadline?.isRunning == true && armedStartupPhase == phase) return

        stopStartupDeadline()
        val timeoutMillis = startupPolicy.timeoutMillis(phase)
        startupDeadline = Timer(timeoutMillis) {
            if (disposed || ready || startupQueue.phase != phase) return@Timer
            val detail = when (phase) {
                JcefStartupRequestQueue.Phase.ENTRY_LOADING ->
                    "renderer entry did not finish loading before its configured startup deadline"
                JcefStartupRequestQueue.Phase.READY_HANDSHAKE ->
                    "renderer entry loaded but did not complete the ready handshake before its configured deadline"
                else -> return@Timer
            }
            failStartupTerminal(detail)
        }.apply {
            isRepeats = false
            start()
        }
        armedStartupPhase = phase
    }

    private fun stopStartupDeadline() {
        startupDeadline?.stop()
        startupDeadline = null
        armedStartupPhase = null
    }

    private fun failStartupTerminal(message: String) {
        if (disposed || ready) return
        stopStartupDeadline()
        startupQueue.markTerminalFailure(message)
        val authoritativeMessage = requireNotNull(startupQueue.terminalFailureMessage)
        val pending = startupQueue.drain()
        pending.forEach { request ->
            activeRequests.remove(request.requestId)
            callbacks.remove(request.requestId)?.invoke(
                failureResult(
                    request,
                    "BACKEND_UNAVAILABLE",
                    retryable = false,
                    authoritativeMessage,
                )
            )
        }
    }

    private fun handleResult(envelope: WireEnvelope) {
        val requestId = envelope.requestId?.takeIf(String::isNotBlank) ?: return
        val request = activeRequests.remove(requestId) ?: return
        val callback = callbacks.remove(requestId) ?: return
        startupQueue.remove(requestId)

        val identityMatches = envelope.sourceGeneration == request.identity.sourceGeneration &&
            envelope.configGeneration == request.identity.configGeneration
        val kindMatches = envelope.kind == request.kind.wireName
        if (!identityMatches || !kindMatches) {
            callback(failureResult(request, "RENDER_FAILED", retryable = true, "renderer response identity mismatch"))
            return
        }

        val status = envelope.status ?: "failure"
        val mediaMatches = status != "success" || when (request.kind) {
            DerivedRendererKind.MERMAID -> envelope.mediaType == "image/svg+xml"
            DerivedRendererKind.KATEX -> envelope.mediaType == "text/html"
        }
        if (!mediaMatches) {
            callback(failureResult(request, "RENDER_FAILED", retryable = true, "renderer response media type mismatch"))
            return
        }

        callback(
            DerivedRendererRuntimeResult(
                requestId = requestId,
                status = status,
                kind = envelope.kind,
                identity = request.identity,
                mediaType = envelope.mediaType,
                content = envelope.content,
                presentationArtifact = presentationArtifactOrNull(envelope),
                code = envelope.code,
                retryable = envelope.retryable == true,
                message = envelope.message,
            )
        )
    }

    private fun presentationArtifactOrNull(envelope: WireEnvelope): DerivedRendererPresentationArtifact? {
        val mediaType = envelope.presentationMediaType ?: return null
        val contentBase64 = envelope.presentationContentBase64 ?: return null
        val width = envelope.presentationWidth ?: return null
        val height = envelope.presentationHeight ?: return null
        if (mediaType != "image/png") return null
        if (contentBase64.isBlank() || contentBase64.length > MAX_PRESENTATION_BASE64_CHARS) return null
        if (width !in 1..MAX_PRESENTATION_DIMENSION || height !in 1..MAX_PRESENTATION_DIMENSION) return null
        if (width.toLong() * height.toLong() > MAX_PRESENTATION_PIXELS) return null
        return DerivedRendererPresentationArtifact(
            mediaType = mediaType,
            contentBase64 = contentBase64,
            width = width,
            height = height,
        )
    }

    private fun sendRequest(request: DerivedRendererRuntimeRequest) {
        if (disposed) return
        val wire = WireRequest(
            requestId = request.requestId,
            kind = request.kind.wireName,
            source = request.source,
            configJson = request.configJson,
            sourceGeneration = request.identity.sourceGeneration,
            configGeneration = request.identity.configGeneration,
        )
        val encoded = Base64.getEncoder().encodeToString(
            gson.toJson(wire).toByteArray(StandardCharsets.UTF_8)
        )
        browser.cefBrowser.executeJavaScript(
            "window.__markflowDerivedRendererRenderBase64?.(\"$encoded\");",
            entryUrl,
            0,
        )
    }

    private fun installRequestPolicy(entryUrl: String) {
        val entry = URI.create(entryUrl)
        val handler = object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(
                browser: CefBrowser?,
                frame: CefFrame?,
                request: CefRequest?,
                userGesture: Boolean,
                isRedirect: Boolean,
            ): Boolean {
                val allowed = request != null && frame?.isMain == true && !isRedirect &&
                    request.method.equals("GET", ignoreCase = true) && isAllowedEntry(request.url, entry)
                if (!allowed) blockedNavigationCounter.incrementAndGet()
                return !allowed
            }

            override fun onOpenURLFromTab(
                browser: CefBrowser?,
                frame: CefFrame?,
                targetUrl: String?,
                userGesture: Boolean,
            ): Boolean {
                blockedNavigationCounter.incrementAndGet()
                return true
            }

            override fun getResourceRequestHandler(
                browser: CefBrowser?,
                frame: CefFrame?,
                request: CefRequest?,
                isNavigation: Boolean,
                isDownload: Boolean,
                requestInitiator: String?,
                disableDefaultHandling: BoolRef?,
            ): CefResourceRequestHandler? {
                val allowed = request != null && !isDownload &&
                    request.method.equals("GET", ignoreCase = true) && isAllowedResource(request.url, entry)
                if (allowed) return null
                return blockingResourceRequestHandler
            }
        }
        browser.jbCefClient.addRequestHandler(handler, browser.cefBrowser)
        requestHandler = handler
    }

    private fun disposeOnEdt() {
        if (disposed) return
        disposed = true
        ready = false
        stopStartupDeadline()
        val pendingCallbacks = callbacks.toMap()
        val pendingRequests = activeRequests.toMap()
        callbacks.clear()
        activeRequests.clear()
        startupQueue.drain()
        pendingCallbacks.forEach { (requestId, callback) ->
            pendingRequests[requestId]?.let { callback(disposedResult(it)) }
        }

        requestHandler?.let { handler ->
            runCatching { browser.jbCefClient.removeRequestHandler(handler, browser.cefBrowser) }
        }
        requestHandler = null
        runCatching { messageQuery.dispose() }
        runCatching { browser.dispose() }
        MarkFlowWebviewResourceManager.release()
        liveInstances.decrementAndGet()
    }

    private fun validate(request: DerivedRendererRuntimeRequest): String? {
        if (request.requestId.isBlank()) return "renderer request id is required"
        if (request.identity.sourceGeneration.isBlank() || request.identity.configGeneration.isBlank()) {
            return "renderer identity is required"
        }
        val sourceLimit = if (request.kind == DerivedRendererKind.MERMAID) {
            MAX_MERMAID_SOURCE_CHARS
        } else {
            MAX_KATEX_SOURCE_CHARS
        }
        if (request.source.length > sourceLimit) return "renderer source exceeds configured bound"
        if (request.configJson.length > MAX_CONFIG_CHARS) return "renderer config exceeds configured bound"
        return null
    }

    private fun failureResult(
        request: DerivedRendererRuntimeRequest,
        code: String,
        retryable: Boolean,
        message: String,
    ) = DerivedRendererRuntimeResult(
        requestId = request.requestId,
        status = "failure",
        kind = request.kind.wireName,
        identity = request.identity,
        code = code,
        retryable = retryable,
        message = message,
    )

    private fun disposedResult(request: DerivedRendererRuntimeRequest) =
        failureResult(request, "DISPOSED", retryable = false, "renderer runtime is disposed")

    private fun onEdt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) action() else application.invokeLater(action)
    }

    private data class WireRequest(
        val requestId: String,
        val kind: String,
        val source: String,
        val configJson: String,
        val sourceGeneration: String,
        val configGeneration: String,
    )

    private data class WireEnvelope(
        val type: String? = null,
        val requestId: String? = null,
        val status: String? = null,
        val kind: String? = null,
        val sourceGeneration: String? = null,
        val configGeneration: String? = null,
        val mediaType: String? = null,
        val content: String? = null,
        val presentationMediaType: String? = null,
        val presentationContentBase64: String? = null,
        val presentationWidth: Int? = null,
        val presentationHeight: Int? = null,
        val code: String? = null,
        val retryable: Boolean? = null,
        val message: String? = null,
    )

    companion object {
        private const val MAX_PENDING_REQUESTS = 64
        private const val MAX_MERMAID_SOURCE_CHARS = 64 * 1024
        private const val MAX_KATEX_SOURCE_CHARS = 32 * 1024
        private const val MAX_CONFIG_CHARS = 16 * 1024
        private const val MAX_PRESENTATION_DIMENSION = 4096
        private const val MAX_PRESENTATION_PIXELS = 8L * 1024L * 1024L
        private const val MAX_PRESENTATION_BASE64_CHARS = 48 * 1024 * 1024
        private val liveInstances = AtomicInteger(0)
        private val failBrowserConstructionOnceForDiagnostics = AtomicBoolean(false)

        internal val liveInstanceCountForDiagnostics: Int
            get() = liveInstances.get()

        internal fun failNextBrowserConstructionForDiagnostics() {
            check(failBrowserConstructionOnceForDiagnostics.compareAndSet(false, true)) {
                "diagnostic browser-construction failure already armed"
            }
        }

        private fun isAllowedEntry(rawUrl: String?, entry: URI): Boolean {
            val uri = safeUri(rawUrl) ?: return false
            return sameOrigin(uri, entry) && uri.path == "/derived-renderer.html" && uri.query == null && uri.fragment == null
        }

        private fun isAllowedResource(rawUrl: String?, entry: URI): Boolean {
            val uri = safeUri(rawUrl) ?: return false
            if (!sameOrigin(uri, entry) || uri.query != null || uri.fragment != null) return false
            return uri.path == "/derived-renderer.html" || uri.path.startsWith("/derived-renderer-assets/")
        }

        private fun sameOrigin(left: URI, right: URI): Boolean =
            left.scheme == right.scheme && left.host == right.host && left.port == right.port

        private fun safeUri(value: String?): URI? = runCatching { value?.let(URI::create) }.getOrNull()

        private fun quoteJs(value: String): String = buildString {
            append('"')
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(ch)
                }
            }
            append('"')
        }
    }
}


internal data class JcefStartupPolicy(
    val entryLoadTimeoutMillis: Int,
    val readyHandshakeTimeoutMillis: Int,
) {
    init {
        require(entryLoadTimeoutMillis > 0) { "entry-load timeout must be positive" }
        require(readyHandshakeTimeoutMillis > 0) { "ready-handshake timeout must be positive" }
    }

    fun timeoutMillis(phase: JcefStartupRequestQueue.Phase): Int = when (phase) {
        JcefStartupRequestQueue.Phase.ENTRY_LOADING -> entryLoadTimeoutMillis
        JcefStartupRequestQueue.Phase.READY_HANDSHAKE -> readyHandshakeTimeoutMillis
        else -> error("startup deadline requested for terminal phase: $phase")
    }

    companion object {
        const val ENTRY_LOAD_TIMEOUT_REGISTRY_KEY = "markflow.renderer.jcef.entryLoadTimeoutMs"
        const val READY_HANDSHAKE_TIMEOUT_REGISTRY_KEY = "markflow.renderer.jcef.readyHandshakeTimeoutMs"

        fun fromRegistry(): JcefStartupPolicy = JcefStartupPolicy(
            entryLoadTimeoutMillis = Registry.intValue(ENTRY_LOAD_TIMEOUT_REGISTRY_KEY),
            readyHandshakeTimeoutMillis = Registry.intValue(READY_HANDSHAKE_TIMEOUT_REGISTRY_KEY),
        )
    }
}

internal class JcefStartupRequestQueue<T>(
    private val capacity: Int,
) {
    enum class Phase {
        ENTRY_LOADING,
        READY_HANDSHAKE,
        READY,
        TERMINAL_FAILURE,
    }

    enum class OfferResult {
        ACCEPTED,
        FULL,
        TERMINAL_FAILURE,
    }

    private val pending = LinkedHashMap<String, T>()

    var phase: Phase = Phase.ENTRY_LOADING
        private set

    var terminalFailureMessage: String? = null
        private set

    val terminallyFailed: Boolean
        get() = phase == Phase.TERMINAL_FAILURE

    init {
        require(capacity > 0) { "startup queue capacity must be positive" }
    }

    val size: Int
        get() = pending.size

    val isEmpty: Boolean
        get() = pending.isEmpty()

    val deadlinePhaseOrNull: Phase?
        get() = phase.takeIf {
            pending.isNotEmpty() && (it == Phase.ENTRY_LOADING || it == Phase.READY_HANDSHAKE)
        }

    fun offer(id: String, value: T): OfferResult {
        require(id.isNotBlank()) { "startup request id must not be blank" }
        if (terminallyFailed) return OfferResult.TERMINAL_FAILURE
        check(phase != Phase.READY) { "ready runtime must not enqueue startup work" }
        if (pending.size >= capacity) return OfferResult.FULL
        pending[id] = value
        return OfferResult.ACCEPTED
    }

    fun remove(id: String): T? = pending.remove(id)

    fun drain(): List<T> {
        val values = pending.values.toList()
        pending.clear()
        return values
    }

    fun markEntryLoaded(): Boolean {
        if (phase != Phase.ENTRY_LOADING) return false
        phase = Phase.READY_HANDSHAKE
        return true
    }

    fun markTerminalFailure(message: String) {
        require(message.isNotBlank()) { "startup failure message must not be blank" }
        if (phase == Phase.READY || phase == Phase.TERMINAL_FAILURE) return
        terminalFailureMessage = terminalFailureMessage ?: message
        phase = Phase.TERMINAL_FAILURE
    }

    /**
     * Any startup failure is terminal for this runtime instance because there is no reload/retry
     * owner. A late ready message must never resurrect an instance that already failed startup.
     */
    fun markReady(): Boolean {
        if (phase == Phase.TERMINAL_FAILURE) return false
        phase = Phase.READY
        return true
    }
}
