package com.algorist.markflow.editor.native

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.CustomFoldRegion
import com.intellij.openapi.editor.CustomFoldRegionRenderer
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Font
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path

class NativeOrdinaryPresentationTest : BasePlatformTestCase() {
    fun testHeadingCustomFoldRendererUsesCurrentEditorTypographyAndPaintsPixels() {
        val source = "## Heading\n\nTail\n"
        myFixture.configureByText("heading-custom-renderer-typography.md", source)
        val editor = myFixture.editor
        val document = editor.document
        val tailOffset = source.indexOf("Tail") + 1
        editor.caretModel.moveToOffset(tailOffset)
        val stampBefore = document.modificationStamp
        val plainY = editor.offsetToXY(tailOffset).y
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(document, 0L))
        val model = NativeHeadingProjectionPlanner.plan(plan).single()
        val sourceLines = requireNotNull(NativeHeadingCustomFoldLinePlanner.plan(editor, model))
        assertEquals(0..1, sourceLines)
        val customRenderer = NativeHeadingCustomFoldRenderer(editor, model)
        var region: CustomFoldRegion? = null
        try {
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                region = editor.foldingModel.addCustomLinesFolding(
                    sourceLines.first, sourceLines.last, customRenderer
                )
            }
            val custom = requireNotNull(region) { "Custom two-line region unavailable" }
            assertTrue(custom.isValid)
            val font = nativeHeadingFont(editor, model.level)
            assertEquals(editor.contentComponent.font.family, font.family)
            assertEquals(java.awt.Font.BOLD, font.style)
            assertEquals(
                editor.colorsScheme.editorFontSize2D * nativeHeadingFontScale(model.level),
                font.size2D,
            )
            val metrics = editor.contentComponent.getFontMetrics(font)
            val padding = NativeHeadingLayoutMetrics.horizontalPadding(editor)
            val naturalWidth = maxOf(1, metrics.stringWidth(model.text) + padding * 2)
            val viewportWidth = editor.scrollingModel.visibleArea.width
            val expectedWidth = if (
                editor.contentComponent.isShowing && viewportWidth > padding * 2 + 1
            ) minOf(naturalWidth, viewportWidth - padding * 2) else naturalWidth
            val expectedHeight = maxOf(editor.lineHeight, metrics.height)
            assertEquals(expectedWidth, custom.widthInPixels)
            assertEquals(expectedHeight, custom.heightInPixels)
            assertEquals(
                "Heading plus blank line must be replaced, not added",
                expectedHeight - editor.lineHeight * 2,
                editor.offsetToXY(tailOffset).y - plainY,
            )
            assertEquals("Heading", customRenderer.displayText)
            assertEquals(model.contentRange.startOffset, customRenderer.contentOffset)

