package com.algorist.markflow.editor.native

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.CaretStateTransferableData
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException

/**
 * Narrow, inert clipboard seed seam for Starter/Driver acceptance tests before #153 cutover.
 *
 * This object has no registration, startup hook, action, Document access, or test-framework
 * dependency. An external diagnostic client may only seed the IDE clipboard with an exact
 * dual-flavor Transferable; the real IntelliJ paste action and MarkFlow production handlers remain
 * solely responsible for insertion, selection/caret semantics, dirty state, and undo/redo.
 */
@Suppress("unused")
internal object NativeClipboardE2EBridge {
    fun seedMarkdownAndPlain(markdown: String, plain: String, singleSourceCaret: Boolean): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        require(markdown != plain) { "E2E clipboard Markdown and plain payloads must be distinct" }
        val caretState = if (singleSourceCaret) {
            CaretStateTransferableData(intArrayOf(0), intArrayOf(plain.length))
        } else {
            null
        }
        CopyPasteManager.getInstance().setContents(MarkdownPlainTransferable(markdown, plain, caretState))
        return true
    }

    fun clear(): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        CopyPasteManager.getInstance().setContents(StringSelection(""))
        return true
    }

    private class MarkdownPlainTransferable(
        private val markdown: String,
        private val plain: String,
        private val caretState: CaretStateTransferableData?,
    ) : Transferable {
        private val markdownFlavor = DataFlavor("text/markdown;class=java.lang.String", "Markdown")
        private val flavors = buildList {
            add(markdownFlavor)
            add(DataFlavor.stringFlavor)
            if (caretState != null) add(CaretStateTransferableData.FLAVOR)
        }.toTypedArray()

        override fun getTransferDataFlavors(): Array<DataFlavor> = flavors.copyOf()

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavors.any { it == flavor }

        override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
            markdownFlavor -> markdown
            DataFlavor.stringFlavor -> plain
            CaretStateTransferableData.FLAVOR -> caretState ?: throw UnsupportedFlavorException(flavor)
            else -> throw UnsupportedFlavorException(flavor)
        }
    }
}
