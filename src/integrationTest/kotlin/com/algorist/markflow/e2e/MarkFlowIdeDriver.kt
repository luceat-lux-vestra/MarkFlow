package com.algorist.markflow.e2e

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.sdk.Document
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.components.common.JEditorUiComponent
import com.intellij.driver.sdk.ui.components.common.codeEditorForFile
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * MarkFlow-owned boundary around the experimental JetBrains Driver API.
 * Keep this adapter limited to behavior exercised by the current acceptance slice.
 */
class MarkFlowIdeDriver(private val driver: Driver) {
    fun openMarkdown(fileName: String): JEditorUiComponent {
        driver.openFile(fileName, waitForCodeAnalysis = false)
        val editor = driver.ui.codeEditorForFile(fileName)
        waitUntil("platform text editor for $fileName") { editor.isEditable() }
        return editor
    }

    fun source(editor: JEditorUiComponent): String = editor.text

    fun isDirty(editor: JEditorUiComponent): Boolean =
        driver.service<FileDocumentManagerRemote>().isDocumentUnsaved(editor.document)

    private fun waitUntil(
        description: String,
        timeout: Duration = 30.seconds,
        predicate: () -> Boolean,
    ) {
        waitFor(
            message = description,
            timeout = timeout,
            errorMessage = { "Timed out waiting for $description" },
            condition = predicate,
        )
    }
}

@Remote("com.intellij.openapi.fileEditor.FileDocumentManager")
private interface FileDocumentManagerRemote {
    fun isDocumentUnsaved(document: Document): Boolean
}
