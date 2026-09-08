package com.algorist.markflow.editor.native

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Starts the #146 proof only in dedicated diagnostic runs, after a real project has opened. */
class NativeEditingProbeProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        NativeEditingProbe.startIfRequested(project)
    }
}
