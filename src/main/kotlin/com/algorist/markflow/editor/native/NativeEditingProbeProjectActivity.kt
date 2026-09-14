package com.algorist.markflow.editor.native

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Starts the #152 editing parity supplement before the #146 proof exits the diagnostic IDE. */
class NativeEditingProbeProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        NativeMarkdownEditingParityProbe.runIfRequested(project)
        NativeEditingProbe.startIfRequested(project)
    }
}
