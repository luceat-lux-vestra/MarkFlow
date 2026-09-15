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
import java.awt.Point
import java.awt.event.KeyEvent
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

    fun modificationStamp(editor: JEditorUiComponent): Long =
        driver.cast(editor.document, DocumentStampRemote::class).getModificationStamp()

    fun isDirty(editor: JEditorUiComponent): Boolean =
        driver.service<FileDocumentManagerRemote>().isDocumentUnsaved(editor.document)

    fun appendAtEnd(editor: JEditorUiComponent, text: String) {
        val sourceBefore = source(editor)
        val appendOffset = sourceBefore.length

        editor.setFocus()
        waitFor(
            message = "native editor owns keyboard focus before append",
            timeout = 10.seconds,
            getter = {
                driver.withContext(OnDispatcher.EDT) {
                    editor.component.isFocusOwner()
                }
            },
            checker = { focused -> focused },
        )

        // Moving a caret does not clear an existing IntelliJ selection. Collapse any selection
        // through real keyboard interaction first so this helper's contract remains append-only
        // even when the preceding acceptance step selected source text.
        editor.keyboard { right() }
        editor.moveCaretToOffset(appendOffset)
        waitFor(
            message = "primary caret reaches the exact append boundary",
            timeout = 10.seconds,
            getter = { primaryCaretOffset(editor) },
            checker = { offset -> offset == appendOffset },
        )
        waitFor(
            message = "native editor retains keyboard focus at append boundary",
            timeout = 10.seconds,
            getter = {
                driver.withContext(OnDispatcher.EDT) {
                    editor.component.isFocusOwner()
                }
            },
            checker = { focused -> focused },
        )

        editor.keyboard {
            typeText(text, delayBetweenCharsInMs = 20)
        }
        waitFor(
            message = "real editor input appends the exact requested source",
            timeout = 10.seconds,
            getter = { source(editor) },
            checker = { actual -> actual == sourceBefore + text },
        )
    }

    fun typeText(editor: JEditorUiComponent, text: String) {
        editor.setFocus()
        editor.keyboard {
            typeText(text, delayBetweenCharsInMs = 20)
        }
    }

    fun clickText(editor: JEditorUiComponent, text: String) {
        editor.setFocus()
        editor.clickOn(text)
    }

    fun resetToSingleCaret(editor: JEditorUiComponent, offset: Int) {
        editor.setFocus()
        driver.withContext(OnDispatcher.EDT) {
            driver.cast(editor.editor, EditorStateRemote::class).getCaretModel().removeSecondaryCarets()
        }
        // moveCaretToOffset does not clear an existing selection, so collapse it through the same
        // user-level keyboard path used by appendAtEnd before positioning the sole caret.
        editor.keyboard { right() }
        editor.moveCaretToOffset(offset)
    }

    fun selectRangeWithKeyboard(editor: JEditorUiComponent, startOffset: Int, length: Int) {
        require(length > 0) { "keyboard selection length must be positive" }
        editor.setFocus()
        editor.keyboard { right() }
        editor.moveCaretToOffset(startOffset)
        editor.keyboard {
            pressing(KeyEvent.VK_SHIFT) {
                repeat(length) { right() }
            }
        }
    }

    fun invokeMarkdownBold(editor: JEditorUiComponent) {
        editor.setFocus()
        driver.invokeAction(
            "org.intellij.plugins.markdown.ui.actions.styling.ToggleBoldAction",
            component = editor.component,
        )
    }

    fun seedMarkdownClipboard(markdown: String, plain: String, singleSourceCaret: Boolean = false) {
        val bridge = driver.utility(NativeClipboardE2EBridgeRemote::class)
        driver.withContext(OnDispatcher.EDT) {
            check(bridge.seedMarkdownAndPlain(markdown, plain, singleSourceCaret)) {
                "native Markdown clipboard E2E seed failed"
            }
        }
    }

    fun clearClipboard() {
        val bridge = driver.utility(NativeClipboardE2EBridgeRemote::class)
        driver.withContext(OnDispatcher.EDT) {
            check(bridge.clear()) { "native Markdown clipboard E2E cleanup failed" }
        }
    }

    fun paste(editor: JEditorUiComponent) {
        editor.setFocus()
        driver.invokeAction("\$Paste", component = editor.component)
    }

    fun cloneCaretBelow(editor: JEditorUiComponent) {
        editor.setFocus()
        driver.invokeAction("EditorCloneCaretBelow", component = editor.component)
    }

    fun caretCount(editor: JEditorUiComponent): Int =
        driver.withContext(OnDispatcher.EDT) {
            driver.cast(editor.editor, EditorStateRemote::class).getCaretModel().getCaretCount()
        }

    fun primaryCaretOffset(editor: JEditorUiComponent): Int =
        driver.withContext(OnDispatcher.EDT) {
            driver.cast(editor.editor, EditorStateRemote::class)
                .getCaretModel()
                .getPrimaryCaret()
                .getOffset()
        }

    fun removeSecondaryCarets(editor: JEditorUiComponent) {
        driver.withContext(OnDispatcher.EDT) {
            driver.cast(editor.editor, EditorStateRemote::class).getCaretModel().removeSecondaryCarets()
        }
    }

    fun isColumnMode(editor: JEditorUiComponent): Boolean =
        driver.withContext(OnDispatcher.EDT) {
            driver.cast(editor.editor, EditorStateRemote::class).isColumnMode()
        }

    fun setColumnMode(editor: JEditorUiComponent, enabled: Boolean) {
        editor.setFocus()
        if (isColumnMode(editor) != enabled) {
            driver.invokeAction("EditorToggleColumnMode", component = editor.component)
        }
        check(isColumnMode(editor) == enabled) { "failed to set editor column mode to $enabled" }
    }

    fun undo(editor: JEditorUiComponent) {
        editor.setFocus()
        driver.invokeAction("\$Undo", component = editor.component)
    }

    fun redo(editor: JEditorUiComponent) {
        editor.setFocus()
        driver.invokeAction("\$Redo", component = editor.component)
    }

    fun attachNativeProjection(editor: JEditorUiComponent) {
        val bridge = driver.utility(NativeProjectionE2EBridgeRemote::class)
        driver.withContext(OnDispatcher.EDT) {
            check(bridge.attach(editor.editor)) { "native projection E2E controller was already attached" }
        }
    }

    fun isNativeProjectionAttached(editor: JEditorUiComponent): Boolean {
        val bridge = driver.utility(NativeProjectionE2EBridgeRemote::class)
        return driver.withContext(OnDispatcher.EDT) {
            bridge.isAttached(editor.editor)
        }
    }

    fun isNativeProjectionPlanReady(editor: JEditorUiComponent): Boolean {
        val bridge = driver.utility(NativeProjectionE2EBridgeRemote::class)
        return driver.withContext(OnDispatcher.EDT) {
            bridge.planReady(editor.editor)
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

    fun tableModels(editor: JEditorUiComponent): Int {
        val bridge = driver.utility(NativeProjectionE2EBridgeRemote::class)
        return driver.withContext(OnDispatcher.EDT) { bridge.tableModels(editor.editor) }
    }

    fun tableOwnedInlays(editor: JEditorUiComponent): Int {
        val bridge = driver.utility(NativeProjectionE2EBridgeRemote::class)
        return driver.withContext(OnDispatcher.EDT) { bridge.tableOwnedInlays(editor.editor) }
    }

    fun tableOwnedFolds(editor: JEditorUiComponent): Int {
        val bridge = driver.utility(NativeProjectionE2EBridgeRemote::class)
        return driver.withContext(OnDispatcher.EDT) { bridge.tableOwnedFolds(editor.editor) }
    }

    fun tableMouseReveals(editor: JEditorUiComponent): Long {
        val bridge = driver.utility(NativeProjectionE2EBridgeRemote::class)
        return driver.withContext(OnDispatcher.EDT) { bridge.tableMouseReveals(editor.editor) }
    }

    fun clickNativeTableInlay(editor: JEditorUiComponent) {
        val bridge = driver.utility(NativeProjectionE2EBridgeRemote::class)
        val x = driver.withContext(OnDispatcher.EDT) { bridge.tableInlayCenterX(editor.editor) }
        val y = driver.withContext(OnDispatcher.EDT) { bridge.tableInlayCenterY(editor.editor) }
        check(x >= 0 && y >= 0) { "native table inlay is not visible in the editor viewport" }
        editor.setFocus()
        editor.click(Point(x, y))
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

@Remote("com.intellij.openapi.editor.Document")
private interface DocumentStampRemote {
    fun getModificationStamp(): Long
}

@Remote(value = "com.algorist.markflow.editor.native.NativeClipboardE2EBridge", plugin = "com.algorist.markflow")
private interface NativeClipboardE2EBridgeRemote {
    fun seedMarkdownAndPlain(markdown: String, plain: String, singleSourceCaret: Boolean): Boolean
    fun clear(): Boolean
}

@Remote(value = "com.algorist.markflow.editor.native.NativeProjectionE2EBridge", plugin = "com.algorist.markflow")
private interface NativeProjectionE2EBridgeRemote {
    fun attach(editor: Editor): Boolean
    fun detach(editor: Editor): Boolean
    fun isAttached(editor: Editor): Boolean
    fun planReady(editor: Editor): Boolean
    fun hasProjection(editor: Editor, kind: String, startOffset: Int, endOffset: Int): Boolean
    fun tableModels(editor: Editor): Int
    fun tableOwnedInlays(editor: Editor): Int
    fun tableOwnedFolds(editor: Editor): Int
    fun tableMouseReveals(editor: Editor): Long
    fun tableInlayCenterX(editor: Editor): Int
    fun tableInlayCenterY(editor: Editor): Int
}

@Remote("com.intellij.openapi.editor.Editor")
private interface EditorStateRemote {
    fun getCaretModel(): CaretModelRemote
    fun isColumnMode(): Boolean
}

@Remote("com.intellij.openapi.editor.CaretModel")
private interface CaretModelRemote {
    fun getCaretCount(): Int
    fun getPrimaryCaret(): CaretRemote
    fun removeSecondaryCarets()
}

@Remote("com.intellij.openapi.editor.Caret")
private interface CaretRemote {
    fun getOffset(): Int
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
