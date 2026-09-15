package com.algorist.markflow.e2e

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.VirtualFile
import com.intellij.driver.sdk.findOpenFile
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.JEditorUiComponent
import com.intellij.driver.sdk.ui.components.common.editor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.remote.Component
import com.intellij.driver.sdk.waitFor
import kotlin.time.Duration.Companion.seconds

/**
 * MarkFlow-owned boundary around the experimental JetBrains Driver API.
 * Keep this adapter limited to behavior exercised by the current acceptance slice.
 */
class MarkFlowIdeDriver(private val driver: Driver) {
    fun openMarkdown(fileName: String): JEditorUiComponent {
        // IntelliJ's Markdown editor is a TextEditorWithPreview. Its persisted layout can be
        // preview-only, in which case the authoritative platform Editor exists but its Swing
        // component is deliberately hidden. Select the maintained EditorOnly layout action in the
        // active FileEditor context before locating the native EditorComponentImpl.
        driver.openFile(fileName, waitForCodeAnalysis = false, isTextEditor = false)
        val file = checkNotNull(driver.findOpenFile(fileName, isTextEditor = false)) {
            "opened Markdown file not found: $fileName"
        }
        val project = driver.singleProject()
        val selectedFileEditor = driver.withContext(OnDispatcher.EDT) {
            driver.service<FileEditorManagerRemote>(project).getSelectedEditor(file)
        }
        checkNotNull(selectedFileEditor) { "selected Markdown FileEditor unavailable: $fileName" }

        driver.invokeAction(
            "TextEditorWithPreview.Layout.EditorOnly",
            component = selectedFileEditor.getComponent(),
        )

        return driver.ideFrame().editor().also { editor ->
            check(editor.editor.getVirtualFile().getName() == fileName) {
                "active native editor does not own expected Markdown file: $fileName"
            }
            check(editor.isEditable()) { "opened Markdown editor is not editable: $fileName" }
        }
    }

    fun source(editor: JEditorUiComponent): String = editor.document.getText()

    fun isDirty(editor: JEditorUiComponent): Boolean =
        driver.service<FileDocumentManagerRemote>().isDocumentUnsaved(editor.document)

    fun appendAtEnd(editor: JEditorUiComponent, text: String) {
        editor.setFocus()
        editor.moveCaretToOffset(editor.text.length)
        editor.keyboard {
            typeText(text, delayBetweenCharsInMs = 20)
        }
    }

    fun save(editor: JEditorUiComponent) {
        driver.invokeAction("SaveAll", component = editor.component)
        waitFor(
            message = "Markdown document is saved",
            timeout = 10.seconds,
            getter = { isDirty(editor) },
            checker = { dirty -> !dirty },
        )
    }

    fun close(editor: JEditorUiComponent) {
        editor.setFocus()
        driver.invokeAction("CloseContent", component = editor.component)
        editor.waitNotFound(10.seconds)
    }
}

@Remote("com.intellij.openapi.fileEditor.FileEditorManager")
private interface FileEditorManagerRemote {
    fun getSelectedEditor(file: VirtualFile): FileEditorRemote?
}

@Remote("com.intellij.openapi.fileEditor.FileEditor")
private interface FileEditorRemote {
    fun getComponent(): Component
}

@Remote("com.intellij.openapi.fileEditor.FileDocumentManager")
private interface FileDocumentManagerRemote {
    fun isDocumentUnsaved(document: com.intellij.driver.sdk.Document): Boolean
}