            val bitmap = java.awt.image.BufferedImage(
                maxOf(1, custom.widthInPixels + padding * 2),
                maxOf(1, custom.heightInPixels + editor.lineHeight),
                java.awt.image.BufferedImage.TYPE_INT_ARGB,
            )
            val graphics = bitmap.createGraphics()
            try {
                customRenderer.paint(
                    custom,
                    graphics,
                    java.awt.geom.Rectangle2D.Double(
                        0.0, 0.0,
                        custom.widthInPixels.toDouble(),
                        custom.heightInPixels.toDouble(),
                    ),
                    TextAttributes(),
                )
            } finally {
                graphics.dispose()
            }
            assertTrue(
                (0 until bitmap.width).any { x ->
                    (0 until bitmap.height).any { y -> (bitmap.getRGB(x, y) ushr 24) != 0 }
                },
            )
            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)
        } finally {
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                region?.takeIf { it.isValid }?.let(editor.foldingModel::removeFoldRegion)
            }
        }
    }

    /**
     * #350 native layout contract: a long H2 is neither ellipsized nor added
     * below the source line. The actual visible IntelliJ fold must reserve
     * exactly the measured wrapped rows, even in a narrow editor.
     */
    /**
     * #350 must derive Heading vertical rhythm from the effective editor font,
     * not a hardcoded 13px/16px layout. This contract applies before any
     * visually reviewed IDE font-size / HiDPI E2E acceptance.
     */
    /**
     * Real IntelliJ EditorEx font switching, not just a dimensionless policy
     * arithmetic test. CustomFold owns the same source rows once at every
     * font size and must reflow without duplicating the source line height.
     */
    fun testCustomFoldReflowsWhenActualEditorFontChanges() {
        val source = "## Heading native font reflow\n\nTail paragraph\n"
        myFixture.configureByText("heading-font-reflow-real-editor.md", source)
        val editor = myFixture.editor as com.intellij.openapi.editor.impl.EditorImpl
        val originalFontSize = editor.colorsScheme.editorFontSize2D
        val originalParent = editor.component.parent
        val testWindow = javax.swing.JFrame("MarkFlow native EditorImpl font resize")
        testWindow.setBounds(80, 80, 380, 520)
        testWindow.contentPane.add(editor.component, java.awt.BorderLayout.CENTER)
        testWindow.isVisible = true
        assertTrue("Font reflow must be tested in an attached showing editor",
            editor.contentComponent.isShowing)
        val document = editor.document
        val stamp = document.modificationStamp
        val tailOffset = source.indexOf("Tail paragraph") + 1
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(document, 0L))
        val model = NativeHeadingProjectionPlanner.plan(plan).single()
        val firstHeightByFont = linkedMapOf<Int, Int>()
        val controller = NativeHeadingPresentationController(editor)
        try {
            for (size in listOf(11, 13, 16, 20)) {
                editor.setFontSize(size.toFloat())
                editor.caretModel.moveToOffset(tailOffset)
                val unfoldedY = editor.offsetToXY(tailOffset).y
                controller.applyPlan(plan, richPresentationEnabled = true)
                val custom = editor.foldingModel.allFoldRegions.filterIsInstance<CustomFoldRegion>()
                    .single { it.isValid && it.renderer is NativeHeadingCustomFoldRenderer }
                val font = nativeHeadingFont(editor, model.level)
                val metrics = editor.contentComponent.getFontMetrics(font)
                val expected = maxOf(editor.lineHeight, metrics.height)
                assertEquals("Measured font $size must determine owned fold height",
                    expected, custom.heightInPixels)
                assertEquals("Following paragraph must see replacement, not additive height",
                    expected - editor.lineHeight * 2,
                    editor.offsetToXY(tailOffset).y - unfoldedY)
                assertEquals(source, document.text)
                assertEquals(stamp, document.modificationStamp)
                assertEquals(tailOffset, editor.caretModel.offset)
                firstHeightByFont[size] = custom.heightInPixels
                controller.clearPresentation()
                assertEquals(unfoldedY, editor.offsetToXY(tailOffset).y)
            }
            val heights = firstHeightByFont.values.toList()
            assertEquals(4, heights.size)
            assertTrue("Actual showing editor fold heights failed to scale: $firstHeightByFont",
                heights.zipWithNext().all { (a, b) -> b > a })
        } finally {
            controller.dispose()
            editor.setFontSize(originalFontSize)
            testWindow.contentPane.remove(editor.component)
            testWindow.dispose()
            originalParent?.add(editor.component)
            originalParent?.revalidate()
        }
        assertEquals(source, document.text)
        assertEquals(stamp, document.modificationStamp)
    }

    fun testNativeHeadingLayoutMetricsFollowCurrentEditorFont() {
        myFixture.configureByText("heading-layout-metrics.md", "# Heading\n")
        val editor = myFixture.editor as com.intellij.openapi.editor.impl.EditorImpl
        val original = editor.colorsScheme.editorFontSize2D
        try {
            for (fontPx in listOf(11f, 13f, 16f, 20f)) {
                editor.setFontSize(fontPx)
                val metrics = editor.contentComponent.getFontMetrics(editor.contentComponent.font)
                assertEquals(
                    maxOf(1, metrics.charWidth(' ')),
                    NativeHeadingLayoutMetrics.horizontalPadding(editor),
                )
            }
        } finally {
            editor.setFontSize(original)
        }
        assertTrue(NativeHeadingLayoutMetrics.fitsVerifiedBlankRow(33, 22))
        assertFalse(NativeHeadingLayoutMetrics.fitsVerifiedBlankRow(45, 22))
    }

    fun testH1InlineFallbackDoesNotDownsizeItsNativeHeadingFont() {
        val source = "# Native Heading\n\nBody\n"
        myFixture.configureByText("native-full-size-h1.txt", source)
        val editor = myFixture.editor
        val doc = editor.document
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(doc, 0L))
        val model = NativeHeadingProjectionPlanner.plan(plan).single()
        val fullSize = editor.colorsScheme.editorFontSize2D * nativeHeadingFontScale(1)
        val renderer = NativeHeadingInlineFoldRenderer(editor, model)
        assertEquals(fullSize, renderer.presentationFont().size2D)
        assertTrue(fullSize > editor.colorsScheme.editorFontSize2D)
        assertEquals(source, doc.text)
    }

    fun testNarrowNativeHeadingEllipsizesOneLineWithoutAddingSourceHeight() {
        val title = ("A very long Heading with 한글 and more words ").repeat(10)
        val source = "## $title\n\nTail paragraph\n"
        myFixture.configureByText("native-heading-single-line-ellipsis.txt", source)
        val editor = myFixture.editor
        val doc = editor.document
        val tail = source.indexOf("Tail paragraph") + 1
        editor.caretModel.moveToOffset(tail)
        val plainY = editor.offsetToXY(tail).y
        val stamp = doc.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(doc, 0L))
        val model = NativeHeadingProjectionPlanner.plan(plan).single()
        val parent = editor.component.parent
        val window = javax.swing.JFrame("MarkFlow single-line native Heading")
        val controller = NativeHeadingPresentationController(editor)
        try {
            window.setBounds(80, 80, 360, 550)
            window.contentPane.add(editor.component, java.awt.BorderLayout.CENTER)
            window.isVisible = true
            assertTrue(editor.contentComponent.isShowing)
            controller.applyPlan(plan, richPresentationEnabled = true)
            val fold = editor.foldingModel.allFoldRegions.filterIsInstance<CustomFoldRegion>()
                .single { it.isValid && it.renderer is NativeHeadingCustomFoldRenderer }
            val renderer = fold.renderer as NativeHeadingCustomFoldRenderer
            val baseFont = editor.contentComponent.font
            val nativeFont = baseFont.deriveFont(Font.BOLD,
                editor.colorsScheme.editorFontSize2D * nativeHeadingFontScale(2))
            val metrics = editor.contentComponent.getFontMetrics(nativeFont)
            val available = maxOf(1, fold.widthInPixels - 8)
            val displayed = NativeHeadingEllipsis.rendered(model.text, metrics, available)
            assertTrue("Long Heading must end in ellipsis", displayed.endsWith("…"))
            assertTrue(displayed.length < model.text.length)
            assertTrue(metrics.stringWidth(displayed) <= available)
            assertEquals("Heading must own only a single font-metric line box",
                maxOf(editor.lineHeight, metrics.height), fold.heightInPixels)
            assertEquals("No extra block inlay on top of the original source line",
                fold.heightInPixels - editor.lineHeight * 2,
                editor.offsetToXY(tail).y - plainY)
            val image = java.awt.image.BufferedImage(
                fold.widthInPixels + 4, fold.heightInPixels + 4,
                java.awt.image.BufferedImage.TYPE_INT_ARGB
            )
            val graphics = image.createGraphics()
            try {
                renderer.paint(fold, graphics, java.awt.geom.Rectangle2D.Double(
                    0.0, 0.0, fold.widthInPixels.toDouble(), fold.heightInPixels.toDouble()
                ), TextAttributes())
            } finally {
                graphics.dispose()
            }
            assertTrue("No native Heading ink painted",
                (0 until fold.heightInPixels).any { y ->
                    (0 until fold.widthInPixels).any { x -> image.getRGB(x, y).ushr(24) != 0 }
                })
            assertTrue("Heading ink must not overpaint following source",
                (fold.heightInPixels until image.height).all { y ->
                    (0 until image.width).all { x -> image.getRGB(x, y).ushr(24) == 0 }
                })
            assertEquals(source, doc.text)
            assertEquals(stamp, doc.modificationStamp)
            assertEquals(tail, editor.caretModel.offset)
        } finally {
            controller.dispose()
            window.contentPane.remove(editor.component)
            window.dispose()
            parent?.add(editor.component)
            parent?.revalidate()
        }
        assertEquals(plainY, editor.offsetToXY(tail).y)
        assertEquals(source, doc.text)
        assertEquals(stamp, doc.modificationStamp)
    }

    /** No JCEF-calibrated post-paragraph spacer may ship in native H1 fallback. */
    fun testProductionH1SameStartFallbackKeepsForeignFoldAndNoPreviewGap() {
        val source = "# Heading\n\nFirst paragraph\n\n## Second\n\nSecond paragraph\n"
        myFixture.configureByText("heading-native-no-preview-gap.txt", source)
        val editor = myFixture.editor
        val doc = editor.document
        val stamp = doc.modificationStamp
        val second = source.indexOf("Second paragraph") + 1
        editor.caretModel.moveToOffset(second)
        val plainY = editor.offsetToXY(second).y
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(doc, 0L))
        var foreign: com.intellij.openapi.editor.FoldRegion? = null
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            foreign = editor.foldingModel.addFoldRegion(0, doc.textLength, "foreign heading section")
        }
        val platform = requireNotNull(foreign)
        assertTrue(platform.isValid && platform.isExpanded)
        val controller = NativeHeadingPresentationController(editor)
        try {
            controller.applyPlan(plan, richPresentationEnabled = true)
            assertTrue(platform.isValid && platform.isExpanded)
            assertEquals(0, editor.inlayModel.getBlockElementsInRange(0, doc.textLength)
                .count { it.isValid })
            assertEquals(1, editor.inlayModel.getInlineElementsInRange(0, doc.textLength)
                .count { it.isValid && it.renderer is NativeHeadingInlineFoldRenderer })
            assertEquals(1, editor.foldingModel.allFoldRegions.filterIsInstance<CustomFoldRegion>()
                .count { it.isValid && it.renderer is NativeHeadingCustomFoldRenderer })
            assertTrue(editor.offsetToXY(second).y < plainY +
                2 * editor.lineHeight + 100)
            assertEquals(source, doc.text)
            assertEquals(stamp, doc.modificationStamp)
            assertEquals(second, editor.caretModel.offset)
        } finally {
            controller.dispose()
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                if (platform.isValid) editor.foldingModel.removeFoldRegion(platform)
            }
        }
        assertEquals(plainY, editor.offsetToXY(second).y)
        assertEquals(source, doc.text)
    }

    fun testHeadingCustomFoldLinePlannerRejectsNonblankAndMultilineCases() {
        fun candidate(text: String, expected: IntRange?) {
            myFixture.configureByText("heading-span-probe.md", text)
            val editor = myFixture.editor
            val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
            val model = NativeHeadingProjectionPlanner.plan(plan).single()
            assertEquals(
                "Unexpected whole-source-line replacement plan for: " + text.replace("\n", "\\n"),
                expected,
                NativeHeadingCustomFoldLinePlanner.plan(editor, model),
            )
        }
        // Only a truly empty adjacent line followed by content may be folded.
        candidate("## Heading\n\nTail\n", 0..1)
        candidate("## Heading\nTail\n", 0..0)
        candidate("## Heading\n \nTail\n", 0..0)
        candidate("## Heading\n\n", 0..0)
        // Setext consumes multiple physical lines: the current proposed
        // single-line-rich renderer must not hide its underline or its source.
        candidate("Heading\n=======\n\nTail\n", 0..2)
    }

    /**
     * #350 fail-closed boundary: multiline Setext headings are valid Markdown,
     * but cannot be replaced by a whole-line custom fold in the current pilot.
     * They must not regain the old double-height block inlay as a fallback.
     */
    fun testSourceReplacingSetextPreservesRichHeadingWithoutAdditiveInlay() {
        val source = "Prelude\n\nHeading\n=======\n\nTail\n"
        myFixture.configureByText("heading-setext-native-fold.md", source)
        val editor = myFixture.editor
        val document = editor.document
        val tailOffset = source.indexOf("Tail") + 1
        editor.caretModel.moveToOffset(tailOffset)
        val stamp = document.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(document, 0L))
        val heading = NativeHeadingProjectionPlanner.plan(plan).single()
        assertEquals(2..4, NativeHeadingCustomFoldLinePlanner.plan(editor, heading))
        val controller = NativeHeadingPresentationController(editor)
        try {
            controller.applyPlan(plan, richPresentationEnabled = true)
            val evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.models)
            assertEquals(0, evidence.ownedInlays)
            assertEquals(1, evidence.ownedCustomFolds)
            val fold = editor.foldingModel.allFoldRegions.filterIsInstance<CustomFoldRegion>()
                .single { it.isValid && it.renderer is NativeHeadingCustomFoldRenderer }
            assertEquals(document.getLineStartOffset(2), fold.startOffset)
            assertTrue(fold.heightInPixels > editor.lineHeight)
            assertTrue(editor.inlayModel.getBlockElementsInRange(0, document.textLength)
                .none { it.isValid })
            assertEquals(tailOffset, editor.caretModel.offset)
            assertEquals(source, document.text)
            assertEquals(stamp, document.modificationStamp)

            editor.caretModel.moveToOffset(source.indexOf("Heading") + 2)
            controller.refreshActivity(plan, richPresentationEnabled = true)
            assertEquals(0, controller.evidenceSnapshot().ownedCustomFolds)
            assertEquals(source, document.text)
        } finally {
            controller.dispose()
        }
        assertEquals(source, document.text)
        assertEquals(stamp, document.modificationStamp)
    }


    /**
     * #353 H1-H6 layout migration pilot: six actual Heading models must
     * replace their own heading + verified blank source lines, never add an
     * inlay on top of the old line height. This is intentionally independent
     * of Preview CSS parity and keeps the shipping opt-in disabled.
     *
     * Preserve the native per-paragraph line-Y accounting exactly: every
     * CustomFoldRegion contributes (physical height - replaced source height)
     * once, whereas the old additive inlays contributed a second copy of
     * the original heading line. A real platform section-fold collision has
     * its own fail-closed regressions; never remove someone else's fold.
     */
    fun testSixSourceReplacingHeadingsHaveNonAdditiveCumulativeLineGeometry() {
        val source = (1..6).joinToString("\n\n") { level ->
            "${"#".repeat(level)} Heading level $level\n\nParagraph after H$level."
        } + "\n"
        myFixture.configureByText("six-heading-custom-layout-pilot.md", source)
        val editor = myFixture.editor
        val doc = editor.document
        val paragraphOffsets = (1..6).map { level ->
            source.indexOf("Paragraph after H$level.").also { assertTrue(it >= 0) }
        }
        val plainParagraphY = paragraphOffsets.map { offset -> editor.offsetToXY(offset).y }
        val caretOutsideHeadings = paragraphOffsets.last() + 1
        editor.caretModel.moveToOffset(caretOutsideHeadings)
        val stamp = doc.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(doc, 0L))
        val models = NativeHeadingProjectionPlanner.plan(plan)
        assertEquals(6, models.size)
        val controller = NativeHeadingPresentationController(editor)
        try {
            controller.applyPlan(plan, richPresentationEnabled = true)
            val ev = controller.evidenceSnapshot()
            assertEquals(6, ev.models)
            assertEquals(6, ev.ownedCustomFolds)
            assertEquals(6, ev.fullyConcealed)
            assertEquals(0, ev.ownedInlays)

            val custom = editor.foldingModel.allFoldRegions
                .filterIsInstance<CustomFoldRegion>()
                .filter { it.isValid && it.renderer is NativeHeadingCustomFoldRenderer }
                .sortedBy { it.startOffset }
            assertEquals(6, custom.size)
            var cumulativeReplacementDelta = 0
            for (i in 0 until 6) {
                val headingLine = i * 4
                val model = models[i]
                assertEquals(headingLine..headingLine + 1,
                    NativeHeadingCustomFoldLinePlanner.plan(editor, model))
                val region = custom[i]
                assertFalse(region.isExpanded)
                assertEquals(doc.getLineStartOffset(headingLine), region.startOffset)
                assertEquals(doc.getLineEndOffset(headingLine + 1), region.endOffset)
                val replacedHeight = editor.lineHeight * 2
                cumulativeReplacementDelta += region.heightInPixels - replacedHeight
                assertEquals(
                    "Heading H${i + 1} added source height rather than replacing its two lines",
                    cumulativeReplacementDelta,
                    editor.offsetToXY(paragraphOffsets[i]).y - plainParagraphY[i],
                )
                assertEquals(source, doc.text)
                assertEquals(stamp, doc.modificationStamp)
                assertEquals(caretOutsideHeadings, editor.caretModel.offset)
            }
        } finally {
            controller.dispose()
        }
        assertEquals(0, editor.foldingModel.allFoldRegions.count { it is CustomFoldRegion && it.isValid })
        paragraphOffsets.forEachIndexed { i, offset ->
            assertEquals("Disposal must restore original H${i + 1} paragraph Y",
                plainParagraphY[i], editor.offsetToXY(offset).y)
        }
        assertEquals(caretOutsideHeadings, editor.caretModel.offset)
        assertEquals(source, doc.text)
        assertEquals(stamp, doc.modificationStamp)
    }

    /**
     * An ordinary platform fold with exactly the same bounds as the last
     * Heading line can coexist with the custom replacement: the platform
     * explicitly allows identical normal/custom fold ranges and lets the
     * custom renderer take display precedence. Never delete that normal fold.
     */
    fun testSourceReplacingHeadingCoexistsWithEqualRangePlatformFold() {
        val source = "Tail\n\n# Heading\n"
        myFixture.configureByText("heading-equal-fold-compatibility.md", source)
        val editor = myFixture.editor
        val document = editor.document
        val tail = source.indexOf("Tail") + 1
        editor.caretModel.moveToOffset(tail)
        val stamp = document.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(document, 0L))
        val model = NativeHeadingProjectionPlanner.plan(plan).single()
        val line = document.getLineNumber(model.sourceRange.startOffset)
        assertEquals(line..line, NativeHeadingCustomFoldLinePlanner.plan(editor, model))
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        var platform: com.intellij.openapi.editor.FoldRegion? = null
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            platform = editor.foldingModel.addFoldRegion(start, end, "IDEA Markdown section")
        }
        val foreign = requireNotNull(platform) { "Equal-range normal fold was not installed" }
        assertTrue(foreign.isValid)
        assertTrue(foreign.isExpanded)
        val controller = NativeHeadingPresentationController(editor)
        try {
            controller.applyPlan(plan, richPresentationEnabled = true)
            val evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.models)
            assertEquals(1, evidence.ownedCustomFolds)
            assertEquals(1, evidence.fullyConcealed)
            assertEquals(0, evidence.ownedInlays)
            assertTrue("MarkFlow must not remove or resize the normal fold", foreign.isValid)
            assertTrue(foreign.isExpanded)
            assertEquals(start, foreign.startOffset)
            assertEquals(end, foreign.endOffset)
            assertEquals(source, document.text)
            assertEquals(stamp, document.modificationStamp)
            assertEquals(tail, editor.caretModel.offset)
        } finally {
            controller.dispose()
        }
        // Releasing the MarkFlow renderer must leave IDE-owned folds intact.
        assertTrue(foreign.isValid)
        assertTrue(foreign.isExpanded)
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            if (foreign.isValid) editor.foldingModel.removeFoldRegion(foreign)
        }
        assertEquals(source, document.text)
        assertEquals(stamp, document.modificationStamp)
    }

    /**
     * Even a final Heading with one trailing source newline can conflict
     * with an IDE-owned section fold that includes the document's last byte.
     * The normal fold begins at the same offset but is just one byte longer
     * than the renderer's parser-proven whole-line replacement span.
     */
    fun testSourceReplacingHeadingRejectsSameStartFoldIncludingFinalNewline() {
        val source = "Tail\n\n# Heading\n"
        myFixture.configureByText("heading-trailing-newline-parent.md", source)
        val editor = myFixture.editor
        val doc = editor.document
        val caret = source.indexOf("Tail") + 1
        editor.caretModel.moveToOffset(caret)
        val stamp = doc.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(doc, 0L))
        val heading = NativeHeadingProjectionPlanner.plan(plan).single()
        val line = doc.getLineNumber(heading.sourceRange.startOffset)
        assertEquals(line..line, NativeHeadingCustomFoldLinePlanner.plan(editor, heading))
        val first = doc.getLineStartOffset(line)
        val lastHeadingByte = doc.getLineEndOffset(line)
        assertEquals(doc.textLength - 1, lastHeadingByte)
        var parent: com.intellij.openapi.editor.FoldRegion? = null
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            parent = editor.foldingModel.addFoldRegion(first, doc.textLength, "IDE heading EOF region")
        }
        val external = requireNotNull(parent)
        assertTrue(external.isValid)
        assertTrue(external.isExpanded)
        val controller = NativeHeadingPresentationController(editor)
        try {
            controller.applyPlan(plan, richPresentationEnabled = true)
            val ev = controller.evidenceSnapshot()
            assertEquals(1, ev.models)
            assertEquals(0, ev.ownedCustomFolds)
            assertEquals(0, ev.ownedInlays)
            assertTrue("IDE-owned EOF parent must survive a failed custom install", external.isValid)
            assertTrue(external.isExpanded)
            assertEquals(first, external.startOffset)
            assertEquals(doc.textLength, external.endOffset)
            assertEquals(caret, editor.caretModel.offset)
            assertEquals(source, doc.text)
            assertEquals(stamp, doc.modificationStamp)
        } finally {
            controller.dispose()
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                if (external.isValid) editor.foldingModel.removeFoldRegion(external)
            }
        }
        assertEquals(source, doc.text)
        assertEquals(stamp, doc.modificationStamp)
    }

    /**
     * #350 real IDEA regression: Markdown can own an expanded section fold
     * starting at the same source byte as its heading and ending after the
     * entire section. IntelliJ disallows a nested CustomFoldRegion with the
     * same start offset as that enclosing ordinary fold. Never remove or
     * shrink the platform-owned section merely to force rich presentation.
     */
    fun testSourceReplacingHeadingLeavesSameStartPlatformSectionFoldUntouched() {
        val source = "# Robot Heading\n\nTail\n"
        myFixture.configureByText("heading-custom-parent-section.md", source)
        val editor = myFixture.editor
        val document = editor.document
        val tailOffset = source.indexOf("Tail") + 1
        editor.caretModel.moveToOffset(tailOffset)
        val stamp = document.modificationStamp
        val originalY = editor.offsetToXY(tailOffset).y
        val originalHeadingY = editor.offsetToXY(0).y
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(document, 0L))
        assertEquals(1, NativeHeadingProjectionPlanner.plan(plan).size)
        var platformSection: com.intellij.openapi.editor.FoldRegion? = null
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            platformSection = editor.foldingModel.addFoldRegion(
                0, document.textLength, "platform expanded heading section",
            )
        }
        val foreign = requireNotNull(platformSection) { "Expanded section fold unavailable" }
        assertTrue(foreign.isValid)
        assertTrue(foreign.isExpanded)
        val controller = NativeHeadingPresentationController(editor)
        try {
            controller.applyPlan(plan, richPresentationEnabled = true)
            var evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.models)
            // Under the expanded co-start section the new candidate owns one
            // collapsed *ordinary* source fold plus one inline rich heading.
            // Never conflate it with a CustomFold or additive block element.
            assertEquals(1, evidence.ownedInlays)
            assertEquals(0, evidence.ownedCustomFolds)
            assertEquals(1, evidence.ownedFolds)
            assertEquals(1, evidence.fullyConcealed)
            assertEquals(1, editor.inlayModel.getInlineElementsInRange(0, document.textLength)
                .count { it.isValid && it.renderer is NativeHeadingInlineFoldRenderer })
            assertEquals(0, editor.inlayModel.getBlockElementsInRange(0, document.textLength)
                .count { it.isValid })
            assertTrue(foreign.isValid)
            assertTrue(foreign.isExpanded)
            assertEquals(0, foreign.startOffset)
            assertEquals(document.textLength, foreign.endOffset)
            val projectedY = editor.offsetToXY(tailOffset).y
            assertEquals("Same-line fallback must not shift the title", originalHeadingY,
                editor.offsetToXY(0).y)
            assertEquals("Same-line fallback must not invent paragraph spacing",
                originalY, projectedY)
            assertEquals(source, document.text)
            assertEquals(stamp, document.modificationStamp)

            // Repeated activity refreshes must retain exactly one inlay and
            // never delete, collapse, shorten or replace the foreign section.
            controller.refreshActivity(plan, richPresentationEnabled = true)
            assertEquals(1, controller.evidenceSnapshot().ownedInlays)
            assertEquals(0, controller.evidenceSnapshot().ownedCustomFolds)
            assertEquals(1, editor.inlayModel.getInlineElementsInRange(0, document.textLength)
                .count { it.isValid && it.renderer is NativeHeadingInlineFoldRenderer })
            assertTrue(foreign.isValid)
            assertTrue(foreign.isExpanded)

            // Source reveal is mandatory: move caret into the H1 and remove
            // the MarkFlow-owned fold+inlay without touching the foreign fold.
            editor.caretModel.moveToOffset(source.indexOf("Robot Heading") + 2)
            controller.refreshActivity(plan, richPresentationEnabled = true)
            evidence = controller.evidenceSnapshot()
            assertEquals(0, evidence.ownedInlays)
            assertEquals(0, evidence.ownedFolds)
            assertEquals(0, editor.inlayModel.getBlockElementsInRange(0, document.textLength)
                .count { it.isValid })
            assertEquals(originalY, editor.offsetToXY(tailOffset).y)
            assertTrue(foreign.isValid)
            assertTrue(foreign.isExpanded)
            editor.caretModel.moveToOffset(tailOffset)
            controller.refreshActivity(plan, richPresentationEnabled = true)
            assertEquals(1, controller.evidenceSnapshot().ownedInlays)
            assertTrue(foreign.isValid)
            assertTrue(foreign.isExpanded)

            // Isolate the exact same-start parent-fold constraint. Removing
            // the synthetic foreign fold is allowed only inside this test;
            // a fresh plan then installs the regular CustomFold path.
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                editor.foldingModel.removeFoldRegion(foreign)
            }
            controller.applyPlan(plan, richPresentationEnabled = true)
            evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.ownedCustomFolds)
            assertEquals(1, evidence.ownedFolds)
            assertEquals(0, evidence.ownedInlays)
            assertEquals(0, editor.inlayModel.getInlineElementsInRange(0, document.textLength)
                .count { it.isValid && it.renderer is NativeHeadingInlineFoldRenderer })
            assertEquals(source, document.text)
            assertEquals(stamp, document.modificationStamp)
        } finally {
            controller.dispose()
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                if (foreign.isValid) editor.foldingModel.removeFoldRegion(foreign)
            }
        }
        assertEquals(originalY, editor.offsetToXY(tailOffset).y)
        assertEquals(source, document.text)
        assertEquals(stamp, document.modificationStamp)
    }

    /**
     * Integration pilot: real production heading controller with explicit
     * sourceLineReplacement=true (the shipping default stays false).
     * Tests ownership through the actual controller's click and refresh paths.
     */
    fun testSourceReplacingHeadingControllerOwnedClickRestoreAndInactiveReattach() {
        val source = "# Heading\n\nTail\n"
        myFixture.configureByText("heading-custom-controller-pilot.md", source)
        val editor = myFixture.editor
        val document = editor.document
        val caretInTail = source.indexOf("Tail") + 1
        editor.caretModel.moveToOffset(caretInTail)
        val baselineY = editor.offsetToXY(caretInTail).y
        val stampBefore = document.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(document, 0L))
        val model = NativeHeadingProjectionPlanner.plan(plan).single()
        var foreign: com.intellij.openapi.editor.FoldRegion? = null
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            foreign = editor.foldingModel.addFoldRegion(0, 2, "platform heading marker")
            requireNotNull(foreign).isExpanded = false
        }
        val controller = NativeHeadingPresentationController(editor)
        fun click(
            region: com.intellij.openapi.editor.FoldRegion?,
            button: Int = MouseEvent.BUTTON1,
        ): EditorMouseEvent {
            val mouse = MouseEvent(
                editor.contentComponent, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), 0, 0, 0, 1, false, button,
            )
            return EditorMouseEvent(
                editor, mouse, EditorMouseEventArea.EDITING_AREA,
                0, editor.offsetToLogicalPosition(0),
                editor.offsetToVisualPosition(0), false, region, null, null,
            )
        }
        try {
            controller.applyPlan(plan, richPresentationEnabled = true)
            var evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.models)
            assertEquals(0, evidence.ownedInlays)
            assertEquals(1, evidence.ownedCustomFolds)
            assertEquals(1, evidence.ownedFolds)
            assertEquals(1, evidence.fullyConcealed)
            val fold = editor.foldingModel.allFoldRegions
                .filterIsInstance<CustomFoldRegion>().single()
            assertEquals(
                "Product pilot must replace both heading and verified blank source lines",
                fold.heightInPixels - editor.lineHeight * 2,
                editor.offsetToXY(caretInTail).y - baselineY,
            )
            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)

            val foreignEvent = click(foreign)
            assertFalse(controller.handleMouseReveal(foreignEvent))
            assertFalse(foreignEvent.isConsumed)
            val rightEvent = click(fold, MouseEvent.BUTTON3)
            assertFalse(controller.handleMouseReveal(rightEvent))
            assertFalse(rightEvent.isConsumed)
            assertEquals(1, controller.evidenceSnapshot().ownedCustomFolds)

            val actual = click(fold)
            assertTrue(controller.handleMouseReveal(actual))
            assertTrue(actual.isConsumed)
            assertFalse(fold.isValid)
            assertEquals(model.contentRange.startOffset, editor.caretModel.offset)
            assertEquals(baselineY, editor.offsetToXY(caretInTail).y)
            evidence = controller.evidenceSnapshot()
            assertEquals(0, evidence.ownedCustomFolds)
            assertEquals(0, evidence.ownedFolds)
            assertEquals(0, evidence.fullyConcealed)
            assertEquals(1L, evidence.mouseReveals)
            assertTrue(requireNotNull(foreign).isValid)
            assertFalse(requireNotNull(foreign).isExpanded)

            val stale = click(fold)
            assertFalse(controller.handleMouseReveal(stale))
            editor.caretModel.moveToOffset(caretInTail)
            controller.refreshActivity(plan, richPresentationEnabled = true)
            evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.ownedCustomFolds)
            assertEquals(1, evidence.fullyConcealed)
            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)
        } finally {
            controller.dispose()
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                foreign?.takeIf { it.isValid }?.let(editor.foldingModel::removeFoldRegion)
            }
        }
        assertEquals(0, editor.foldingModel.allFoldRegions.filterIsInstance<CustomFoldRegion>().size)
        assertEquals(baselineY, editor.offsetToXY(caretInTail).y)
        assertEquals(source, document.text)
        assertEquals(stampBefore, document.modificationStamp)
    }

    /**
     * #350 production-path pilot: unlike constructed EditorMouseEvent tests,
     * dispatch an AWT click to the actual IntelliJ editor component so
     * EditorImpl.createEditorMouseEvent must hit-test the visible custom fold.
     * This is in-process platform UI evidence, not a full Starter Robot run.
     */
    fun testSourceReplacingHeadingUsesRealEditorMouseHitTestingForSourceReveal() {
        val source = "# Heading\n\nTail\n"
        myFixture.configureByText("heading-custom-editor-hit-test.md", source)
        val editor = myFixture.editor
        val document = editor.document
        val tailOffset = source.indexOf("Tail") + 1
        editor.caretModel.moveToOffset(tailOffset)
        val stamp = document.modificationStamp
        val baselineY = editor.offsetToXY(tailOffset).y
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(document, 0L))
        val contentOffset = NativeHeadingProjectionPlanner.plan(plan).single().contentRange.startOffset
        val controller = NativeHeadingPresentationController(editor)
        // BasePlatformTestCase supplies an editor model without a visible Swing
        // hierarchy. AWT dispatchEvent silently discards input to a hidden
        // component, so the real EditorImpl mouse path must run in a visible
        // component hosted by the CI Xvfb display. Do not substitute a
        // manufactured EditorMouseEvent or directly invoke the controller.
        assertFalse(
            "Real EditorImpl mouse route requires a display-backed IDEA test worker",
            java.awt.GraphicsEnvironment.isHeadless(),
        )
        val editorComponent = editor.component
        val previousParent = editorComponent.parent
        val displayFrame = javax.swing.JFrame("MarkFlow custom heading editor mouse contract")
        try {
            displayFrame.setBounds(80, 80, 1000, 650)
            displayFrame.contentPane.add(editorComponent, java.awt.BorderLayout.CENTER)
            displayFrame.isVisible = true
            assertTrue(
                "Editor component failed to join a visible Swing hierarchy",
                editor.contentComponent.isShowing,
            )
            controller.applyPlan(plan, richPresentationEnabled = true)
            val fold = editor.foldingModel.allFoldRegions.filterIsInstance<CustomFoldRegion>().single()
            val loc = requireNotNull(fold.location) {
                "Custom heading placeholder has no editor location: physical hit testing unverified"
            }
            assertTrue(fold.isValid)
            assertTrue(fold.widthInPixels > 0)
            assertTrue(fold.heightInPixels > 0)

            val x = loc.x + minOf(4, fold.widthInPixels - 1)
            val y = loc.y + fold.heightInPixels / 2
            // MarkFlow must consume a real custom-fold mousePressed event
            // before the platform caret action invalidates its fold identity.
            // Never manufacture an EditorMouseEvent or call the controller.
            val pressed = MouseEvent(
                editor.contentComponent, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), java.awt.event.InputEvent.BUTTON1_DOWN_MASK,
                x, y, 1, false, MouseEvent.BUTTON1,
            )
            val released = MouseEvent(
                editor.contentComponent, MouseEvent.MOUSE_RELEASED,
                pressed.`when` + 1, 0,
                x, y, 1, false, MouseEvent.BUTTON1,
            )
            val routed = mutableListOf<String>()
            val observer = object : com.intellij.openapi.editor.event.EditorMouseListener {
                override fun mousePressed(event: EditorMouseEvent) {
                    routed += "pressed:area=${event.area},input=${event.mouseEvent.id}"
                }
                override fun mouseReleased(event: EditorMouseEvent) {
                    routed += "released:area=${event.area},input=${event.mouseEvent.id}"
                }
                override fun mouseClicked(event: EditorMouseEvent) {
                    routed += "clicked:area=${event.area},fold=${event.collapsedFoldRegion?.javaClass?.simpleName}," +
                        "same=${event.collapsedFoldRegion === fold},input=${event.mouseEvent.id}"
                }
            }
            editor.addEditorMouseListener(observer)
            try {
                editor.contentComponent.dispatchEvent(pressed)
                editor.contentComponent.dispatchEvent(released)
            } finally {
                editor.removeEditorMouseListener(observer)
            }

            assertTrue(
                "EditorImpl actual mousePressed reveal route is unverified: releasedConsumed=${released.isConsumed}," +
                    " pressedConsumed=${pressed.isConsumed}, regionValid=${fold.isValid}," +
                    " regionLocation=${fold.location}, point=(${released.x},${released.y})," +
                    " showing=${editor.contentComponent.isShowing}," +
                    " componentMouseListeners=${editor.contentComponent.mouseListeners.size}," +
                    " routed=$routed",
                pressed.isConsumed,
            )
            assertFalse("Owned region still visible after real editor component click", fold.isValid)
            assertEquals(contentOffset, editor.caretModel.offset)
            assertEquals(1L, controller.evidenceSnapshot().mouseReveals)
            assertEquals(0, controller.evidenceSnapshot().ownedCustomFolds)
            assertEquals(baselineY, editor.offsetToXY(tailOffset).y)
            assertEquals(source, document.text)
            assertEquals(stamp, document.modificationStamp)

            editor.caretModel.moveToOffset(tailOffset)
            controller.refreshActivity(plan, richPresentationEnabled = true)
            assertEquals(1, controller.evidenceSnapshot().ownedCustomFolds)
        } finally {
            controller.dispose()
            displayFrame.contentPane.remove(editorComponent)
            displayFrame.dispose()
            // Restore the fixture's component ownership before teardown.
            previousParent?.add(editorComponent)
            previousParent?.revalidate()
        }
        assertEquals(baselineY, editor.offsetToXY(tailOffset).y)
        assertEquals(source, document.text)
        assertEquals(stamp, document.modificationStamp)
    }

    fun testFallbackConcealsParserProvenSyntaxWithoutChangingSource() {
        val source = """# Heading

A [link](https://example.com) with *emphasis* and `code`.

- unordered

3) ordered

> quote

---

After
"""
        myFixture.configureByText("ordinary.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("After"))
        val document = editor.document
        val sourceBefore = document.text
        val stampBefore = document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val evidence = controller.evidenceSnapshot()
            assertEquals(ProjectionPlanStatus.READY, evidence.planStatus)
            assertTrue("expected inline-owned ordinary highlighters", evidence.inlineOwnedHighlighters >= 3)
            assertTrue("expected inline-owned ordinary syntax folds", evidence.inlineOwnedFolds > 0)
            assertTrue("expected remaining block-owned ordinary highlighters", evidence.blockOwnedHighlighters >= 1)
            assertTrue("expected block-owned ordinary folds", evidence.blockOwnedFolds > 0)
            assertEquals(1, evidence.blockQuoteModels)
            assertEquals(1, evidence.blockQuoteInlays)
            assertEquals(1, evidence.blockQuoteFullyConcealed)
            assertEquals(2, evidence.listModels)
            assertEquals(2, evidence.listRows)
            assertEquals(2, evidence.listInlays)
            assertEquals(2, evidence.listFullyConcealed)
            assertEquals(listOf("-", "3)"), evidence.listMarkers)
            assertEquals(
                evidence.inlineOwnedHighlighters + evidence.blockOwnedHighlighters,
                evidence.ownedHighlighters,
            )
            assertEquals(evidence.inlineOwnedFolds + evidence.blockOwnedFolds, evidence.ownedFolds)
            assertEquals(evidence.inlineCollapsedFolds + evidence.blockCollapsedFolds, evidence.collapsedFolds)
            assertTrue("expected MarkFlow-owned ordinary syntax folds", evidence.ownedFolds >= 8)
            assertTrue(evidence.collapsedFolds > 0)

            val thematicBreak = requireNotNull(controller.currentPlan)
                .projections
                .single { projection -> projection.kind == NativeProjectionKind.THEMATIC_BREAK }
            val thematicFold = editor.foldingModel.allFoldRegions.singleOrNull { candidate ->
                candidate.isValid &&
                    !candidate.isExpanded &&
                    candidate.startOffset == thematicBreak.sourceRange.startOffset &&
                    candidate.endOffset >= thematicBreak.sourceRange.endOffset &&
                    candidate.placeholderText.isNotEmpty() &&
                    candidate.placeholderText.all { char -> char == '─' }
            }
            assertNotNull(
                "thematic break was not projected as a collapsed rule fold over its parser-owned source",
                thematicFold,
            )

            val link = requireNotNull(controller.currentPlan)
                .projections
                .single { projection ->
                    projection.kind == NativeProjectionKind.LINK &&
                        source.substring(projection.sourceRange.startOffset, projection.sourceRange.endOffset)
                            .startsWith("[link]")
                }
            assertEquals(2, link.syntaxRanges.size)
            link.syntaxRanges.forEach { range ->
                val fold = editor.foldingModel.getFoldRegion(range.startOffset, range.endOffset)
                assertNotNull("link syntax range was not concealed: $range", fold)
                assertFalse(fold!!.isExpanded)
            }

            assertEquals(sourceBefore, document.text)
            assertEquals(stampBefore, document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testCaretAtConcealedConstructEndRevealsExactLinkSource() {
        val source = "Before [label](https://example.com) after\n"
        myFixture.configureByText("boundary.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(0)
        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val link = requireNotNull(controller.currentPlan)
                .projections
                .single { it.kind == NativeProjectionKind.LINK }
            assertTrue(link.syntaxRanges.isNotEmpty())
            link.syntaxRanges.forEach { range ->
                assertFalse(requireNotNull(editor.foldingModel.getFoldRegion(range.startOffset, range.endOffset)).isExpanded)
            }

            editor.caretModel.moveToOffset(link.sourceRange.endOffset)

            link.syntaxRanges.forEach { range ->
                assertTrue(
                    "caret touching link end must reveal exact source syntax",
                    requireNotNull(editor.foldingModel.getFoldRegion(range.startOffset, range.endOffset)).isExpanded,
                )
            }
            assertEquals(source, editor.document.text)
        } finally {
            controller.dispose()
        }
    }

    fun testInactiveReferenceDefinitionUsesExactMetadataPlaceholderAndCaretSelectionReveal() {
        val source = """First [consumer][guide], second [consumer two][guide].

[guide]: https://example.com/guide "Guide title"

Tail
"""
        myFixture.configureByText("reference-definition.md", source)
        val editor = myFixture.editor
        val document = editor.document
        val tailOffset = source.indexOf("Tail") + 1
        editor.caretModel.moveToOffset(tailOffset)
        val stampBefore = document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val plan = requireNotNull(controller.currentPlan)
            val definition = plan.projections.single {
                it.kind == NativeProjectionKind.REFERENCE_DEFINITION
            }
            val ranges = requireNotNull(definition.referenceDefinitionRanges)
            assertEquals(
                2,
                plan.projections.count {
                    it.kind == NativeProjectionKind.LINK &&
                        source.substring(it.sourceRange.startOffset, it.sourceRange.endOffset)
                            .endsWith("[guide]")
                },
            )

            val fold = requireNotNull(
                editor.foldingModel.getFoldRegion(
                    definition.sourceRange.startOffset,
                    definition.sourceRange.endOffset,
                )
            ) { "inactive reference definition did not install one full-range metadata fold" }
            assertFalse(fold.isExpanded)
            assertEquals(
                "↳ reference [guide]",
                fold.placeholderText,
            )
            assertEquals(1, editor.foldingModel.allFoldRegions.count {
                it.isValid &&
                    it.startOffset == definition.sourceRange.startOffset &&
                    it.endOffset == definition.sourceRange.endOffset
            })

            editor.caretModel.moveToOffset(ranges.destination.startOffset + 3)
            assertTrue("caret inside definition must reveal exact source", fold.isExpanded)
            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)

            editor.caretModel.moveToOffset(tailOffset)
            assertFalse("leaving definition must restore inactive metadata presentation", fold.isExpanded)

            editor.selectionModel.setSelection(
                definition.sourceRange.startOffset + 1,
                definition.sourceRange.endOffset - 1,
            )
            assertTrue("selection intersecting definition must reveal exact source", fold.isExpanded)
            editor.selectionModel.removeSelection()
            assertFalse("clearing inactive selection must restore metadata presentation", fold.isExpanded)

            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testMultilineReferenceDefinitionUsesOneExactMetadataFold() {
        val source = """A [consumer][multi].

[multi]:
      /multi
           'Multi title'

Tail
"""
        myFixture.configureByText("reference-definition-multiline.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val definition = requireNotNull(controller.currentPlan)
                .projections
                .single { it.kind == NativeProjectionKind.REFERENCE_DEFINITION }
            assertTrue(
                source.substring(definition.sourceRange.startOffset, definition.sourceRange.endOffset)
                    .contains("\n")
            )
            val fold = requireNotNull(
                editor.foldingModel.getFoldRegion(
                    definition.sourceRange.startOffset,
                    definition.sourceRange.endOffset,
                )
            )
            assertFalse(fold.isExpanded)
            assertEquals("↳ reference [multi]", fold.placeholderText)
            assertEquals(
                1,
                editor.foldingModel.allFoldRegions.count {
                    it.isValid &&
                        it.startOffset == definition.sourceRange.startOffset &&
                        it.endOffset == definition.sourceRange.endOffset
                },
            )
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testOversizedReferenceDefinitionUsesCompactSecondaryLabelWithoutLeakingDestinationOrTitle() {
        val destination = "https://example.com/" + "x".repeat(600)
        val title = "t".repeat(600)
        val source = "A [consumer][huge].\n\n[huge]: $destination \"$title\"\n\nTail\n"
        myFixture.configureByText("reference-definition-compact-metadata.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val definition = requireNotNull(controller.currentPlan)
                .projections
                .single { it.kind == NativeProjectionKind.REFERENCE_DEFINITION }
            val fold = requireNotNull(
                editor.foldingModel.getFoldRegion(
                    definition.sourceRange.startOffset,
                    definition.sourceRange.endOffset,
                )
            )
            assertFalse(fold.isExpanded)
            assertEquals("↳ reference [huge]", fold.placeholderText)
            assertFalse(fold.placeholderText.contains("example.com"))
            assertFalse(fold.placeholderText.contains(title.take(16)))
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testReferenceDefinitionPresentationFailsClosedWhenRichPresentationIsDisabledOrSyntaxIsMalformed() {
        val valid = """A [consumer][guide].

[guide]: https://example.com/guide

Tail
"""
        myFixture.configureByText("reference-definition-accessible.md", valid)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(valid.indexOf("Tail") + 1)
        val validStamp = editor.document.modificationStamp
        val accessible = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { false },
        )
        try {
            val definition = requireNotNull(accessible.currentPlan)
                .projections
                .single { it.kind == NativeProjectionKind.REFERENCE_DEFINITION }
            assertNull(
                editor.foldingModel.getFoldRegion(
                    definition.sourceRange.startOffset,
                    definition.sourceRange.endOffset,
                )
            )
            assertEquals(valid, editor.document.text)
            assertEquals(validStamp, editor.document.modificationStamp)
        } finally {
            accessible.dispose()
        }

        val malformed = """[broken] https://example.com/no-colon

Tail
"""
        myFixture.configureByText("reference-definition-malformed.md", malformed)
        val malformedEditor = myFixture.editor
        malformedEditor.caretModel.moveToOffset(malformed.indexOf("Tail") + 1)
        val malformedStamp = malformedEditor.document.modificationStamp
        val fallback = NativePresentationController(
            editor = malformedEditor,
            richPresentationEnabled = { true },
        )
        try {
            assertTrue(
                requireNotNull(fallback.currentPlan).projections.none {
                    it.kind == NativeProjectionKind.REFERENCE_DEFINITION
                }
            )
            assertFalse(
                malformedEditor.foldingModel.allFoldRegions.any {
                    it.isValid && it.placeholderText.startsWith("↳ ")
                }
            )
            assertEquals(malformed, malformedEditor.document.text)
            assertEquals(malformedStamp, malformedEditor.document.modificationStamp)
        } finally {
            fallback.dispose()
        }
    }

    fun testAccessibilityDispositionLeavesOrdinaryMarkdownAsExactSource() {
        val source = "# Heading\n\nA [link](https://example.com), **strong**, and ~~struck~~ text.\n\n- item\n"
        myFixture.configureByText("accessible.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.length)
        val document = editor.document
        val stampBefore = document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { false },
        )
        try {
            val evidence = controller.evidenceSnapshot()
            assertEquals(ProjectionPlanStatus.READY, evidence.planStatus)
            assertEquals(0, evidence.ownedHighlighters)
            assertEquals(0, evidence.ownedFolds)
            assertEquals(0, evidence.collapsedFolds)
            assertEquals(0, evidence.inlineOwnedHighlighters)
            assertEquals(0, evidence.inlineOwnedFolds)
            assertEquals(0, evidence.blockOwnedHighlighters)
            assertEquals(0, evidence.blockOwnedFolds)
            assertEquals(1, evidence.headingModels)
            assertEquals(0, evidence.headingInlays)
            assertEquals(0, evidence.headingFolds)
            assertEquals(0, evidence.headingFullyConcealed)
            assertEquals(1, evidence.listModels)
            assertEquals(1, evidence.listRows)
            assertEquals(0, evidence.listInlays)
            assertEquals(0, evidence.listFolds)
            assertEquals(0, evidence.listFullyConcealed)
            assertEquals(1L, evidence.sourceFallbacks)
            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }
    fun testInlineOnlyFixtureUsesOnlyInlinePresentationOwner() {
        val source = "A [link](https://example.com) with *emphasis*, **strong**, ~~struck~~, and `code`.\n\nTail\n"
        myFixture.configureByText("inline-only.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val evidence = controller.evidenceSnapshot()
            assertTrue(evidence.inlineOwnedHighlighters >= 5)
            assertTrue(evidence.inlineOwnedFolds > 0)
            assertEquals(0, evidence.blockOwnedHighlighters)
            assertEquals(0, evidence.blockOwnedFolds)
            assertEquals(evidence.inlineOwnedHighlighters, evidence.ownedHighlighters)
            assertEquals(evidence.inlineOwnedFolds, evidence.ownedFolds)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testWrappedAutolinkUsesMaintainedSemanticKeyOnlyOnVisibleUrl() {
        val source = "Before <https://example.com/path> after\n\nTail\n"
        myFixture.configureByText("wrapped-autolink-semantics.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val link = requireNotNull(controller.currentPlan)
                .projections
                .single { projection ->
                    projection.kind == NativeProjectionKind.LINK &&
                        source.substring(projection.sourceRange.startOffset, projection.sourceRange.endOffset) ==
                        "<https://example.com/path>"
                }
            val visible = link.contentRanges.single()
            assertEquals(
                "https://example.com/path",
                source.substring(visible.startOffset, visible.endOffset),
            )
            assertEquals(
                listOf("<", ">"),
                link.syntaxRanges.map { range -> source.substring(range.startOffset, range.endOffset) },
            )

            val semantic = requireNotNull(
                editor.markupModel.allHighlighters.singleOrNull { highlighter ->
                    highlighter.isValid &&
                        highlighter.startOffset == visible.startOffset &&
                        highlighter.endOffset == visible.endOffset &&
                        highlighter.textAttributesKey == EditorColors.REFERENCE_HYPERLINK_COLOR
                }
            ) { "wrapped autolink did not install exact maintained hyperlink semantics" }
            assertEquals(HighlighterLayer.ADDITIONAL_SYNTAX + 1, semantic.layer)
            assertEquals(HighlighterTargetArea.EXACT_RANGE, semantic.targetArea)
            assertFalse(
                editor.markupModel.allHighlighters.any { highlighter ->
                    highlighter.isValid &&
                        highlighter.textAttributesKey == EditorColors.REFERENCE_HYPERLINK_COLOR &&
                        highlighter.startOffset == link.sourceRange.startOffset &&
                        highlighter.endOffset == link.sourceRange.endOffset
                }
            )
            link.syntaxRanges.forEach { range ->
                assertFalse(
                    "inactive wrapped autolink delimiter must be concealed",
                    requireNotNull(editor.foldingModel.getFoldRegion(range.startOffset, range.endOffset)).isExpanded,
                )
            }
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testInactiveInlineSemanticsUseItalicBoldAndHyperlinkAttributesWithoutChangingSource() {
        val source = "A *emphasis*, **strong**, and [link](https://example.com).\n\nTail\n"
        myFixture.configureByText("inline-semantics.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val document = editor.document
        val stampBefore = document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val plan = requireNotNull(controller.currentPlan)
            val emphasis = plan.projections.single { projection ->
                projection.kind == NativeProjectionKind.EMPHASIS &&
                    source.substring(projection.sourceRange.startOffset, projection.sourceRange.endOffset) == "*emphasis*"
            }
            val strong = plan.projections.single { projection ->
                projection.kind == NativeProjectionKind.STRONG &&
                    source.substring(projection.sourceRange.startOffset, projection.sourceRange.endOffset) == "**strong**"
            }
            val link = plan.projections.single { projection ->
                projection.kind == NativeProjectionKind.LINK &&
                    source.substring(projection.sourceRange.startOffset, projection.sourceRange.endOffset)
                        .startsWith("[link]")
            }

            fun semanticRange(projection: NativeProjection): ProjectionRange =
                if (projection.kind == NativeProjectionKind.LINK) {
                    projection.contentRanges.single()
                } else {
                    projection.sourceRange
                }

            fun semanticHighlighter(projection: NativeProjection) = semanticRange(projection).let { range ->
                requireNotNull(
                    editor.markupModel.allHighlighters.singleOrNull { highlighter ->
                        highlighter.isValid &&
                            highlighter.startOffset == range.startOffset &&
                            highlighter.endOffset == range.endOffset
                    }
                ) { "missing inactive semantic highlighter for ${projection.kind}" }
            }

            fun attributes(projection: NativeProjection) =
                requireNotNull(semanticHighlighter(projection).getTextAttributes(editor.colorsScheme)) {
                    "inactive semantic highlighter for ${projection.kind} has no text attributes"
                }

            assertTrue(attributes(emphasis).fontType and Font.ITALIC != 0)
            assertTrue(attributes(strong).fontType and Font.BOLD != 0)

            val expectedLink = requireNotNull(
                editor.colorsScheme.getAttributes(EditorColors.REFERENCE_HYPERLINK_COLOR)
            )
            val linkRange = link.contentRanges.single()
            val linkHighlighter = semanticHighlighter(link)
            assertEquals(linkRange.startOffset, linkHighlighter.startOffset)
            assertEquals(linkRange.endOffset, linkHighlighter.endOffset)
            assertEquals(EditorColors.REFERENCE_HYPERLINK_COLOR, linkHighlighter.textAttributesKey)
            assertEquals(HighlighterLayer.ADDITIONAL_SYNTAX + 1, linkHighlighter.layer)
            assertEquals(HighlighterTargetArea.EXACT_RANGE, linkHighlighter.targetArea)
            assertFalse(
                editor.markupModel.allHighlighters.any { highlighter ->
                    highlighter.isValid &&
                        highlighter.textAttributesKey == EditorColors.REFERENCE_HYPERLINK_COLOR &&
                        highlighter.startOffset == link.sourceRange.startOffset &&
                        highlighter.endOffset == link.sourceRange.endOffset
                }
            )
            val actualLink = attributes(link)
            assertEquals(expectedLink.foregroundColor, actualLink.foregroundColor)
            assertEquals(expectedLink.effectColor, actualLink.effectColor)
            assertEquals(expectedLink.effectType, actualLink.effectType)

            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)

            editor.caretModel.moveToOffset(source.indexOf("emphasis") + 2)
            assertFalse(
                editor.markupModel.allHighlighters.any { highlighter ->
                    highlighter.isValid &&
                        highlighter.startOffset == emphasis.sourceRange.startOffset &&
                        highlighter.endOffset == emphasis.sourceRange.endOffset
                }
            )
            emphasis.syntaxRanges.forEach { range ->
                assertTrue(requireNotNull(editor.foldingModel.getFoldRegion(range.startOffset, range.endOffset)).isExpanded)
            }
            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)

            editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
            assertTrue(attributes(emphasis).fontType and Font.ITALIC != 0)
            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testNestedEmphasisAndStrongRetainBothSemanticOwnersWithoutChangingSource() {
        val source = "Before ***both*** after\n\nTail\n"
        myFixture.configureByText("inline-nested-semantics.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val plan = requireNotNull(controller.currentPlan)
            val emphasis = plan.projections.filter { it.kind == NativeProjectionKind.EMPHASIS }
            val strong = plan.projections.filter { it.kind == NativeProjectionKind.STRONG }
            assertTrue("nested source produced no parser-proven emphasis projection", emphasis.isNotEmpty())
            assertTrue("nested source produced no parser-proven strong projection", strong.isNotEmpty())

            fun resolvedAttributes(projection: NativeProjection) =
                editor.markupModel.allHighlighters
                    .filter { highlighter ->
                        highlighter.isValid &&
                            highlighter.startOffset == projection.sourceRange.startOffset &&
                            highlighter.endOffset == projection.sourceRange.endOffset
                    }
                    .mapNotNull { highlighter -> highlighter.getTextAttributes(editor.colorsScheme) }

            assertTrue(
                "nested emphasis lost native italic semantics",
                emphasis.any { projection ->
                    resolvedAttributes(projection).any { attributes ->
                        attributes.fontType and Font.ITALIC != 0
                    }
                },
            )
            assertTrue(
                "nested strong lost native bold semantics",
                strong.any { projection ->
                    resolvedAttributes(projection).any { attributes ->
                        attributes.fontType and Font.BOLD != 0
                    }
                },
            )
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testInactiveStrikethroughUsesNativeStrikeoutAndCaretRevealWithoutChangingSource() {
        val source = "Before ~~obsolete~~ after\n\nTail\n"
        myFixture.configureByText("strikethrough.md", source)
        val editor = myFixture.editor
        val tailOffset = source.indexOf("Tail") + 1
        editor.caretModel.moveToOffset(tailOffset)
        val stampBefore = editor.document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val projection = requireNotNull(controller.currentPlan)
                .projections
                .single { it.kind == NativeProjectionKind.STRIKETHROUGH }
            assertEquals(
                listOf("~~", "~~"),
                projection.syntaxRanges.map { range -> source.substring(range.startOffset, range.endOffset) },
            )

            fun hasStrikeoutHighlighter(): Boolean =
                editor.markupModel.allHighlighters.any { highlighter ->
                    highlighter.isValid &&
                        highlighter.startOffset == projection.sourceRange.startOffset &&
                        highlighter.endOffset == projection.sourceRange.endOffset &&
                        highlighter.getTextAttributes(editor.colorsScheme)?.effectType == EffectType.STRIKEOUT
                }

            assertTrue("inactive strikethrough must use a native strikeout text effect", hasStrikeoutHighlighter())
            projection.syntaxRanges.forEach { range ->
                val fold = requireNotNull(editor.foldingModel.getFoldRegion(range.startOffset, range.endOffset))
                assertFalse("inactive strikethrough delimiter must be concealed", fold.isExpanded)
            }
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)

            editor.caretModel.moveToOffset(source.indexOf("obsolete") + 2)
            projection.syntaxRanges.forEach { range ->
                val fold = requireNotNull(editor.foldingModel.getFoldRegion(range.startOffset, range.endOffset))
                assertTrue("active strikethrough must reveal exact delimiters", fold.isExpanded)
            }
            assertFalse("active strikethrough must remove the inactive strikeout presentation", hasStrikeoutHighlighter())
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)

            editor.caretModel.moveToOffset(tailOffset)
            assertTrue("moving away must restore native strikeout presentation", hasStrikeoutHighlighter())
            projection.syntaxRanges.forEach { range ->
                assertFalse(requireNotNull(editor.foldingModel.getFoldRegion(range.startOffset, range.endOffset)).isExpanded)
            }
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testStrikethroughSelectionRevealRestoresExactDelimitersWithoutChangingSource() {
        val source = "Before ~~obsolete~~ after\n\nTail\n"
        myFixture.configureByText("strikethrough-selection.md", source)
        val editor = myFixture.editor
        val tailOffset = source.indexOf("Tail") + 1
        val contentStart = source.indexOf("obsolete")
        editor.caretModel.moveToOffset(tailOffset)
        val stampBefore = editor.document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val projection = requireNotNull(controller.currentPlan)
                .projections
                .single { it.kind == NativeProjectionKind.STRIKETHROUGH }
            projection.syntaxRanges.forEach { range ->
                assertFalse(requireNotNull(editor.foldingModel.getFoldRegion(range.startOffset, range.endOffset)).isExpanded)
            }

            editor.selectionModel.setSelection(contentStart, contentStart + "obsolete".length)
            projection.syntaxRanges.forEach { range ->
                assertTrue("selection intersecting strikethrough must reveal exact delimiters", requireNotNull(editor.foldingModel.getFoldRegion(range.startOffset, range.endOffset)).isExpanded)
            }
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)

            editor.selectionModel.removeSelection()
            editor.caretModel.moveToOffset(tailOffset)
            projection.syntaxRanges.forEach { range ->
                assertFalse(requireNotNull(editor.foldingModel.getFoldRegion(range.startOffset, range.endOffset)).isExpanded)
            }
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testSingleTildeTextFailsClosedToExactSourceWithoutRichPresentation() {
        val source = "Before ~legacy~ after\n\nTail\n"
        myFixture.configureByText("strikethrough-single.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp
        val openingTilde = source.indexOf("~")
        val closingTilde = source.lastIndexOf("~")

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val plan = requireNotNull(controller.currentPlan)
            assertTrue(plan.projections.none { it.kind == NativeProjectionKind.STRIKETHROUGH })
            assertNull(editor.foldingModel.getFoldRegion(openingTilde, openingTilde + 1))
            assertNull(editor.foldingModel.getFoldRegion(closingTilde, closingTilde + 1))
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testBlockOnlyFixtureUsesOnlyBlockPresentationOwner() {
        val source = "# Heading\n\n- item\n\n> quote\n\n---\n\nTail\n"
        myFixture.configureByText("block-only.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val evidence = controller.evidenceSnapshot()
            assertEquals(0, evidence.inlineOwnedHighlighters)
            assertEquals(0, evidence.inlineOwnedFolds)
            assertTrue(evidence.blockOwnedHighlighters >= 1)
            assertTrue(evidence.blockOwnedFolds > 0)
            assertEquals(1, evidence.blockQuoteModels)
            assertEquals(1, evidence.blockQuoteInlays)
            assertEquals(1, evidence.blockQuoteFullyConcealed)
            assertEquals(evidence.blockOwnedHighlighters, evidence.ownedHighlighters)
            assertEquals(evidence.blockOwnedFolds, evidence.ownedFolds)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }


    fun testInactivePlainHeadingsUseNativeHierarchyWithoutChangingSource() {
        val source = """# H1
## H2 ##
### H3
#### H4
##### H5
###### H6

Setext one
==========

Setext two
----------

Tail
"""
        myFixture.configureByText("headings.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val evidence = controller.evidenceSnapshot()
            assertEquals(8, evidence.headingModels)
            assertEquals(8, evidence.headingFullyConcealed)
            assertTrue(evidence.headingFolds >= 8)
            assertEquals(listOf(1, 2, 3, 4, 5, 6, 1, 2), evidence.headingLevels)
            assertEquals(0, editor.inlayModel
                .getBlockElementsInRange(0, editor.document.textLength)
                .count { it.isValid })
            val customHeadings = editor.foldingModel.allFoldRegions
                .filterIsInstance<CustomFoldRegion>()
                .count { it.isValid && it.renderer is NativeHeadingCustomFoldRenderer }
            val inlineHeadings = editor.inlayModel
                .getInlineElementsInRange(0, editor.document.textLength)
                .count { it.isValid && it.renderer is NativeHeadingInlineFoldRenderer }
            assertEquals(8, customHeadings + inlineHeadings)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testActiveHeadingRevealsExactSourceAndRestoresInactivePresentation() {
        val source = "# Heading\n\nTail\n"
        myFixture.configureByText("heading-reveal.md", source)
        val editor = myFixture.editor
        val tailOffset = source.indexOf("Tail") + 1
        editor.caretModel.moveToOffset(tailOffset)
        val stampBefore = editor.document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            assertTrue(controller.evidenceSnapshot().headingFolds > 0)
            assertEquals(1, controller.evidenceSnapshot().headingFullyConcealed)

            editor.caretModel.moveToOffset(source.indexOf("Heading") + 2)
            assertEquals(0, controller.evidenceSnapshot().headingFolds)
            assertEquals(0, controller.evidenceSnapshot().headingFullyConcealed)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)

            editor.caretModel.moveToOffset(tailOffset)
            assertTrue(controller.evidenceSnapshot().headingFolds > 0)
            assertEquals(1, controller.evidenceSnapshot().headingFullyConcealed)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testComplexHeadingFailsClosedToExactSourceUntilInlineRichHeadingSupportExists() {
        val source = "# Heading with *emphasis*\n\nTail\n"
        myFixture.configureByText("heading-complex.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val evidence = controller.evidenceSnapshot()
            assertEquals(0, evidence.headingModels)
            assertEquals(0, evidence.headingInlays)
            assertEquals(0, evidence.headingFolds)
            assertEquals(0, evidence.headingFullyConcealed)
            assertEquals(0, evidence.inlineOwnedHighlighters)
            assertEquals(0, evidence.inlineOwnedFolds)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testStrikethroughRichHeadingFailsClosedToExactSource() {
        val source = "# Heading with ~~obsolete~~\n\nTail\n"
        myFixture.configureByText("heading-strikethrough.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val evidence = controller.evidenceSnapshot()
            assertEquals(0, evidence.headingModels)
            assertEquals(0, evidence.headingInlays)
            assertEquals(0, evidence.headingFolds)
            assertEquals(0, evidence.inlineOwnedHighlighters)
            assertEquals(0, evidence.inlineOwnedFolds)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testEscapedHeadingTextAlsoFailsClosedToExactSource() {
        val source = "# Heading with \\*literal\\*\n\nTail\n"
        myFixture.configureByText("heading-escaped.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val evidence = controller.evidenceSnapshot()
            assertEquals(0, evidence.headingModels)
            assertEquals(0, evidence.headingInlays)
            assertEquals(0, evidence.headingFolds)
            assertEquals(0, evidence.headingFullyConcealed)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testHeadingPresentationCoexistsWithCollapsedForeignSyntaxFold() {
        val source = "# Heading\n\nTail\n"
        myFixture.configureByText("heading-foreign-fold.md", source)
        val editor = myFixture.editor
        val tailOffset = source.indexOf("Tail") + 1
        editor.caretModel.moveToOffset(tailOffset)
        val stampBefore = editor.document.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val heading = plan.projections.single { it.kind == NativeProjectionKind.HEADING }
        val syntax = heading.syntaxRanges.single()
        var foreignFold: com.intellij.openapi.editor.FoldRegion? = null
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            foreignFold = editor.foldingModel.addFoldRegion(
                syntax.startOffset,
                syntax.endOffset,
                "platform heading syntax",
            )
            requireNotNull(foreignFold).isExpanded = false
        }

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val inactive = controller.evidenceSnapshot()
            assertEquals(1, inactive.headingModels)
            assertEquals(1, inactive.headingInlays)
            assertTrue(inactive.headingFolds > 0)
            assertEquals(1, inactive.headingFullyConcealed)
            assertTrue(requireNotNull(foreignFold).isValid)
            assertFalse(requireNotNull(foreignFold).isExpanded)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)

            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                requireNotNull(foreignFold).isExpanded = true
            }
            editor.caretModel.moveToOffset(source.indexOf("Heading") + 2)
            assertEquals(0, controller.evidenceSnapshot().headingInlays)
            assertEquals(0, controller.evidenceSnapshot().headingFolds)
            assertEquals(0, controller.evidenceSnapshot().headingFullyConcealed)
            assertTrue(requireNotNull(foreignFold).isValid)
            assertTrue(requireNotNull(foreignFold).isExpanded)

            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                requireNotNull(foreignFold).isExpanded = false
            }
            editor.caretModel.moveToOffset(tailOffset)
            assertEquals(1, controller.evidenceSnapshot().headingInlays)
            assertTrue(controller.evidenceSnapshot().headingFolds > 0)
            assertEquals(1, controller.evidenceSnapshot().headingFullyConcealed)
            assertTrue(requireNotNull(foreignFold).isValid)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
            assertTrue(requireNotNull(foreignFold).isValid)
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                requireNotNull(foreignFold).takeIf { it.isValid }?.let(editor.foldingModel::removeFoldRegion)
            }
        }
    }

    fun testHeadingTypographyScaleIsStrictlyDescendingFromH1ToH6() {
        val scales = (1..6).map(::nativeHeadingFontScale)
        assertTrue(scales.zipWithNext().all { (higher, lower) -> higher > lower })
        assertEquals(1.0f, scales.last())
    }


    fun testInactiveSimpleBlockQuoteUsesNativeQuoteBlockWithoutChangingSource() {
        val source = "> quoted text\n\nTail\n"
        myFixture.configureByText("blockquote.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.blockQuoteModels)
            assertEquals(1, evidence.blockQuoteInlays)
            assertEquals(2, evidence.blockQuoteFolds)
            assertEquals(1, evidence.blockQuoteFullyConcealed)

            val renderer = editor.inlayModel
                .getBlockElementsInRange(0, editor.document.textLength)
                .mapNotNull { it.renderer as? NativeBlockQuoteInlayRenderer }
                .single()
            assertEquals("quoted text", renderer.displayText)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testActiveBlockQuoteRevealsExactSourceAndRestoresInactivePresentation() {
        val source = "> quoted text\n\nTail\n"
        myFixture.configureByText("blockquote-reveal.md", source)
        val editor = myFixture.editor
        val tailOffset = source.indexOf("Tail") + 1
        editor.caretModel.moveToOffset(tailOffset)
        val stampBefore = editor.document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            assertEquals(1, controller.evidenceSnapshot().blockQuoteInlays)
            assertEquals(1, controller.evidenceSnapshot().blockQuoteFullyConcealed)

            editor.caretModel.moveToOffset(source.indexOf("quoted") + 2)
            assertEquals(0, controller.evidenceSnapshot().blockQuoteInlays)
            assertEquals(0, controller.evidenceSnapshot().blockQuoteFolds)
            assertEquals(0, controller.evidenceSnapshot().blockQuoteFullyConcealed)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)

            editor.caretModel.moveToOffset(tailOffset)
            assertEquals(1, controller.evidenceSnapshot().blockQuoteInlays)
            assertEquals(2, controller.evidenceSnapshot().blockQuoteFolds)
            assertEquals(1, controller.evidenceSnapshot().blockQuoteFullyConcealed)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testInactiveSameDepthMultilineBlockQuoteUsesOneNativeContainerWithoutChangingSource() {
        val source = "> first line\n> second line\n\nTail\n"
        myFixture.configureByText("blockquote-multiline.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))

        val model = NativeBlockQuoteProjectionPlanner.plan(plan).single()
        assertEquals(listOf("first line", "second line"), model.lines.map(NativeBlockQuoteLine::text))
        assertEquals(
            listOf(">", ">"),
            model.lines.map { line ->
                source.substring(line.markerRange.startOffset, line.markerRange.endOffset)
            },
        )
        model.lines.forEach { line ->
            assertEquals(
                line.text,
                requireNotNull(line.contentRange).let { range -> source.substring(range.startOffset, range.endOffset) },
            )
        }

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.blockQuoteModels)
            assertEquals(1, evidence.blockQuoteInlays)
            assertTrue(evidence.blockQuoteFolds > 0)
            assertEquals(1, evidence.blockQuoteFullyConcealed)

            val renderer = editor.inlayModel
                .getBlockElementsInRange(0, editor.document.textLength)
                .mapNotNull { it.renderer as? NativeBlockQuoteInlayRenderer }
                .single()
            assertEquals(listOf("first line", "second line"), renderer.displayLines)
            assertEquals("first line\nsecond line", renderer.displayText)
            assertEquals(2, renderer.visualLineCount)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)

            editor.caretModel.moveToOffset(requireNotNull(model.lines[1].contentRange).startOffset + 1)
            assertEquals(0, controller.evidenceSnapshot().blockQuoteInlays)
            assertEquals(0, controller.evidenceSnapshot().blockQuoteFolds)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)

            editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
            assertEquals(1, controller.evidenceSnapshot().blockQuoteInlays)
            assertEquals(1, controller.evidenceSnapshot().blockQuoteFullyConcealed)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testNestedPlainTextBlockQuoteUsesDepthAwareContainerAndAncestorReturn() {
        val source = "> outer\n> > nested\n> outer again\n\nTail\n"
        myFixture.configureByText("blockquote-nested-depth.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))

        val model = NativeBlockQuoteProjectionPlanner.plan(plan).single()
        assertEquals(listOf("outer", "nested", "outer again"), model.lines.map(NativeBlockQuoteLine::text))
        assertEquals(listOf(0, 1, 0), model.lines.map(NativeBlockQuoteLine::depth))
        assertEquals(listOf(1, 2, 1), model.lines.map { line -> line.markerRanges.size })
        assertTrue(
            model.lines.flatMap(NativeBlockQuoteLine::markerRanges).all { range ->
                source.substring(range.startOffset, range.endOffset) == ">"
            }
        )
        model.lines.forEach { line ->
            assertEquals(
                line.text,
                requireNotNull(line.contentRange).let { range -> source.substring(range.startOffset, range.endOffset) },
            )
        }

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.blockQuoteModels)
            assertEquals(1, evidence.blockQuoteInlays)
            assertTrue(evidence.blockQuoteFolds > 0)
            assertEquals(1, evidence.blockQuoteFullyConcealed)

            val renderer = editor.inlayModel
                .getBlockElementsInRange(0, editor.document.textLength)
                .mapNotNull { it.renderer as? NativeBlockQuoteInlayRenderer }
                .single()
            assertEquals(listOf("outer", "nested", "outer again"), renderer.displayLines)
            assertEquals(listOf(0, 1, 0), renderer.displayDepths)
            assertEquals(0, renderer.contentIndentPx(0))
            assertTrue(renderer.contentIndentPx(1) > 0)
            assertEquals(0, renderer.contentIndentPx(2))
            assertEquals(3, renderer.visualLineCount)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)

            editor.caretModel.moveToOffset(requireNotNull(model.lines[1].contentRange).startOffset + 1)
            assertEquals(0, controller.evidenceSnapshot().blockQuoteInlays)
            assertEquals(0, controller.evidenceSnapshot().blockQuoteFolds)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)

            editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
            assertEquals(1, controller.evidenceSnapshot().blockQuoteInlays)
            assertEquals(1, controller.evidenceSnapshot().blockQuoteFullyConcealed)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testQuoteContainedOrdinaryListComposesInsideQuoteWithoutDuplicateListInlay() {
        val source = "> outer\n> > - nested item\n> > - another item\n> outer again\n\nTail\n"
        myFixture.configureByText("blockquote-contained-list.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))

        val sharedListModel = NativeListProjectionPlanner.plan(plan).single()
        assertEquals(listOf("-", "-"), sharedListModel.rows.map(NativeListRow::marker))
        assertEquals(listOf(0, 0), sharedListModel.rows.map(NativeListRow::depth))
        assertEquals(listOf("nested item", "another item"), sharedListModel.rows.map(NativeListRow::text))

        val quote = NativeBlockQuoteProjectionPlanner.plan(plan).single()
        assertEquals(
            listOf("outer", "nested item", "another item", "outer again"),
            quote.lines.map(NativeBlockQuoteLine::displayText),
        )
        assertEquals(listOf(0, 1, 1, 0), quote.lines.map(NativeBlockQuoteLine::depth))
        assertEquals(listOf(null, "-", "-", null), quote.lines.map { line -> line.listRow?.marker })
        assertEquals(listOf(null, 0, 0, null), quote.lines.map { line -> line.listRow?.depth })

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.blockQuoteModels)
            assertEquals(1, evidence.blockQuoteInlays)
            assertEquals(1, evidence.blockQuoteFullyConcealed)
            assertEquals(0, evidence.listModels)
            assertEquals(0, evidence.listInlays)
            assertEquals(0, evidence.listFolds)

            val renderer = editor.inlayModel
                .getBlockElementsInRange(0, editor.document.textLength)
                .mapNotNull { it.renderer as? NativeBlockQuoteInlayRenderer }
                .single()
            assertEquals(
                listOf("outer", "nested item", "another item", "outer again"),
                renderer.displayLines,
            )
            assertEquals(listOf(0, 1, 1, 0), renderer.displayDepths)
            assertEquals(listOf(null, "•", "•", null), renderer.displayListMarkers)
            assertEquals(listOf(null, 0, 0, null), renderer.displayListDepths)
            assertEquals(0, renderer.contentIndentPx(0))
            assertTrue(renderer.listMarkerIndentPx(1) > renderer.contentIndentPx(0))
            assertEquals(renderer.listMarkerIndentPx(1), renderer.listMarkerIndentPx(2))
            assertEquals(0, renderer.contentIndentPx(3))
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)

            val nestedListContent = requireNotNull(quote.lines[1].listRow).contentRange.startOffset + 1
            editor.caretModel.moveToOffset(nestedListContent)
            assertEquals(0, controller.evidenceSnapshot().blockQuoteInlays)
            assertEquals(0, controller.evidenceSnapshot().listInlays)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)

            editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
            assertEquals(1, controller.evidenceSnapshot().blockQuoteInlays)
            assertEquals(0, controller.evidenceSnapshot().listInlays)
            assertEquals(1, controller.evidenceSnapshot().blockQuoteFullyConcealed)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testQuoteContainedTaskListRemainsUnsupportedByQuotePlanner() {
        val source = "> - [ ] task\n\nTail\n"
        myFixture.configureByText("blockquote-task-fallback.md", source)
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(myFixture.editor.document, 0L))

        assertTrue(NativeListProjectionPlanner.plan(plan).single().rows.single().task != null)
        assertTrue(NativeBlockQuoteProjectionPlanner.plan(plan).isEmpty())
        assertEquals(source, plan.identity.source)
    }

    fun testManualBlockQuoteFixtureUsesOneCoherentContainerIncludingBlankRows() {
        val source =
            "> First-level quote.\n" +
                ">\n" +
                "> > Nested quote.\n" +
                "> >\n" +
                "> > - Nested quoted list item\n" +
                "> > - Another quoted item\n" +
                ">\n" +
                "> Back to first level.\n\n" +
                "Tail\n"
        myFixture.configureByText("markdown-core-blockquote-acceptance.md", source)
        val editor = myFixture.editor
        val tailOffset = source.indexOf("Tail") + 1
        editor.caretModel.moveToOffset(tailOffset)
        val stampBefore = editor.document.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))

        val quote = NativeBlockQuoteProjectionPlanner.plan(plan).single()
        assertEquals(8, quote.lines.size)
        assertEquals(
            listOf(
                "First-level quote.",
                "",
                "Nested quote.",
                "",
                "Nested quoted list item",
                "Another quoted item",
                "",
                "Back to first level.",
            ),
            quote.lines.map(NativeBlockQuoteLine::displayText),
        )
        assertEquals(listOf(0, 0, 1, 1, 1, 1, 0, 0), quote.lines.map(NativeBlockQuoteLine::depth))
        assertEquals(listOf(false, true, false, true, false, false, true, false), quote.lines.map(NativeBlockQuoteLine::isBlank))
        assertEquals(listOf(null, null, null, null, "-", "-", null, null), quote.lines.map { line -> line.listRow?.marker })
        quote.lines.filterNot(NativeBlockQuoteLine::isBlank).forEach { line ->
            val range = requireNotNull(line.contentRange)
            assertEquals(line.text, source.substring(range.startOffset, range.endOffset))
        }
        quote.lines.filter(NativeBlockQuoteLine::isBlank).forEach { line ->
            assertEquals("", line.text)
            assertEquals(null, line.contentRange)
            assertTrue(line.markerRanges.isNotEmpty())
        }

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.blockQuoteModels)
            assertEquals(1, evidence.blockQuoteInlays)
            assertEquals(1, evidence.blockQuoteFullyConcealed)
            assertEquals(0, evidence.listModels)
            assertEquals(0, evidence.listInlays)

            val renderer = editor.inlayModel
                .getBlockElementsInRange(0, editor.document.textLength)
                .mapNotNull { it.renderer as? NativeBlockQuoteInlayRenderer }
                .single()
            assertEquals(8, renderer.visualLineCount)
            assertEquals(
                listOf(
                    "First-level quote.",
                    "",
                    "Nested quote.",
                    "",
                    "Nested quoted list item",
                    "Another quoted item",
                    "",
                    "Back to first level.",
                ),
                renderer.displayLines,
            )
            assertEquals(listOf(0, 0, 1, 1, 1, 1, 0, 0), renderer.displayDepths)
            assertEquals(listOf(null, null, null, null, "•", "•", null, null), renderer.displayListMarkers)
            assertEquals(0, renderer.contentIndentPx(1))
            assertTrue(renderer.contentIndentPx(3) > 0)
            assertEquals(0, renderer.contentIndentPx(6))
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)

            val nestedContent = requireNotNull(quote.lines[4].contentRange).startOffset + 1
            editor.caretModel.moveToOffset(nestedContent)
            assertEquals(0, controller.evidenceSnapshot().blockQuoteInlays)
            assertEquals(0, controller.evidenceSnapshot().listInlays)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)

            editor.caretModel.moveToOffset(tailOffset)
            assertEquals(1, controller.evidenceSnapshot().blockQuoteInlays)
            assertEquals(1, controller.evidenceSnapshot().blockQuoteFullyConcealed)
            assertEquals(0, controller.evidenceSnapshot().listInlays)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testManualBlockQuoteInlayRelayoutsWhenVisibleEditorWidthChanges() {
        val source =
            "> First-level quote.\n" +
                ">\n" +
                "> > Nested quote.\n" +
                "> >\n" +
                "> > - Nested quoted list item\n" +
                "> > - Another quoted item\n" +
                ">\n" +
                "> Back to first level.\n\n" +
                "Tail\n"
        myFixture.configureByText("markdown-core-blockquote-width-relayout.md", source)
        val editor = myFixture.editor as com.intellij.openapi.editor.impl.EditorImpl
        val document = editor.document
        val stampBefore = document.modificationStamp
        val tailOffset = source.indexOf("Tail") + 1
        editor.caretModel.moveToOffset(tailOffset)

        val originalParent = editor.component.parent
        val testWindow = javax.swing.JFrame("MarkFlow blockquote viewport relayout")
        val initialWindowWidth = 420
        val expandedWindowWidth = initialWindowWidth * 2
        var controller: NativePresentationController? = null
        try {
            testWindow.setBounds(80, 80, initialWindowWidth, 520)
            testWindow.contentPane.add(editor.component, java.awt.BorderLayout.CENTER)
            testWindow.isVisible = true
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertTrue(
                "Blockquote viewport relayout must be tested in an attached showing editor",
                editor.contentComponent.isShowing,
            )

            controller = NativePresentationController(
                editor = editor,
                richPresentationEnabled = { true },
            )
            val inlay = editor.inlayModel
                .getBlockElementsInRange(0, editor.document.textLength)
                .single { it.renderer is NativeBlockQuoteInlayRenderer }
            val renderer = inlay.renderer as NativeBlockQuoteInlayRenderer
            inlay.update()
            val initialViewportWidth = editor.scrollingModel.visibleArea.width
            val initialInlayWidth = inlay.widthInPixels
            assertTrue(initialViewportWidth > 0)
            assertEquals(renderer.calcWidthInPixels(inlay), initialInlayWidth)

            testWindow.setSize(expandedWindowWidth, testWindow.height)
            testWindow.validate()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            val expandedViewportWidth = editor.scrollingModel.visibleArea.width
            val expectedExpandedWidth = renderer.calcWidthInPixels(inlay)
            assertTrue(
                "Showing editor viewport did not expand: initial=$initialViewportWidth expanded=$expandedViewportWidth",
                expandedViewportWidth > initialViewportWidth,
            )
            assertTrue(
                "Blockquote renderer should request more width after viewport expansion",
                expectedExpandedWidth > initialInlayWidth,
            )
            assertEquals(
                "Owned blockquote inlay retained stale minimum/narrow width after viewport expansion",
                expectedExpandedWidth,
                inlay.widthInPixels,
            )
            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)
        } finally {
            controller?.dispose()
            testWindow.contentPane.remove(editor.component)
            testWindow.dispose()
            originalParent?.add(editor.component)
            originalParent?.revalidate()
        }
        assertEquals(source, document.text)
        assertEquals(stampBefore, document.modificationStamp)
    }

    fun testInlineRichAndStructuredBlockQuotesFailClosedToExactSource() {
        val sources = listOf(
            "> quote with *emphasis*\n\nTail\n",
            "> quote with ~~strikethrough~~\n\nTail\n",
            "> # nested heading\n\nTail\n",
        )

        sources.forEachIndexed { index, source ->
            myFixture.configureByText("blockquote-fallback-$index.md", source)
            val editor = myFixture.editor
            editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
            val stampBefore = editor.document.modificationStamp

            val controller = NativePresentationController(
                editor = editor,
                richPresentationEnabled = { true },
            )
            try {
                val evidence = controller.evidenceSnapshot()
                assertEquals(0, evidence.blockQuoteModels)
                assertEquals(0, evidence.blockQuoteInlays)
                assertEquals(0, evidence.blockQuoteFolds)
                assertEquals(0, evidence.blockQuoteFullyConcealed)
                assertEquals(0, evidence.headingInlays)
                assertEquals(0, evidence.headingFolds)
                assertEquals(
                    "unsupported blockquote must not retain inline presentation inside exact-source fallback",
                    0,
                    evidence.inlineOwnedHighlighters,
                )
                assertEquals(source, editor.document.text)
                assertEquals(stampBefore, editor.document.modificationStamp)
            } finally {
                controller.dispose()
            }
        }
    }

    fun testBlockQuoteInlayLeftClickRevealsSourceAndMovesCaretToContent() {
        val source = "> quoted text\n\nTail\n"
        myFixture.configureByText("blockquote-click.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val quote = NativeBlockQuoteProjectionPlanner.plan(plan).single()
        val controller = NativeBlockQuotePresentationController(editor)

        try {
            controller.applyPlan(plan, richPresentationEnabled = true)
            val inlay = editor.inlayModel
                .getBlockElementsInRange(0, editor.document.textLength)
                .single { it.renderer is NativeBlockQuoteInlayRenderer }
            val click = MouseEvent(
                editor.contentComponent,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                0,
                0,
                1,
                false,
                MouseEvent.BUTTON1,
            )
            val inlayOffset = inlay.offset.coerceIn(0, editor.document.textLength)
            val event = EditorMouseEvent(
                editor,
                click,
                EditorMouseEventArea.EDITING_AREA,
                inlayOffset,
                editor.offsetToLogicalPosition(inlayOffset),
                editor.offsetToVisualPosition(inlayOffset),
                false,
                null,
                inlay,
                null,
            )

            assertTrue(controller.handleMouseReveal(event))
            val evidence = controller.evidenceSnapshot()
            assertTrue(click.isConsumed)
            assertEquals(1L, evidence.mouseReveals)
            assertEquals(0, evidence.ownedInlays)
            assertEquals(0, evidence.ownedFolds)
            assertEquals(quote.contentRange.startOffset, editor.caretModel.primaryCaret.offset)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testBlockQuoteAccessibilityDispositionKeepsExactSource() {
        val source = "> quoted text\n\nTail\n"
        myFixture.configureByText("blockquote-accessible.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { false },
        )
        try {
            val evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.blockQuoteModels)
            assertEquals(0, evidence.blockQuoteInlays)
            assertEquals(0, evidence.blockQuoteFolds)
            assertEquals(0, evidence.blockQuoteFullyConcealed)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }


    fun testBlockQuotePresentationCoexistsWithCollapsedForeignMarkerFold() {
        val source = "> quoted text\n\nTail\n"
        myFixture.configureByText("blockquote-foreign-fold.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val quote = plan.projections.single { it.kind == NativeProjectionKind.BLOCK_QUOTE }
        val markerRange = quote.syntaxRanges.single()
        var foreignFold: com.intellij.openapi.editor.FoldRegion? = null

        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            foreignFold = editor.foldingModel.addFoldRegion(
                markerRange.startOffset,
                markerRange.endOffset,
                "platform blockquote marker",
            )
            requireNotNull(foreignFold).isExpanded = false
        }

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.blockQuoteModels)
            assertEquals(1, evidence.blockQuoteInlays)
            assertEquals(2, evidence.blockQuoteFolds)
            assertEquals(1, evidence.blockQuoteFullyConcealed)
            assertTrue(requireNotNull(foreignFold).isValid)
            assertFalse(requireNotNull(foreignFold).isExpanded)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
            assertTrue(requireNotNull(foreignFold).isValid)
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                requireNotNull(foreignFold).takeIf { it.isValid }?.let(editor.foldingModel::removeFoldRegion)
            }
        }
    }

    fun testThematicBreakVariantsUseEditorRelativeRuleAndPreserveFollowingSeparator() {
        val source = """Paragraph before.

---

***

___

Tail
"""
        myFixture.configureByText("thematic-break-presentation.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val thematicBreaks = requireNotNull(controller.currentPlan)
                .projections
                .filter { it.kind == NativeProjectionKind.THEMATIC_BREAK }
            assertEquals(3, thematicBreaks.size)
            assertEquals(
                listOf("---", "***", "___"),
                thematicBreaks.map { projection ->
                    source.substring(projection.sourceRange.startOffset, projection.sourceRange.endOffset)
                },
            )

            fun thematicFoldFor(projection: NativeProjection) =
                editor.foldingModel.allFoldRegions.singleOrNull { candidate ->
                    candidate.isValid &&
                        candidate.startOffset == projection.sourceRange.startOffset &&
                        candidate.endOffset >= projection.sourceRange.endOffset &&
                        candidate.placeholderText.isNotEmpty() &&
                        candidate.placeholderText.all { char -> char == '─' }
                }

            thematicBreaks.forEach { projection ->
                val fold = requireNotNull(thematicFoldFor(projection))
                assertFalse(fold.isExpanded)
                assertEquals(
                    "thematic placeholder must preserve the following separator as a block boundary",
                    projection.sourceRange.endOffset,
                    fold.endOffset,
                )
            }

            val middle = thematicBreaks[1]
            editor.caretModel.moveToOffset(middle.sourceRange.startOffset)
            val middleFold = requireNotNull(thematicFoldFor(middle))
            assertTrue(middleFold.isExpanded)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testInactiveHardLineBreakMarkersAreConcealedWithoutChangingPhysicalLineBoundaryOrSource() {
        val source =
            "Soft line\n" +
                "soft continuation\n\n" +
                "Backslash hard\\\n" +
                "backslash continuation\n\n" +
                "Two-space hard  \n" +
                "space continuation\n\n" +
                "Tail\n"
        myFixture.configureByText("hard-line-break-presentation.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val hardBreaks = requireNotNull(controller.currentPlan)
                .projections
                .filter { it.kind == NativeProjectionKind.HARD_LINE_BREAK }
            assertEquals(2, hardBreaks.size)

            hardBreaks.forEach { projection ->
                val fold = requireNotNull(
                    editor.foldingModel.getFoldRegion(
                        projection.sourceRange.startOffset,
                        projection.sourceRange.endOffset,
                    )
                ) { "inactive hard-break marker did not get an owned syntax fold" }
                assertFalse(fold.isExpanded)
                assertEquals("\u200B", fold.placeholderText)
                assertEquals('\n', source[projection.sourceRange.endOffset])
            }
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)

            val first = hardBreaks.first()
            editor.caretModel.moveToOffset(first.sourceRange.startOffset)
            assertTrue(
                "caret touching hard-break marker must reveal exact source marker",
                requireNotNull(
                    editor.foldingModel.getFoldRegion(
                        first.sourceRange.startOffset,
                        first.sourceRange.endOffset,
                    )
                ).isExpanded,
            )
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }


    fun testMouseRevealTransitionReconcilesPreviousRichBlockAfterGesture() {
        val source = Files.readString(Path.of("examples/markdown-core.md"))
        myFixture.configureByText("markdown-core-mouse-transition.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(editor.document.textLength)
        val stampBefore = editor.document.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val listPlan = plan.withoutBlockQuotePresentationProjections()

        lateinit var blockQuotes: NativeBlockQuotePresentationController
        lateinit var lists: NativeListPresentationController
        var reconciliationRequests = 0

        val requestReconciliation = {
            reconciliationRequests += 1
            ApplicationManager.getApplication().invokeLater {
                blockQuotes.refreshActivity(plan, richPresentationEnabled = true)
                lists.refreshActivity(listPlan, richPresentationEnabled = true)
            }
        }
        blockQuotes = NativeBlockQuotePresentationController(editor, requestReconciliation)
        lists = NativeListPresentationController(editor, requestReconciliation)

        fun click(inlay: com.intellij.openapi.editor.Inlay<*>): EditorMouseEvent {
            val mouseEvent = MouseEvent(
                editor.contentComponent,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                0,
                0,
                1,
                false,
                MouseEvent.BUTTON1,
            )
            val offset = inlay.offset.coerceIn(0, editor.document.textLength)
            return EditorMouseEvent(
                editor,
                mouseEvent,
                EditorMouseEventArea.EDITING_AREA,
                offset,
                editor.offsetToLogicalPosition(offset),
                editor.offsetToVisualPosition(offset),
                false,
                null,
                inlay,
                null,
            )
        }

        fun quoteInlay(): com.intellij.openapi.editor.Inlay<*> =
            editor.inlayModel
                .getBlockElementsInRange(0, editor.document.textLength)
                .single { it.renderer is NativeBlockQuoteInlayRenderer }

        fun firstListInlay(): com.intellij.openapi.editor.Inlay<*> =
            checkNotNull(
                editor.inlayModel
                    .getBlockElementsInRange(0, editor.document.textLength)
                    .filter { it.renderer is NativeListInlayRenderer }
                    .minByOrNull { it.offset }
            ) { "manual Markdown fixture has no visible native list presentation" }

        try {
            blockQuotes.applyPlan(plan, richPresentationEnabled = true)
            lists.applyPlan(listPlan, richPresentationEnabled = true)
            val inactiveListInlays = lists.evidenceSnapshot().ownedInlays
            assertEquals(1, blockQuotes.evidenceSnapshot().ownedInlays)
            assertTrue(inactiveListInlays > 0)

            val quoteClick = click(quoteInlay())
            assertTrue(blockQuotes.handleMouseReveal(quoteClick))
            assertTrue(quoteClick.isConsumed)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals(0, blockQuotes.evidenceSnapshot().ownedInlays)
            assertEquals(inactiveListInlays, lists.evidenceSnapshot().ownedInlays)

            val listClick = click(firstListInlay())
            assertTrue(lists.handleMouseReveal(listClick))
            assertTrue(listClick.isConsumed)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals(
                "leaving the blockquote for another rich block must restore its inactive presentation",
                1,
                blockQuotes.evidenceSnapshot().ownedInlays,
            )
            assertEquals(1, blockQuotes.evidenceSnapshot().fullyConcealed)
            assertEquals(inactiveListInlays - 1, lists.evidenceSnapshot().ownedInlays)

            val restoredQuoteClick = click(quoteInlay())
            assertTrue(blockQuotes.handleMouseReveal(restoredQuoteClick))
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals(0, blockQuotes.evidenceSnapshot().ownedInlays)
            assertEquals(
                "repeated A -> B -> A transitions must restore the list rather than leave stale source",
                inactiveListInlays,
                lists.evidenceSnapshot().ownedInlays,
            )
            assertEquals(3, reconciliationRequests)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            lists.dispose()
            blockQuotes.dispose()
        }
    }

}
