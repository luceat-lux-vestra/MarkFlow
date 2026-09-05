package com.algorist.markflow.runtime

import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest

/**
 * The only production [SourceNativeRuntimeTransport]: exactly one [JBCefBrowser] realm and exactly
 * two [JBCefJSQuery] objects (mutation/recovery transport, readiness handshake) for one owning
 * [SourceNativeEditorRuntime]'s current lifetime.
 *
 * This class is never pooled, never reused across runtime owners, and never shared with the
 * legacy `MarkFlowBrowserLeasePool`. It does not read or interpret wire messages itself; it only
 * carries bytes and dispatches lifecycle callbacks to its owner.
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

    @Volatile
    private var disposed = false

    init {
        val createdBrowser = JBCefBrowser()
        val createdTransportQuery = try {
            JBCefJSQuery.create(createdBrowser as JBCefBrowserBase)
        } catch (failure: Throwable) {
            cleanupConstructionFailure(
                failure = failure,
                browser = createdBrowser,
                transportQuery = null,
            )
            throw failure
        }
        val createdReadinessQuery = try {
            JBCefJSQuery.create(createdBrowser as JBCefBrowserBase)
        } catch (failure: Throwable) {
            cleanupConstructionFailure(
                failure = failure,
                browser = createdBrowser,
                transportQuery = createdTransportQuery,
            )
            throw failure
        }

        browser = createdBrowser
        transportQuery = createdTransportQuery
        readinessQuery = createdReadinessQuery
    }

    override fun loadUrl(url: String) {
        if (disposed) return
        browser.loadURL(url)
    }

    override fun executeJavaScript(script: String) {
        if (disposed) return
        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
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

        release { readinessQuery.dispose() }
        release { transportQuery.dispose() }
        release { browser.dispose() }
        firstFailure?.let { throw it }
    }

    private fun toResponse(payload: String?): JBCefJSQuery.Response =
        if (payload != null) JBCefJSQuery.Response(payload) else JBCefJSQuery.Response(null, REJECTED_STATUS, "rejected")

    private companion object {
        private const val REJECTED_STATUS = 400

        private fun cleanupConstructionFailure(
            failure: Throwable,
            browser: JBCefBrowser,
            transportQuery: JBCefJSQuery?,
        ) {
            fun release(action: () -> Unit) {
                try {
                    action()
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
            }

            transportQuery?.let { release(it::dispose) }
            release { browser.dispose() }
        }
    }
}
