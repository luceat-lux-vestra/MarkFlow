package com.algorist.markflow.editor.native

import com.intellij.codeInsight.editorActions.CopyPastePostProcessor
import com.intellij.codeInsight.editorActions.TextBlockTransferableData
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiFile
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.lang.ref.WeakReference

/**
 * One-shot bridge from IntelliJ's actual paste Transferable into #146 preprocessing.
 *
 * PasteHandler extracts post-processor data from the active Transferable immediately before it
 * invokes CopyPastePreProcessor. The bridge does not perform insertion or post-process source; it
 * only makes that exact Transferable available to the MarkFlow preprocessor on the same thread.
 */
class NativePasteTransferableCapturePostProcessor : CopyPastePostProcessor<TextBlockTransferableData>() {
    override fun collectTransferableData(
        file: PsiFile,
        editor: Editor,
        startOffsets: IntArray,
        endOffsets: IntArray,
    ): List<TextBlockTransferableData> = emptyList()

    override fun extractTransferableData(content: Transferable): List<TextBlockTransferableData> {
        NativePasteTransferableBridge.capture(content)
        return CAPTURE_TOKEN
    }

    override fun processTransferableData(
        project: Project,
        editor: Editor,
        bounds: RangeMarker,
        caretOffset: Int,
        indented: Ref<in Boolean>,
        values: List<TextBlockTransferableData>,
    ) {
        NativePasteTransferableBridge.clear()
    }

    override fun requiresAllDocumentsToBeCommitted(editor: Editor, project: Project): Boolean = false

    private object Token : TextBlockTransferableData {
        override fun getFlavor(): DataFlavor? = null
    }

    private companion object {
        val CAPTURE_TOKEN: List<TextBlockTransferableData> = listOf(Token)
    }
}

internal object NativePasteTransferableBridge {
    private val activeTransferable = ThreadLocal<WeakReference<Transferable>?>()

    fun capture(transferable: Transferable) {
        activeTransferable.set(WeakReference(transferable))
    }

    fun take(): Transferable? {
        val transferable = activeTransferable.get()?.get()
        activeTransferable.remove()
        return transferable
    }

    fun clear() {
        activeTransferable.remove()
    }
}
