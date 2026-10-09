package com.algorist.markflow.editor.native

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import java.awt.Component
import java.awt.Container
import javax.swing.JLabel
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import java.awt.Rectangle

/**
 * #353 Starter-only seam to the genuine bundled IntelliJ Markdown Preview.
 * No product registration, source mutation, or synthetic renderer.
 * An upstream 2026.2.3 internal change fails closed at the inspected boundary.
 */
@Suppress("unused")
internal object NativeDifferentialPreviewE2EBridge {
    private const val MARKDOWN_SPLIT = "org.intellij.plugins.markdown.ui.preview.MarkdownEditorWithPreview"
    private const val MARKDOWN_PREVIEW = "org.intellij.plugins.markdown.ui.preview.MarkdownPreviewFileEditor"

    private fun editors(editor: Editor): Pair<TextEditorWithPreview, FileEditor> {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val project = requireNotNull(editor.project) { "Differential editor has no project" }
        val selected = requireNotNull(FileEditorManager.getInstance(project).selectedEditor)
        check(selected.javaClass.name == MARKDOWN_SPLIT) {
            "Reference is not the platform Markdown split editor: " + selected.javaClass.name
        }
        check(selected is TextEditorWithPreview && selected.textEditor.editor === editor) {
            "Reference split editor does not own the candidate source Editor"
        }
        // myPreview is protected in TextEditorWithPreview, with no public preview accessor.
        // Reflection is deliberately confined to this test-only compatibility boundary.
        val field = TextEditorWithPreview::class.java.getDeclaredField("myPreview")
        check(field.trySetAccessible()) { "Platform preview field access changed" }
        val preview = field.get(selected) as? FileEditor
            ?: error("Bundled Markdown preview FileEditor unavailable")
        check(preview.javaClass.name == MARKDOWN_PREVIEW) {
            "Reference is not MarkdownPreviewFileEditor: " + preview.javaClass.name
        }
        check(preview.isValid) { "Platform Markdown preview is invalid" }
        return selected to preview
    }

    fun showReferenceAtSourceLine(editor: Editor, line: Int): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        require(line in 0 until editor.document.lineCount) { "Invalid source anchor line" }
        val (selected, preview) = editors(editor)
        selected.setLayout(TextEditorWithPreview.Layout.SHOW_PREVIEW)
        // Invoke the real bundled preview's source-line navigation; never guess scroll offsets.
        preview.javaClass.getMethod(
            "scrollToLine", Editor::class.java, Int::class.javaPrimitiveType
        ).invoke(preview, editor, line)
        return selected.javaClass.name + ";" + preview.javaClass.name + ";line=" + line
    }

    fun referenceShowing(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val (_, preview) = editors(editor)
        val component = preview.component
        return component.isShowing && component.width > 100 && component.height > 100
    }

    // SHOW_PREVIEW hides the Swing editor; callers must cache the remote Editor
    // before that transition and inspect authoritative Document state through it.
    fun sourceText(editor: Editor): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return editor.document.text
    }
    fun sourceStamp(editor: Editor): Long {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return editor.document.modificationStamp
    }
    fun sourceUnsaved(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return FileDocumentManager.getInstance().isDocumentUnsaved(editor.document)
    }
    private fun hasSuspendedBrowserStub(component: Component): Boolean =
        when (component) {
            is JLabel -> component.text?.contains("Embedded Browser is suspended", ignoreCase = true) == true
            is Container -> component.components.any(::hasSuspendedBrowserStub)
            else -> false
        }

    fun referenceBounds(editor: Editor): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        check(referenceShowing(editor)) { "Actual platform Markdown Preview is not showing" }
        val component = editors(editor).second.component
        check(!hasSuspendedBrowserStub(component)) {
            "Bundled Markdown Preview JCEF is suspended; sandbox stub is not a valid visual oracle"
        }
        val point = component.locationOnScreen
        val rect = Rectangle(point.x, point.y, component.width, component.height)
        check(rect.width > 100 && rect.height > 100)
        return listOf(rect.x, rect.y, rect.width, rect.height).joinToString(",")
    }

    fun restoreNative(editor: Editor) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val (selected, _) = editors(editor)
        selected.setLayout(TextEditorWithPreview.Layout.SHOW_EDITOR)
        check(editor.contentComponent.isShowing) { "Source editor was not restored" }
    }
}
