package com.algorist.markflow.editor.native

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Starts the #145 proof only in dedicated diagnostic runs, after a real project has opened. */
class NativeProjectionProbeProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        NativeProjectionProbe.startIfRequested(project)
    }
}
