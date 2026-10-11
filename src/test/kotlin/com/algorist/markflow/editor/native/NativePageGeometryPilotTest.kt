package com.algorist.markflow.editor.native

import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Non-production, source-neutral feasibility regression for #353. This
 * intentionally tests the original IDEA editing model, not a custom browser
 * surface or synthetic Preview rendering.
 */
class NativePageGeometryPilotTest : BasePlatformTestCase() {
    fun testScopedProportionalPageInsetsAndParagraphBlockMarginsAreReversible() {
        val source = "# Heading level 1\n\nParagraph after H1.\n\n" +
            "## Heading level 2\n\nParagraph after H2.\n"
        myFixture.configureByText("page-geometry-pilot.md", source)
        val editor = myFixture.editor as EditorEx
        val doc = editor.document
        val paragraph = source.indexOf("Paragraph after H1.")
        val secondHeading = source.indexOf("## Heading level 2")
        val caret = paragraph + 4
        editor.caretModel.moveToOffset(caret)

        // Deliberately start from an IDE-owned, expanded section region.
        var platform: com.intellij.openapi.editor.FoldRegion? = null
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            platform = editor.foldingModel.addFoldRegion(0, doc.textLength - 1, "platform section")
            requireNotNull(platform).isExpanded = true
        }
        val foreign = requireNotNull(platform)
        val originalScheme = editor.colorsScheme
        val originalBorder = editor.scrollPane.border
        val originalText = doc.text
        val stamp = doc.modificationStamp
        val originalSecondY = editor.offsetToXY(secondHeading).y
        val originalFirstY = editor.offsetToXY(paragraph).y
        val originalInsets = editor.insets

