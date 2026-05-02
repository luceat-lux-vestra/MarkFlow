package com.algorist.markflow.editor

import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.application.ApplicationManager
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
import com.intellij.util.concurrency.AppExecutorUtil
import java.beans.PropertyChangeListener
import java.awt.event.HierarchyEvent
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JPanel
import java.awt.BorderLayout
import com.algorist.markflow.MarkFlowDiagnostics
import com.algorist.markflow.browser.MarkFlowSharedBrowserService
import com.algorist.markflow.editor.state.MarkFlowEditorState

class MarkFlowEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    private val hostPanel = JPanel(BorderLayout())
    private val document: Document? = FileDocumentManager.getInstance().getDocument(file)
    private val sharedBrowserService = project.service<MarkFlowSharedBrowserService>()
    private var pendingState: MarkFlowEditorState? = null
    private var lastKnownScrollTop = 0
    private var lastKnownCursorOffset = -1
    private var lastKnownSelectionStart = -1
    private var lastKnownSelectionEnd = -1
    private var lastActivationSettingsPushAtMs = 0L
    private val webContentLock = Any()
    @Volatile
    private var pendingWebContent: String? = null
    private var pendingWebContentApplyFuture: ScheduledFuture<*>? = null
    private var pendingDocumentSaveFuture: ScheduledFuture<*>? = null
    @Volatile
    private var cachedFileText: String? = null
    @Volatile
    private var cachedFileTextStamp: Long = Long.MIN_VALUE
    private var isAttachedToSharedBrowser = false
    private val isUpdatingFromWeb = AtomicBoolean(false)
    @Volatile
    private var disposed = false

    init {
        if (MarkFlowDiagnostics.enabled) {
            LOG.info("MARKFLOW_UI editor init: ${file.path}")
        }
        sharedBrowserService.registerEditor(this)

        document?.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (isUpdatingFromWeb.get() || disposed) return
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
        val hasLease = sharedBrowserService.hasLease(this)
        if (shouldAttach && !hasLease) {
            isAttachedToSharedBrowser = sharedBrowserService.attach(this, hostPanel)
            return
        }
        if (shouldAttach) {
            isAttachedToSharedBrowser = hasLease
            return
        }
        if (!shouldAttach && hasLease) {
            sharedBrowserService.detach(this, hostPanel)
            isAttachedToSharedBrowser = false
        } else if (!shouldAttach) {
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
        queueWebContentSave(content)
    }

    private fun queueWebContentSave(newContent: String) {
        synchronized(webContentLock) {
            if (pendingWebContent == newContent) {
                return
            }
            pendingWebContent = newContent
            pendingDocumentSaveFuture?.cancel(false)
            pendingDocumentSaveFuture = null
            pendingWebContentApplyFuture?.cancel(false)
            pendingWebContentApplyFuture = AppExecutorUtil.getAppScheduledExecutorService().schedule(
                {
                    flushQueuedWebContent()
                },
                WEB_CONTENT_SAVE_COALESCE_MS,
                TimeUnit.MILLISECONDS
            )
        }
    }

    private fun takePendingWebContent(): String? {
        synchronized(webContentLock) {
            pendingWebContentApplyFuture?.cancel(false)
            pendingWebContentApplyFuture = null
            return pendingWebContent.also {
                pendingWebContent = null
            }
        }
    }

    private fun cancelPendingDocumentSave() {
        synchronized(webContentLock) {
            pendingDocumentSaveFuture?.cancel(false)
            pendingDocumentSaveFuture = null
        }
    }

    private fun persistDocument(currentDocument: Document) {
        if (!FileDocumentManager.getInstance().isDocumentUnsaved(currentDocument)) {
            if (MarkFlowDiagnostics.enabled) {
                LOG.debug("MARKFLOW_SAVE persistDocument: skipped (already saved), file=${file.path}")
            }
            return
        }

        val app = ApplicationManager.getApplication()
        val saveAction = {
            try {
                FileDocumentManager.getInstance().saveDocument(currentDocument)
                cachedFileText = currentDocument.text
                cachedFileTextStamp = file.timeStamp
                if (MarkFlowDiagnostics.enabled) {
                    LOG.debug("MARKFLOW_SAVE persistDocument: saved, file=${file.path}")
                }
            } catch (e: Exception) {
                LOG.error("MARKFLOW_SAVE persistDocument: failed, file=${file.path}: ${e.message}", e)
            }
        }

        if (app.isDispatchThread) {
            saveAction()
        } else {
            app.invokeLater(saveAction)
        }
    }

    private fun scheduleDocumentSave(currentDocument: Document) {
        synchronized(webContentLock) {
            pendingDocumentSaveFuture?.cancel(false)
            pendingDocumentSaveFuture = AppExecutorUtil.getAppScheduledExecutorService().schedule(
                {
                    val app = ApplicationManager.getApplication()
                    app.invokeLater {
                        if (!disposed && document === currentDocument) {
                            persistDocument(currentDocument)
                        }
                    }
                },
                WEB_CONTENT_DISK_SAVE_DELAY_MS,
                TimeUnit.MILLISECONDS
            )
        }
    }

    private fun applyContentToDocument(newContent: String, persistImmediately: Boolean) {
        val currentDocument = document ?: run {
            LOG.error("MARKFLOW_SAVE applyContentToDocument: document is null, file=${file.path}")
            return
        }

        // Fast pre-check to avoid EDT dispatch when content is already up-to-date.
        // The definitive check is repeated inside the write action to handle concurrent mutations.
        if (currentDocument.text == newContent) {
            if (MarkFlowDiagnostics.enabled) {
                LOG.debug("MARKFLOW_SAVE applyContentToDocument: no-op (text unchanged)")
            }
            return
        }

        val app = ApplicationManager.getApplication()
        var applied = false
        val applyAction = {
            isUpdatingFromWeb.set(true)
            try {
                CommandProcessor.getInstance().runUndoTransparentAction {
                    app.runWriteAction {
                        // Re-read the document text and re-compute the diff inside the write
                        // action so that replacement offsets are always based on the current
                        // document state. This eliminates the race where a background-thread
                        // caller computes offsets against a stale snapshot and then applies
                        // them to a document that has since changed.
                        val currentText = currentDocument.text
                        if (currentText == newContent) {
                            return@runWriteAction
                        }
                        val edit = DocumentContentDiff.compute(currentText, newContent)
                            ?: return@runWriteAction
                        if (edit.startOffset == 0 && edit.endOffset == currentText.length) {
                            currentDocument.setText(edit.replacement)
                        } else {
                            currentDocument.replaceString(edit.startOffset, edit.endOffset, edit.replacement)
                        }
                        applied = true
                    }
                }
                if (applied) {
                    if (MarkFlowDiagnostics.enabled) {
                        LOG.debug("MARKFLOW_SAVE applyContentToDocument: applied, file=${file.path} contentLength=${newContent.length}")
                    }
                    if (persistImmediately) {
                        cancelPendingDocumentSave()
                        persistDocument(currentDocument)
                    } else {
                        scheduleDocumentSave(currentDocument)
                    }
                } else if (MarkFlowDiagnostics.enabled) {
                    LOG.debug("MARKFLOW_SAVE applyContentToDocument: diff resolved to no-op, file=${file.path}")
                }
            } catch (e: Exception) {
                LOG.error("MARKFLOW_SAVE applyContentToDocument: failed, file=${file.path}: ${e.message}", e)
            } finally {
                isUpdatingFromWeb.set(false)
            }
        }

        if (app.isDispatchThread) {
            applyAction()
        } else {
            if (MarkFlowDiagnostics.enabled) {
                LOG.debug("MARKFLOW_SAVE applyContentToDocument: dispatching via EDT, file=${file.path} thread=${Thread.currentThread().name}")
            }
            app.invokeAndWait(applyAction)
        }
    }

    private fun flushPendingWebContent() {
        cancelPendingDocumentSave()
        val markdown = takePendingWebContent()
        if (markdown != null) {
            applyContentToDocument(markdown, persistImmediately = true)
            return
        }

        val currentDocument = document ?: return
        if (!FileDocumentManager.getInstance().isDocumentUnsaved(currentDocument)) {
            return
        }

        persistDocument(currentDocument)
    }

    private fun flushQueuedWebContent() {
        val markdown = takePendingWebContent() ?: return
        applyContentToDocument(markdown, persistImmediately = false)
    }

    internal fun currentMarkdownText(): String {
        document?.text?.let { return it }
        return readFileTextCached()
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

    override fun isModified(): Boolean {
        val docText = document?.text ?: return false
        return docText != readFileTextCached()
    }

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
        if (disposed) return
        // Trigger an immediate flush of any debounced content in the webview before
        // persisting, so the cefQuery has a chance to arrive before flushPendingWebContent().
        sharedBrowserService.executeForEditor(this, "window.markflowFlushNow?.()")
        // Synchronously flush and save current webview content BEFORE IntelliJ calls detach()
        // This ensures content is saved even when the async cefQuery would be dropped by detach()
        flushPendingWebContent()
        sharedBrowserService.setEditorActive(this, false)
    }

    override fun dispose() {
        if (disposed) return
        // Trigger an immediate flush of any debounced content in the webview before cleanup,
        // so the cefQuery has a chance to arrive before flushPendingWebContent().
        sharedBrowserService.executeForEditor(this, "window.markflowFlushNow?.()")
        // Synchronously flush and save current webview content before cleanup
        flushPendingWebContent()
        disposed = true
        if (isAttachedToSharedBrowser) {
            sharedBrowserService.detach(this, hostPanel)
            isAttachedToSharedBrowser = false
        }
        sharedBrowserService.unregisterEditor(this)
        if (MarkFlowDiagnostics.enabled) {
            LOG.info("MARKFLOW_UI editor dispose: ${file.path}")
        }
    }

    internal fun onSharedBrowserDetachedByPool() {
        isAttachedToSharedBrowser = false
    }

    internal fun isShowingInHost(): Boolean = hostPanel.isShowing

    companion object {
        private val LOG = Logger.getInstance(MarkFlowEditor::class.java)
        private const val ACTIVATION_SETTINGS_REAPPLY_THROTTLE_MS = 300L
        private const val WEB_CONTENT_SAVE_COALESCE_MS = 75L
        private const val WEB_CONTENT_DISK_SAVE_DELAY_MS = 250L
    }

    private fun readFileTextCached(): String {
        val stamp = file.timeStamp
        val cachedStamp = cachedFileTextStamp
        val cachedText = cachedFileText
        if (cachedText != null && cachedStamp == stamp) {
            return cachedText
        }

        return try {
            val text = String(file.contentsToByteArray(), file.charset)
            cachedFileText = text
            cachedFileTextStamp = stamp
            text
        } catch (ex: Exception) {
            LOG.warn("MARKFLOW_UI failed to read markdown for ${file.path}: ${ex.message}")
            cachedFileText = null
            cachedFileTextStamp = stamp
            ""
        }
    }
}
