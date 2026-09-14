package com.algorist.markflow.editor.native

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Starts the #152 parity supplement before the #145 proof exits the dedicated diagnostic IDE. */
class NativeProjectionProbeProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        NativeMarkdownParityProbe.runIfRequested(project)
        NativeProjectionProbe.startIfRequested(project)
    }
}
