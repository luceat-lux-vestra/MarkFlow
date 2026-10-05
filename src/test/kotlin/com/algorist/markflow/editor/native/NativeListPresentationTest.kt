package com.algorist.markflow.editor.native

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Rectangle
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

    fun testListIndentResultRefreshesNativeHierarchyWithUndoRedo() {
        val source = "- parent\n- child\n- sibling\n\nTail\n"
        val indented = "- parent\n  - child\n- sibling\n\nTail\n"
        myFixture.configureByText("list-indent-action.md", source)
        val editor = myFixture.editor
        val document = editor.document
        val fileEditor = requireNotNull(TextEditorProvider.getInstance().getTextEditor(editor))
        editor.caretModel.moveToOffset(source.indexOf("child") + 2)

        val controller = NativePresentationController(editor = editor, richPresentationEnabled = { true })
        try {
            assertEquals(listOf(0, 0, 0), controller.evidenceSnapshot().listDepths)
            assertEquals(listOf("-", "-", "-"), controller.evidenceSnapshot().listMarkers)
            val stampBefore = document.modificationStamp

            // Unit fixtures do not install the bundled Markdown editorActionHandler chain. The
            // production Starter/Driver test owns the actual EditorIndentSelection/UnindentSelection
            // proof. Here we isolate MarkFlow's responsibility after that maintained handler result:
            // one source-local indentation edit must rebuild hierarchy state without reconstruction.
            val childMarkerStart = source.indexOf("- child")
            WriteCommandAction.writeCommandAction(project)
                .withName("IntelliJ Markdown list indent result")
                .run<RuntimeException> {
                    document.insertString(childMarkerStart, "  ")
                }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            assertEquals(indented, document.text)
            assertTrue(document.modificationStamp != stampBefore)
            controller.refreshNow()
            assertEquals(listOf(0, 1, 0), controller.evidenceSnapshot().listDepths)
            assertEquals(1, controller.evidenceSnapshot().listMaxDepth)
            assertEquals(listOf("-", "-", "-"), controller.evidenceSnapshot().listMarkers)

            val undo = UndoManager.getInstance(project)
            assertTrue(undo.isUndoAvailable(fileEditor))
            undo.undo(fileEditor)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals(source, document.text)
            // BasePlatformTestCase proves the source Undo contract here, but it does not own the
            // real production event-loop timing. Recompute explicitly so this unit case stays
            // scoped to MarkFlow's post-Undo parser/projection responsibility; Starter/Driver
            // below proves automatic production-path reprojection after the actual user action.
            controller.refreshNow()
            assertEquals(listOf(0, 0, 0), controller.evidenceSnapshot().listDepths)

            assertTrue(undo.isRedoAvailable(fileEditor))
            undo.redo(fileEditor)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals(indented, document.text)
            controller.refreshNow()
            assertEquals(listOf(0, 1, 0), controller.evidenceSnapshot().listDepths)

            val indentedMarkerStart = document.text.indexOf("  - child")
            WriteCommandAction.writeCommandAction(project)
                .withName("IntelliJ Markdown list outdent result")
                .run<RuntimeException> {
                    document.deleteString(indentedMarkerStart, indentedMarkerStart + 2)
                }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            assertEquals(source, document.text)
            controller.refreshNow()
            assertEquals(listOf(0, 0, 0), controller.evidenceSnapshot().listDepths)
            assertEquals(0, controller.evidenceSnapshot().listMaxDepth)
            assertEquals(listOf("-", "-", "-"), controller.evidenceSnapshot().listMarkers)

            assertTrue(undo.isUndoAvailable(fileEditor))
            undo.undo(fileEditor)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals(indented, document.text)
            controller.refreshNow()
            assertEquals(listOf(0, 1, 0), controller.evidenceSnapshot().listDepths)

            assertTrue(undo.isRedoAvailable(fileEditor))
            undo.redo(fileEditor)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals(source, document.text)
            controller.refreshNow()
            assertEquals(listOf(0, 0, 0), controller.evidenceSnapshot().listDepths)
        } finally {
            controller.dispose()
        }
    }

    fun testIndentedUnsupportedRichListRemainsExactSourceFallback() {
        val source = "- parent\n- child with *emphasis*\n- sibling\n\nTail\n"
        val indented = "- parent\n  - child with *emphasis*\n- sibling\n\nTail\n"
        myFixture.configureByText("list-indent-rich-fallback.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("child") + 2)
        val controller = NativePresentationController(editor = editor, richPresentationEnabled = { true })

        try {
            assertEquals(0, controller.evidenceSnapshot().listModels)
            assertEquals(0, controller.evidenceSnapshot().listInlays)

            val childMarkerStart = source.indexOf("- child")
            WriteCommandAction.writeCommandAction(project)
                .withName("IntelliJ Markdown rich-list indent result")
                .run<RuntimeException> {
                    editor.document.insertString(childMarkerStart, "  ")
                }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            assertEquals(indented, editor.document.text)
            assertEquals(0, controller.evidenceSnapshot().listModels)
            assertEquals(0, controller.evidenceSnapshot().listInlays)
            assertEquals(0, controller.evidenceSnapshot().listFolds)
        } finally {
            controller.dispose()
        }
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

    fun testUnsupportedMixedNestedMultilineAndInlineRichListsFailClosedToExactSource() {
        val sources = listOf(
            "- [ ] task\n- ordinary\n\nTail\n",
            "1. [ ] ordered task\n\nTail\n",
            "- [ ] parent\n  - [ ] nested\n\nTail\n",
            "- first line\n  continuation\n\nTail\n",
            "- item with *emphasis*\n\nTail\n",
            "- item with ~~strikethrough~~\n\nTail\n",
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

    fun testTaskListModelPreservesMarkerStateAndCheckboxGeometry() {
        val source = "- [ ] first task\n- [X] second task\n\nTail\n"
        myFixture.configureByText("task-model.md", source)
        val editor = myFixture.editor
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val model = NativeListProjectionPlanner.plan(plan).single()

        assertEquals(2, model.rows.size)
        assertTrue(model.rows.all { it.task != null })
        assertEquals(listOf(false, true), model.rows.map { it.task?.checked })
        assertEquals(listOf(' ', 'X'), model.rows.map { it.task?.sourceState })
        assertEquals(
            listOf(" ", "X"),
            model.rows.map { row ->
                val range = requireNotNull(row.task).stateRange
                source.substring(range.startOffset, range.endOffset)
            },
        )
        assertEquals(listOf("first task", "second task"), model.rows.map(NativeListRow::text))

        val renderer = NativeListInlayRenderer(editor, model)
        val bounds = Rectangle(10, 20, 400, 100)
        val center = requireNotNull(renderer.firstTaskCheckboxCenter(bounds))
        assertEquals(0, renderer.taskRowAt(center, bounds))
    }

    fun testInactiveTaskListUsesNativeCheckboxPresentationWithoutChangingSource() {
        val source = "- [ ] first task\n- [x] completed task\n\nTail\n"
        myFixture.configureByText("task-render.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp

        val controller = NativePresentationController(editor = editor, richPresentationEnabled = { true })
        try {
            val evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.listModels)
            assertEquals(2, evidence.listRows)
            assertEquals(1, evidence.listInlays)
            assertTrue(evidence.listFolds > 0)
            assertEquals(1, evidence.listFullyConcealed)
            assertEquals(2, evidence.taskRows)
            assertEquals(1, evidence.checkedTasks)
            assertEquals(0L, evidence.taskToggles)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testTaskListAccessibilityFallbackKeepsExactSource() {
        val source = "- [ ] first task\n- [x] completed task\n\nTail\n"
        myFixture.configureByText("task-accessible.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp
        val controller = NativePresentationController(editor = editor, richPresentationEnabled = { false })

        try {
            val evidence = controller.evidenceSnapshot()
            assertEquals(2, evidence.taskRows)
            assertEquals(1, evidence.checkedTasks)
            assertEquals(0, evidence.listInlays)
            assertEquals(0, evidence.listFolds)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testReadOnlyTaskToggleFailsClosedWithoutMutation() {
        val source = "- [ ] first task\n- [x] completed task\n\nTail\n"
        myFixture.configureByText("task-read-only.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val model = NativeListProjectionPlanner.plan(plan).single()
        val controller = NativeListPresentationController(editor)

        try {
            controller.applyPlan(plan, true)
            editor.document.setReadOnly(true)
            try {
                assertFalse(controller.toggleTask(model.sourceRange, 0))
                assertEquals(source, editor.document.text)
                assertEquals(0L, controller.evidenceSnapshot().taskToggles)
            } finally {
                editor.document.setReadOnly(false)
            }
        } finally {
            controller.dispose()
        }
    }

    fun testStaleTaskToggleFailsClosedWithoutMutation() {
        val source = "- [ ] first task\n- [x] completed task\n\nTail\n"
        myFixture.configureByText("task-stale.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val model = NativeListProjectionPlanner.plan(plan).single()
        val controller = NativeListPresentationController(editor)

        try {
            controller.applyPlan(plan, true)
            WriteCommandAction.runWriteCommandAction(project) {
                editor.document.insertString(editor.document.textLength, "changed\n")
            }
            val changed = editor.document.text
            assertFalse(controller.toggleTask(model.sourceRange, 0))
            assertEquals(changed, editor.document.text)
            assertEquals(0L, controller.evidenceSnapshot().taskToggles)
        } finally {
            controller.dispose()
        }
    }

    fun testTaskToggleChangesOnlyOneStateCharacter() {
        val source = "- [ ] first task\n- [X] second task\n\nTail\n"
        myFixture.configureByText("task-toggle.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val model = NativeListProjectionPlanner.plan(plan).single()
        val controller = NativeListPresentationController(editor)

        try {
            controller.applyPlan(plan, true)
            val firstState = requireNotNull(model.rows[0].task).stateRange
            val expected = source.replaceRange(firstState.startOffset, firstState.endOffset, "x")
            assertTrue(controller.toggleTask(model.sourceRange, 0))
            assertEquals(expected, editor.document.text)
            assertEquals(source.length, editor.document.textLength)
            assertEquals(1L, controller.evidenceSnapshot().taskToggles)
        } finally {
            controller.dispose()
        }
    }

    fun testCheckedUppercaseTaskToggleClearsOnlyStateCharacter() {
        val source = "- [X] first task\n- [x] second task\n\nTail\n"
        myFixture.configureByText("task-toggle-checked.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val model = NativeListProjectionPlanner.plan(plan).single()
        val controller = NativeListPresentationController(editor)

        try {
            controller.applyPlan(plan, true)
            val firstState = requireNotNull(model.rows[0].task).stateRange
            val expected = source.replaceRange(firstState.startOffset, firstState.endOffset, " ")
            assertTrue(controller.toggleTask(model.sourceRange, 0))
            assertEquals(expected, editor.document.text)
            assertEquals(source.length, editor.document.textLength)
            assertEquals(1L, controller.evidenceSnapshot().taskToggles)
        } finally {
            controller.dispose()
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

    fun testListPresentationNestsInsideExpandedForeignWholeListFold() {
        val source = "- [ ] first task\n- [x] completed task\n\nTail\n"
        myFixture.configureByText("task-expanded-foreign-list-fold.md", source)
        val editor = myFixture.editor
        val tail = source.indexOf("Tail") + 1
        editor.caretModel.moveToOffset(tail)
        val stampBefore = editor.document.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val listRange = NativeListProjectionPlanner.sourceRanges(plan).single()
        var foreign: com.intellij.openapi.editor.FoldRegion? = null
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            foreign = editor.foldingModel.addFoldRegion(
                listRange.startOffset,
                listRange.endOffset,
                "platform whole-list fold",
            )
            requireNotNull(foreign).isExpanded = true
        }

        val controller = NativePresentationController(editor = editor, richPresentationEnabled = { true })
        try {
            val inactive = controller.evidenceSnapshot()
            assertEquals(1, inactive.listInlays)
            assertTrue(inactive.listFolds >= 2)
            assertEquals(1, inactive.listFullyConcealed)
            assertEquals(2, inactive.taskRows)
            assertEquals(1, inactive.checkedTasks)
            assertTrue(requireNotNull(foreign).isValid)
            assertTrue(requireNotNull(foreign).isExpanded)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)

            editor.caretModel.moveToOffset(source.indexOf("first task") + 1)
            assertEquals(0, controller.evidenceSnapshot().listInlays)
            assertEquals(0, controller.evidenceSnapshot().listFolds)
            assertTrue(requireNotNull(foreign).isValid)
            assertTrue(requireNotNull(foreign).isExpanded)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
            assertTrue(requireNotNull(foreign).isValid)
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                requireNotNull(foreign).takeIf { it.isValid }?.let(editor.foldingModel::removeFoldRegion)
            }
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
