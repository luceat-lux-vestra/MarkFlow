package com.algorist.markflow.editor.native

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Starts the #151 image-import proof only in its dedicated real-IDE run. */
class NativeImageImportProbeProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        NativeImageImportProbe.runIfRequested(project)
    }
}
