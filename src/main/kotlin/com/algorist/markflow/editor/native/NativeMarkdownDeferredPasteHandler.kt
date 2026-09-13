package com.algorist.markflow.editor.native

import com.algorist.markflow.file.MarkFlowFileSupport
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.CaretStateTransferableData
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.actionSystem.EditorTextInsertHandler
import com.intellij.openapi.editor.actions.PasteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.util.Producer
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable

internal enum class NativeDeferredPasteDisposition {
    NOT_DEFERRED,
    DELEGATE_NO_TEXT,
    DELEGATE_SOURCE_MULTICARET_PAYLOAD,
    DELEGATE_CODE_CONTEXT,
    DELEGATE_UNCHANGED,
    TRANSFORMED,
}

internal data class NativeDeferredPastePreparation(
    val transferable: Transferable,
    val disposition: NativeDeferredPasteDisposition,
)

/**
 * Closes IntelliJ 2026.2's deliberate CopyPastePreProcessor bypass for multicaret/column paste.
 *
 * The handler never mutates the Document. It captures the exact Transferable once, optionally
 * replaces only DataFlavor.stringFlavor, and delegates the resulting Transferable to IntelliJ's
 * existing paste handler. Caret distribution, column-mode caret cloning, selection replacement,
 * guarded/read-only handling, command/undo and caret movement therefore remain platform-owned.
 */
class NativeMarkdownDeferredPasteHandler(
    private val baseHandler: EditorActionHandler?,
) : EditorActionHandler(), EditorTextInsertHandler {
    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        baseHandler?.isEnabled(editor, caret, dataContext) == true

    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        if (!isDeferredMarkFlowPaste(editor)) {
            baseHandler?.execute(editor, caret, dataContext)
            return
        }
        val captured = captureExactTransferable(dataContext, null) ?: return
        delegateCaptured(editor, dataContext, NativeDeferredMarkdownPaste.prepare(editor, captured).transferable)
    }

    override fun execute(
        editor: Editor,
        dataContext: DataContext?,
        producer: Producer<out Transferable>?,
    ) {
        if (!isDeferredMarkFlowPaste(editor)) {
            when (val base = baseHandler) {
                is EditorTextInsertHandler -> base.execute(editor, dataContext, producer)
                else -> base?.execute(editor, null, dataContext)
            }
            return
        }
        val captured = captureExactTransferable(dataContext, producer) ?: return
        delegateCaptured(editor, dataContext, NativeDeferredMarkdownPaste.prepare(editor, captured).transferable)
    }

    private fun delegateCaptured(editor: Editor, dataContext: DataContext?, transferable: Transferable) {
        val base = baseHandler
        if (base is EditorTextInsertHandler) {
            base.execute(editor, dataContext, Producer { transferable })
        } else {
            // EditorPaste's maintained chain is EditorTextInsertHandler-based. If that invariant ever
            // changes, prefer unchanged platform behavior over installing a second paste owner.
            base?.execute(editor, null, dataContext)
        }
    }

    private fun captureExactTransferable(
        dataContext: DataContext?,
        producer: Producer<out Transferable>?,
    ): Transferable? {
        val exactProducer = producer ?: dataContext?.getData(PasteAction.TRANSFERABLE_PROVIDER)
        return EditorModificationUtil.getContentsToPasteToEditor(exactProducer)
    }
}

internal object NativeDeferredMarkdownPaste {
    fun prepare(editor: Editor, transferable: Transferable): NativeDeferredPastePreparation {
        if (!isDeferredMarkFlowPaste(editor)) {
            return NativeDeferredPastePreparation(transferable, NativeDeferredPasteDisposition.NOT_DEFERRED)
        }

        val plain = NativeMarkdownClipboard.readPlainText(transferable)
            ?: return NativeDeferredPastePreparation(transferable, NativeDeferredPasteDisposition.DELEGATE_NO_TEXT)
        val markdown = NativeMarkdownClipboard.readMarkdownForPlatformText(transferable, plain)
        val chosen = NativeMarkdownPastePolicy.choosePayload(markdown, plain)
        if (chosen == plain) {
            return NativeDeferredPastePreparation(transferable, NativeDeferredPasteDisposition.DELEGATE_UNCHANGED)
        }

        val caretState = CaretStateTransferableData.getFrom(transferable)
        if (caretState != null && caretState.caretCount > 1) {
            // Its offsets index the original string flavor. Replacing that string would make
            // ClipboardTextPerCaretSplitter slice a different payload with stale boundaries.
            return NativeDeferredPastePreparation(
                transferable,
                NativeDeferredPasteDisposition.DELEGATE_SOURCE_MULTICARET_PAYLOAD,
            )
        }

        val source = editor.document.immutableCharSequence.toString()
        val locations = deferredDestinationLocations(editor, chosen)
        if (!NativeMarkdownPastePolicy.canNormalizeAt(source, locations)) {
            return NativeDeferredPastePreparation(transferable, NativeDeferredPasteDisposition.DELEGATE_CODE_CONTEXT)
        }

        return NativeDeferredPastePreparation(
            StringFlavorOverrideTransferable(transferable, chosen),
            NativeDeferredPasteDisposition.TRANSFORMED,
        )
    }

    internal fun deferredDestinationLocations(editor: Editor, chosenText: String): List<NativePasteLocation> {
        val carets = editor.caretModel.allCarets
        if (!editor.isColumnMode || carets.size != 1) {
            return carets.map { caret ->
                NativePasteLocation(caret.offset, caret.selectionStart, caret.selectionEnd)
            }
        }

        val primary = carets.single()
        val normalized = NativeMarkdownPastePolicy.normalizeInsertedMarkdown(chosenText)
        val requestedLines = normalized.count { it == '\n' } + 1
        val current = primary.logicalPosition
        val documentLines = editor.document.lineCount
        return buildList {
            repeat(requestedLines) { index ->
                val line = current.line + index
                if (line >= documentLines) return@repeat
                val offset = editor.logicalPositionToOffset(LogicalPosition(line, current.column))
                if (index == 0) {
                    add(NativePasteLocation(offset, primary.selectionStart, primary.selectionEnd))
                } else {
                    add(NativePasteLocation(offset, offset, offset))
                }
            }
        }
    }

    private class StringFlavorOverrideTransferable(
        private val delegate: Transferable,
        private val text: String,
    ) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = delegate.transferDataFlavors

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = delegate.isDataFlavorSupported(flavor)

        override fun getTransferData(flavor: DataFlavor): Any =
            if (flavor == DataFlavor.stringFlavor) text else delegate.getTransferData(flavor)
    }
}

private fun isDeferredMarkFlowPaste(editor: Editor): Boolean {
    if (editor.isDisposed || editor.isViewer) return false
    if (!editor.isColumnMode && editor.caretModel.caretCount <= 1) return false
    val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return false
    return file.isInLocalFileSystem && MarkFlowFileSupport.isMarkFlowTarget(file)
}
