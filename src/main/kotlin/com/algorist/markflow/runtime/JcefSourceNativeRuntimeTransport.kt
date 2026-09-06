package com.algorist.markflow.runtime

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
import java.util.concurrent.atomic.AtomicInteger

/**
 * The only production [SourceNativeRuntimeTransport]: exactly one [JBCefBrowser] realm and exactly
 * three [JBCefJSQuery] objects (mutation/recovery transport, readiness handshake and isolated
 * external navigation) for one owning [SourceNativeEditorRuntime]'s current lifetime.
 *
 * This class is never pooled, never reused across runtime owners, and never shared with the
 * legacy `MarkFlowBrowserLeasePool`. It does not read or interpret wire messages itself; it only
 * carries bytes and dispatches lifecycle callbacks to its owner.
 *
 * Before its first page load it also installs one browser-owned request handler. That handler is
 * the source-native realm's network boundary: only the exact initial loopback entry, target-owned
 * static asset namespace and existing source-native local-image capability namespace may reach the
 * default browser loader. It never opens URLs externally and never logs rejected request values.
 *
 * Construction and disposal are failure-atomic with respect to resources this class can observe:
 * if query creation fails after the browser exists, already-created queries and the browser are
 * released before the exception escapes; disposal attempts every owned release even if an earlier
 * one throws.
 */
internal class JcefSourceNativeRuntimeTransport : SourceNativeRuntimeTransport {
    private val browser: JBCefBrowser
    private val transportQuery: JBCefJSQuery
    private val readinessQuery: JBCefJSQuery
    private val externalNavigationQuery: JBCefJSQuery

    @Volatile
    private var disposed = false

    @Volatile
    private var requestPolicy: SourceNativeBrowserRequestPolicy? = null

    @Volatile
    private var requestHandler: CefRequestHandlerAdapter? = null

    @Volatile
    private var requestPolicyBlockHandlerForDiagnostics: ((String) -> Unit)? = null

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
        val createdBrowser = JBCefBrowser()
        val createdTransportQuery = try {
            JBCefJSQuery.create(createdBrowser as JBCefBrowserBase)
        } catch (failure: Throwable) {
            cleanupConstructionFailure(failure, createdBrowser, emptyList())
            throw failure
        }
        val createdReadinessQuery = try {
            JBCefJSQuery.create(createdBrowser as JBCefBrowserBase)
        } catch (failure: Throwable) {
            cleanupConstructionFailure(failure, createdBrowser, listOf(createdTransportQuery))
            throw failure
        }
        val createdExternalNavigationQuery = try {
            JBCefJSQuery.create(createdBrowser as JBCefBrowserBase)
        } catch (failure: Throwable) {
            cleanupConstructionFailure(
                failure,
                createdBrowser,
                listOf(createdReadinessQuery, createdTransportQuery),
            )
            throw failure
        }

