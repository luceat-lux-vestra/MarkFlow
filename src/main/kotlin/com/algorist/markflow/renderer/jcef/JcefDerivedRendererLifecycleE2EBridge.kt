package com.algorist.markflow.renderer.jcef

/**
 * Starter-only lifecycle diagnostic for #353.
 *
 * Exposes only the number of MarkFlow-owned derived JCEF runtimes so the real-IDE corpus can prove
 * that every case returns to its pre-case browser baseline before the next Markdown editor opens.
 */
@Suppress("unused")
internal object JcefDerivedRendererLifecycleE2EBridge {
    fun liveRuntimeCount(): Int = JcefDerivedRendererRuntime.liveInstanceCountForDiagnostics
}
