package com.algorist.markflow.editor.native

import com.intellij.openapi.editor.CustomFoldRegion
import com.intellij.openapi.editor.CustomFoldRegionRenderer
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Font
import java.awt.event.MouseEvent

class NativeOrdinaryPresentationTest : BasePlatformTestCase() {
    /**
     * #350 research: a platform custom line fold takes the place of the
     * source visual line, unlike the current heading block-inlay approach.
     * No product switch until caret, mouse reveal and platform parser-fold
     * coexistence have their own actual IDE evidence.
     */
    fun testCustomSingleLineHeadingFoldReplacesOriginalEditorLineHeight() {
        val source = "# Heading\n\nParagraph after heading.\n"
        myFixture.configureByText("single-line-fold-geometry.txt", source)
        val editor = myFixture.editor
        val document = editor.document
        val nextTextOffset = source.indexOf("Paragraph")
        editor.caretModel.moveToOffset(nextTextOffset + 1)
        val caretBefore = editor.caretModel.offset
        val stampBefore = document.modificationStamp
        val baselineY = editor.offsetToXY(nextTextOffset).y
        val blockHeight = editor.lineHeight + 18
        var region: CustomFoldRegion? = null
        val renderer = object : CustomFoldRegionRenderer {
            override fun calcWidthInPixels(region: CustomFoldRegion): Int = 360
            override fun calcHeightInPixels(region: CustomFoldRegion): Int = blockHeight
            override fun paint(
                region: CustomFoldRegion,
                g: Graphics2D,
                targetRegion: Rectangle2D,
                textAttributes: TextAttributes,
            ) {
                // Geometry probe only: do not mutate editor state.
            }
        }
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            region = editor.foldingModel.addCustomLinesFolding(0, 0, renderer)
        }
        val installed = requireNotNull(region) { "Platform custom line fold unavailable" }
        try {
            assertTrue(installed.isValid)
            assertFalse(installed.isExpanded)
            assertEquals(
                "Single-line custom fold must replace, not add to, source line",
                blockHeight - editor.lineHeight,
                editor.offsetToXY(nextTextOffset).y - baselineY,
            )
            assertEquals(caretBefore, editor.caretModel.offset)
            assertEquals(stampBefore, document.modificationStamp)
            assertEquals(source, document.text)

            // Platform API: custom line folds are always collapsed and
            // cannot be expanded. Reveal means REMOVE, then reinstall.
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                editor.foldingModel.removeFoldRegion(installed)
            }
            assertEquals("Removing custom fold must restore plain vertical rhythm",
                baselineY, editor.offsetToXY(nextTextOffset).y)
            assertEquals(source, document.text)
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                region = editor.foldingModel.addCustomLinesFolding(0, 0, renderer)
            }
            assertTrue(requireNotNull(region).isValid)
            assertEquals(blockHeight - editor.lineHeight,
                editor.offsetToXY(nextTextOffset).y - baselineY)
        } finally {
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                region?.takeIf { it.isValid }?.let(editor.foldingModel::removeFoldRegion)
            }
        }
        assertEquals("Disposal must restore source-line advance",
            baselineY, editor.offsetToXY(nextTextOffset).y)
        assertEquals(caretBefore, editor.caretModel.offset)
        assertEquals(stampBefore, document.modificationStamp)
        assertEquals(source, document.text)
    }

    /**
     * #353 H1: distinguish an enclosing platform-style section fold from a
     * short Markdown syntax fold. A long expanded region beginning at H1's
     * source offset competes with a whole-line H1 CustomFoldRegion, while a
     * strictly nested H2 CustomFold can be installed. Preserve all foreign
     * fold ownership; do not manufacture a green 6/6 by removing it.
     *
     * This is a deterministic synthetic platform-fold contract in
     * BasePlatformTestCase. The actual 2026.2.3 Markdown provider and the
     * exact B fixture remain independently validated in Starter.
     */
    fun testH1CustomFoldFailsClosedOnExpandedForeignSectionSharingStart() {
        val source = "# Heading level 1\n\nParagraph after H1.\n\n" +
            "## Heading level 2\n\nParagraph after H2.\n"
        myFixture.configureByText("heading-platform-section-collision.md", source)
        val editor = myFixture.editor
        val document = editor.document
        val stamp = document.modificationStamp
        editor.caretModel.moveToOffset(source.length)
        val caret = editor.caretModel.offset
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot.capture(document, 0L)
        )
        assertEquals(ProjectionPlanStatus.READY, plan.status)
        assertEquals(listOf(1, 2), NativeHeadingProjectionPlanner.plan(plan).map { it.level })

        var foreign: com.intellij.openapi.editor.FoldRegion? = null
        val pilot = NativeHeadingPresentationController(
            editor, sourceLineReplacement = true
        )
        try {
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                foreign = editor.foldingModel.addFoldRegion(
                    0, document.textLength - 1, "platform-owned Markdown section"
                )
                requireNotNull(foreign).isExpanded = true
            }
            val section = requireNotNull(foreign)
            assertTrue(section.isValid)
            assertTrue(section.isExpanded)
            pilot.applyPlan(plan, richPresentationEnabled = true)
            val installed = editor.foldingModel.allFoldRegions
                .filterIsInstance<CustomFoldRegion>()
                .filter { it.isValid && !it.isExpanded &&
                    it.renderer is NativeHeadingCustomFoldRenderer }
            assertEquals(
                "The same-start platform section must block H1 without blocking the nested H2",
                1, installed.size,
            )
            assertEquals(source.indexOf("## Heading level 2"), installed.single().startOffset)
            assertEquals(1, pilot.evidenceSnapshot().ownedCustomFolds)
            assertTrue("Never remove or replace the foreign section fold", section.isValid)
            assertTrue("Never change platform expansion state to force H1", section.isExpanded)
            assertEquals(stamp, document.modificationStamp)
            assertEquals(source, document.text)
            assertEquals(caret, editor.caretModel.offset)
        } finally {
            pilot.dispose()
            // Even disposal must leave platform-owned folding untouched.
            assertTrue(requireNotNull(foreign).isValid)
            assertTrue(requireNotNull(foreign).isExpanded)
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                foreign?.takeIf { it.isValid }?.let(editor.foldingModel::removeFoldRegion)
            }
        }
        assertEquals(stamp, document.modificationStamp)
        assertEquals(source, document.text)
        assertEquals(caret, editor.caretModel.offset)
    }

    /**
     * #353 possible non-destructive fallback: only probe whether IDEA allows a
     * conventional source-heading fold inside an expanded platform section
     * with the *same start*. A successful CustomFold is explicitly NOT required
     * here (see the separate failing-H1 contract), and no foreign fold may be
     * changed to make this proof pass. It is intentionally a feasibility probe,
     * not a claim that native heading presentation now matches the Preview.
     */
    fun testH1ConventionalSourceFoldCanNestInsideForeignSectionAtSameStart() {
        val source = "# Heading level 1\n\nParagraph after H1.\n\n" +
            "## Heading level 2\n\nParagraph after H2.\n"
        myFixture.configureByText("heading-section-same-start-normal-fold.txt", source)
        val editor = myFixture.editor
        val document = editor.document
        val stamp = document.modificationStamp
        val tailOffset = source.indexOf("Paragraph after H2.") + 1
        editor.caretModel.moveToOffset(tailOffset)
        var foreign: com.intellij.openapi.editor.FoldRegion? = null
        var sourceFold: com.intellij.openapi.editor.FoldRegion? = null
        try {
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                foreign = editor.foldingModel.addFoldRegion(
                    0, document.textLength - 1, "platform section"
                )
                requireNotNull(foreign).isExpanded = true
            }
            val platformFold = requireNotNull(foreign)
            val h1End = document.getLineEndOffset(0)
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                sourceFold = editor.foldingModel.addFoldRegion(0, h1End, "\u200B")
                sourceFold?.isExpanded = false
            }
            val own = requireNotNull(sourceFold) {
                "Same-start conventional source fold also rejected by pinned IDEA"
            }
            assertTrue(own.isValid)
            assertFalse(own.isExpanded)
            assertEquals(0, own.startOffset)
            assertEquals(h1End, own.endOffset)
            assertTrue(platformFold.isValid)
            assertTrue(platformFold.isExpanded)
            assertEquals(source, document.text)
            assertEquals(stamp, document.modificationStamp)
            assertEquals(tailOffset, editor.caretModel.offset)
        } finally {
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                sourceFold?.takeIf { it.isValid }?.let(editor.foldingModel::removeFoldRegion)
            }
            // The normal-fold owner must only dispose its own region.
            assertTrue(requireNotNull(foreign).isValid)
            assertTrue(requireNotNull(foreign).isExpanded)
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                foreign?.takeIf { it.isValid }?.let(editor.foldingModel::removeFoldRegion)
            }
        }
        assertEquals(source, document.text)
        assertEquals(stamp, document.modificationStamp)
        assertEquals(tailOffset, editor.caretModel.offset)
    }

    /**
     * #350 custom-fold compatibility with the bundled Markdown editor's own
     * collapsed heading-syntax fold. A custom line fold may coexist with a
     * foreign fold entirely inside that line; neither must be owned/removed
     * by our projection when revealing exact source.
     */
    fun testCustomHeadingFoldCoexistsWithPlatformSyntaxFoldAndSourceReveal() {
        val source = "# Heading\n\nTail\n"
        myFixture.configureByText("heading-platform-syntax-custom-fold.md", source)
        val editor = myFixture.editor
        val document = editor.document
        val tailOffset = source.indexOf("Tail") + 1
        val headingOffset = source.indexOf("Heading") + 2
        editor.caretModel.moveToOffset(tailOffset)
        val stampBefore = document.modificationStamp
        val beforeY = editor.offsetToXY(tailOffset).y
        var foreign: com.intellij.openapi.editor.FoldRegion? = null
        var rich: CustomFoldRegion? = null
        val targetHeight = editor.lineHeight + 16
        val renderer = object : CustomFoldRegionRenderer {
            override fun calcWidthInPixels(region: CustomFoldRegion): Int = 360
            override fun calcHeightInPixels(region: CustomFoldRegion): Int = targetHeight
            override fun paint(
                region: CustomFoldRegion,
                g: Graphics2D,
                targetRegion: Rectangle2D,
                textAttributes: TextAttributes,
            ) {
                // Geometry and lifetime compatibility only; production paint is unchanged.
            }
        }
        try {
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                foreign = editor.foldingModel.addFoldRegion(0, 2, "markdown syntax")
                requireNotNull(foreign).isExpanded = false
            }
            assertTrue(requireNotNull(foreign).isValid)
            assertFalse(requireNotNull(foreign).isExpanded)
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                rich = editor.foldingModel.addCustomLinesFolding(0, 0, renderer)
            }
            assertTrue("Native heading custom fold must coexist with a foreign syntax fold",
                requireNotNull(rich).isValid)
            assertEquals(targetHeight - editor.lineHeight,
                editor.offsetToXY(tailOffset).y - beforeY)
            assertEquals(tailOffset, editor.caretModel.offset)
            assertTrue(requireNotNull(foreign).isValid)
            assertFalse(requireNotNull(foreign).isExpanded)

            // Custom line folds cannot expand. Removing ours must reveal the
            // original source without deleting or expanding JetBrains's fold.
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                requireNotNull(rich).takeIf { it.isValid }?.let(editor.foldingModel::removeFoldRegion)
            }
            rich = null
            assertEquals(beforeY, editor.offsetToXY(tailOffset).y)
            assertTrue(requireNotNull(foreign).isValid)
            assertFalse(requireNotNull(foreign).isExpanded)

            editor.caretModel.moveToOffset(headingOffset)
            assertEquals(headingOffset, editor.caretModel.offset)
            assertEquals(source, document.text)
            editor.caretModel.moveToOffset(tailOffset)
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                rich = editor.foldingModel.addCustomLinesFolding(0, 0, renderer)
            }
            assertTrue(requireNotNull(rich).isValid)
            assertEquals(targetHeight - editor.lineHeight,
                editor.offsetToXY(tailOffset).y - beforeY)
        } finally {
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                rich?.takeIf { it.isValid }?.let(editor.foldingModel::removeFoldRegion)
                foreign?.takeIf { it.isValid }?.let(editor.foldingModel::removeFoldRegion)
            }
        }
        assertEquals(beforeY, editor.offsetToXY(tailOffset).y)
        assertEquals(tailOffset, editor.caretModel.offset)
        assertEquals(source, document.text)
        assertEquals(stampBefore, document.modificationStamp)
    }

    /**
     * #350 measure candidate two-line fold of an ATX heading plus the verified
     * immediately following blank line. It must replace TWO source lines,
     * not conceal any nonblank paragraph text or change the authoritative file.
     */
    fun testCustomHeadingAndVerifiedBlankLineFoldDoesNotDoubleAllocateHeight() {
        val source = "# Heading\n\nParagraph after heading.\n"
        myFixture.configureByText("heading-plus-blank-custom-fold.md", source)
        val editor = myFixture.editor
        val document = editor.document
        assertEquals("", document.getText(
            com.intellij.openapi.util.TextRange(
                document.getLineStartOffset(1), document.getLineEndOffset(1),
            )
        ))
        val paragraphOffset = source.indexOf("Paragraph") + 2
        editor.caretModel.moveToOffset(paragraphOffset)
        val stampBefore = document.modificationStamp
        val plainY = editor.offsetToXY(paragraphOffset).y
        val targetHeight = editor.lineHeight + 20
        var region: CustomFoldRegion? = null
        val renderer = object : CustomFoldRegionRenderer {
            override fun calcWidthInPixels(region: CustomFoldRegion): Int = 360
            override fun calcHeightInPixels(region: CustomFoldRegion): Int = targetHeight
            override fun paint(
                region: CustomFoldRegion,
                g: Graphics2D,
                targetRegion: Rectangle2D,
                textAttributes: TextAttributes,
            ) {
                // Proof-only, no product glyphs changed.
            }
        }
        try {
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                region = editor.foldingModel.addCustomLinesFolding(0, 1, renderer)
            }
            val installed = requireNotNull(region)
            assertTrue(installed.isValid)
            assertEquals(
                "Two-line custom fold must replace both original visual lines",
                targetHeight - editor.lineHeight * 2,
                editor.offsetToXY(paragraphOffset).y - plainY,
            )
            assertEquals(paragraphOffset, editor.caretModel.offset)
            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)
        } finally {
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                region?.takeIf { it.isValid }?.let(editor.foldingModel::removeFoldRegion)
            }
        }
        assertEquals(plainY, editor.offsetToXY(paragraphOffset).y)
        assertEquals(paragraphOffset, editor.caretModel.offset)
        assertEquals(source, document.text)
        assertEquals(stampBefore, document.modificationStamp)
    }

    /**
     * #350 bridge research: EditorImpl reports a visible CustomFoldRegion in
     * EditorMouseEvent.collapsedFoldRegion. Its owner must gate removal; an
     * arbitrary foreign fold, wrong button or non-editing area must not reveal.
     *
     * Events here are constructed to exercise the ownership callback, not
     * physical hit testing. A real Starter mouse path remains mandatory before
     * changing production heading presentation.
     */
    fun testCustomTwoLineHeadingFoldLeftClickOwnershipAndForeignSyntaxPreservation() {
        val source = "# Heading\n\nTail\n"
        myFixture.configureByText("heading-custom-click-ownership.md", source)
        val editor = myFixture.editor
        val document = editor.document
        val caretInTail = source.indexOf("Tail") + 1
        val headingContentOffset = source.indexOf("Heading")
        editor.caretModel.moveToOffset(caretInTail)
        val plainY = editor.offsetToXY(caretInTail).y
        val stampBefore = document.modificationStamp
        var foreign: com.intellij.openapi.editor.FoldRegion? = null
        var owned: CustomFoldRegion? = null
        val displayHeight = editor.lineHeight + 16
        val renderer = object : CustomFoldRegionRenderer {
            override fun calcWidthInPixels(region: CustomFoldRegion): Int = 320
            override fun calcHeightInPixels(region: CustomFoldRegion): Int = displayHeight
            override fun paint(
                region: CustomFoldRegion,
                g: Graphics2D,
                targetRegion: Rectangle2D,
                textAttributes: TextAttributes,
            ) {
                // Event and lifetime contract only; product paint is untouched.
            }
        }
        var revealCount = 0
        val clickListener = object : com.intellij.openapi.editor.event.EditorMouseListener {
            override fun mouseClicked(event: EditorMouseEvent) {
                if (event.editor !== editor ||
                    event.area != EditorMouseEventArea.EDITING_AREA ||
                    event.mouseEvent.button != MouseEvent.BUTTON1) return
                val target = event.collapsedFoldRegion as? CustomFoldRegion ?: return
                if (target !== owned || !target.isValid) return

                editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                    editor.foldingModel.removeFoldRegion(target)
                }
                owned = null
                editor.selectionModel.removeSelection()
                editor.caretModel.primaryCaret.moveToOffset(headingContentOffset)
                revealCount++
                event.consume()
            }
        }
        fun click(
            region: com.intellij.openapi.editor.FoldRegion?,
            button: Int = MouseEvent.BUTTON1,
            area: EditorMouseEventArea = EditorMouseEventArea.EDITING_AREA,
        ): EditorMouseEvent {
            val mouseEvent = MouseEvent(
                editor.contentComponent,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0, 0, 0, 1, false, button,
            )
            return EditorMouseEvent(
                editor, mouseEvent, area, 0,
                editor.offsetToLogicalPosition(0),
                editor.offsetToVisualPosition(0),
                false, region, null, null,
            )
        }
        editor.addEditorMouseListener(clickListener)
        try {
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                foreign = editor.foldingModel.addFoldRegion(0, 2, "platform marker")
                requireNotNull(foreign).isExpanded = false
                owned = editor.foldingModel.addCustomLinesFolding(0, 1, renderer)
            }
            val target = requireNotNull(owned) {
                "Two-line custom heading fold must coexist with platform marker"
            }
            assertTrue(target.isValid)
            assertTrue(requireNotNull(foreign).isValid)
            assertFalse(requireNotNull(foreign).isExpanded)
            assertEquals(displayHeight - editor.lineHeight * 2,
                editor.offsetToXY(caretInTail).y - plainY)

            val foreignClick = click(foreign)
            clickListener.mouseClicked(foreignClick)
            assertFalse(foreignClick.isConsumed)
            val rightClick = click(target, MouseEvent.BUTTON3)
            clickListener.mouseClicked(rightClick)
            assertFalse(rightClick.isConsumed)
            val wrongArea = click(target, area = EditorMouseEventArea.LINE_NUMBERS_AREA)
            clickListener.mouseClicked(wrongArea)
            assertFalse(wrongArea.isConsumed)
            assertEquals(0, revealCount)
            assertTrue(requireNotNull(owned).isValid)
            assertEquals(caretInTail, editor.caretModel.offset)

            val ownedClick = click(target)
            clickListener.mouseClicked(ownedClick)
            assertTrue(ownedClick.isConsumed)
            assertEquals(1, revealCount)
            assertNull(owned)
            assertFalse(target.isValid)
            assertEquals(headingContentOffset, editor.caretModel.offset)
            assertEquals(plainY, editor.offsetToXY(caretInTail).y)
            assertTrue("Foreign Markdown syntax region must not be removed",
                requireNotNull(foreign).isValid)
            assertFalse(requireNotNull(foreign).isExpanded)
            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)

            // An inactive heading can be projected again only after caret
            // leaves the source range. No stale click may remove its replacement.
            editor.caretModel.moveToOffset(caretInTail)
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                owned = editor.foldingModel.addCustomLinesFolding(0, 1, renderer)
            }
            assertTrue(requireNotNull(owned).isValid)
            val staleClick = click(target)
            clickListener.mouseClicked(staleClick)
            assertFalse(staleClick.isConsumed)
            assertEquals(1, revealCount)
            assertTrue(requireNotNull(owned).isValid)
        } finally {
            editor.removeEditorMouseListener(clickListener)
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                owned?.takeIf { it.isValid }?.let(editor.foldingModel::removeFoldRegion)
                foreign?.takeIf { it.isValid }?.let(editor.foldingModel::removeFoldRegion)
            }
        }
        assertEquals(plainY, editor.offsetToXY(caretInTail).y)
        assertEquals(caretInTail, editor.caretModel.offset)
        assertEquals(source, document.text)
        assertEquals(stampBefore, document.modificationStamp)
    }

    /**
     * Renderer intended for #350 product migration, not yet wired into the
     * production controller. Assert typography parity with the currently
     * shipping native heading inlay before modifying the source layout path.
     */
    fun testHeadingCustomFoldRendererMatchesExistingH2TypographyAndPaintsPixels() {
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
        val activeRenderer = NativeHeadingPresentationController(editor)
        val originalWidth: Int
        val originalHeight: Int
        try {
            activeRenderer.applyPlan(plan, richPresentationEnabled = true)
            val inlay = editor.inlayModel.getBlockElementsInRange(0, document.textLength)
                .single { it.renderer is NativeHeadingInlayRenderer }
            originalWidth = inlay.widthInPixels
            originalHeight = inlay.heightInPixels
        } finally {
            activeRenderer.dispose()
        }
        assertEquals(plainY, editor.offsetToXY(tailOffset).y)
        val sourceLines = requireNotNull(NativeHeadingCustomFoldLinePlanner.plan(editor, model))
        assertEquals(0..1, sourceLines)
        val customRenderer = NativeHeadingCustomFoldRenderer(editor, model)
        var region: CustomFoldRegion? = null
        try {
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                region = editor.foldingModel.addCustomLinesFolding(sourceLines.first, sourceLines.last, customRenderer)
            }
            val custom = requireNotNull(region) { "Custom two-line region unavailable" }
            assertTrue(custom.isValid)
            assertEquals("H2 width must match previous native typography", originalWidth, custom.widthInPixels)
            assertTrue(
                "Custom Heading must never cache a one-pixel ellipsis in an unlaid-out viewport",
                custom.widthInPixels > 1,
            )
            assertEquals("H2 height must match previous native typography", originalHeight, custom.heightInPixels)
            assertEquals("Heading plus blank line must be REPLACED, not added",
                originalHeight - editor.lineHeight * 2, editor.offsetToXY(tailOffset).y - plainY)
            assertEquals("Heading", customRenderer.displayText)
            assertEquals(model.contentRange.startOffset, customRenderer.contentOffset)

            // The real renderer must paint text, not merely return geometry.
            val bitmap = java.awt.image.BufferedImage(
                maxOf(1, custom.widthInPixels + 16),
                maxOf(1, custom.heightInPixels + 16),
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
            var opaquePixels = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    if (bitmap.getRGB(x, y).ushr(24) != 0) opaquePixels++
                }
            }
            assertTrue("Custom region renderer did not paint visible glyph pixels", opaquePixels > 0)
            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)
            assertEquals(tailOffset, editor.caretModel.offset)
        } finally {
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                region?.takeIf { it.isValid }?.let(editor.foldingModel::removeFoldRegion)
            }
        }
        assertEquals(plainY, editor.offsetToXY(tailOffset).y)
        assertEquals(source, document.text)
        assertEquals(stampBefore, document.modificationStamp)
        assertEquals(tailOffset, editor.caretModel.offset)
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
        candidate("Heading\n=======\n\nTail\n", null)
    }

    /**
     * #350 fail-closed boundary: multiline Setext headings are valid Markdown,
     * but cannot be replaced by a whole-line custom fold in the current pilot.
     * They must not regain the old double-height block inlay as a fallback.
     */
    fun testSourceReplacingHeadingRejectsMultilineSetextWithoutLegacyInlay() {
        val source = "Heading\n=======\n\nTail\n"
        myFixture.configureByText("heading-setext-unsafe-custom-fold.md", source)
        val editor = myFixture.editor
        val document = editor.document
        val tailOffset = source.indexOf("Tail") + 1
        editor.caretModel.moveToOffset(tailOffset)
        val baselineY = editor.offsetToXY(tailOffset).y
        val stamp = document.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(document, 0L))
        val heading = NativeHeadingProjectionPlanner.plan(plan).single()
        assertNull(NativeHeadingCustomFoldLinePlanner.plan(editor, heading))
        val controller = NativeHeadingPresentationController(editor, sourceLineReplacement = true)
        try {
            controller.applyPlan(plan, richPresentationEnabled = true)
            val evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.models)
            assertEquals(0, evidence.ownedInlays)
            assertEquals(0, evidence.ownedFolds)
            assertEquals(0, evidence.ownedCustomFolds)
            assertTrue(editor.inlayModel.getBlockElementsInRange(0, document.textLength)
                .none { it.renderer is NativeHeadingInlayRenderer })
            assertEquals(baselineY, editor.offsetToXY(tailOffset).y)
            assertEquals(tailOffset, editor.caretModel.offset)
            assertEquals(source, document.text)
            assertEquals(stamp, document.modificationStamp)
        } finally {
            controller.dispose()
        }
        assertEquals(baselineY, editor.offsetToXY(tailOffset).y)
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
        val controller = NativeHeadingPresentationController(editor, sourceLineReplacement = true)
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
        val controller = NativeHeadingPresentationController(editor, sourceLineReplacement = true)
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
        val controller = NativeHeadingPresentationController(editor, sourceLineReplacement = true)
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
        val controller = NativeHeadingPresentationController(editor, sourceLineReplacement = true)
        try {
            controller.applyPlan(plan, richPresentationEnabled = true)
            var evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.models)
            assertEquals(0, evidence.ownedInlays)
            assertEquals(0, evidence.ownedCustomFolds)
            assertEquals(0, evidence.ownedFolds)
            assertTrue(foreign.isValid)
            assertTrue(foreign.isExpanded)
            assertEquals(0, foreign.startOffset)
            assertEquals(document.textLength, foreign.endOffset)
            assertEquals(originalY, editor.offsetToXY(tailOffset).y)
            assertEquals(source, document.text)
            assertEquals(stamp, document.modificationStamp)

            // Repeated activity refreshes must not steal a platform fold.
            controller.refreshActivity(plan, richPresentationEnabled = true)
            assertEquals(0, controller.evidenceSnapshot().ownedCustomFolds)
            assertTrue(foreign.isValid)
            assertTrue(foreign.isExpanded)

            // Isolate the exact same-start parent-fold constraint. Removing
            // the foreign fold is allowed only inside this controlled test.
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                editor.foldingModel.removeFoldRegion(foreign)
            }
            controller.refreshActivity(plan, richPresentationEnabled = true)
            evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.ownedCustomFolds)
            assertEquals(1, evidence.ownedFolds)
            assertEquals(0, evidence.ownedInlays)
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
        val controller = NativeHeadingPresentationController(editor, sourceLineReplacement = true)
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
        val controller = NativeHeadingPresentationController(editor, sourceLineReplacement = true)
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

            val placeholders = editor.foldingModel.allFoldRegions
                .filter { it.isValid && !it.isExpanded }
                .map { it.placeholderText }
            assertTrue("thematic break was not projected", placeholders.contains("────────"))

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

            fun attributes(projection: NativeProjection) = requireNotNull(
                editor.markupModel.allHighlighters
                    .singleOrNull { highlighter ->
                        highlighter.isValid &&
                            highlighter.startOffset == projection.sourceRange.startOffset &&
                            highlighter.endOffset == projection.sourceRange.endOffset
                    }
                    ?.getTextAttributes(editor.colorsScheme)
            ) { "missing inactive semantic highlighter for ${projection.kind}" }

            assertTrue(attributes(emphasis).fontType and Font.ITALIC != 0)
            assertTrue(attributes(strong).fontType and Font.BOLD != 0)

            val expectedLink = requireNotNull(
                editor.colorsScheme.getAttributes(EditorColors.REFERENCE_HYPERLINK_COLOR)
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
            assertEquals(8, evidence.headingInlays)
            assertTrue(evidence.headingFolds >= 16)
            assertEquals(8, evidence.headingFullyConcealed)
            assertEquals(listOf(1, 2, 3, 4, 5, 6, 1, 2), evidence.headingLevels)

            val renderers = editor.inlayModel
                .getBlockElementsInRange(0, editor.document.textLength)
                .mapNotNull { inlay -> inlay.renderer as? NativeHeadingInlayRenderer }
            assertEquals(8, renderers.size)
            assertEquals(
                listOf("H1", "H2", "H3", "H4", "H5", "H6", "Setext one", "Setext two"),
                renderers.map(NativeHeadingInlayRenderer::displayText),
            )
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
            assertEquals(1, controller.evidenceSnapshot().headingInlays)
            assertTrue(controller.evidenceSnapshot().headingFolds > 0)
            assertEquals(1, controller.evidenceSnapshot().headingFullyConcealed)

            editor.caretModel.moveToOffset(source.indexOf("Heading") + 2)
            assertEquals(0, controller.evidenceSnapshot().headingInlays)
            assertEquals(0, controller.evidenceSnapshot().headingFolds)
            assertEquals(0, controller.evidenceSnapshot().headingFullyConcealed)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)

            editor.caretModel.moveToOffset(tailOffset)
            assertEquals(1, controller.evidenceSnapshot().headingInlays)
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

    fun testHeadingInlayLeftClickRevealsSourceAndMovesCaretToParserContent() {
        val source = "# Heading\n\nTail\n"
        myFixture.configureByText("heading-click.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val heading = plan.projections.single { it.kind == NativeProjectionKind.HEADING }
        val contentOffset = heading.contentRanges.single().startOffset
        val controller = NativeHeadingPresentationController(editor)

        try {
            controller.applyPlan(plan, richPresentationEnabled = true)
            val inlay = editor.inlayModel
                .getBlockElementsInRange(0, editor.document.textLength)
                .single { it.renderer is NativeHeadingInlayRenderer }
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
            assertEquals(0, evidence.fullyConcealed)
            assertEquals(contentOffset, editor.caretModel.primaryCaret.offset)
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

    fun testInlineRichAndMultilineBlockQuotesFailClosedToExactSource() {
        val sources = listOf(
            "> quote with *emphasis*\n\nTail\n",
            "> quote with ~~strikethrough~~\n\nTail\n",
            "> first line\n> second line\n\nTail\n",
            "> outer\n>> nested\n\nTail\n",
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

}
