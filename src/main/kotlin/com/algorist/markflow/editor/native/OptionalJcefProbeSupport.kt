package com.algorist.markflow.editor.native

/** Diagnostic-only observation of the optional JCEF class boundary. */
internal object OptionalJcefProbeSupport {
    data class State(
        val classPresent: Boolean,
        val supported: Boolean,
    )

    fun read(): State {
        val jcefApp = try {
            Class.forName("com.intellij.ui.jcef.JBCefApp", false, OptionalJcefProbeSupport::class.java.classLoader)
        } catch (_: ClassNotFoundException) {
            return State(classPresent = false, supported = false)
        }

        val isSupported = jcefApp.getMethod("isSupported")
        val supported = isSupported.invoke(null) as? Boolean
            ?: error("JBCefApp.isSupported() did not return Boolean")
        return State(classPresent = true, supported = supported)
    }
}
