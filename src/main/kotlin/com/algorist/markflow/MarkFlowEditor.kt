package com.algorist.markflow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.beans.PropertyChangeListener
import java.awt.event.HierarchyEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JPanel
import java.awt.BorderLayout

class MarkFlowEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    private val hostPanel = JPanel(BorderLayout())
    private val document: Document? = FileDocumentManager.getInstance().getDocument(file)
    private val sharedBrowserService = project.service<MarkFlowSharedBrowserService>()
    private var pendingState: MarkFlowEditorState? = null
    private var lastKnownScrollTop = 0
    private var lastKnownCursorOffset = -1
    private var lastKnownSelectionStart = -1
    private var lastKnownSelectionEnd = -1
    private var pendingWebToDocumentContent: String? = null
    private var webToDocumentApplyScheduled = false
    private var lastActivationSettingsPushAtMs = 0L
    private var isAttachedToSharedBrowser = false
    private val isUpdatingFromWeb = AtomicBoolean(false)
    @Volatile
    private var disposed = false

    init {
        LOG.info("MARKFLOW_UI editor init: ${file.path}")
        sharedBrowserService.registerEditor(this)

        document?.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (isUpdatingFromWeb.get() || disposed) return
                sharedBrowserService.pushMarkdownFromEditor(this@MarkFlowEditor, event.document.text)
            }
        }, this)

        hostPanel.addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() == 0L) {
                return@addHierarchyListener
            }
            syncAttachmentWithVisibility()
        }
    }

    private fun syncAttachmentWithVisibility() {
        if (disposed) return
        val shouldAttach = hostPanel.isShowing
        if (shouldAttach && !isAttachedToSharedBrowser) {
            sharedBrowserService.attach(this, hostPanel)
            isAttachedToSharedBrowser = true
            return
        }
        if (!shouldAttach && isAttachedToSharedBrowser) {
            sharedBrowserService.detach(this, hostPanel)
            isAttachedToSharedBrowser = false
        }
    }

    internal fun applyWebUpdate(
        content: String,
        scrollTop: Int,
        cursorOffset: Int,
        selectionStart: Int,
        selectionEnd: Int
    ) {
        lastKnownScrollTop = scrollTop
        lastKnownCursorOffset = cursorOffset
        lastKnownSelectionStart = selectionStart
        lastKnownSelectionEnd = selectionEnd
        scheduleWebToDocumentApply(content)
    }

    private fun scheduleWebToDocumentApply(newContent: String) {
        pendingWebToDocumentContent = newContent
        if (webToDocumentApplyScheduled) return

        webToDocumentApplyScheduled = true
        ApplicationManager.getApplication().invokeLater {
            webToDocumentApplyScheduled = false
            if (disposed) return@invokeLater
            val target = pendingWebToDocumentContent ?: return@invokeLater
            pendingWebToDocumentContent = null

            WriteCommandAction.runWriteCommandAction(project) {
                val currentDocument = document ?: return@runWriteCommandAction
                if (currentDocument.text == target) return@runWriteCommandAction
                isUpdatingFromWeb.set(true)
                try {
                    currentDocument.setText(target)
                } finally {
                    isUpdatingFromWeb.set(false)
                }
            }
        }
    }

    internal fun currentMarkdownText(): String {
        document?.text?.let { return it }
        return try {
            String(file.contentsToByteArray(), file.charset)
        } catch (ex: Exception) {
            LOG.warn("MARKFLOW_UI failed to read markdown for ${file.path}: ${ex.message}")
            ""
        }
    }

    internal fun applyPendingStateIfPossible() {
        val state = pendingState ?: return
        val safeScrollTop = state.scrollTop.coerceAtLeast(0)
        val safeCursorOffset = state.cursorOffset.coerceAtLeast(-1)
        val safeSelectionStart = state.selectionStart.coerceAtLeast(-1)
        val safeSelectionEnd = state.selectionEnd.coerceAtLeast(-1)
        val script = """
            (function() {
              var state = {
                version: ${state.version},
                scrollTop: $safeScrollTop,
                cursorOffset: $safeCursorOffset,
                selectionStart: $safeSelectionStart,
                selectionEnd: $safeSelectionEnd
              };
              if (typeof window.applyEditorStateFromIntelliJ === 'function') {
                window.applyEditorStateFromIntelliJ(state);
              } else {
                window.scrollTo(0, state.scrollTop || 0);
              }
            })();
        """.trimIndent()
        if (sharedBrowserService.executeForEditor(this, script)) {
            pendingState = null
        }
    }

    internal fun onActivatedInSharedBrowser() {
        applyPendingStateIfPossible()
    }

    internal fun isDisposedEditor(): Boolean = disposed

    override fun getComponent(): JComponent = hostPanel

    override fun getPreferredFocusedComponent(): JComponent = hostPanel

    override fun getName(): String = "MarkFlow Editor"

    override fun getFile(): VirtualFile = file

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = !disposed

    override fun getState(level: FileEditorStateLevel): FileEditorState {
        return MarkFlowEditorState(
            version = MarkFlowEditorState.CURRENT_VERSION,
            scrollTop = lastKnownScrollTop.coerceAtLeast(0),
            cursorOffset = lastKnownCursorOffset,
            selectionStart = lastKnownSelectionStart,
            selectionEnd = lastKnownSelectionEnd
        )
    }

    override fun setState(state: FileEditorState) {
        val incoming = state as? MarkFlowEditorState ?: return
        pendingState = incoming
        applyPendingStateIfPossible()
    }

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        // Pull-based editor state.
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        // Pull-based editor state.
    }

    override fun selectNotify() {
        syncAttachmentWithVisibility()
        sharedBrowserService.setEditorActive(this, true)

        val now = System.currentTimeMillis()
        if (now - lastActivationSettingsPushAtMs >= ACTIVATION_SETTINGS_REAPPLY_THROTTLE_MS) {
            lastActivationSettingsPushAtMs = now
            sharedBrowserService.reapplyRuntimeSettingsForEditor(this, forceReload = false)
        }
    }

    override fun deselectNotify() {
        sharedBrowserService.setEditorActive(this, false)
    }

    fun forceRerenderPreviews() {
        sharedBrowserService.forceRerender(this)
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        if (isAttachedToSharedBrowser) {
            sharedBrowserService.detach(this, hostPanel)
            isAttachedToSharedBrowser = false
        }
        sharedBrowserService.unregisterEditor(this)
        LOG.info("MARKFLOW_UI editor dispose: ${file.path}")
    }

    companion object {
        private val LOG = Logger.getInstance(MarkFlowEditor::class.java)
        private const val ACTIVATION_SETTINGS_REAPPLY_THROTTLE_MS = 300L
    }
}
