package com.algorist.markflow.editor.native

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.MouseEvent

class NativeFootnotePresentationTest : BasePlatformTestCase() {
    fun testParentPresentationControllerWiresFootnoteOwner() {
        val source = """Reference.[^one]

[^one]: Definition.

Tail
"""
        myFixture.configureByText("footnote-parent-controller.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp
        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )

        try {
            val evidence = controller.footnoteEvidenceSnapshot()
            assertEquals(2, evidence.models)
            assertEquals(2, evidence.fullyPresented)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testSplitEditorsOwnFootnotePresentationIndependentlyOnSharedDocument() {
        val source = """Reference.[^shared]

[^shared]: Shared definition.

Tail
"""
        myFixture.configureByText("footnote-split-editor.md", source)
        val first = myFixture.editor
        val second = EditorFactory.getInstance().createEditor(
            first.document,
            project,
            myFixture.file.virtualFile,
            false,
        )
        val tail = source.indexOf("Tail") + 1
        val referenceStart = source.indexOf("[^shared]")
        first.caretModel.moveToOffset(tail)
        second.caretModel.moveToOffset(tail)
        val firstController = NativePresentationController(first, richPresentationEnabled = { true })
        val secondController = NativePresentationController(second, richPresentationEnabled = { true })

        try {
            assertSame(first.document, second.document)
            assertEquals(2, firstController.footnoteEvidenceSnapshot().fullyPresented)
            assertEquals(2, secondController.footnoteEvidenceSnapshot().fullyPresented)

            first.caretModel.moveToOffset(referenceStart + 2)
            firstController.refreshNow()

            assertEquals(1, firstController.footnoteEvidenceSnapshot().fullyPresented)
            assertEquals(
                "revealing source in one editor must not remove the other editor's presentation",
                2,
                secondController.footnoteEvidenceSnapshot().fullyPresented,
            )
            assertEquals(source, first.document.text)
        } finally {
            firstController.dispose()
            secondController.dispose()
            EditorFactory.getInstance().releaseEditor(second)
        }
    }

    fun testFootnoteSemanticsRebuildAfterEditUndoRedoWithoutSourceReconstruction() {
        val source = """Reference.[^one]

[^one]: Definition.

Tail
"""
        val updated = source.replace("Definition.", "Updated definition.")
        myFixture.configureByText("footnote-undo-redo.md", source)
        val editor = myFixture.editor
        val document = editor.document
        val fileEditor = requireNotNull(TextEditorProvider.getInstance().getTextEditor(editor))
        val tail = source.indexOf("Tail") + 1
        editor.caretModel.moveToOffset(tail)
        val controller = NativePresentationController(editor, richPresentationEnabled = { true })

        try {
            assertEquals(2, controller.footnoteEvidenceSnapshot().fullyPresented)

            val bodyStart = document.text.indexOf("Definition.")
            WriteCommandAction.writeCommandAction(project)
                .withName("Edit footnote definition")
                .run<RuntimeException> {
                    document.replaceString(
                        bodyStart,
                        bodyStart + "Definition.".length,
                        "Updated definition.",
                    )
                }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            editor.caretModel.moveToOffset(updated.indexOf("Tail") + 1)
            controller.refreshNow()
            assertEquals(updated, document.text)
            assertEquals(2, controller.footnoteEvidenceSnapshot().fullyPresented)

            val undo = UndoManager.getInstance(project)
            assertTrue(undo.isUndoAvailable(fileEditor))
            undo.undo(fileEditor)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            editor.caretModel.moveToOffset(tail)
            controller.refreshNow()
            assertEquals(source, document.text)
            assertEquals(2, controller.footnoteEvidenceSnapshot().fullyPresented)

            assertTrue(undo.isRedoAvailable(fileEditor))
            undo.redo(fileEditor)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            editor.caretModel.moveToOffset(updated.indexOf("Tail") + 1)
            controller.refreshNow()
            assertEquals(updated, document.text)
            assertEquals(2, controller.footnoteEvidenceSnapshot().fullyPresented)
        } finally {
            controller.dispose()
        }
    }

    fun testFootnoteMarkersResolveCurrentEditorThemeAtPaintTime() {
        val source = """Reference.[^theme]

[^theme]: Definition.

Tail
"""
        myFixture.configureByText("footnote-theme.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot.capture(editor.document, 0L)
        )
        val controller = NativeFootnotePresentationController(editor)

        try {
            controller.applyPlan(plan, richPresentationEnabled = true)
            val renderers = editor.inlayModel
                .getInlineElementsInRange(0, editor.document.textLength)
                .mapNotNull { inlay -> inlay.renderer as? NativeFootnoteMarkerRenderer }
            val reference = renderers.single { renderer -> renderer.role == FootnoteRole.REFERENCE }
            val definition = renderers.single { renderer -> renderer.role == FootnoteRole.DEFINITION }

            val editorEx = editor as EditorEx
            val originalScheme = editorEx.colorsScheme
            val themedScheme = originalScheme.clone() as EditorColorsScheme
            val referenceAttributes = requireNotNull(
                themedScheme.getAttributes(EditorColors.REFERENCE_HYPERLINK_COLOR)
            ).clone()
            val definitionAttributes = requireNotNull(
                themedScheme.getAttributes(DefaultLanguageHighlighterColors.LINE_COMMENT)
            ).clone()

            themedScheme.setAttributes(
                EditorColors.REFERENCE_HYPERLINK_COLOR,
                definitionAttributes,
            )
            themedScheme.setAttributes(
                DefaultLanguageHighlighterColors.LINE_COMMENT,
                referenceAttributes,
            )
            editorEx.setColorsScheme(themedScheme)

            val fallback = TextAttributes()
            assertEquals(
                themedScheme.getAttributes(EditorColors.REFERENCE_HYPERLINK_COLOR).foregroundColor,
                reference.presentationForeground(fallback),
            )
            assertEquals(
                themedScheme.getAttributes(DefaultLanguageHighlighterColors.LINE_COMMENT).foregroundColor,
                definition.presentationForeground(fallback),
            )
            assertEquals(source, editor.document.text)
        } finally {
            controller.dispose()
        }
    }

    fun testInactiveFootnotePairUsesNativePresentationWithoutChangingSource() {
        val source = """Text with a footnote reference.[^manual-note]

[^manual-note]: Footnote definition text.

Tail
"""
        myFixture.configureByText("footnote-presentation.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val controller = NativeFootnotePresentationController(editor)

        try {
            controller.applyPlan(plan, richPresentationEnabled = true)

            val evidence = controller.evidenceSnapshot()
            assertEquals(2, evidence.models)
            assertEquals(1, evidence.references)
            assertEquals(1, evidence.definitions)
            assertEquals(2, evidence.ownedInlays)
            assertEquals(2, evidence.ownedFolds)
            assertEquals(1, evidence.ownedHighlighters)
            assertEquals(2, evidence.fullyPresented)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)

            val renderers = editor.inlayModel
                .getInlineElementsInRange(0, editor.document.textLength)
                .mapNotNull { inlay -> inlay.renderer as? NativeFootnoteMarkerRenderer }
            assertEquals(setOf(FootnoteRole.REFERENCE, FootnoteRole.DEFINITION), renderers.map { it.role }.toSet())
            assertTrue(
                renderers.first { it.role == FootnoteRole.REFERENCE }.presentationFont().size2D <
                    editor.contentComponent.font.size2D
            )
        } finally {
            controller.dispose()
        }
    }

    fun testCaretRevealRestoresExactFootnoteSourceAndRebuildsPresentation() {
        val source = """Text with a footnote reference.[^manual-note]

[^manual-note]: Footnote definition text.

Tail
"""
        myFixture.configureByText("footnote-caret-reveal.md", source)
        val editor = myFixture.editor
        val tail = source.indexOf("Tail") + 1
        val referenceStart = source.indexOf("[^manual-note]")
        editor.caretModel.moveToOffset(tail)
        val stampBefore = editor.document.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val controller = NativeFootnotePresentationController(editor)

        try {
            controller.applyPlan(plan, richPresentationEnabled = true)
            assertEquals(2, controller.evidenceSnapshot().fullyPresented)

            editor.caretModel.moveToOffset(referenceStart + 2)
            controller.refreshActivity(plan, richPresentationEnabled = true)
            assertEquals(1, controller.evidenceSnapshot().fullyPresented)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)

            editor.caretModel.moveToOffset(tail)
            controller.refreshActivity(plan, richPresentationEnabled = true)
            assertEquals(2, controller.evidenceSnapshot().fullyPresented)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testReferenceMarkerClickRevealsExactLexicalSourceWithoutNavigation() {
        val source = """Text with a footnote reference.[^manual-note]

[^manual-note]: Footnote definition text.

Tail
"""
        myFixture.configureByText("footnote-mouse-reveal.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
        val stampBefore = editor.document.modificationStamp
        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        val reference = plan.projections.single { it.kind == NativeProjectionKind.FOOTNOTE_REFERENCE }
        val controller = NativeFootnotePresentationController(editor)

        try {
            controller.applyPlan(plan, richPresentationEnabled = true)
            val inlay = editor.inlayModel
                .getInlineElementsInRange(0, editor.document.textLength)
                .single { inlay ->
                    (inlay.renderer as? NativeFootnoteMarkerRenderer)?.role == FootnoteRole.REFERENCE
                }
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
            assertEquals(reference.sourceRange.startOffset, editor.caretModel.primaryCaret.offset)
            assertEquals(1L, controller.evidenceSnapshot().mouseReveals)
            assertEquals(1, controller.evidenceSnapshot().fullyPresented)
            assertEquals(source, editor.document.text)
            assertEquals(stampBefore, editor.document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testAmbiguousAndAccessibilityCasesRemainExactSource() {
        val duplicate = """Duplicate reference.[^same]

[^same]: First definition.

[^same]: Second definition.
"""
        myFixture.configureByText("footnote-duplicate.md", duplicate)
        var plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot.capture(myFixture.editor.document, 0L)
        )
        var controller = NativeFootnotePresentationController(myFixture.editor)
        try {
            controller.applyPlan(plan, richPresentationEnabled = true)
            assertEquals(0, controller.evidenceSnapshot().models)
            assertEquals(duplicate, myFixture.editor.document.text)
        } finally {
            controller.dispose()
        }

        val valid = """Reference.[^one]

[^one]: Definition.

Tail
"""
        myFixture.configureByText("footnote-accessibility.md", valid)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(valid.indexOf("Tail") + 1)
        plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
        controller = NativeFootnotePresentationController(editor)
        try {
            controller.applyPlan(plan, richPresentationEnabled = false)
            val evidence = controller.evidenceSnapshot()
            assertEquals(2, evidence.models)
            assertEquals(0, evidence.ownedInlays)
            assertEquals(0, evidence.ownedFolds)
            assertEquals(2L, evidence.accessibilityFallbacks)
            assertEquals(valid, editor.document.text)
        } finally {
            controller.dispose()
        }
    }
}
