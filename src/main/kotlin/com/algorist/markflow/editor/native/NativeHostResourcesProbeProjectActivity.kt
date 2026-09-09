package com.algorist.markflow.editor.native

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Starts the #147 host-resource proof only in its dedicated real-IDE run. */
class NativeHostResourcesProbeProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        NativeHostResourcesProbe.startIfRequested(project)
    }
}
