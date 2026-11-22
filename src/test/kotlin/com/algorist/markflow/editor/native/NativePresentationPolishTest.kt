package com.algorist.markflow.editor.native

import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.BorderLayout
import java.awt.Rectangle
import javax.swing.JFrame
import javax.swing.UIManager
import kotlin.math.max

class NativePresentationPolishTest : BasePlatformTestCase() {
    fun testListPresentationCompactsOnlyVerifiedFollowingBlankSeparator() {
        val source = "- first\n- second\n\n* third\n* fourth\n\nTail\n"
        myFixture.configureByText("list-polish.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val models = NativeListProjectionPlanner.plan(plan)
        assertEquals(2, models.size)

        val firstPresentationRange =
            nativePresentationRangeWithFollowingBlankLine(models[0].sourceRange, source)
        val secondPresentationRange =
            nativePresentationRangeWithFollowingBlankLine(models[1].sourceRange, source)
        assertTrue(firstPresentationRange.endOffset > models[0].sourceRange.endOffset)
        assertTrue(secondPresentationRange.endOffset > models[1].sourceRange.endOffset)
        assertEquals(source.indexOf("* third"), firstPresentationRange.endOffset)
        assertEquals(source.indexOf("Tail"), secondPresentationRange.endOffset)

        val controller = NativeListPresentationController(editor)
        try {
            controller.applyPlan(plan, richPresentationEnabled = true)
            val inlays = editor.inlayModel
                .getBlockElementsInRange(0, editor.document.textLength)
                .filter { it.renderer is NativeListInlayRenderer }
                .sortedBy { it.offset }
            assertEquals(2, inlays.size)
            assertEquals(firstPresentationRange.endOffset, inlays[0].offset)
            assertEquals(secondPresentationRange.endOffset, inlays[1].offset)

            listOf(firstPresentationRange, secondPresentationRange).forEach { presentationRange ->
                assertTrue(
                    "inactive list must conceal the verified source-only blank separator",
                    editor.foldingModel.allFoldRegions.any { fold ->
                        fold.isValid &&
                            !fold.isExpanded &&
                            fold.startOffset < presentationRange.endOffset &&
                            fold.endOffset == presentationRange.endOffset
                    },
                )
            }
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testListPresentationDoesNotConsumeNonBlankFollowingLine() {
        val source = "- first\n- second\nTail\n"
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(ProjectionSourceIdentity(1L, source, 0L))
        )
        val model = NativeListProjectionPlanner.plan(plan).single()
        assertEquals(
            model.sourceRange,
            nativePresentationRangeWithFollowingBlankLine(model.sourceRange, source),
        )
    }

    fun testListRowRhythmDerivesFromEditorFontAndLineMetrics() {
        val source = "- first\n- second\n\nTail\n"
        myFixture.configureByText("list-rhythm.md", source)
        val editor = myFixture.editor
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val model = NativeListProjectionPlanner.plan(plan).single()
        val renderer = NativeListInlayRenderer(editor, model)
        val metrics = editor.contentComponent.getFontMetrics(editor.contentComponent.font)
        val expectedRowHeight = max(editor.lineHeight, metrics.height)
        val expectedVerticalPadding = max(0, (editor.lineHeight - metrics.height) / 2)
        val evidence = renderer.visualRowGeometryEvidence(Rectangle(0, 0, 600, 200))

        assertTrue(evidence.contains("renderer_row_height_px=$expectedRowHeight"))
        assertTrue(evidence.contains("vertical_padding_px=$expectedVerticalPadding"))
    }

    fun testTaskCheckboxGeometryUsesCurrentLookAndFeelIconWhenAvailable() {
        val source = "- [ ] pending\n- [x] complete\n\nTail\n"
        myFixture.configureByText("task-checkbox-theme.md", source)
        val editor = myFixture.editor
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val model = NativeListProjectionPlanner.plan(plan).single()
        val renderer = NativeListInlayRenderer(editor, model)
        val bounds = Rectangle(0, 0, 600, 200)
        val checkbox = requireNotNull(renderer.firstTaskCheckboxBounds(bounds))
        val lafIcon = UIManager.getIcon("CheckBox.icon")

        val usableLafIcon = lafIcon?.takeIf { it.iconWidth > 0 && it.iconHeight > 0 }
        assertEquals(usableLafIcon != null, renderer.taskCheckboxUsesCurrentLafIcon())
        if (usableLafIcon != null) {
            val expected = max(usableLafIcon.iconWidth, usableLafIcon.iconHeight)
            assertEquals(expected, checkbox.width)
            assertEquals(expected, checkbox.height)
        } else {
            val metrics = editor.contentComponent.getFontMetrics(editor.contentComponent.font)
            assertEquals(max(1, metrics.height), checkbox.width)
            assertEquals(max(1, metrics.height), checkbox.height)
        }
    }

    fun testThematicRuleWidthTracksCurrentEditorGeometryInsteadOfFixedGlyphCount() {
        myFixture.configureByText("thematic-width.md", "---\n\nTail\n")
        val editor = myFixture.editor
        val narrowWidth = editor.lineHeight * 12
        val wideWidth = editor.lineHeight * 36
        val narrow = nativeThematicBreakPlaceholder(editor, narrowWidth)
        val wide = nativeThematicBreakPlaceholder(editor, wideWidth)

        assertTrue(narrow.isNotEmpty())
        assertTrue(narrow.all { it == '─' })
        assertTrue(wide.all { it == '─' })
        assertTrue(
            "thematic rule placeholder must grow with available editor geometry",
            wide.length > narrow.length,
        )
    }

    fun testThematicRuleRebuildsWhenShowingEditorViewportWidthChanges() {
        val source = "---\n\nTail\n"
        myFixture.configureByText("thematic-resize.md", source)
        val editor = myFixture.editor as EditorImpl
        val document = editor.document
        val stampBefore = document.modificationStamp
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)

        val originalParent = editor.component.parent
        val window = JFrame("MarkFlow thematic break resize")
        var controller: NativePresentationController? = null
        try {
            window.contentPane.add(editor.component, BorderLayout.CENTER)
            window.setSize(editor.lineHeight * 16, editor.lineHeight * 12)
            window.isVisible = true
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertTrue(editor.contentComponent.isShowing)

            controller = NativePresentationController(
                editor = editor,
                richPresentationEnabled = { true },
            )
            val thematic = requireNotNull(controller.currentPlan)
                .projections
                .single { it.kind == NativeProjectionKind.THEMATIC_BREAK }
            fun currentFold() = requireNotNull(
                editor.foldingModel.allFoldRegions.singleOrNull { fold ->
                    fold.isValid &&
                        fold.startOffset == thematic.sourceRange.startOffset &&
                        !fold.isExpanded
                }
            )

            val narrow = currentFold().placeholderText
            val initialViewport = editor.scrollingModel.visibleArea.width
            window.setSize(window.width * 2, window.height)
            window.validate()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            val expandedViewport = editor.scrollingModel.visibleArea.width
            val wide = currentFold().placeholderText
            assertTrue(expandedViewport > initialViewport)
            assertTrue(
                "thematic fold placeholder did not converge after viewport expansion",
                wide.length > narrow.length,
            )
            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)
        } finally {
            controller?.dispose()
            window.contentPane.remove(editor.component)
            window.dispose()
            originalParent?.add(editor.component)
            originalParent?.revalidate()
        }
        assertEquals(source, document.text)
        assertEquals(stampBefore, document.modificationStamp)
    }
}
