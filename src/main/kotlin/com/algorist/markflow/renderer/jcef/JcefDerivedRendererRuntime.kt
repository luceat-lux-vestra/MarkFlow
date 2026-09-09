package com.algorist.markflow.renderer.jcef

import com.algorist.markflow.browser.MarkFlowWebviewResourceManager
import com.algorist.markflow.renderer.DerivedRendererIdentity
import com.algorist.markflow.renderer.DerivedRendererKind
import com.algorist.markflow.renderer.DerivedRendererRuntime
import com.algorist.markflow.renderer.DerivedRendererRuntimeRequest
import com.algorist.markflow.renderer.DerivedRendererRuntimeResult
import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * JCEF execution adapter for #144. This realm hosts only derived rendering; it has no editor,
 * Document, source-sync, local-file, or external-navigation authority.
 */
class JcefDerivedRendererRuntime(
    createImmediatelyForDiagnostics: Boolean = false,
) : DerivedRendererRuntime {
    private val gson = Gson()
    private val browser: JBCefBrowser
    private val messageQuery: JBCefJSQuery
    private val entryUrl: String
    private val callbacks = LinkedHashMap<String, (DerivedRendererRuntimeResult) -> Unit>()
    private val activeRequests = LinkedHashMap<String, DerivedRendererRuntimeRequest>()
    private val queuedRequests = LinkedHashMap<String, DerivedRendererRuntimeRequest>()

    @Volatile
    private var disposed = false

    @Volatile
    private var ready = false

    private var requestHandler: CefRequestHandlerAdapter? = null
    private val blockedNavigationCounter = AtomicInteger(0)
    private val blockedResourceCounter = AtomicInteger(0)

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

        val createdBrowser = JBCefBrowser()
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
            if (createImmediatelyForDiagnostics) browser.createImmediately()
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
            val invalid = validate(request)
            if (invalid != null) {
                callback(failureResult(request, "INVALID_REQUEST", retryable = false, invalid))
                return@onEdt
            }

            if (activeRequests.containsKey(request.requestId)) {
                callback(failureResult(request, "INVALID_REQUEST", retryable = false, "renderer request id is already active"))
                return@onEdt
            }
            activeRequests[request.requestId] = request
            callbacks[request.requestId] = callback

            if (!ready) {
                if (queuedRequests.size >= MAX_PENDING_REQUESTS) {
                    callbacks.remove(request.requestId)
                    activeRequests.remove(request.requestId)
                    callback(failureResult(request, "BACKEND_UNAVAILABLE", retryable = true, "renderer runtime is not ready"))
                    return@onEdt
                }
                queuedRequests[request.requestId] = request
                return@onEdt
            }
            sendRequest(request)
        }
    }

    override fun cancel(requestId: String) {
        onEdt {
            val request = activeRequests.remove(requestId) ?: return@onEdt
            queuedRequests.remove(requestId)
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

    internal val blockedResourceCountForDiagnostics: Int
        get() = blockedResourceCounter.get()

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
                        if (!disposed) installBridgeGlue()
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
                if (ready) return
                ready = true
                val pending = queuedRequests.values.toList()
                queuedRequests.clear()
                pending.forEach(::sendRequest)
            }

            "result", "failure" -> handleResult(envelope)
        }
    }

    private fun handleResult(envelope: WireEnvelope) {
        val requestId = envelope.requestId?.takeIf(String::isNotBlank) ?: return
        val request = activeRequests.remove(requestId) ?: return
        val callback = callbacks.remove(requestId) ?: return
        queuedRequests.remove(requestId)

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
                code = envelope.code,
                retryable = envelope.retryable == true,
                message = envelope.message,
            )
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
                blockedResourceCounter.incrementAndGet()
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
        val pendingCallbacks = callbacks.toMap()
        val pendingRequests = activeRequests.toMap()
        callbacks.clear()
        activeRequests.clear()
        queuedRequests.clear()
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
        val sourceLimit = if (request.kind == DerivedRendererKind.MERMAID) MAX_MERMAID_SOURCE_CHARS else MAX_KATEX_SOURCE_CHARS
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
        val code: String? = null,
        val retryable: Boolean? = null,
        val message: String? = null,
    )

    companion object {
        private const val MAX_PENDING_REQUESTS = 64
        private const val MAX_MERMAID_SOURCE_CHARS = 64 * 1024
        private const val MAX_KATEX_SOURCE_CHARS = 32 * 1024
        private const val MAX_CONFIG_CHARS = 16 * 1024
        private val liveInstances = AtomicInteger(0)

        internal val liveInstanceCountForDiagnostics: Int
            get() = liveInstances.get()

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
