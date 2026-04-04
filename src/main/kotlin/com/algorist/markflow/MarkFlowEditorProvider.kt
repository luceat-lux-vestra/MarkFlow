package com.algorist.markflow

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jdom.Element

// plugin.xml에 <fileEditorProvider implementation="your.package.MakFlowEditorProvider"/> 로 등록해야 합니다.
class MarkFlowEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        val ext = file.extension?.lowercase()
        val accepted = file.fileType.name.equals("Markdown", ignoreCase = true) || isMarkdownExtension(ext)
        if (accepted) {
            LOG.info("MARKFLOW_UI accept: ${file.path} (type=${file.fileType.name}, ext=${ext ?: "<none>"})")
        }
        return accepted
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        LOG.info("MARKFLOW_UI createEditor: ${file.path}")
        return MarkFlowEditor(project, file)
    }

    override fun getEditorTypeId(): String = "MarkFlowEditor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    override fun readState(sourceElement: Element, project: Project, file: VirtualFile): FileEditorState {
        return MarkFlowEditorState.readFrom(sourceElement) ?: FileEditorState.INSTANCE
    }

    override fun writeState(state: FileEditorState, project: Project, targetElement: Element) {
        (state as? MarkFlowEditorState)?.writeTo(targetElement)
    }

    private fun isMarkdownExtension(ext: String?): Boolean {
        return ext == "md" || ext == "markdown" || ext == "mdown" || ext == "mkdn"
    }

    private companion object {
        private val LOG = Logger.getInstance(MarkFlowEditorProvider::class.java)
    }
}