package com.algorist.markflow.runtime

import com.intellij.openapi.Disposable

/**
 * Runtime-owned JCEF transport seam for exactly one [SourceNativeEditorRuntime] surface.
 *
 * This interface isolates the actual `JBCefBrowser` / `JBCefJSQuery` wiring from the runtime
 * owner's identity/readiness/disposal logic so ownership and lifecycle invariants can be proven
 * with a deterministic fake instead of a real JCEF browser. [JcefSourceNativeRuntimeTransport] is
 * the only production implementation; it is intentionally not a pool and is never shared across
 * runtime owners.
 *
 * Every method must be a no-op (not an exception) once [dispose] has run, since JCEF's native
 * callbacks are inherently allowed to arrive after a Kotlin-side dispose call has already started.
 */
internal interface SourceNativeRuntimeTransport : Disposable {
    /** Navigates the owned browser realm to [url]. Called at most once per transport instance. */
    fun loadUrl(url: String)

    /** Runs [script] in the current page. Silently ignored once disposed. */
    fun executeJavaScript(script: String)

    /**
     * Builds the one-time glue script that defines the window-level bridge functions the browser
     * bootstrap uses to reach [setTransportMessageHandler] and [setReadinessMessageHandler]. Safe
     * to execute repeatedly; each execution simply redefines the same functions.
     */
    fun buildBridgeGlueScript(): String

    /**
     * Registers the sole handler for the web -> host mutation/recovery query. The handler runs on
     * whatever thread JCEF invokes it on; returning `null` means fail-closed rejection, never a
     * "success" payload for a malformed/failed request.
     */
    fun setTransportMessageHandler(handler: (String) -> String?)

    /** Registers the sole handler for the narrow web -> host readiness handshake query. */
    fun setReadinessMessageHandler(handler: (String) -> String?)

    /**
     * Registers the sole handler invoked when a main-frame navigation starts. The runtime uses
     * this earlier boundary to fence a replacement realm before old bootstrap state can deliver
     * host updates into a newly navigating page.
     */
    fun setLoadStartHandler(handler: () -> Unit)

    /** Registers the sole handler invoked once the main frame finishes loading. */
    fun setLoadEndHandler(handler: () -> Unit)
}
