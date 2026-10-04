package com.algorist.markflow.editor.native

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.MouseEvent

class NativeFencedCodePresentationTest : BasePlatformTestCase() {
    fun testPlannerPreservesBacktickAndTildeFenceBytesAndInfo() {
        val backtickFence = "\u0060\u0060\u0060"
        val source = backtickFence + "kotlin\nval value = 1\n" + backtickFence + "\n\n" +
            "~~~~text meta\npayload\n~~~~~\n"
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(ProjectionSourceIdentity(1L, source, 0L))
        )

        assertEquals(ProjectionPlanStatus.READY, plan.status)
        val models = NativeFencedCodeProjectionPlanner.plan(plan)
        assertEquals(2, models.size)

        val backtick = models[0]
        assertEquals('\u0060', backtick.marker)
        assertEquals(3, backtick.fenceLength)
        assertEquals("kotlin", backtick.info)
        assertEquals("val value = 1\n", backtick.code)
        assertEquals(
            listOf(backtickFence, backtickFence),
            backtick.syntaxRanges.map { range -> source.substring(range.startOffset, range.endOffset) },
        )

        val tilde = models[1]
        assertEquals('~', tilde.marker)
        assertEquals(4, tilde.fenceLength)
        assertEquals("text meta", tilde.info)
        assertEquals("payload\n", tilde.code)
        assertEquals(
            listOf("~~~~", "~~~~~"),
            tilde.syntaxRanges.map { range -> source.substring(range.startOffset, range.endOffset) },
        )
        assertEquals(source, plan.identity.source)
    }

    fun testEmptyAndBoundExceededFencesRemainExactSource() {
        val fence = "\u0060\u0060\u0060"
        val longFence = "\u0060".repeat(257)
        val sources = listOf(
            fence + "text\n\n" + fence + "\n",
            fence + "x".repeat(1025) + "\npayload\n" + fence + "\n",
            longFence + "text\npayload\n" + longFence + "\n",
            fence + "text\n" + "x".repeat(64 * 1024 + 1) + "\n" + fence + "\n",
            fence + "text\n" + "x".repeat(72 * 1024 + 1) + "\n" + fence + "\n",
            fence + "text\n" + "line\n".repeat(200) + fence + "\n",
        )

        sources.forEachIndexed { index, source ->
            val plan = NativeMarkdownProjectionPlanner.plan(
                ProjectionSnapshot(ProjectionSourceIdentity(index.toLong() + 10L, source, 0L))
            )
            assertEquals(ProjectionPlanStatus.READY, plan.status)
            assertTrue(
                "ordinary fenced-code presentation must fail closed for bounded fixture $index",
                NativeFencedCodeProjectionPlanner.plan(plan).isEmpty(),
            )
            assertEquals(source, plan.identity.source)
        }
    }

    fun testMermaidAndUnclosedFencesRemainOutsideOrdinaryRichOwner() {
        val fence = "\u0060\u0060\u0060"
        val sources = listOf(
            fence + "mermaid\ngraph TD; A-->B;\n" + fence + "\n",
            fence + "kotlin\nval incomplete = true\n",
        )

        sources.forEachIndexed { index, source ->
            val plan = NativeMarkdownProjectionPlanner.plan(
                ProjectionSnapshot(ProjectionSourceIdentity(index.toLong() + 1L, source, 0L))
            )
            assertEquals(ProjectionPlanStatus.READY, plan.status)
            assertTrue(
                "ordinary fenced-code presentation must fail closed for fixture $index",
                NativeFencedCodeProjectionPlanner.plan(plan).isEmpty(),
            )
            assertEquals(source, plan.identity.source)
        }
    }

    fun testInactiveFenceUsesNativeBlockAndCaretRevealWithoutChangingSource() {
        val fence = "\u0060\u0060\u0060"
        val source = fence + "kotlin\nval value = 1\n" + fence + "\n\nTail\n"
        myFixture.configureByText("fenced-code.md", source)
        val editor = myFixture.editor
        val tailOffset = source.indexOf("Tail") + 1
        editor.caretModel.moveToOffset(tailOffset)
        val document = editor.document
        val stampBefore = document.modificationStamp

        val controller = NativePresentationController(editor, richPresentationEnabled = { true })
        try {
            val inactive = controller.evidenceSnapshot()
            assertEquals(1, inactive.fencedCodeModels)
            assertEquals(1, inactive.fencedCodeInlays)
            assertTrue(inactive.fencedCodeFolds > 0)
            assertEquals(1, inactive.fencedCodeFullyConcealed)
            assertEquals(listOf("kotlin"), inactive.fencedCodeInfos)

            val renderer = editor.inlayModel
                .getBlockElementsInRange(0, document.textLength)
                .mapNotNull { it.renderer as? NativeFencedCodeInlayRenderer }
                .single()
            assertEquals("kotlin", renderer.displayInfo)
            assertEquals("val value = 1\n", renderer.displayCode)
            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)

            editor.caretModel.moveToOffset(source.indexOf("value") + 1)
            val active = controller.evidenceSnapshot()
            assertEquals(0, active.fencedCodeInlays)
            assertEquals(0, active.fencedCodeFolds)
            assertEquals(0, active.fencedCodeFullyConcealed)
            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)

            editor.caretModel.moveToOffset(tailOffset)
            val restored = controller.evidenceSnapshot()
            assertEquals(1, restored.fencedCodeInlays)
            assertTrue(restored.fencedCodeFolds > 0)
            assertEquals(1, restored.fencedCodeFullyConcealed)
            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testCollapsedForeignFenceFoldFailsClosedWithoutChangingOwnership() {
        val fence = "\u0060\u0060\u0060"
        val source = fence + "kotlin\nval value = 1\n" + fence + "\n\nTail\n"
        myFixture.configureByText("fenced-code-foreign-fold.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp
        var foreignFold: com.intellij.openapi.editor.FoldRegion? = null

        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            foreignFold = editor.foldingModel.addFoldRegion(0, fence.length, "foreign")
            requireNotNull(foreignFold).isExpanded = false
        }
        val foreign = requireNotNull(foreignFold)
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val controller = NativeFencedCodePresentationController(editor)

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

    fun testAccessibilityDispositionKeepsExactFenceSource() {
        val fence = "\u0060\u0060\u0060"
        val source = fence + "text\npayload\n" + fence + "\n\nTail\n"
        myFixture.configureByText("fenced-code-accessible.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp

        val controller = NativePresentationController(editor, richPresentationEnabled = { false })
        try {
            val evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.fencedCodeModels)
            assertEquals(0, evidence.fencedCodeInlays)
            assertEquals(0, evidence.fencedCodeFolds)
            assertEquals(0, evidence.fencedCodeFullyConcealed)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testFenceInlayClickRevealsSourceAndMovesCaretToCode() {
        val fence = "\u0060\u0060\u0060"
        val source = fence + "kotlin\nval value = 1\n" + fence + "\n\nTail\n"
        myFixture.configureByText("fenced-code-click.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val model = NativeFencedCodeProjectionPlanner.plan(plan).single()
        val controller = NativeFencedCodePresentationController(editor)

        try {
            controller.applyPlan(plan, richPresentationEnabled = true)
            val inlay = editor.inlayModel
                .getBlockElementsInRange(0, editor.document.textLength)
                .single { it.renderer is NativeFencedCodeInlayRenderer }
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
            assertEquals(model.contentRange.startOffset, editor.caretModel.primaryCaret.offset)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testEditUndoRedoUsesAuthoritativeDocumentAfterSourceReveal() {
        val fence = "\u0060\u0060\u0060"
        val before = fence + "kotlin\nval value = 1\n" + fence + "\n\nTail\n"
        val after = before.replace("val value = 1", "val value = 2")
        myFixture.configureByText("fenced-code-edit.md", before)
        val editor = myFixture.editor
        val document = editor.document
        val fileEditor = TextEditorProvider.getInstance().getTextEditor(editor)
        assertNotNull(fileEditor)
        val controller = NativePresentationController(editor, richPresentationEnabled = { true })

        try {
            editor.caretModel.moveToOffset(before.indexOf("value") + 1)
            assertEquals(0, controller.evidenceSnapshot().fencedCodeInlays)

            val valueOffset = document.text.indexOf("1")
            WriteCommandAction.writeCommandAction(project)
                .withName("MarkFlow Fenced Code Edit Test")
                .run<RuntimeException> {
                    document.replaceString(valueOffset, valueOffset + 1, "2")
                }
            assertEquals(after, document.text)

            val undo = UndoManager.getInstance(project)
            assertTrue(undo.isUndoAvailable(fileEditor))
            undo.undo(fileEditor)
            assertEquals(before, document.text)
            assertTrue(undo.isRedoAvailable(fileEditor))
            undo.redo(fileEditor)
            assertEquals(after, document.text)

            controller.refreshNow()
            editor.caretModel.moveToOffset(after.indexOf("Tail") + 1)
            val restored = controller.evidenceSnapshot()
            assertEquals(1, restored.fencedCodeInlays)
            assertEquals(1, restored.fencedCodeFullyConcealed)
            assertEquals(after, document.text)
        } finally {
            controller.dispose()
        }
    }
    fun testFenceDispositionSeparatesRichMermaidAndExactSourceFallback() {
        val fence = "\u0060\u0060\u0060"
        val source = fence + "kotlin\nval value = 1\n" + fence + "\n\n" +
            fence + "mermaid\ngraph TD; A-->B;\n" + fence + "\n\n" +
            fence + "text\n\n" + fence + "\n"
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(ProjectionSourceIdentity(41L, source, 0L))
        )

        assertEquals(ProjectionPlanStatus.READY, plan.status)
        val ownership = NativeFencedCodeProjectionPlanner.dispositions(plan)
        assertEquals(
            listOf(
                NativeFencedCodeDisposition.ORDINARY_RICH,
                NativeFencedCodeDisposition.MERMAID_DERIVED,
                NativeFencedCodeDisposition.EXACT_SOURCE,
            ),
            ownership.map(NativeFencedCodeOwnership::disposition),
        )
        assertEquals(3, NativeFencedCodeProjectionPlanner.sourceRanges(plan).size)
        assertEquals(1, NativeFencedCodeProjectionPlanner.plan(plan).size)
        assertEquals(
            1,
            NativeDerivedProjectionPlanner.plan(plan)
                .count { projection -> projection.kind == NativeDerivedProjectionKind.MERMAID },
        )
        assertEquals(source, plan.identity.source)
    }

    fun testExactSourceAndMermaidFencesNeverLeakIntoGenericBlockOwner() {
        val fence = "\u0060\u0060\u0060"
        val oversizedInfo = "x".repeat(1025)
        val source = fence + "text\n\n" + fence + "\n\n" +
            fence + oversizedInfo + "\npayload\n" + fence + "\n\n" +
            fence + "mermaid\ngraph TD; A-->B;\n" + fence + "\n\n" +
            "Tail\n"
        myFixture.configureByText("fenced-code-fallback-ownership.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp
        val controller = NativePresentationController(editor, richPresentationEnabled = { true })

        try {
            val plan = requireNotNull(controller.currentPlan)
            val ownership = NativeFencedCodeProjectionPlanner.dispositions(plan)
            assertEquals(
                listOf(
                    NativeFencedCodeDisposition.EXACT_SOURCE,
                    NativeFencedCodeDisposition.EXACT_SOURCE,
                    NativeFencedCodeDisposition.MERMAID_DERIVED,
                ),
                ownership.map(NativeFencedCodeOwnership::disposition),
            )
            assertEquals(
                1,
                NativeDerivedProjectionPlanner.plan(plan)
                    .count { projection -> projection.kind == NativeDerivedProjectionKind.MERMAID },
            )

            val evidence = controller.evidenceSnapshot()
            assertEquals(0, evidence.fencedCodeModels)
            assertEquals(0, evidence.fencedCodeInlays)
            assertEquals(0, evidence.fencedCodeFolds)
            assertEquals(0, evidence.blockOwnedHighlighters)
            assertEquals(0, evidence.blockSyntaxOwnedFolds)

            ownership.forEach { entry ->
                assertTrue(
                    "fence disposition ${entry.disposition} retained collapsed MarkFlow source concealment",
                    editor.foldingModel.allFoldRegions.none { fold ->
                        fold.isValid &&
                            !fold.isExpanded &&
                            fold.startOffset < entry.sourceRange.endOffset &&
                            fold.endOffset > entry.sourceRange.startOffset
                    },
                )
            }
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

}
