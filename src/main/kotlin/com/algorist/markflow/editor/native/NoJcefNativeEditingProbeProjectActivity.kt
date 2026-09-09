package com.algorist.markflow.editor.native

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Starts the #144 optional-JCEF package proof only in its dedicated real-IDE run. */
class NoJcefNativeEditingProbeProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        NoJcefNativeEditingProbe.startIfRequested(project)
    }
}