        browser = createdBrowser
        transportQuery = createdTransportQuery
        readinessQuery = createdReadinessQuery
        externalNavigationQuery = createdExternalNavigationQuery
        liveInstances.incrementAndGet()
    }

    override fun loadUrl(url: String) {
        if (disposed) return
        if (requestPolicy == null && !installRequestPolicy(url)) return
        browser.loadURL(url)
    }

    override fun executeJavaScript(script: String) {
        if (disposed) return
        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
    }

    /**
     * Forces creation of the native browser only for the headless-by-construction evidence harness.
     * Production editor surfaces rely on the normal Swing hierarchy realization path instead.
     */
    internal fun createImmediatelyForDiagnostics() {
        if (disposed) return
        browser.createImmediately()
    }

    /**
     * Evidence-only notification seam. It exposes only a fixed rejection class, never the URL,
     * origin, capability token or local path that caused the rejection.
     */
    internal fun setRequestPolicyBlockHandlerForDiagnostics(handler: ((String) -> Unit)?) {
        requestPolicyBlockHandlerForDiagnostics = handler
    }

    override fun buildBridgeGlueScript(): String {
        val sendSnippet = transportQuery.inject(
            "window.__markflowSNTransportRequest",
            "window.__markflowSNTransportOnSuccess",
            "window.__markflowSNTransportOnFailure",
        )
        val readySnippet = readinessQuery.inject(
            "window.__markflowSNReadyRequest",
            "window.__markflowSNReadyOnSuccess",
            "window.__markflowSNReadyOnFailure",
        )
        val externalNavigationSnippet = externalNavigationQuery.inject(
            "window.__markflowSNExternalNavigationRequest",
            "window.__markflowSNExternalNavigationOnSuccess",
            "window.__markflowSNExternalNavigationOnFailure",
        )
        return """
            window.__markflowSourceNativeSend = function(raw, onSuccess, onFailure) {
                window.__markflowSNTransportRequest = raw;
                window.__markflowSNTransportOnSuccess = onSuccess;
                window.__markflowSNTransportOnFailure = onFailure;
                $sendSnippet
            };
            window.__markflowSourceNativeReady = function(raw, onSuccess, onFailure) {
                window.__markflowSNReadyRequest = raw;
                window.__markflowSNReadyOnSuccess = onSuccess;
                window.__markflowSNReadyOnFailure = onFailure;
                $readySnippet
            };
            window.__markflowSourceNativeOpenExternal = function(raw, onSuccess, onFailure) {
                window.__markflowSNExternalNavigationRequest = raw;
                window.__markflowSNExternalNavigationOnSuccess = onSuccess;
                window.__markflowSNExternalNavigationOnFailure = onFailure;
                $externalNavigationSnippet
            };
            window.__markflowHostGlueInstalled = true;
            if (typeof window.__markflowSourceNativeInit === 'function') {
                window.__markflowSourceNativeInit();
            }
        """.trimIndent()
    }

    override fun setTransportMessageHandler(handler: (String) -> String?) {
        transportQuery.addHandler { raw -> toResponse(handler(raw)) }
    }

    override fun setReadinessMessageHandler(handler: (String) -> String?) {
        readinessQuery.addHandler { raw -> toResponse(handler(raw)) }
    }

    override fun setExternalNavigationMessageHandler(handler: (String) -> String?) {
        externalNavigationQuery.addHandler { raw -> toResponse(handler(raw)) }
    }

    override fun setLoadStartHandler(handler: () -> Unit) {
        browser.jbCefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadStart(
                    cefBrowser: CefBrowser?,
                    frame: CefFrame?,
                    transitionType: CefRequest.TransitionType?,
                ) {
                    if (disposed || frame == null || !frame.isMain) return
                    handler()
                }
            },
            browser.cefBrowser,
        )
    }

    override fun setLoadEndHandler(handler: () -> Unit) {
        browser.jbCefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    if (disposed || frame == null || !frame.isMain) return
                    handler()
                }
            },
            browser.cefBrowser,
        )
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        requestPolicyBlockHandlerForDiagnostics = null

        var firstFailure: Throwable? = null
        fun release(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                val existingFailure = firstFailure
                if (existingFailure == null) {
                    firstFailure = failure
                } else {
                    existingFailure.addSuppressed(failure)
                }
            }
        }

        requestHandler?.let { handler ->
            release { browser.jbCefClient.removeRequestHandler(handler, browser.cefBrowser) }
        }
        requestHandler = null
        requestPolicy = null
        release { externalNavigationQuery.dispose() }
        release { readinessQuery.dispose() }
        release { transportQuery.dispose() }
        release { browser.dispose() }
        val failure = firstFailure
        if (failure == null) {
            liveInstances.decrementAndGet()
        } else {
            throw failure
        }
    }

    private fun installRequestPolicy(entryUrl: String): Boolean {
        val policy = SourceNativeBrowserRequestPolicy.fromEntryUrl(entryUrl) ?: return false
        val handler = object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(
                browser: CefBrowser?,
                frame: CefFrame?,
                request: CefRequest?,
                userGesture: Boolean,
                isRedirect: Boolean,
            ): Boolean {
                if (disposed || frame == null || !frame.isMain || request == null) {
                    notifyRequestBlocked(BLOCK_NAVIGATION)
                    return true
                }
                val allowed = policy.allowInitialMainNavigation(request.url, request.method, isRedirect)
                if (!allowed) notifyRequestBlocked(BLOCK_NAVIGATION)
                return !allowed
            }

            override fun onOpenURLFromTab(
                browser: CefBrowser?,
                frame: CefFrame?,
                targetUrl: String?,
                userGesture: Boolean,
            ): Boolean {
                notifyRequestBlocked(BLOCK_POPUP)
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
                if (disposed || request == null) {
                    notifyRequestBlocked(BLOCK_RESOURCE)
                    return blockingResourceRequestHandler
                }
                val allowed = policy.allowResourceRequest(
                    url = request.url,
                    method = request.method,
                    resourceType = request.resourceType,
                    isNavigation = isNavigation,
                    isDownload = isDownload,
                )
                if (allowed) return null
                notifyRequestBlocked(BLOCK_RESOURCE)
                return blockingResourceRequestHandler
            }
        }

        return try {
            browser.jbCefClient.addRequestHandler(handler, browser.cefBrowser)
            requestPolicy = policy
            requestHandler = handler
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun notifyRequestBlocked(reason: String) {
        try {
            requestPolicyBlockHandlerForDiagnostics?.invoke(reason)
        } catch (_: Throwable) {
            // Evidence observation must never weaken or interrupt the blocking decision.
        }
    }

    private fun toResponse(payload: String?): JBCefJSQuery.Response =
        if (payload != null) JBCefJSQuery.Response(payload) else JBCefJSQuery.Response(null, REJECTED_STATUS, "rejected")

    companion object {
        private const val REJECTED_STATUS = 400
        private const val BLOCK_NAVIGATION = "navigation"
        private const val BLOCK_POPUP = "popup"
        private const val BLOCK_RESOURCE = "resource"
        private val liveInstances = AtomicInteger(0)

        /** Diagnostic lifecycle evidence only; it is never a correctness or ownership authority. */
        internal val liveInstanceCount: Int
            get() = liveInstances.get()

        private fun cleanupConstructionFailure(
            failure: Throwable,
            browser: JBCefBrowser,
            queries: List<JBCefJSQuery>,
        ) {
            fun release(action: () -> Unit) {
                try {
                    action()
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
            }

            queries.forEach { query -> release(query::dispose) }
            release { browser.dispose() }
        }
    }
}