        val pilot = requireNotNull(NativePageGeometryPilot.install(editor))
        try {
            assertNotSame("The pilot must clone, never mutate, global/editor scheme", originalScheme,
                editor.colorsScheme)
            assertEquals(13, pilot.measurements().fontSizePx)
            assertEquals(1, pilot.measurements().paragraphSpacerCount)
            assertTrue("Pilot font must be proportionally spaced",
                editor.contentComponent.getFontMetrics(editor.contentComponent.font)
                    .stringWidth("MMMM") >
                    editor.contentComponent.getFontMetrics(editor.contentComponent.font)
                        .stringWidth("iiii"))
            assertTrue("Source-neutral border must add at least left padding",
                pilot.measurements().leftInsetPx >= originalInsets.left + 26)
            assertTrue("Source-neutral border must add top padding",
                pilot.measurements().topInsetPx >= originalInsets.top + 26)
            assertTrue(foreign.isValid)
            assertTrue(foreign.isExpanded)
            assertEquals(source, doc.text)
            assertEquals(stamp, doc.modificationStamp)
            assertEquals(caret, editor.caretModel.offset)

            // A raw absolute Y delta includes the local font/line-height
            // change. Check the paragraph margin via its owned inlay instead.
            val extra = editor.inlayModel.getBlockElementsInRange(0, doc.textLength)
                .single { it.isValid && it.renderer === NativePageParagraphSpacing }
            assertEquals(doc.getLineEndOffset(2), extra.offset)
            assertEquals(16, extra.heightInPixels)
            assertTrue(editor.offsetToXY(secondHeading).y > editor.offsetToXY(paragraph).y)
            // Editor-owned XY <-> source offset mapping must survive even
            // though the document's visual font metrics have changed.
            val pos = editor.offsetToXY(caret)
            assertEquals(editor.offsetToLogicalPosition(caret), editor.xyToLogicalPosition(pos))
        } finally {
            pilot.dispose()
            assertSame(originalScheme, editor.colorsScheme)
            assertSame(originalBorder, editor.scrollPane.border)
            assertEquals(originalInsets, editor.insets)
            assertTrue(foreign.isValid)
            assertTrue(foreign.isExpanded)
            assertEquals(0, editor.inlayModel.getBlockElementsInRange(0, doc.textLength)
                .count { it.isValid && it.renderer === NativePageParagraphSpacing })
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                if (foreign.isValid) editor.foldingModel.removeFoldRegion(foreign)
            }
        }
        assertEquals(originalSecondY, editor.offsetToXY(secondHeading).y)
        assertEquals(originalFirstY, editor.offsetToXY(paragraph).y)
        assertEquals(caret, editor.caretModel.offset)
        assertEquals(originalText, doc.text)
        assertEquals(stamp, doc.modificationStamp)
    }

    fun testCodeFencesFailClosedWithoutTouchingEditorOrPlatformFold() {
        val source = "# Head\n\nParagraph.\n\n```kotlin\nval x = 1\n```\n"
        myFixture.configureByText("mixed-code-pilot.md", source)
        val editor = myFixture.editor as EditorEx
        val scheme = editor.colorsScheme
        val border = editor.scrollPane.border
        val stamp = editor.document.modificationStamp
        assertNull(NativePageGeometryPilot.install(editor))
        assertSame(scheme, editor.colorsScheme)
        assertSame(border, editor.scrollPane.border)
        assertEquals(source, editor.document.text)
        assertEquals(stamp, editor.document.modificationStamp)
    }

    fun testInlineCodeAlsoFailsClosedRatherThanLosingMonospaceSemantics() {
        val source = "# Head\n\nParagraph with `inline code`.\n"
        myFixture.configureByText("inline-code-pilot.md", source)
        val editor = myFixture.editor
        val scheme = editor.colorsScheme
        assertNull(NativePageGeometryPilot.install(editor))
        assertSame(scheme, editor.colorsScheme)
        assertEquals(source, editor.document.text)
    }
    fun testH1SourceFoldAndPagePilotNeverDoubleOwnParagraphGap() {
        val source = "# Heading level 1\n\nParagraph after H1.\n\n" +
            "## Heading level 2\n\nParagraph after H2.\n"
        myFixture.configureByText("heading-layout-b.md", source)
        val editor = myFixture.editor as EditorEx
        val doc = editor.document
        val firstParagraphEnd = doc.getLineEndOffset(2)
        editor.caretModel.moveToOffset(source.indexOf("Paragraph after H1.") + 3)

        // Same-start platform fold is the reason H1 uses an inline fallback
        // rather than an overlapping IDEA CustomFoldRegion in the pinned IDE.
        var platform: com.intellij.openapi.editor.FoldRegion? = null
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            platform = editor.foldingModel.addFoldRegion(0, doc.textLength - 1, "platform section")
            requireNotNull(platform).isExpanded = true
        }
        val foreign = requireNotNull(platform)
        val originalText = doc.text
        val stamp = doc.modificationStamp
        val pilot = requireNotNull(NativePageGeometryPilot.install(editor))
        val headings = NativeHeadingPresentationController(editor, sourceLineReplacement = true)
        try {
            assertEquals(1, pilot.measurements().paragraphSpacerCount)
            headings.applyPlan(
                NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(doc, 0L)),
                richPresentationEnabled = true,
            )
            assertEquals("H1 must use the same-start inline fallback", 1,
                headings.evidenceSnapshot().ownedInlays)
            val gaps = editor.inlayModel.getBlockElementsInRange(firstParagraphEnd, firstParagraphEnd)
                .filter { it.isValid && it.offset == firstParagraphEnd &&
                    (it.renderer === NativePageParagraphSpacing ||
                        it.renderer === NativeHeadingParagraphGapRenderer) }
            assertEquals("Only one owner may allocate the H1 -> H2 paragraph margin", 1, gaps.size)
            assertSame(NativePageParagraphSpacing, gaps.single().renderer)
            assertTrue(foreign.isValid)
            assertTrue(foreign.isExpanded)
            assertEquals(originalText, doc.text)
            assertEquals(stamp, doc.modificationStamp)

            headings.clearPresentation()
            assertTrue("Releasing H1 must not dispose the page owner's inlay",
                gaps.single().isValid)
        } finally {
            headings.dispose()
            pilot.dispose()
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                if (foreign.isValid) editor.foldingModel.removeFoldRegion(foreign)
            }
        }
        assertEquals(0, editor.inlayModel.getBlockElementsInRange(
            firstParagraphEnd, firstParagraphEnd
        ).count { it.isValid && (it.renderer === NativePageParagraphSpacing ||
            it.renderer === NativeHeadingParagraphGapRenderer) })
        assertEquals(originalText, doc.text)
        assertEquals(stamp, doc.modificationStamp)
    }

    fun testPreexistingHeadingOwnedGapPreventsLatePageGapInstallation() {
        val source = "# Heading level 1\n\nParagraph after H1.\n\n" +
            "## Heading level 2\n\nParagraph after H2.\n"
        myFixture.configureByText("heading-layout-b.md", source)
        val editor = myFixture.editor as EditorEx
        val doc = editor.document
        val firstParagraphEnd = doc.getLineEndOffset(2)
        val preexisting = requireNotNull(editor.inlayModel.addBlockElement(
            firstParagraphEnd, true, false, 0, NativeHeadingParagraphGapRenderer
        ))
        try {
            val pilot = requireNotNull(NativePageGeometryPilot.install(editor))
            try {
                assertEquals(0, pilot.measurements().paragraphSpacerCount)
                assertEquals(1, editor.inlayModel.getBlockElementsInRange(
                    firstParagraphEnd, firstParagraphEnd
                ).count { it.isValid && it.offset == firstParagraphEnd &&
                    (it.renderer === NativePageParagraphSpacing ||
                        it.renderer === NativeHeadingParagraphGapRenderer) })
            } finally {
                pilot.dispose()
            }
            assertTrue("Page pilot must not dispose another owner's gap", preexisting.isValid)
        } finally {
            if (preexisting.isValid) preexisting.dispose()
        }
    }

}
