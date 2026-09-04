package com.algorist.markflow.runtime

import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Deterministic lifecycle fake for [SourceNativeRuntimeTransport].
 *
 * This fake proves ownership/lifecycle/disposal/stale-callback invariants for
 * [SourceNativeEditorRuntime] without a real JCEF browser, exactly as the #105 evidence
 * requirements permit ("use lifecycle fakes around browser/query boundaries where practical").
 * It intentionally exposes the raw handler closures so a test can invoke them directly,
 * including invoking a handler captured *before* [dispose] after disposal has run, to simulate a
 * JCEF callback that was already in flight when disposal started.
 */
internal class FakeSourceNativeRuntimeTransport : SourceNativeRuntimeTransport {
    private val panel = JPanel()

    var transportHandler: ((String) -> String?)? = null
        private set
    var readinessHandler: ((String) -> String?)? = null
        private set
    var loadEndHandler: (() -> Unit)? = null
        private set

    val loadedUrls = mutableListOf<String>()
    val executedScripts = mutableListOf<String>()

    var disposed = false
        private set

    override val component: JComponent
        get() = panel

    override fun loadUrl(url: String) {
        if (disposed) return
        loadedUrls += url
    }

    override fun executeJavaScript(script: String) {
        if (disposed) return
        executedScripts += script
    }

    override fun buildBridgeGlueScript(): String = "/* fake-bridge-glue */"

    override fun setTransportMessageHandler(handler: (String) -> String?) {
        transportHandler = handler
    }

    override fun setReadinessMessageHandler(handler: (String) -> String?) {
        readinessHandler = handler
    }

    override fun setLoadEndHandler(handler: () -> Unit) {
        loadEndHandler = handler
    }

    fun fireLoadEnd() {
        loadEndHandler?.invoke()
    }

    /** Number of [deliverToWeb]-shaped script pushes that carried [messageType] in this fake's log. */
    fun deliveredMessageCount(messageType: String): Int =
        executedScripts.count { it.contains("\\\"type\\\":\\\"$messageType\\\"") }

    override fun dispose() {
        if (disposed) return
        disposed = true
    }
}
