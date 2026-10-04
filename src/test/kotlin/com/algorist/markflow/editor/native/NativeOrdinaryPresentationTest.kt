package com.algorist.markflow.editor.native

import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.MouseEvent

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
            assertTrue("expected inline-owned ordinary highlighters", evidence.inlineOwnedHighlighters >= 3)
            assertTrue("expected inline-owned ordinary syntax folds", evidence.inlineOwnedFolds > 0)
            assertTrue("expected remaining block-owned ordinary highlighters", evidence.blockOwnedHighlighters >= 1)
            assertTrue("expected block-owned ordinary folds", evidence.blockOwnedFolds > 0)
            assertEquals(1, evidence.blockQuoteModels)
            assertEquals(1, evidence.blockQuoteInlays)
            assertEquals(1, evidence.blockQuoteFullyConcealed)
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
            assertTrue("unordered list marker was not projected", placeholders.contains("•"))
            assertTrue("ordered list marker was not projected", placeholders.contains("3."))
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
            assertEquals(0, evidence.inlineOwnedHighlighters)
            assertEquals(0, evidence.inlineOwnedFolds)
            assertEquals(0, evidence.blockOwnedHighlighters)
            assertEquals(0, evidence.blockOwnedFolds)
            assertEquals(1, evidence.headingModels)
            assertEquals(0, evidence.headingInlays)
            assertEquals(0, evidence.headingFolds)
            assertEquals(0, evidence.headingFullyConcealed)
            assertEquals(1L, evidence.sourceFallbacks)
            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }
    fun testInlineOnlyFixtureUsesOnlyInlinePresentationOwner() {
        val source = "A [link](https://example.com) with *emphasis*, **strong**, and `code`.\n\nTail\n"
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
            assertTrue(evidence.inlineOwnedHighlighters >= 4)
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
