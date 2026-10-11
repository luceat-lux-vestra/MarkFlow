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
        if ((iw >= 16 && dw < 2) || (ih >= 16 && dh < 2)) return false
        // Integer-pixel rounding at each axis can contribute at most ~1 pixel.
        return kotlin.math.abs(dw.toLong() * ih - dh.toLong() * iw) <= iw.toLong() + ih
    }

    fun rasterGeometryNegativeControls(): Boolean =
        rasterAspectPreserved(320, 180, 640, 360) &&
            rasterAspectPreserved(333, 201, 666, 403) &&
            !rasterAspectPreserved(320, 180, 640, 240) &&
            !rasterAspectPreserved(320, 180, 0, 360) &&
            !rasterAspectPreserved(320, 180, 1, 1)

    /**
     * #350/#353 source-line geometry ledger, measured from installed production
     * NativeHeadingInlayRenderer instances (never from estimated CSS or screenshots).
     * These native-only measurements are NOT platform-preview parity evidence.
     * No source mutation, renderer substitution, or tolerance-based acceptance.
     */
    fun nativeHeadingSourceGeometry(editor: Editor): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val document = editor.document
        val heading = Regex("^ {0,3}(#{1,6})(?:[ \\t]+|$)")
        fun lineText(line: Int): String = document.getText(
            com.intellij.openapi.util.TextRange(
                document.getLineStartOffset(line), document.getLineEndOffset(line),
            )
        )
        fun lineY(line: Int): Int {
            val offset = document.getLineStartOffset(line)
            return editor.logicalPositionToXY(editor.offsetToLogicalPosition(offset)).y
        }
        val baseHeight = editor.lineHeight
        check(baseHeight > 0) { "Native editor line height is unavailable" }
        val inlays = editor.inlayModel.getBlockElementsInRange(0, document.textLength)
            .filter { it.renderer is NativeHeadingInlayRenderer }
            .sortedBy { (it.renderer as NativeHeadingInlayRenderer).sourceRange.startOffset }
        // Stable first installed ATX inlay is the cumulative reference point.
        // Avoid mutable origin state inside mapNotNull: Qodana correctly rejects
        // constant-condition checks that appear to initialize on each iteration.
        val firstHeadingLine = inlays.asSequence().mapNotNull { inlay ->
            val sourceLine = document.getLineNumber(
                (inlay.renderer as NativeHeadingInlayRenderer).sourceRange.startOffset
            )
            sourceLine.takeIf { heading.containsMatchIn(lineText(it)) }
        }.firstOrNull()
        val firstHeadingY = firstHeadingLine?.let(::lineY)
        val cumulativeNativeExcess = mutableListOf<Long>()
        val rows = inlays.mapNotNull { inlay ->
            val renderer = inlay.renderer as NativeHeadingInlayRenderer
            val sourceLine = document.getLineNumber(renderer.sourceRange.startOffset)
            val match = heading.find(lineText(sourceLine))?.takeIf { it.range.first == 0 }
                ?: return@mapNotNull null // Setext is handled in a later corpus slice.
            val endLine = document.getLineNumber(renderer.sourceRange.endOffset)
            val nextContentLine = (endLine + 1 until document.lineCount)
                .firstOrNull { lineText(it).isNotBlank() }
                ?: error("No following content checkpoint for ATX source line " + sourceLine)
            val startY = lineY(sourceLine)
            val nextY = lineY(nextContentLine)
            check(nextY > startY) { "Non-monotone installed native heading source anchors" }
            val logicalSpan = nextContentLine - sourceLine
            val expectedPlainPx = logicalSpan.toLong() * baseHeight
            val measuredPx = (nextY - startY).toLong()
            val excessPx = sourceSpanExcess(logicalSpan, nextY - startY, baseHeight)
            val cumulativePx = sourceSpanExcess(
                nextContentLine - requireNotNull(firstHeadingLine),
                nextY - requireNotNull(firstHeadingY), baseHeight,
            )
            cumulativeNativeExcess += cumulativePx
            val bounds = inlay.bounds
            listOf(
                "level=" + match.groupValues[1].length,
                "source_line_zero_based=" + sourceLine,
                "source_offset=" + renderer.sourceRange.startOffset,
                "following_line_zero_based=" + nextContentLine,
                "following_offset=" + document.getLineStartOffset(nextContentLine),
                "heading_y_document_px=" + startY,
                "following_y_document_px=" + nextY,
                "source_span_lines=" + logicalSpan,
                "plain_line_height_px=" + baseHeight,
                "plain_span_px=" + expectedPlainPx,
                "measured_span_px=" + measuredPx,
                "native_extra_px=" + excessPx,
                "cumulative_native_extra_px=" + cumulativePx,
                "inlay_height_px=" + inlay.heightInPixels,
                "inlay_bounds_y=" + (bounds?.y?.toString() ?: "UNAVAILABLE"),
                "inlay_bounds_height=" + (bounds?.height?.toString() ?: "UNAVAILABLE"),
            ).joinToString(separator = "\t")
        }
        val atxSourceCount = (0 until document.lineCount)
            .count { heading.find(lineText(it))?.range?.first == 0 }
        // Raw prefix count is diagnostic only: ATX-looking lines inside fenced
        // code are not real Markdown headings. The selected H1–H6 fixture
        // applies an exact expected installed-inlay count in the Starter test.
        return listOf(
            "schema=markflow-native-heading-source-geometry/v1",
            "geometry_scope=NATIVE_ONLY_NO_PLATFORM_PARITY",
            "base_line_height_px=" + baseHeight,
            "raw_atx_prefixed_line_count=" + atxSourceCount,
            "measured_atx_heading_count=" + rows.size,
            "worst_abs_cumulative_native_extra_px=" +
                (cumulativeNativeExcess.maxOfOrNull { kotlin.math.abs(it) } ?: 0L),
        ).joinToString(separator = "\n", postfix = "\n") +
            rows.joinToString(separator = "\n", postfix = "\n")
    }


    /**
     * #353 Stage E9: retain an *observational* six-heading ledger even when
     * IDEA rejects a CustomFoldRegion due to platform-owned folding geometry.
     * Never remove/expand another plugin's fold to manufacture success.
     *
     * A missing fold is measured as its actual source-line fallback, not as a
     * fabricated CustomFold rectangle. The Starter caller classifies it as FAIL.
     */
    @Suppress("UnstableApiUsage")
    fun nativeHeadingReplacementGeometry(editor: Editor): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val doc = editor.document
        fun lineText(line: Int) = doc.getText(
            com.intellij.openapi.util.TextRange(
                doc.getLineStartOffset(line), doc.getLineEndOffset(line),
            ),
        )
        val folds = editor.foldingModel.allFoldRegions.filter { it.isValid }
        val custom = folds.filterIsInstance<com.intellij.openapi.editor.CustomFoldRegion>()
            .filter { !it.isExpanded && it.renderer is NativeHeadingCustomFoldRenderer }
            .sortedBy { it.startOffset }
        val foreign = folds.filterNot {
            it is com.intellij.openapi.editor.CustomFoldRegion &&
                it.renderer is NativeHeadingCustomFoldRenderer
        }
        val additiveInlays = editor.inlayModel.getBlockElementsInRange(0, doc.textLength)
            .count { it.renderer is NativeHeadingInlayRenderer }
        val coStartInlines = editor.inlayModel.getInlineElementsInRange(0, doc.textLength)
            .filter { it.isValid && it.renderer is NativeHeadingInlineFoldRenderer }
        val headingPattern = Regex("^ {0,3}(#{1,6})(?:[ \\t]+|$)")
        val rows = (0 until 6).map { index ->
            val line = index * 4
            val nextLine = line + 2
            check(nextLine < doc.lineCount &&
                lineText(nextLine) == "Paragraph after H" + (index + 1) + ".") {
                "Stage E9 six-heading fixture source mismatch"
            }
            val match = headingPattern.find(lineText(line))
            check(match != null && match.range.first == 0 &&
                match.groupValues[1].length == index + 1) {
                "Stage E9 ATX H1–H6 fixture identity mismatch"
            }
            val start = doc.getLineStartOffset(line)
            val end = doc.getLineEndOffset(line)
            val headingY = editor.logicalPositionToXY(
                editor.offsetToLogicalPosition(start)
            ).y
            val afterOffset = doc.getLineStartOffset(nextLine)
            val followingY = editor.logicalPositionToXY(
                editor.offsetToLogicalPosition(afterOffset)
            ).y
            val installed = custom.singleOrNull {
                (it.renderer as NativeHeadingCustomFoldRenderer).sourceRange.startOffset == start
            }
            val inlinePilot = coStartInlines.singleOrNull {
                (it.renderer as NativeHeadingInlineFoldRenderer).sourceRange.startOffset == start
            }
            val pilotSourceFold = if (inlinePilot == null) null else folds.singleOrNull {
                it !is com.intellij.openapi.editor.CustomFoldRegion &&
                    it.isValid && !it.isExpanded &&
                    it.startOffset == start && it.endOffset == end
            }
            val rendered = installed?.renderer as? NativeHeadingCustomFoldRenderer
            if (rendered != null) {
                check(rendered.sourceRange.endOffset == end) {
                    "Installed CustomFold source end does not match expected H" + (index + 1)
                }
            }
            val overlap = foreign.filter { it.startOffset < end && it.endOffset > start }
            val foreignRecord = overlap.joinToString(",") {
                it.javaClass.simpleName + ":" + it.startOffset + "-" + it.endOffset +
                    ":expanded=" + it.isExpanded
            }
            val origin = installed?.let { editor.offsetToXY(it.startOffset) }
            // Same declared native font as the opt-in heading renderer.
            // Records paint inputs, NOT inferred ink boundaries.
            val font = NativeHeadingPreviewTypographyPilot.font(editor, index + 1)
            val text = lineText(line).trimStart('#', ' ', '\t')
            val fm = editor.contentComponent.getFontMetrics(font)
            listOf(
                "level=" + (index + 1),
                "source_line_zero_based=" + line,
                "source_start_offset=" + start,
                "source_end_offset=" + end,
                "fold_status=" + (if (installed != null) "INSTALLED" else "MISSING"),
                "fold_start_offset=" + (installed?.startOffset ?: "UNAVAILABLE"),
                "fold_end_offset=" + (installed?.endOffset ?: "UNAVAILABLE"),
                "fold_origin_x_document_px=" + (origin?.x ?: "UNAVAILABLE"),
                "fold_origin_y_document_px=" + (origin?.y ?: "UNAVAILABLE"),
                "fold_width_px=" + (installed?.widthInPixels ?: "UNAVAILABLE"),
                "fold_height_px=" + (installed?.heightInPixels ?: "UNAVAILABLE"),
                "inline_pilot_status=" + (if (inlinePilot != null) "INSTALLED_UNREVIEWED" else "ABSENT"),
                "inline_pilot_source_fold=" + (if (pilotSourceFold != null) "COLLAPSED_OWN_CANDIDATE" else "ABSENT"),
                "inline_pilot_width_px=" + (inlinePilot?.widthInPixels ?: "UNAVAILABLE"),
                "inline_pilot_height_px=" + (inlinePilot?.heightInPixels ?: "UNAVAILABLE"),
                "inline_pilot_bounds_y_px=" + (inlinePilot?.bounds?.y ?: "UNAVAILABLE"),
                "heading_y_document_px=" + headingY,
                "native_paint_font_family=" + font.family,
                "native_paint_font_size_px=" + "%.3f".format(java.util.Locale.ROOT, font.size2D),
                "native_paint_font_weight=BOLD",
                "native_paint_text_width_px=" + fm.stringWidth(text),
                "native_paint_text_origin_x_document_px=" + ((origin?.x ?: editor.offsetToXY(start).x) + 4),
                "following_line_zero_based=" + nextLine,
                "following_offset=" + afterOffset,
                "following_y_document_px=" + followingY,
                "paragraph_after_heading=" + (followingY > headingY),
                "foreign_fold_overlap_count=" + overlap.size,
                "foreign_fold_overlaps=" + foreignRecord,
            ).joinToString("\t")
        }
        val absent = rows.filter { it.contains("fold_status=MISSING") }
            .map { it.substringAfter("level=").substringBefore('\t') }
        return listOf(
            "schema=markflow-native-heading-customfold-geometry/v2",
            "geometry_scope=NATIVE_ONLY_NO_PLATFORM_PARITY",
            "measured_atx_heading_count=" + custom.size,
            "missing_heading_levels=" + absent.joinToString(","),
            "legacy_heading_inlay_count=" + additiveInlays,
            "co_start_inline_pilot_count=" + coStartInlines.size,
            "foreign_fold_count_including_pilot_owned_normal_fold=" + foreign.size,
            "editor_is_showing=" + editor.contentComponent.isShowing,
            "plain_line_height_px=" + editor.lineHeight,
            "native_body_font_family=" + editor.contentComponent.font.family,
            "native_body_font_size_px=" + editor.contentComponent.font.size2D,
            "native_research_page_pilot=" + (NativeMarkFlowProductionLifecycle.researchPagePilot(editor) != null),
            "native_page_spacers_by_following_heading_px=" +
                (NativeMarkFlowProductionLifecycle.researchPagePilot(editor)
                    ?.measurements()?.paragraphSpacerLevelsAndHeights ?: "UNAVAILABLE"),
            "native_editor_left_inset_px=" + editor.insets.left,
            "native_editor_top_inset_px=" + editor.insets.top,
            "native_editor_line_height_px=" + editor.lineHeight,
        ).joinToString("\n", postfix = "\n") +
            rows.joinToString("\n", postfix = "\n")
    }

    // Negative controls for the source-span to actual-pixel excess calculation.
    // The large-drift fixture must not be confused with a plain-line baseline.
    private fun sourceSpanExcess(lineDistance: Int, pixelDistance: Int, baseHeight: Int): Long {
        require(lineDistance > 0 && pixelDistance >= 0 && baseHeight > 0)
        return pixelDistance.toLong() - lineDistance.toLong() * baseHeight
    }

    fun nativeHeadingGeometryNegativeControls(): Boolean =
        sourceSpanExcess(2, 40, 20) == 0L &&
            sourceSpanExcess(2, 88, 20) == 48L &&
            sourceSpanExcess(2, 20, 20) == -20L &&
            sourceSpanExcess(8, 260, 20) == 100L

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
    // Unlike JEditorUiComponent's locator-based accessor, the exact Editor
    // reference remains authoritative when SHOW_PREVIEW hides its Swing view.
    fun primaryCaretOffset(editor: Editor): Int {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return editor.caretModel.primaryCaret.offset
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
