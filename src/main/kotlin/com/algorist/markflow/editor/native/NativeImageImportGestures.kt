package com.algorist.markflow.editor.native

import com.algorist.markflow.file.MarkFlowFileSupport
import com.intellij.codeInsight.hint.HintManager
import com.intellij.ide.PasteProvider
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.FileDropEvent
import com.intellij.openapi.editor.FileDropHandler
import com.intellij.openapi.editor.actions.PasteAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.nio.file.Path
import java.util.Locale

/** Exact-active-transferable clipboard adapter for #151. Plain text never grants file authority. */
class NativeImageImportPasteProvider : PasteProvider {
    override fun performPaste(dataContext: DataContext) {
        val editor = dataContext.getData(CommonDataKeys.EDITOR) ?: return
        val producer = dataContext.getData(PasteAction.TRANSFERABLE_PROVIDER) ?: return
        val transferable = runCatching { producer.produce() }.getOrNull() ?: return
        val inputs = NativeImageImportTransferable.inputsOrNull(transferable) ?: return
        showFailure(editor, NativeImageImportService.import(editor, inputs))
    }

    override fun isPasteEnabled(dataContext: DataContext): Boolean {
        val editor = dataContext.getData(CommonDataKeys.EDITOR) ?: return false
        if (!isSupportedEditor(editor)) return false
        if (editor.caretModel.caretCount != 1) return false
        val producer = dataContext.getData(PasteAction.TRANSFERABLE_PROVIDER) ?: return false
        val transferable = runCatching { producer.produce() }.getOrNull() ?: return false
        return NativeImageImportTransferable.isImageImportPayload(transferable)
    }

    override fun isPastePossible(dataContext: DataContext): Boolean = isPasteEnabled(dataContext)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

/** Maintained IntelliJ 2026.2 file-drop extension. The platform's current caret is the drop offset. */
class NativeImageFileDropHandler : FileDropHandler {
    override suspend fun handleDrop(e: FileDropEvent): Boolean {
        val editor = e.editor ?: return false
        val applicable = readAction {
            isSupportedEditor(editor) && e.files.isNotEmpty() &&
                e.files.any { file -> NativeImageImportTransferable.looksLikeImageFilename(file.name) }
        }
        if (!applicable) return false

        return withContext(Dispatchers.EDT) {
            if (editor.isDisposed) return@withContext true
            val inputs = e.files.map { file -> NativeImageImportInput.LocalFile(file.toPath()) }
            showFailure(editor, NativeImageImportService.import(editor, inputs, editor.caretModel.offset))
            true
        }
    }
}

/** Explicit Insert Image action. Selection order from IntelliJ's chooser is preserved. */
class NativeInsertImageAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        if (!isSupportedEditor(editor)) return

        val descriptor = FileChooserDescriptor(
            true,
            false,
            false,
            false,
            false,
            true,
        ).withTitle("Insert Images into MarkFlow")
            .withDescription("Choose PNG, JPEG, GIF, or BMP image files to import")

        val files = FileChooser.chooseFiles(descriptor, project, null)
        if (files.isEmpty()) return
        val inputs = files.mapNotNull { file ->
            if (!file.isInLocalFileSystem || file.isDirectory) null
            else runCatching { NativeImageImportInput.LocalFile(file.toNioPath()) }.getOrNull()
        }
        if (inputs.size != files.size) {
            showFailure(
                editor,
                NativeImageImportResult.Failure(
                    NativeImageImportFailureCode.INVALID_SOURCE,
                    "Every selected image must be a local regular file.",
                ),
            )
            return
        }
        showFailure(editor, NativeImageImportService.import(editor, inputs))
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null && isSupportedEditor(editor) && editor.caretModel.caretCount == 1
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

internal object NativeImageImportTransferable {
    private val imageLikeExtensions = setOf("png", "jpg", "jpeg", "gif", "bmp", "svg", "webp")

    fun isImageImportPayload(transferable: Transferable): Boolean {
        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            val files = localFiles(transferable) ?: return false
            return files.isNotEmpty() && files.any { looksLikeImageFilename(it.name) }
        }
        return transferable.isDataFlavorSupported(DataFlavor.imageFlavor)
    }

    fun inputsOrNull(transferable: Transferable): List<NativeImageImportInput>? {
        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            val files = localFiles(transferable) ?: return null
            if (files.isEmpty() || files.none { looksLikeImageFilename(it.name) }) return null
            return files.map { NativeImageImportInput.LocalFile(it.toPath()) }
        }
        if (!transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) return null
        val image = runCatching { transferable.getTransferData(DataFlavor.imageFlavor) as? Image }.getOrNull() ?: return null
        return listOf(NativeImageImportInput.ClipboardImage(image))
    }

    fun looksLikeImageFilename(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in imageLikeExtensions

    private fun localFiles(transferable: Transferable): List<File>? {
        val raw = runCatching { transferable.getTransferData(DataFlavor.javaFileListFlavor) }.getOrNull() ?: return null
        val values = raw as? List<*> ?: return null
        if (values.any { it !is File }) return null
        return values.filterIsInstance<File>()
    }
}

private fun isSupportedEditor(editor: Editor): Boolean {
    if (editor.isDisposed) return false
    val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return false
    return file.isInLocalFileSystem && MarkFlowFileSupport.isMarkFlowTarget(file)
}

private fun showFailure(editor: Editor, result: NativeImageImportResult) {
    val failure = result as? NativeImageImportResult.Failure ?: return
    val suffix = if (failure.orphanRelativePaths.isEmpty()) {
        ""
    } else {
        " Orphaned: ${failure.orphanRelativePaths.joinToString(", ")}"
    }
    HintManager.getInstance().showErrorHint(editor, failure.message + suffix)
}
