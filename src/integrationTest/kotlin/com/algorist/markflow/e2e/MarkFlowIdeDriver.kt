package com.algorist.markflow.e2e

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.components.common.JEditorUiComponent
import com.intellij.driver.sdk.ui.components.common.editor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.waitFor
import kotlin.time.Duration.Companion.seconds

/**
 * MarkFlow-owned boundary around the experimental JetBrains Driver API.
 * Keep this adapter limited to behavior exercised by the current acceptance slice.
 */
class MarkFlowIdeDriver(private val driver: Driver) {
    fun openMarkdown(fileName: String): JEditorUiComponent {
        // The native MarkFlow target augments IntelliJ's normal platform text editor. With JCEF
        // disabled, Markdown may still have other accepting providers (for example the bundled
        // Markdown Compose editor), so explicitly select the platform text-editor surface here.
        driver.openFile(fileName, waitForCodeAnalysis = false, isTextEditor = true)
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

@Remote("com.intellij.openapi.fileEditor.FileDocumentManager")
private interface FileDocumentManagerRemote {
    fun isDocumentUnsaved(document: com.intellij.driver.sdk.Document): Boolean
}
