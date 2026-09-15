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

    fun clickText(editor: JEditorUiComponent, text: String) {
        editor.setFocus()
        editor.clickOn(text)
    }

    fun attachNativeProjection(editor: JEditorUiComponent) {
        val bridge = driver.utility(NativeProjectionE2EBridgeRemote::class)
        driver.withContext(OnDispatcher.EDT) {
            check(bridge.attach(editor.editor)) { "native projection E2E controller was already attached" }
            check(bridge.isAttached(editor.editor)) { "native projection E2E controller did not attach" }
            check(bridge.planReady(editor.editor)) { "native projection E2E plan is not READY" }
        }
    }

    fun detachNativeProjection(editor: JEditorUiComponent) {
        val bridge = driver.utility(NativeProjectionE2EBridgeRemote::class)
        driver.withContext(OnDispatcher.EDT) {
            check(bridge.detach(editor.editor)) { "native projection E2E controller was not attached" }
        }
    }

    fun hasProjection(
        editor: JEditorUiComponent,
        kind: String,
        startOffset: Int,
        endOffset: Int,
    ): Boolean {
        val bridge = driver.utility(NativeProjectionE2EBridgeRemote::class)
        return driver.withContext(OnDispatcher.EDT) {
            bridge.hasProjection(editor.editor, kind, startOffset, endOffset)
        }
    }

    fun isFoldCollapsed(editor: JEditorUiComponent, startOffset: Int, endOffset: Int): Boolean =
        driver.withContext(OnDispatcher.EDT) {
            val remoteEditor = driver.cast(editor.editor, FoldingEditorRemote::class)
            val region = remoteEditor.getFoldingModel().getFoldRegion(startOffset, endOffset)
                ?: return@withContext false
            region.isValid() && !region.isExpanded()
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

@Remote(value = "com.algorist.markflow.editor.native.NativeProjectionE2EBridge", plugin = "com.algorist.markflow")
private interface NativeProjectionE2EBridgeRemote {
    fun attach(editor: Editor): Boolean
    fun detach(editor: Editor): Boolean
    fun isAttached(editor: Editor): Boolean
    fun planReady(editor: Editor): Boolean
    fun hasProjection(editor: Editor, kind: String, startOffset: Int, endOffset: Int): Boolean
}

@Remote("com.intellij.openapi.editor.Editor")
private interface FoldingEditorRemote {
    fun getFoldingModel(): FoldingModelRemote
}

@Remote("com.intellij.openapi.editor.FoldingModel")
private interface FoldingModelRemote {
    fun getFoldRegion(startOffset: Int, endOffset: Int): FoldRegionRemote?
}

@Remote("com.intellij.openapi.editor.FoldRegion")
private interface FoldRegionRemote {
    fun isValid(): Boolean
    fun isExpanded(): Boolean
}
