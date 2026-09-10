package com.algorist.markflow.editor.native

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Routes the dedicated no-JCEF real-IDE harness to the requested native proof. */
class NoJcefNativeEditingProbeProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val imageImportRequested = System.getProperty(NativeImageImportProbe.OUTPUT_PROPERTY)
            ?.isNotBlank() == true
        if (imageImportRequested) {
            NativeImageImportProbe.runIfRequested(project)
        } else {
            NoJcefNativeEditingProbe.startIfRequested(project)
        }
    }
}
