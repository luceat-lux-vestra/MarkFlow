package com.algorist.markflow.editor.native

import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.MouseEvent

class NativeCodePresentationTest : BasePlatformTestCase() {
    fun testInlineCodeUsesDistinctNativeSemanticAndRevealsExactSource() {
        val source = "Before `inline code` after\n"
        myFixture.configureByText("inline-code.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(0)
        val document = editor.document
        val stampBefore = document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val projection = requireNotNull(controller.currentPlan)
                .projections
                .single { it.kind == NativeProjectionKind.INLINE_CODE }
            val highlighter = editor.markupModel.allHighlighters.single { candidate ->
                candidate.isValid &&
                    candidate.startOffset == projection.sourceRange.startOffset &&
                    candidate.endOffset == projection.sourceRange.endOffset
            }
            val attributes = requireNotNull(highlighter.getTextAttributes(editor.colorsScheme))
            assertNotNull("inline code must have an explicit code background semantic", attributes.backgroundColor)
            assertTrue("inline code must retain parser-owned delimiter ranges", projection.syntaxRanges.isNotEmpty())
            projection.syntaxRanges.forEach { range ->
                val fold = editor.foldingModel.getFoldRegion(range.startOffset, range.endOffset)
                assertNotNull("inline-code delimiter was not concealed: $range", fold)
                assertFalse(fold!!.isExpanded)
            }
            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)

            editor.caretModel.moveToOffset(projection.sourceRange.startOffset + 1)

            projection.syntaxRanges.forEach { range ->
                val fold = editor.foldingModel.getFoldRegion(range.startOffset, range.endOffset)
                assertNotNull("inline-code delimiter fold disappeared instead of revealing source", fold)
                assertTrue("active inline code must reveal exact delimiters", fold!!.isExpanded)
            }
            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testIndentedCodeUsesNativeBlockWithoutRewritingIndentation() {
        val source = "    val first = 1\n    val second = 2\n\nTail\n"
        myFixture.configureByText("indented-code.md", source)
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
            val codeProjection = plan.projections.single { it.kind == NativeProjectionKind.CODE_BLOCK }
            val models = NativeIndentedCodeProjectionPlanner.plan(plan)
            assertEquals(1, models.size)
            assertEquals("val first = 1\nval second = 2", models.single().code.trimEnd())

            val evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.indentedCodeModels)
            assertEquals(1, evidence.indentedCodeInlays)
            assertTrue(evidence.indentedCodeFolds > 0)
            assertEquals(1, evidence.indentedCodeFullyConcealed)

            val inlay = editor.inlayModel
                .getBlockElementsInRange(0, document.textLength)
                .single { it.renderer is NativeIndentedCodeInlayRenderer }
            val renderer = inlay.renderer as NativeIndentedCodeInlayRenderer
            assertEquals("val first = 1\nval second = 2", renderer.displayCode.trimEnd())
            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)

            editor.caretModel.moveToOffset(codeProjection.sourceRange.startOffset + 4)

            val revealed = controller.evidenceSnapshot()
            assertEquals(1, revealed.indentedCodeModels)
            assertEquals(0, revealed.indentedCodeInlays)
            assertEquals(0, revealed.indentedCodeFolds)
            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testBoundedOutIndentedCodeStaysExactSourceWithoutGenericOwner() {
        val source = buildString {
            repeat(201) { index -> append("    line-").append(index).append('\n') }
            append("\nTail\n")
        }
        myFixture.configureByText("indented-code-bounded.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val plan = requireNotNull(controller.currentPlan)
            val sourceRanges = NativeIndentedCodeProjectionPlanner.sourceRanges(plan)
            assertTrue("parser did not produce the bounded CODE_BLOCK fixture", sourceRanges.isNotEmpty())
            assertTrue(
                "oversized indented code must not be promoted to rich presentation",
                NativeIndentedCodeProjectionPlanner.plan(plan).isEmpty(),
            )
            val evidence = controller.evidenceSnapshot()
            assertEquals(0, evidence.indentedCodeModels)
            assertEquals(0, evidence.indentedCodeInlays)
            assertEquals(0, evidence.indentedCodeFolds)
            assertEquals(
                "rejected parser-proven CODE_BLOCK must not fall through to generic block highlighting",
                0,
                evidence.blockOwnedHighlighters,
            )
            sourceRanges.forEach { range ->
                assertTrue(
                    "rejected CODE_BLOCK retained collapsed MarkFlow presentation",
                    editor.foldingModel.allFoldRegions.none { fold ->
                        fold.isValid &&
                            !fold.isExpanded &&
                            fold.startOffset < range.endOffset &&
                            fold.endOffset > range.startOffset
                    },
                )
            }
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testCollapsedForeignIndentedCodeFoldFailsClosedWithoutChangingOwnership() {
        val source = "    val value = 1\n\nTail\n"
        myFixture.configureByText("indented-code-foreign-fold.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp
        var foreignFold: com.intellij.openapi.editor.FoldRegion? = null

        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            foreignFold = editor.foldingModel.addFoldRegion(0, 4, "foreign")
            requireNotNull(foreignFold).isExpanded = false
        }
        val foreign = requireNotNull(foreignFold)
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val controller = NativeIndentedCodePresentationController(editor)

        try {
            controller.applyPlan(plan, richPresentationEnabled = true)
            val evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.models)
            assertEquals(0, evidence.ownedInlays)
            assertEquals(0, evidence.ownedFolds)
            assertTrue(foreign.isValid)
            assertFalse(foreign.isExpanded)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }

        assertTrue(foreign.isValid)
        assertFalse(foreign.isExpanded)
    }

    fun testIndentedCodeInlayClickRevealsExactSourceAndMovesCaretToContent() {
        val source = "    val value = 1\n\nTail\n"
        myFixture.configureByText("indented-code-click.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val model = NativeIndentedCodeProjectionPlanner.plan(plan).single()
        val controller = NativeIndentedCodePresentationController(editor)

        try {
            controller.applyPlan(plan, richPresentationEnabled = true)
            val inlay = editor.inlayModel
                .getBlockElementsInRange(0, editor.document.textLength)
                .single { it.renderer is NativeIndentedCodeInlayRenderer }
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
            assertEquals(model.contentOffset, editor.caretModel.primaryCaret.offset)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testIndentedCodeAccessibilityFallbackLeavesExactSourceVisible() {
        val source = "    val value = 1\n\nTail\n"
        myFixture.configureByText("indented-code-accessible.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { false },
        )
        try {
            val evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.indentedCodeModels)
            assertEquals(0, evidence.indentedCodeInlays)
            assertEquals(0, evidence.indentedCodeFolds)
            assertEquals(0, evidence.blockOwnedHighlighters)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }
}
