package com.algorist.markflow.editor.native

import com.algorist.markflow.file.MarkFlowFileSupport
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComponent

/**
 * #563 production MarkFlow-owned native FileEditor provider.
 *
 * The provider coexists with the bundled IntelliJ Markdown/text surfaces and edits the exact same
 * FileDocumentManager Document. Presentation ownership belongs only to the owned editor lifecycle.
 */
class MarkFlowOwnedNativeFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean =
        MarkFlowFileSupport.isMarkFlowTarget(file)

    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(project: Project, file: VirtualFile): TextEditor =
        MarkFlowOwnedNativeFileEditor(project, file)

    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR

    companion object {
        const val EDITOR_TYPE_ID = "markflow-owned-native"

        // Temporary #556 task compatibility only; production acceptance no longer consults it.
        const val PROOF_ENABLED_PROPERTY = "markflow.leap2OwnedNativeFileEditor.enabled"
    }
}

/**
 * Production-ready MarkFlow-owned native editor shell.
 *
 * Source authority remains FileDocumentManager's Document. This class owns only its native Editor,
 * FileEditor state and the MarkFlow presentation attachment registered through the explicit owned
 * lifecycle boundary.
 */
internal class MarkFlowOwnedNativeFileEditor(
    project: Project,
    private val file: VirtualFile,
) : UserDataHolderBase(), TextEditor {
    private val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file)) {
        "No authoritative Document for ${file.path}"
    }
    private val editor: Editor = EditorFactory.getInstance().createEditor(document, project, file, false)
    private val propertyChanges = PropertyChangeSupport(this)
    private var disposed = false
    private var presentationAttached = false

    init {
        ApplicationManager.getApplication().assertIsDispatchThread()
        liveEditors.incrementAndGet()
        try {
            presentationAttached = NativeMarkFlowProductionLifecycle.attachOwned(editor)
        } catch (failure: Throwable) {
            if (!editor.isDisposed) EditorFactory.getInstance().releaseEditor(editor)
            liveEditors.decrementAndGet()
            throw failure
        }
    }

    override fun getEditor(): Editor = editor

    override fun getComponent(): JComponent = editor.component

    override fun getPreferredFocusedComponent(): JComponent = editor.contentComponent

    override fun getName(): String = "MarkFlow"

    override fun getFile(): VirtualFile = file

    override fun getState(level: FileEditorStateLevel): FileEditorState {
        val caret = editor.caretModel.primaryCaret
        return MarkFlowOwnedNativeFileEditorState(
            caretOffset = caret.offset,
            selectionStart = if (caret.hasSelection()) caret.selectionStart else -1,
            selectionEnd = if (caret.hasSelection()) caret.selectionEnd else -1,
            verticalScrollOffset = editor.scrollingModel.verticalScrollOffset,
        )
    }

    override fun setState(state: FileEditorState) {
        val owned = state as? MarkFlowOwnedNativeFileEditorState ?: return
        val maxOffset = editor.document.textLength
        val caretOffset = owned.caretOffset.coerceIn(0, maxOffset)
        editor.caretModel.primaryCaret.moveToOffset(caretOffset)
        if (owned.selectionStart >= 0 && owned.selectionEnd >= owned.selectionStart) {
            editor.selectionModel.setSelection(
                owned.selectionStart.coerceIn(0, maxOffset),
                owned.selectionEnd.coerceIn(0, maxOffset),
            )
        } else {
            editor.selectionModel.removeSelection()
        }
        editor.scrollingModel.scrollVertically(owned.verticalScrollOffset.coerceAtLeast(0))
    }

    override fun isModified(): Boolean = FileDocumentManager.getInstance().isDocumentUnsaved(document)

    override fun isValid(): Boolean = !disposed && file.isValid && !editor.isDisposed

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        propertyChanges.addPropertyChangeListener(listener)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        propertyChanges.removePropertyChangeListener(listener)
    }

    override fun canNavigateTo(navigatable: Navigatable): Boolean = false

    override fun navigateTo(navigatable: Navigatable) = Unit

    override fun dispose() {
        if (disposed) return
        ApplicationManager.getApplication().assertIsDispatchThread()
        disposed = true
        try {
            if (presentationAttached) {
                NativeMarkFlowProductionLifecycle.releaseOwned(editor)
                presentationAttached = false
            }
        } finally {
            if (!editor.isDisposed) EditorFactory.getInstance().releaseEditor(editor)
            liveEditors.decrementAndGet()
        }
    }

    companion object {
        private val liveEditors = AtomicInteger(0)

        internal val liveEditorCountForDiagnostics: Int
            get() = liveEditors.get()

        internal val livePresentationOwnerCountForDiagnostics: Int
            get() = NativeMarkFlowProductionLifecycle.liveOwnedControllerCountForDiagnostics
    }
}

internal data class MarkFlowOwnedNativeFileEditorState(
    val caretOffset: Int,
    val selectionStart: Int,
    val selectionEnd: Int,
    val verticalScrollOffset: Int,
) : FileEditorState {
    override fun canBeMergedWith(otherState: FileEditorState, level: FileEditorStateLevel): Boolean =
        otherState is MarkFlowOwnedNativeFileEditorState
}
