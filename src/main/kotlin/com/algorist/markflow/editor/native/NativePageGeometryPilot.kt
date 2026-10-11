package com.algorist.markflow.editor.native

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Graphics
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import javax.swing.BorderFactory
import javax.swing.border.Border
import kotlin.math.roundToInt

/**
 * Research-only #353 source-neutral page typography experiment.
 *
 * The production editor/controller never installs this owner. It cannot be
 * promoted solely because it resembles a screenshot: a whole-editor font is
 * necessarily also used by code spans, fenced code and editable source text,
 * whereas JCEF uses distinct per-element fonts. Use this as a feasibility
 * boundary for source-preserving layout, not a production Markdown renderer.
 *
 * Every mutation is local to one EditorEx, and dispose restores the exact
 * scheme and border objects. Never touch the shared global color scheme or
 * any platform-owned folding region.
 */
internal class NativePageGeometryPilot private constructor(
    private val editor: EditorEx,
    private val originalScheme: EditorColorsScheme,
    private val originalBorder: Border?,
    private val localScheme: EditorColorsScheme,
    private val pageBorder: Border,
    private val paragraphSpacing: List<Inlay<*>>,
) : Disposable {
    private var disposed = false

    data class Measurements(
        val fontFamily: String,
        val fontSizePx: Int,
        val lineHeightPx: Int,
        val leftInsetPx: Int,
        val topInsetPx: Int,
        val paragraphSpacerCount: Int,
        val paragraphSpacerLevelsAndHeights: String,
    )

    fun measurements(): Measurements {
        check(!disposed)
        val insets = editor.insets
        return Measurements(
            editor.colorsScheme.editorFontName,
            editor.colorsScheme.editorFontSize,
            editor.lineHeight,
            insets.left,
            insets.top,
            paragraphSpacing.count { it.isValid },
            paragraphSpacing.asSequence()
                .filter { it.isValid }
                .mapNotNull { inlay ->
                    (inlay.renderer as? NativePageParagraphSpacing)?.let { renderer ->
                        "${renderer.followingHeadingLevel}:${inlay.heightInPixels}"
                    }
                }
                .joinToString(","),
        )
    }

    /**
     * The real Starter capture configures its global editor scheme and editor
     * layout *after* EditorFactory.editorCreated. Re-assert this editor-local
     * research styling only after those platform operations have completed.
     * It never creates another spacer or touches the authoritative Document.
     */
    fun reapplyAfterEditorConfiguration() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        check(!disposed && !editor.isDisposed)
        val source = editor.document.immutableCharSequence.toString()
        val stamp = editor.document.modificationStamp
        val caretOffsets = editor.caretModel.allCarets.map { it.offset }
        val foldStates = editor.foldingModel.allFoldRegions.map {
            Triple(it.startOffset, it.endOffset, it.isExpanded)
        }
        editor.setColorsScheme(localScheme)
        editor.setBorder(pageBorder)
        check(editor.colorsScheme.editorFontSize == BUNDLED_PARAGRAPH_FONT_PX)
        check(editor.colorsScheme.editorFontName == localScheme.editorFontName)
        check(editor.insets.left == REFERENCE_LEFT_INSET_PX &&
            editor.insets.top == EXPERIMENTAL_TOP_INSET_PX) {
            "Research page geometry did not survive editor configuration: insets=${editor.insets}"
        }
        check(editor.document.immutableCharSequence.toString() == source &&
            editor.document.modificationStamp == stamp)
        check(editor.caretModel.allCarets.map { it.offset } == caretOffsets)
        check(editor.foldingModel.allFoldRegions.map {
            Triple(it.startOffset, it.endOffset, it.isExpanded)
        } == foldStates)
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        paragraphSpacing.asReversed().forEach { it.takeIf { inlay -> inlay.isValid }?.dispose() }
        if (editor.isDisposed) return
        // Restore local formatting even when a test fails after installation.
        editor.setBorder(originalBorder)
        editor.setColorsScheme(originalScheme)
    }

    companion object {
        // Genuine 2026.2.3 bundled JCEF computed paragraph font-size=13px;
        // box-left=26px in the pinned A/B capture at DPR=1.
        private const val BUNDLED_PARAGRAPH_FONT_PX = 13
        private const val REFERENCE_LEFT_INSET_PX = 26
        // Top inset is an explicit experimental variable, NOT an accepted
        // global 26px parity rule. The screenshot's image origin includes IDE
        // editor/preview chrome and cannot be equated with CSS page padding.
        private const val EXPERIMENTAL_TOP_INSET_PX = 26

        fun install(editor: Editor): NativePageGeometryPilot? {
            ApplicationManager.getApplication().assertIsDispatchThread()
            if (editor !is EditorEx || editor.isDisposed || editor.isViewer) return null

            // Fail closed on mixed code: changing one editor-wide font to
            // proportional must not silently destroy monospaced code layout.
            val source = editor.document.immutableCharSequence.toString()
            if (source.contains('`') || source.lineSequence().any {
                val line = it.trimStart()
                line.startsWith("```") || line.startsWith("~~~") ||
                    it.startsWith("    ") || it.startsWith("\t")
            }) return null
            val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
            if (plan.status != ProjectionPlanStatus.READY ||
                plan.projections.any {
                    it.kind != NativeProjectionKind.PARAGRAPH && it.kind != NativeProjectionKind.HEADING
                }) return null

            val stamp = editor.document.modificationStamp
            val originalCaret = editor.caretModel.offset
            val originalScheme = editor.colorsScheme
            val originalBorder = editor.scrollPane.border
            val foreignFolds = editor.foldingModel.allFoldRegions.toList()
            val foreignStates = foreignFolds.map { Triple(it.startOffset, it.endOffset, it.isExpanded) }
            val paragraphSpacing = mutableListOf<Inlay<*>>()
            try {
                // IDEA can install a transient/delegating editor scheme whose clone()
                // returns null. Create an explicit editor-local child instead of
                // assuming that clone() produces a valid EditorColorsScheme.
                // Colors/attributes still delegate to the original scheme, while
                // font preferences are copied before local changes.
                val localScheme = EditorColorsSchemeImpl(originalScheme)
                localScheme.name = originalScheme.name
                localScheme.setFontPreferences(originalScheme.fontPreferences)
                localScheme.editorFontName = bundledSansFamily()
                localScheme.editorFontSize = BUNDLED_PARAGRAPH_FONT_PX
                val pageBorder = BorderFactory.createEmptyBorder(
                    EXPERIMENTAL_TOP_INSET_PX, REFERENCE_LEFT_INSET_PX, 0, 0,
                )
                editor.setColorsScheme(localScheme)
                editor.setBorder(pageBorder)

                // Narrow research-only block model based on the real
                // Markdown AST, not hard-coded "Paragraph after H" text.
                // A single physical paragraph line, bounded by blank lines,
                // followed immediately by a parser-proven heading can own
                // spacing at its document end offset. No HTML/CSS margin
                // collapsing is claimed or simulated.
                val doc = editor.document
                val headingLevelsByOffset = plan.projections.asSequence()
                    .filter { it.kind == NativeProjectionKind.HEADING }
                    .mapNotNull { heading ->
                        heading.headingLevel?.let { level ->
                            heading.sourceRange.startOffset to level
                        }
                    }
                    .toMap()
                plan.projections.filter { it.kind == NativeProjectionKind.PARAGRAPH }
                    .forEach { paragraph ->
                        val start = paragraph.sourceRange.startOffset
                        val end = paragraph.sourceRange.endOffset
                        val line = doc.getLineNumber(start)
                        if (line < 1 || line + 2 >= doc.lineCount ||
                            doc.getLineNumber(end - 1) != line ||
                            start != doc.getLineStartOffset(line) ||
                            end != doc.getLineEndOffset(line)) return@forEach
                        val beforeBlank = doc.getLineStartOffset(line - 1) ==
                            doc.getLineEndOffset(line - 1)
                        val afterBlank = doc.getLineStartOffset(line + 1) ==
                            doc.getLineEndOffset(line + 1)
                        val followingLevel =
                            headingLevelsByOffset[doc.getLineStartOffset(line + 2)]
                        if (!beforeBlank || !afterBlank || followingLevel == null) return@forEach
                        // The legacy H1 inline fallback may have installed
                        // this boundary first. Whichever owner got there first
                        // remains the sole owner; never stack two 16px inlays.
                        val headingOwnsGap = editor.inlayModel.getBlockElementsInRange(end, end)
                            .any { it.isValid && it.offset == end &&
                                it.renderer === NativeHeadingParagraphGapRenderer }
                        if (headingOwnsGap) return@forEach
                        // Pinned research model: derive the required gap from
                        // independently measured JCEF P-to-P text intervals,
                        // two IDEA paragraph line heights and the exact native
                        // CustomFold renderer height for this heading level.
                        // This deliberately does NOT claim general CSS layout
                        // fidelity outside the restricted B fixture.
                        val spacingRenderer = NativePageParagraphSpacing.forFollowingHeading(
                            editor, followingLevel,
                        ) ?: return@forEach
                        val extra = editor.inlayModel.addBlockElement(
                            end, true, false, 0, spacingRenderer
                        ) ?: error("Paragraph spacing inlay rejected by IDEA")
                        paragraphSpacing += extra
                    }

                check(editor.document.modificationStamp == stamp && editor.document.text == source)
                check(editor.caretModel.offset == originalCaret)
                check(foreignFolds == editor.foldingModel.allFoldRegions.toList())
                check(foreignFolds.map { Triple(it.startOffset, it.endOffset, it.isExpanded) } == foreignStates)
                return NativePageGeometryPilot(
                    editor, originalScheme, originalBorder, localScheme, pageBorder, paragraphSpacing
                )
            } catch (failure: Throwable) {
                paragraphSpacing.asReversed().forEach { it.takeIf { inlay -> inlay.isValid }?.dispose() }
                if (!editor.isDisposed) {
                    editor.setBorder(originalBorder)
                    editor.setColorsScheme(originalScheme)
                }
                throw failure
            }
        }

        private fun bundledSansFamily(): String {
            val families = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .availableFontFamilyNames.toSet()
            return listOf("Helvetica", "Arial", "FreeSans", "Liberation Sans", "Arimo", "SansSerif")
                .firstOrNull { it in families } ?: "SansSerif"
        }
    }
}

