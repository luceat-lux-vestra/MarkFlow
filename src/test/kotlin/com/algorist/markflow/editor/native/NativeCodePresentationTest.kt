package com.algorist.markflow.editor.native

import com.intellij.testFramework.fixtures.BasePlatformTestCase

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
            assertEquals("val first = 1\nval second = 2\n", models.single().code)

            val evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.indentedCodeModels)
            assertEquals(1, evidence.indentedCodeInlays)
            assertTrue(evidence.indentedCodeFolds > 0)
            assertEquals(1, evidence.indentedCodeFullyConcealed)

            val inlay = editor.inlayModel
                .getBlockElementsInRange(0, document.textLength)
                .single { it.renderer is NativeIndentedCodeInlayRenderer }
            val renderer = inlay.renderer as NativeIndentedCodeInlayRenderer
            assertEquals("val first = 1\nval second = 2\n", renderer.displayCode)
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
