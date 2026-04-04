package com.algorist.markflow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager

class MarkFlowForceRerenderAction : AnAction() {

    init {
        templatePresentation.text = MyBundle.message("action.markflow.forceRerender.text")
        templatePresentation.description = MyBundle.message("action.markflow.forceRerender.description")
    }
    override fun actionPerformed(e: AnActionEvent) {
        val editor = resolveMarkFlowEditor(e) ?: return
        LOG.debug("MARKFLOW_UI action:forceRerender triggered for ${editor.getFile().path}")
        editor.forceRerenderPreviews()
    }

    override fun update(e: AnActionEvent) {
        val isMarkFlowEditor = resolveMarkFlowEditor(e) != null

        e.presentation.isEnabled = isMarkFlowEditor
        e.presentation.isVisible = isMarkFlowEditor
    }

    private fun resolveMarkFlowEditor(e: AnActionEvent): MarkFlowEditor? =
        when (val fromContext = e.getData(PlatformCoreDataKeys.FILE_EDITOR)) {
            is MarkFlowEditor -> fromContext
            else -> e.project?.let { project ->
                FileEditorManager.getInstance(project).selectedEditor as? MarkFlowEditor
            }
        }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    companion object {
        private val LOG = Logger.getInstance(MarkFlowForceRerenderAction::class.java)
        const val ACTION_ID = "MarkFlowForceRerenderAction"
    }
}

