package com.algorist.markflow.renderer.jcef

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Starts the #148 proof only in dedicated real-IDE diagnostic runs. */
class NativeDerivedPresentationProbeProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        NativeDerivedPresentationProbe.startIfRequested(project)
    }
}