/**
 * Pinned 2026.2.3 JCEF/IDE B-fixture paragraph rhythm experiment ONLY.
 *
 * JCEF reference text-range intervals were independently measured on exact
 * source-identical A/B captures at DPR=1.0, Islands Light, 1200x760 viewport:
 * P1->P2(H2) 102.297, P2->P3(H3) 80.719,
 * P3->P4(H4) 70.984, P4->P5(H5) 70.984, P5->P6(H6) 70.985 CSS px.
 *
 * IDEA places two line-height contributions plus the folded heading box into
 * that same source-anchored interval. One *fixed* 16px gap therefore creates
 * cumulative error. Solve for the remaining spacing with platform font
 * metrics; do not embed fixture source text or overwrite the Markdown Document.
 *
 * This is a measured, reference-pinned feasibility probe, not a generic CSS
 * margin-collapsing implementation and not #353 acceptance.
 */
internal class NativePageParagraphSpacing private constructor(
    val followingHeadingLevel: Int,
    val referenceIntervalCssPx: Double,
    val anticipatedFoldHeightPx: Int,
    val heightPx: Int,
) : com.intellij.openapi.editor.EditorCustomElementRenderer {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int = 1
    override fun calcHeightInPixels(inlay: Inlay<*>): Int = heightPx
    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) = Unit

    companion object {
        private val referenceIntervalsCssPx = mapOf(
            2 to 102.297,
            3 to 80.719,
            4 to 70.984,
            5 to 70.984,
            6 to 70.985,
        )

        fun forFollowingHeading(editor: EditorEx, level: Int): NativePageParagraphSpacing? {
            val referenceInterval = referenceIntervalsCssPx[level] ?: return null
            val verticalPadding = when (level) {
                2 -> 8
                3 -> 7
                4 -> 6
                5 -> 5
                6 -> 4
                else -> return null
            }
            val font = NativeHeadingPreviewTypographyPilot.font(editor, level)
            val metrics = editor.contentComponent.getFontMetrics(font)
            // Mirrors NativeHeadingCustomFoldRenderer's own calcHeightInPixels
            // and IDEA's minimum custom-region height.
            val anticipatedFoldHeight = maxOf(
                editor.lineHeight, metrics.height + verticalPadding * 2,
            )
            val gapPx = (
                referenceInterval - 2.0 * editor.lineHeight - anticipatedFoldHeight
            ).roundToInt()
            // Fail closed instead of inserting impossible/negative spacing
            // when IDE font metrics no longer fit the pinned 2026.2.3 baseline.
            if (gapPx !in 1..64) return null
            return NativePageParagraphSpacing(
                level, referenceInterval, anticipatedFoldHeight, gapPx,
            )
        }
    }
}
