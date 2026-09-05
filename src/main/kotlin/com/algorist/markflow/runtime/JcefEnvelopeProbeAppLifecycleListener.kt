package com.algorist.markflow.runtime

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.application.ApplicationManager

/**
 * Diagnostic-only fallback for CI/sandbox launches that reach the welcome screen without opening a project.
 * Normal project launches start the same probe from MarkFlowStartupActivity. The probe itself is start-once.
 */
internal class JcefEnvelopeProbeAppLifecycleListener : AppLifecycleListener {
    override fun welcomeScreenDisplayed() {
        if (!JcefTransportEnvelopeProbe.enabled) return
        ApplicationManager.getApplication().invokeLater {
            JcefTransportEnvelopeProbe.start()
        }
    }
}
