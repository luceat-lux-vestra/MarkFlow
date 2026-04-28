package com.algorist.markflow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.algorist.markflow.browser.MarkFlowSharedBrowserService
import com.algorist.markflow.file.MarkFlowFileSupport

class MarkFlowStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val manager = FileEditorManager.getInstance(project)
        val sharedBrowserService = project.getService(MarkFlowSharedBrowserService::class.java)

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            sharedBrowserService.preWarm()
        }

        // Re-assert MarkFlow editor for markdown files restored during IDE startup.
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            manager.openFiles
                .filter(MarkFlowFileSupport::isMarkFlowTarget)
                .forEach { file ->
                    manager.setSelectedEditor(file, MarkFlowFileSupport.EDITOR_TYPE_ID)
                }
        }

        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: com.intellij.openapi.vfs.VirtualFile) {
                    if (!MarkFlowFileSupport.isMarkFlowTarget(file)) return
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        source.setSelectedEditor(file, MarkFlowFileSupport.EDITOR_TYPE_ID)
                    }
                    LOG.debug("MARKFLOW_UI startup:forced editor for ${file.path}")
                }
            }
        )
    }

    private companion object {
        private val LOG = Logger.getInstance(MarkFlowStartupActivity::class.java)
    }
}
