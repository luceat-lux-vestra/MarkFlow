package com.algorist.markflow.e2e

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.Editor
import com.intellij.driver.sdk.VirtualFile
import com.intellij.driver.sdk.findOpenFile
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.singleProject
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
        // Open through the normal FileEditorManager path first. In local Driver mode the SDK's
        // isTextEditor argument does not select a provider, so Markdown can initially select the
        // bundled Compose editor. Then use FileEditorManager's maintained openTextEditor contract
        // to focus the platform text editor that the native MarkFlow architecture augments.
        driver.openFile(fileName, waitForCodeAnalysis = false, isTextEditor = false)
        val file = checkNotNull(driver.findOpenFile(fileName, isTextEditor = false)) {
            "opened Markdown file not found: $fileName"
        }
        val project = driver.singleProject()
        val descriptor = driver.new(OpenFileDescriptorRemote::class, project, file)
        val nativeEditor = driver.withContext(OnDispatcher.EDT) {
            driver.service<FileEditorManagerRemote>(project).openTextEditor(descriptor, true)
        }
        checkNotNull(nativeEditor) { "platform text editor unavailable for Markdown file: $fileName" }

        return driver.ideFrame().editor().also { editor ->
            check(editor.editor.getVirtualFile().getPath() == nativeEditor.getVirtualFile().getPath()) {
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

@Remote("com.intellij.openapi.fileEditor.OpenFileDescriptor")
private interface OpenFileDescriptorRemote

@Remote("com.intellij.openapi.fileEditor.FileEditorManager")
private interface FileEditorManagerRemote {
    fun openTextEditor(descriptor: OpenFileDescriptorRemote, focusEditor: Boolean): Editor?
}

@Remote("com.intellij.openapi.fileEditor.FileDocumentManager")
private interface FileDocumentManagerRemote {
    fun isDocumentUnsaved(document: com.intellij.driver.sdk.Document): Boolean
}
