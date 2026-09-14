package com.algorist.markflow.e2e

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.sdk.Document
import com.intellij.driver.sdk.FileEditorManager
import com.intellij.driver.sdk.copyToClipboard
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.components.common.JEditorUiComponent
import com.intellij.driver.sdk.ui.components.common.codeEditorForFile
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * MarkFlow-owned boundary around the experimental JetBrains Driver API.
 * Acceptance tests use this DSL instead of spreading raw Driver calls and selectors.
 */
class MarkFlowIdeDriver(private val driver: Driver) {
    private val presentationBridge = driver.new(NativePresentationBridgeRemote::class)

    fun openMarkdown(fileName: String): JEditorUiComponent {
        driver.openFile(fileName, waitForCodeAnalysis = false)
        val editor = driver.ui.codeEditorForFile(fileName)
        waitUntil("platform text editor for $fileName") { editor.isEditable() }
        return editor
    }

    fun source(editor: JEditorUiComponent): String = editor.text

    fun select(editor: JEditorUiComponent, literal: String) {
        val start = source(editor).indexOf(literal)
        check(start >= 0) { "literal '$literal' not found in editor source" }
        editor.setSelection(start, start + literal.length)
        check(editor.getSelection() == literal)
    }

    fun moveCaret(editor: JEditorUiComponent, literal: String, delta: Int = 0) {
        val start = source(editor).indexOf(literal)
        check(start >= 0) { "literal '$literal' not found in editor source" }
        editor.moveCaretToOffset(start + delta)
    }

    fun type(editor: JEditorUiComponent, text: String) {
        editor.click()
        editor.keyboard { typeText(text) }
    }

    fun pastePlain(editor: JEditorUiComponent, text: String) {
        driver.copyToClipboard(text)
        editor.click()
        driver.invokeAction("\$Paste", component = editor.component)
    }

    fun invokeBold(editor: JEditorUiComponent) {
        driver.invokeAction(BOLD_ACTION_ID, component = editor.component)
    }

    fun undo(editor: JEditorUiComponent) {
        driver.invokeAction("\$Undo", component = editor.component)
    }

    fun redo(editor: JEditorUiComponent) {
        driver.invokeAction("\$Redo", component = editor.component)
    }

    fun save(editor: JEditorUiComponent) {
        driver.invokeAction("SaveAll", component = editor.component)
        waitUntil("document is saved") { !isDirty(editor) }
    }

    fun isDirty(editor: JEditorUiComponent): Boolean =
        driver.service<FileDocumentManagerRemote>().isDocumentUnsaved(editor.document)

    fun close(editor: JEditorUiComponent) {
        driver.withContext {
            driver.service<FileEditorManager>(driver.singleProject()).closeFile(editor.editor.getVirtualFile())
        }
    }

    fun attachPresentation(editor: JEditorUiComponent) {
        check(presentationBridge.attach(editor.editor))
    }

    fun collapsedFoldCount(editor: JEditorUiComponent): Int =
        presentationBridge.collapsedFoldCount(editor.editor)

    fun tableModelCount(editor: JEditorUiComponent): Int =
        presentationBridge.tableModelCount(editor.editor)

    fun tableInlayCount(editor: JEditorUiComponent): Int =
        presentationBridge.tableInlayCount(editor.editor)

    fun tableMouseRevealCount(editor: JEditorUiComponent): Long =
        presentationBridge.tableMouseRevealCount(editor.editor)

    fun clickOnlyBlockInlay(editor: JEditorUiComponent) {
        val inlays = editor.editor.getInlayModel().getBlockElementsInRange(0, source(editor).length)
        check(inlays.size == 1) { "expected exactly one block inlay, observed ${inlays.size}" }
        editor.clickInlay(inlays.single())
    }

    fun disposePresentation(editor: JEditorUiComponent) {
        presentationBridge.dispose(editor.editor)
    }

    fun waitForSource(editor: JEditorUiComponent, expected: String) {
        waitUntil("exact editor source") { source(editor) == expected }
    }

    fun waitUntil(
        description: String,
        timeout: Duration = 30.seconds,
        predicate: () -> Boolean,
    ) {
        driver.waitFor(
            message = description,
            timeout = timeout,
            errorMessage = { "Timed out waiting for $description" },
            checker = predicate,
        )
    }

    private companion object {
        const val BOLD_ACTION_ID = "org.intellij.plugins.markdown.ui.actions.styling.ToggleBoldAction"
    }
}

@Remote("com.intellij.openapi.fileEditor.FileDocumentManager")
private interface FileDocumentManagerRemote {
    fun isDocumentUnsaved(document: Document): Boolean
}

@Remote(
    value = "com.algorist.markflow.editor.native.NativePresentationE2EBridge",
    plugin = "com.algorist.markflow",
)
private interface NativePresentationBridgeRemote {
    fun attach(editor: com.intellij.driver.sdk.Editor): Boolean
    fun collapsedFoldCount(editor: com.intellij.driver.sdk.Editor): Int
    fun tableModelCount(editor: com.intellij.driver.sdk.Editor): Int
    fun tableInlayCount(editor: com.intellij.driver.sdk.Editor): Int
    fun tableMouseRevealCount(editor: com.intellij.driver.sdk.Editor): Long
    fun dispose(editor: com.intellij.driver.sdk.Editor)
}
