package com.algorist.markflow.e2e

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.sdk.Document
import com.intellij.driver.sdk.VirtualFile
import com.intellij.driver.sdk.findOpenFile
import com.intellij.driver.sdk.openFile

/**
 * MarkFlow-owned boundary around the experimental JetBrains Driver API.
 * Keep this adapter limited to behavior exercised by the current acceptance slice.
 */
class MarkFlowIdeDriver(private val driver: Driver) {
    fun openMarkdown(fileName: String): Document {
        driver.openFile(fileName, waitForCodeAnalysis = false, isTextEditor = false)
        val file = checkNotNull(driver.findOpenFile(fileName, isTextEditor = false)) {
            "opened file not found after production FileEditorManager open: $fileName"
        }
        return checkNotNull(driver.service<FileDocumentManagerRemote>().getDocument(file)) {
            "authoritative Document unavailable for opened file: $fileName"
        }
    }

    fun source(document: Document): String = document.getText()

    fun isDirty(document: Document): Boolean =
        driver.service<FileDocumentManagerRemote>().isDocumentUnsaved(document)
}

@Remote("com.intellij.openapi.fileEditor.FileDocumentManager")
private interface FileDocumentManagerRemote {
    fun getDocument(file: VirtualFile): Document?
    fun isDocumentUnsaved(document: Document): Boolean
}
