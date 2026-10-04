package com.algorist.markflow.editor.native

import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.MouseEvent

class NativeListPresentationTest : BasePlatformTestCase() {
    fun testNestedListModelPreservesMarkersAndDepth() {
        val source = "- parent\n  - child\n    7. grandchild\n- sibling\n\nTail\n"
        myFixture.configureByText("nested-list.md", source)
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(myFixture.editor.document, 0L))

        val model = NativeListProjectionPlanner.plan(plan).single()
        assertEquals(listOf("-", "-", "7.", "-"), model.rows.map(NativeListRow::marker))
        assertEquals(listOf("parent", "child", "grandchild", "sibling"), model.rows.map(NativeListRow::text))
        assertEquals(listOf(0, 1, 2, 0), model.rows.map(NativeListRow::depth))
        assertTrue(nativeListIndentPixels(2) > nativeListIndentPixels(1))
        assertTrue(nativeListIndentPixels(1) > nativeListIndentPixels(0))
    }

    fun testInactiveNestedListUsesNativeHierarchyWithoutChangingSource() {
        val source = "- parent\n  - child\n- sibling\n\nTail\n"
        myFixture.configureByText("list-render.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp

        val controller = NativePresentationController(editor = editor, richPresentationEnabled = { true })
        try {
            val evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.listModels)
            assertEquals(3, evidence.listRows)
            assertEquals(1, evidence.listInlays)
            assertTrue(evidence.listFolds > 0)
            assertEquals(1, evidence.listFullyConcealed)
            assertEquals(1, evidence.listMaxDepth)
            assertEquals(listOf(0, 1, 0), evidence.listDepths)
            assertEquals(listOf("-", "-", "-"), evidence.listMarkers)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testActiveListRevealsExactSourceAndRestoresInactivePresentation() {
        val source = "3) first\n4) second\n\nTail\n"
        myFixture.configureByText("list-reveal.md", source)
        val editor = myFixture.editor
        val tail = source.indexOf("Tail") + 1
        editor.caretModel.moveToOffset(tail)
        val stampBefore = editor.document.modificationStamp
        val controller = NativePresentationController(editor = editor, richPresentationEnabled = { true })

        try {
            assertEquals(1, controller.evidenceSnapshot().listInlays)
            assertEquals(listOf("3)", "4)"), controller.evidenceSnapshot().listMarkers)

            editor.caretModel.moveToOffset(source.indexOf("first") + 1)
            assertEquals(0, controller.evidenceSnapshot().listInlays)
            assertEquals(0, controller.evidenceSnapshot().listFolds)
            val listRange = NativeListProjectionPlanner.sourceRanges(requireNotNull(controller.currentPlan)).single()
            assertTrue(editor.foldingModel.allFoldRegions.none {
                it.isValid && !it.isExpanded &&
                    it.startOffset < listRange.endOffset && it.endOffset > listRange.startOffset
            })

            editor.caretModel.moveToOffset(tail)
            assertEquals(1, controller.evidenceSnapshot().listInlays)
            assertEquals(1, controller.evidenceSnapshot().listFullyConcealed)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testTaskMultilineAndInlineRichListsFailClosedToExactSource() {
        val sources = listOf(
            "- [ ] task\n\nTail\n",
            "- first line\n  continuation\n\nTail\n",
            "- item with *emphasis*\n\nTail\n",
        )
        sources.forEachIndexed { index, source ->
            myFixture.configureByText("list-fallback-$index.md", source)
            val editor = myFixture.editor
            editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
            val stampBefore = editor.document.modificationStamp
            val controller = NativePresentationController(editor = editor, richPresentationEnabled = { true })
            try {
                val evidence = controller.evidenceSnapshot()
                assertEquals(0, evidence.listModels)
                assertEquals(0, evidence.listInlays)
                assertEquals(0, evidence.listFolds)
                assertEquals(0, evidence.inlineOwnedHighlighters)
                assertEquals(0, evidence.inlineOwnedFolds)
                assertEquals(source, editor.document.text)
                assertEquals(stampBefore, editor.document.modificationStamp)
            } finally {
                controller.dispose()
            }
        }
    }

    fun testListInlayClickRevealsSourceAndMovesCaretToFirstItemContent() {
        val source = "- first\n- second\n\nTail\n"
        myFixture.configureByText("list-click.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val model = NativeListProjectionPlanner.plan(plan).single()
        val controller = NativeListPresentationController(editor)

        try {
            controller.applyPlan(plan, true)
            val inlay = editor.inlayModel
                .getBlockElementsInRange(0, editor.document.textLength)
                .single { it.renderer is NativeListInlayRenderer }
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
            val offset = inlay.offset.coerceIn(0, editor.document.textLength)
            val event = EditorMouseEvent(
                editor,
                click,
                EditorMouseEventArea.EDITING_AREA,
                offset,
                editor.offsetToLogicalPosition(offset),
                editor.offsetToVisualPosition(offset),
                false,
                null,
                inlay,
                null,
            )
            assertTrue(controller.handleMouseReveal(event))
            assertTrue(click.isConsumed)
            assertEquals(model.rows.first().contentRange.startOffset, editor.caretModel.primaryCaret.offset)
            assertEquals(0, controller.evidenceSnapshot().ownedInlays)
            assertEquals(0, controller.evidenceSnapshot().ownedFolds)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testListPresentationCoexistsWithCollapsedForeignMarkerFold() {
        val source = "- first\n- second\n\nTail\n"
        myFixture.configureByText("list-foreign-fold.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val firstMarker = plan.projections
            .first { it.kind == NativeProjectionKind.LIST_ITEM }
            .syntaxRanges.single()
        var foreign: com.intellij.openapi.editor.FoldRegion? = null
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            foreign = editor.foldingModel.addFoldRegion(firstMarker.startOffset, firstMarker.endOffset, "platform list marker")
            requireNotNull(foreign).isExpanded = false
        }

        val controller = NativePresentationController(editor = editor, richPresentationEnabled = { true })
        try {
            val evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.listInlays)
            assertEquals(1, evidence.listFullyConcealed)
            assertTrue(requireNotNull(foreign).isValid)
            assertFalse(requireNotNull(foreign).isExpanded)
        } finally {
            controller.dispose()
            assertTrue(requireNotNull(foreign).isValid)
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                requireNotNull(foreign).takeIf { it.isValid }?.let(editor.foldingModel::removeFoldRegion)
            }
        }
    }
}
