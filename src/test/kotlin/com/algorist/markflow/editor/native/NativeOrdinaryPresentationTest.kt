package com.algorist.markflow.editor.native

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class NativeOrdinaryPresentationTest : BasePlatformTestCase() {
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
            assertTrue("expected MarkFlow-owned ordinary syntax folds", evidence.ownedFolds >= 8)
            assertTrue(evidence.collapsedFolds > 0)

            val placeholders = editor.foldingModel.allFoldRegions
                .filter { it.isValid && !it.isExpanded }
                .map { it.placeholderText }
            assertTrue("unordered list marker was not projected", placeholders.contains("•"))
            assertTrue("ordered list marker was not projected", placeholders.contains("3."))
            assertTrue("blockquote marker was not projected", placeholders.contains("│"))
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
        val source = "# Heading\n\nA [link](https://example.com) and **strong** text.\n\n- item\n"
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
            assertEquals(1L, evidence.sourceFallbacks)
            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }
}
