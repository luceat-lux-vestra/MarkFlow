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
import java.nio.file.Files
import java.nio.file.Path

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

    /**
     * Request source-line navigation on the native editor without moving the caret
     * into a folded Mermaid block. Navigation is not a geometry-parity assertion.
     */
    fun showNativeAtSourceLine(editor: Editor, line: Int): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        require(line in 0 until editor.document.lineCount) { "Invalid native source anchor" }
        val offset = editor.document.getLineStartOffset(line)
        editor.scrollingModel.scrollTo(
            editor.offsetToLogicalPosition(offset),
            com.intellij.openapi.editor.ScrollType.CENTER,
        )
        return "line=" + line + ";offset=" + offset
    }


    /**
     * Stage D: read-only native raster dimensions from the actual installed inlays.
     * Private renderer image access is limited to this test-only bridge and fails
     * closed if its runtime contract changes. The viewport does not imply a clipped
     * artifact: a tall inlay may remain reachable by scrolling.
     */
    fun nativeRasterGeometry(editor: Editor): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val region = editor.scrollingModel.visibleArea
        val rasterInlays = editor.inlayModel
            .getBlockElementsInRange(0, editor.document.textLength)
            .filter { it.renderer.javaClass.name.endsWith(".NativeRasterInlayRenderer") }
        val records = rasterInlays.mapIndexed { index, inlay ->
            val renderer = inlay.renderer
            val field = renderer.javaClass.getDeclaredField("image")
            check(field.trySetAccessible()) { "Stage D native raster image field changed" }
            val image = field.get(renderer) as? java.awt.image.BufferedImage
                ?: error("Stage D inlay does not contain a decoded BufferedImage")
            val displayedWidth = inlay.widthInPixels
            val displayedHeight = inlay.heightInPixels
            val intrinsicWidth = image.width
            val intrinsicHeight = image.height
            check(rasterAspectPreserved(intrinsicWidth, intrinsicHeight, displayedWidth, displayedHeight)) {
                "Stage D detected nonuniform native raster scaling or collapsed dimensions"
            }
            val bounds = requireNotNull(inlay.bounds) {
                "Stage D decoded raster lacks a real inlay bounds rectangle"
            }
            val line = editor.document.getLineNumber(inlay.offset.coerceAtMost(editor.document.textLength))
            listOf(
                "index=" + index,
                "source_line_zero_based=" + line,
                "source_offset=" + inlay.offset,
                "intrinsic_width=" + intrinsicWidth,
                "intrinsic_height=" + intrinsicHeight,
                "displayed_width=" + displayedWidth,
                "displayed_height=" + displayedHeight,
                "bounds_x=" + bounds.x,
                "bounds_y=" + bounds.y,
                "bounds_width=" + bounds.width,
                "bounds_height=" + bounds.height,
            ).joinToString(separator = "\t")
        }
        val header = listOf(
            "schema=markflow-native-raster-geometry/v1",
            "visible_x=" + region.x,
            "visible_y=" + region.y,
            "visible_width=" + region.width,
            "visible_height=" + region.height,
            "raster_count=" + records.size,
        ).joinToString(separator = "\n")
        return header + "\n" + records.joinToString(separator = "\n", postfix = "\n")
    }

    private fun rasterAspectPreserved(iw: Int, ih: Int, dw: Int, dh: Int): Boolean {
        if (iw <= 0 || ih <= 0 || dw <= 0 || dh <= 0) return false
        // Integer-pixel rounding at each axis can contribute at most ~1 pixel.
        return kotlin.math.abs(dw.toLong() * ih - dh.toLong() * iw) <= iw.toLong() + ih
    }

    fun rasterGeometryNegativeControls(): Boolean =
        rasterAspectPreserved(320, 180, 640, 360) &&
            rasterAspectPreserved(333, 201, 666, 403) &&
            !rasterAspectPreserved(320, 180, 640, 240) &&
            !rasterAspectPreserved(320, 180, 0, 360) &&
            !rasterAspectPreserved(320, 180, 1, 1)

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

    /**
     * Executed inside the actual Starter IDEA process, not Gradle's test JVM.
     * Distinguish a loaded-but-unused AppArmor profile from a genuine JCEF failure.
     * Read-only observations; never alter the kernel, JCEF settings or source.
     */
    fun jcefRuntimeEvidence(): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        fun read(path: String): String = runCatching {
            Files.readString(Path.of(path)).trim()
        }.getOrElse { error ->
            "UNAVAILABLE:" + error.javaClass.simpleName
        }
        val command = ProcessHandle.current().info().command().orElse("UNKNOWN")
        val executable = runCatching {
            Files.readSymbolicLink(Path.of("/proc/self/exe")).toString()
        }.getOrElse { error ->
            "UNAVAILABLE:" + error.javaClass.simpleName
        }
        return listOf(
            "idea_process_command=" + command,
            "idea_proc_self_exe=" + executable,
            "idea_java_home=" + System.getProperty("java.home", "UNKNOWN"),
            "idea_apparmor_current=" + read("/proc/self/attr/current"),
            "kernel_apparmor_restrict_unprivileged_userns=" +
                read("/proc/sys/kernel/apparmor_restrict_unprivileged_userns"),
            "kernel_apparmor_restrict_unprivileged_unconfined=" +
                read("/proc/sys/kernel/apparmor_restrict_unprivileged_unconfined"),
        ).joinToString(separator = "\n", postfix = "\n")
    }

    fun referenceBounds(editor: Editor): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        check(referenceShowing(editor)) { "Actual platform Markdown Preview is not showing" }
        val component = editors(editor).second.component
        check(!hasSuspendedBrowserStub(component)) {
            "Bundled Markdown Preview JCEF is suspended; sandbox stub is not a valid visual oracle.\n" +
                jcefRuntimeEvidence()
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
        // Swing layout is asynchronous: caller must wait for actual restored visibility.
    }

    fun nativeShowing(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val (selected, _) = editors(editor)
        return selected.textEditor.editor === editor && editor.contentComponent.isShowing
    }
}
