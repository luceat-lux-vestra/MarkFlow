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
        val editor = myFixture.editor
        assertTrue(nativeListIndentPixels(editor, 2) > nativeListIndentPixels(editor, 1))
        assertTrue(nativeListIndentPixels(editor, 1) > nativeListIndentPixels(editor, 0))
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

    fun testIndentedEmphasisListKeepsRichModelAcrossHierarchyReprojection() {
        val source = "- parent\n- child with *emphasis*\n- sibling\n\nTail\n"
        val indented = "- parent\n  - child with *emphasis*\n- sibling\n\nTail\n"
        myFixture.configureByText("list-indent-rich.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("child") + 2)
        val controller = NativePresentationController(editor = editor, richPresentationEnabled = { true })

        try {
            assertEquals(1, controller.evidenceSnapshot().listModels)
            assertEquals(listOf(0, 0, 0), controller.evidenceSnapshot().listDepths)
            assertEquals(0, controller.evidenceSnapshot().listInlays)

            val childMarkerStart = source.indexOf("- child")
            WriteCommandAction.writeCommandAction(project)
                .withName("IntelliJ Markdown rich-list indent result")
                .run<RuntimeException> {
                    editor.document.insertString(childMarkerStart, "  ")
                }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            assertEquals(indented, editor.document.text)
            controller.refreshNow()
            assertEquals(1, controller.evidenceSnapshot().listModels)
            assertEquals(listOf(0, 1, 0), controller.evidenceSnapshot().listDepths)
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

    fun testUnsupportedMixedTaskAndUnsupportedRichListsFailClosedToExactSource() {
        val sources = listOf(
            "1. [ ] ordered task\n\nTail\n",
            "- [ ] first task\n  continuation with *emphasis*\n\nTail\n",
            "- item\n  continuation with *emphasis*\n\nTail\n",
            "- item with ~~strikethrough~~\n\nTail\n",
            "- item with `code`\n\nTail\n",
            "- item with [link](https://example.com)\n\nTail\n",
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

    fun testEmphasisAndStrongRowsUseStyledDisplayRunsWithoutChangingSource() {
        val source =
            "- plain *italic* and **bold**\n" +
                "  - nested _emphasis_\n" +
                "- sibling\n\nTail\n"
        myFixture.configureByText("list-inline-rich.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val model = NativeListProjectionPlanner.plan(plan).single()

        assertEquals(listOf(0, 1, 0), model.rows.map(NativeListRow::depth))
        val first = model.rows[0]
        assertEquals("plain *italic* and **bold**", first.text)
        assertEquals(
            first.text,
            source.substring(first.contentRange.startOffset, first.contentRange.endOffset),
        )
        assertEquals("plain italic and bold", first.displayText)
        assertTrue(first.displayRuns.any { it.text == "italic" && it.style == NativeListInlineStyle.EMPHASIS })
        assertTrue(first.displayRuns.any { it.text == "bold" && it.style == NativeListInlineStyle.STRONG })

        val nested = model.rows[1]
        assertEquals("nested _emphasis_", nested.text)
        assertEquals("nested emphasis", nested.displayText)
        assertTrue(nested.displayRuns.any {
            it.text == "emphasis" && it.style == NativeListInlineStyle.EMPHASIS
        })

        val controller = NativePresentationController(editor = editor, richPresentationEnabled = { true })
        try {
            val evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.listModels)
            assertEquals(3, evidence.listRows)
            assertEquals(1, evidence.listInlays)
            assertEquals(1, evidence.listFullyConcealed)

            val renderer = editor.inlayModel
                .getBlockElementsInRange(0, editor.document.textLength)
                .mapNotNull { it.renderer as? NativeListInlayRenderer }
                .single()
            assertEquals(first.displayText, renderer.displayText(0))
            assertEquals(first.displayRuns.map { it.style.fontMask }, renderer.displayFontMasks(0))
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testMixedAndNestedUnorderedTaskRowsShareOneListModelAndToggleLocally() {
        val source =
            "- ordinary parent\n" +
                "  - [ ] nested task\n" +
                "- [x] top-level task\n" +
                "- ordinary sibling\n\n" +
                "Tail\n"
        myFixture.configureByText("task-mixed-nested.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val model = NativeListProjectionPlanner.plan(plan).single()

        assertEquals(4, model.rows.size)
        assertEquals(listOf(0, 1, 0, 0), model.rows.map(NativeListRow::depth))
        assertEquals(listOf(false, true, true, false), model.rows.map { it.task != null })
        assertEquals(listOf(null, false, true, null), model.rows.map { it.task?.checked })

        val renderer = NativeListInlayRenderer(editor, model)
        val bounds = Rectangle(10, 20, 500, 140)
        val firstTaskCenter = requireNotNull(renderer.firstTaskCheckboxCenter(bounds))
        assertEquals(1, renderer.taskRowAt(firstTaskCenter, bounds))
        assertEquals(source, editor.document.text)
        assertEquals(stampBefore, editor.document.modificationStamp)

        val controller = NativeListPresentationController(editor)
        try {
            controller.applyPlan(plan, true)
            val nestedState = requireNotNull(model.rows[1].task).stateRange
            val expected = source.replaceRange(nestedState.startOffset, nestedState.endOffset, "x")

            assertTrue(controller.toggleTask(model.sourceRange, 1))
            assertEquals(expected, editor.document.text)
            assertEquals(source.length, editor.document.textLength)
            assertEquals(1L, controller.evidenceSnapshot().taskToggles)
        } finally {
            controller.dispose()
        }
    }

    fun testPlainTextTaskContinuationUsesCheckboxContentColumnAndPreservesExactSource() {
        val source =
            "- [ ] task with continuation\n" +
                "  continuation text\n\n" +
                "Tail\n"
        myFixture.configureByText("task-continuation.md", source)
        val editor = myFixture.editor
        val tailOffset = source.indexOf("Tail") + 1
        editor.caretModel.moveToOffset(tailOffset)
        val stampBefore = editor.document.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val model = NativeListProjectionPlanner.plan(plan).single()

        assertEquals(1, model.rows.size)
        val row = model.rows.single()
        assertNotNull(row.task)
        assertEquals(1, row.continuations.size)
        val continuation = row.continuations.single()
        assertEquals("continuation text", continuation.text)
        assertEquals(
            continuation.text,
            source.substring(continuation.contentRange.startOffset, continuation.contentRange.endOffset),
        )

        val renderer = NativeListInlayRenderer(editor, model)
        val geometry = renderer.visualRowGeometryEvidence(Rectangle(10, 20, 600, 160))
        assertTrue(geometry.contains("row=0\tdepth=0\tmarker=-"))
        assertTrue(geometry.contains("\tcontinuations=1\tvisual_lines=2\ttask=true\t"))
        assertEquals(source, editor.document.text)
        assertEquals(stampBefore, editor.document.modificationStamp)

        val presentation = NativePresentationController(editor = editor, richPresentationEnabled = { true })
        try {
            assertEquals(1, presentation.evidenceSnapshot().listInlays)
            editor.caretModel.moveToOffset(continuation.contentRange.startOffset + 1)
            assertEquals(0, presentation.evidenceSnapshot().listInlays)
            assertEquals(0, presentation.evidenceSnapshot().listFolds)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)

            editor.caretModel.moveToOffset(tailOffset)
            assertEquals(1, presentation.evidenceSnapshot().listInlays)
            assertEquals(1, presentation.evidenceSnapshot().listFullyConcealed)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            presentation.dispose()
        }

        val taskController = NativeListPresentationController(editor)
        try {
            taskController.applyPlan(plan, true)
            val stateRange = requireNotNull(row.task).stateRange
            val expected = source.replaceRange(stateRange.startOffset, stateRange.endOffset, "x")
            assertTrue(taskController.toggleTask(model.sourceRange, 0))
            assertEquals(expected, editor.document.text)
            assertEquals(source.length, editor.document.textLength)
            assertEquals(1L, taskController.evidenceSnapshot().taskToggles)
        } finally {
            taskController.dispose()
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

    fun testTaskListSupportsParserOwnedStrongInlineRunsAndToggleLocality() {
        val source =
            "- [x] Completed task\n" +
                "- [ ] Pending task\n" +
                "- [ ] Another pending task with **formatting**\n\n" +
                "Tail\n"
        myFixture.configureByText("task-inline-strong.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val model = NativeListProjectionPlanner.plan(plan).single()

        assertEquals(3, model.rows.size)
        assertTrue(model.rows.all { it.task != null })
        assertEquals(
            listOf("Completed task", "Pending task", "Another pending task with formatting"),
            model.rows.map(NativeListRow::displayText),
        )
        assertTrue(
            model.rows[2].displayRuns.any {
                it.text == "formatting" && it.style == NativeListInlineStyle.STRONG
            }
        )
        assertEquals(source, editor.document.text)

        val controller = NativeListPresentationController(editor)
        try {
            controller.applyPlan(plan, true)
            val stateRange = requireNotNull(model.rows[2].task).stateRange
            val expected = source.replaceRange(stateRange.startOffset, stateRange.endOffset, "x")

            assertTrue(controller.toggleTask(model.sourceRange, 2))
            assertEquals(expected, editor.document.text)
            assertEquals(source.length, editor.document.textLength)
            assertEquals(1L, controller.evidenceSnapshot().taskToggles)
        } finally {
            controller.dispose()
        }
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

    fun testListPresentationNestsInsideExpandedForeignNestedListFold() {
        val source = "- parent\n  - child\n    - deeper\n- sibling\n\nTail\n"
        myFixture.configureByText("list-expanded-foreign-nested-fold.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val topLevelRange = NativeListProjectionPlanner.sourceRanges(plan).single()
        val nestedRange = plan.projections
            .asSequence()
            .filter { it.kind == NativeProjectionKind.UNORDERED_LIST }
            .map(NativeProjection::sourceRange)
            .filter { it.startOffset > topLevelRange.startOffset && it.endOffset < topLevelRange.endOffset }
            .maxByOrNull { it.endOffset - it.startOffset }
        requireNotNull(nestedRange)

        var foreign: com.intellij.openapi.editor.FoldRegion? = null
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            foreign = editor.foldingModel.addFoldRegion(
                nestedRange.startOffset,
                nestedRange.endOffset,
                "platform nested-list fold",
            )
            requireNotNull(foreign).isExpanded = true
        }

        val controller = NativePresentationController(editor = editor, richPresentationEnabled = { true })
        try {
            val evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.listModels)
            assertEquals(4, evidence.listRows)
            assertEquals(1, evidence.listInlays)
            assertEquals(1, evidence.listFullyConcealed)
            assertEquals(2, evidence.listMaxDepth)
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


    fun testPlainTextContinuationBelongsToParentRowAndAlignsAsAdditionalVisualLine() {
        val source =
            "- first item\n" +
                "- item with continuation\n" +
                "  on the next indented line.\n" +
                "  - nested child\n" +
                "    - deeper child\n" +
                "- sibling\n\nTail\n"
        myFixture.configureByText("list-continuation.md", source)
        val editor = myFixture.editor
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val model = NativeListProjectionPlanner.plan(plan).single()

        assertEquals(listOf(0, 0, 1, 2, 0), model.rows.map(NativeListRow::depth))
        assertEquals(listOf(0, 1, 0, 0, 0), model.rows.map { it.continuations.size })
        val continuation = model.rows[1].continuations.single()
        assertEquals("on the next indented line.", continuation.text)
        assertEquals(
            continuation.text,
            source.substring(continuation.contentRange.startOffset, continuation.contentRange.endOffset),
        )

        val geometry = NativeListInlayRenderer(editor, model)
            .visualRowGeometryEvidence(Rectangle(10, 20, 600, 300))
        assertTrue(geometry.contains("row=1\tdepth=0\tmarker=-"))
        assertTrue(geometry.contains("\tcontinuations=1\tvisual_lines=2\t"))
        assertEquals(source, plan.identity.source)
    }

    fun testUnorderedLexicalMarkersShareSemanticBulletWithoutNormalizingSource() {
        val source = "- dash\n\n* star\n\n+ plus\n\n1. ordered\n"
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(ProjectionSourceIdentity(1L, source, 0L))
        )

        assertEquals(ProjectionPlanStatus.READY, plan.status)
        val rows = NativeListProjectionPlanner.plan(plan).flatMap(NativeListModel::rows)
        assertEquals(listOf("-", "*", "+", "1."), rows.map(NativeListRow::marker))
        assertEquals(
            listOf("•", "•", "•", "1."),
            rows.map { row -> nativeListDisplayMarker(row.marker) },
        )
        assertEquals(source, plan.identity.source)
    }

}
