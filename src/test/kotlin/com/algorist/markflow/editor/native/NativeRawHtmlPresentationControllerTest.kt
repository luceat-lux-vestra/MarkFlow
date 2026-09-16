package com.algorist.markflow.editor.native

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.image.BufferedImage

class NativeRawHtmlPresentationControllerTest : BasePlatformTestCase() {
    fun testSafeInlinePreviewIsSourceNeutralAndCaretRevealRestoresExactSource() {
        val source = "Before <span>safe content</span> after.\n"
        myFixture.configureByText("safe-html.md", source)
        myFixture.editor.caretModel.moveToOffset(0)
        val stampBefore = myFixture.editor.document.modificationStamp
        val controller = NativeRawHtmlPresentationController(myFixture.editor, SuccessRenderer)

        controller.applyPlan(plan())
        assertEquals(1, controller.evidenceSnapshot().fragments)
        assertEquals(1, controller.evidenceSnapshot().decodedArtifacts)
        assertEquals(1, controller.evidenceSnapshot().ownedInlays)
        assertEquals(1, controller.evidenceSnapshot().ownedFolds)
        assertEquals(source, myFixture.editor.document.text)
        assertEquals(stampBefore, myFixture.editor.document.modificationStamp)

        val htmlOffset = source.indexOf("safe content")
        myFixture.editor.caretModel.moveToOffset(htmlOffset)
        assertEquals(0, controller.evidenceSnapshot().ownedInlays)
        assertEquals(0, controller.evidenceSnapshot().ownedFolds)
        assertEquals(source, myFixture.editor.document.text)

        myFixture.editor.caretModel.moveToOffset(0)
        assertEquals(1, controller.evidenceSnapshot().ownedInlays)
        assertEquals(1, controller.evidenceSnapshot().ownedFolds)
        assertEquals(source, myFixture.editor.document.text)
        Disposer.dispose(controller)
    }

    fun testAccessibilityFallbackKeepsExactSourceAndSkipsRenderer() {
        val source = "Before <span>safe</span> after.\n"
        myFixture.configureByText("accessible-html.md", source)
        val renderer = DeferredRenderer()
        val controller = NativeRawHtmlPresentationController(
            editor = myFixture.editor,
            renderer = renderer,
            richPresentationEnabled = { false },
        )

        controller.applyPlan(plan())
        val evidence = controller.evidenceSnapshot()
        assertEquals(1, evidence.fragments)
        assertEquals(1L, evidence.accessibilityFallbacks)
        assertEquals(0, evidence.pendingRequests)
        assertEquals(0, evidence.ownedInlays)
        assertEquals(0, evidence.ownedFolds)
        assertTrue(renderer.callbacks.isEmpty())
        assertEquals(source, myFixture.editor.document.text)
        Disposer.dispose(controller)
    }

    fun testBlockedOrFailedPreviewLeavesOnlyExactEditableSource() {
        val source = "Before <span>safe</span> after.\n"
        myFixture.configureByText("blocked-html.md", source)
        myFixture.editor.caretModel.moveToOffset(0)

        val blocked = NativeRawHtmlPresentationController(
            myFixture.editor,
            NativeRawHtmlRenderer { _, callback -> callback(NativeRawHtmlRenderResult.Blocked("ACTIVE_TAG")) },
        )
        blocked.applyPlan(plan())
        assertEquals(1L, blocked.evidenceSnapshot().blockedPreviews)
        assertEquals(0, blocked.evidenceSnapshot().ownedInlays)
        assertEquals(0, blocked.evidenceSnapshot().ownedFolds)
        assertEquals(source, myFixture.editor.document.text)
        Disposer.dispose(blocked)

        val failed = NativeRawHtmlPresentationController(
            myFixture.editor,
            NativeRawHtmlRenderer { _, callback -> callback(NativeRawHtmlRenderResult.Failure("SYNTHETIC")) },
        )
        failed.applyPlan(plan())
        assertEquals(1L, failed.evidenceSnapshot().rendererFailures)
        assertEquals(0, failed.evidenceSnapshot().ownedInlays)
        assertEquals(0, failed.evidenceSnapshot().ownedFolds)
        assertEquals(source, myFixture.editor.document.text)
        Disposer.dispose(failed)
    }

    fun testDocumentMutationInvalidatesPendingPreviewBeforeLateResult() {
        val source = "Before <span>safe</span> after.\n"
        myFixture.configureByText("stale-html.md", source)
        myFixture.editor.caretModel.moveToOffset(0)
        val renderer = DeferredRenderer()
        val controller = NativeRawHtmlPresentationController(myFixture.editor, renderer)

        controller.applyPlan(plan())
        assertEquals(1, controller.evidenceSnapshot().pendingRequests)
        assertEquals(1, renderer.callbacks.size)

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(myFixture.editor.document.textLength, "new source\n")
        }
        val editedSource = myFixture.editor.document.text
        assertEquals(0, controller.evidenceSnapshot().pendingRequests)
        assertEquals(1L, controller.evidenceSnapshot().staleResultsRejected)
        assertEquals(0, controller.evidenceSnapshot().ownedInlays)
        assertEquals(0, controller.evidenceSnapshot().ownedFolds)

        renderer.callbacks.single().invoke(NativeRawHtmlRenderResult.Success(onePixel()))
        assertEquals(0, controller.evidenceSnapshot().ownedInlays)
        assertEquals(0, controller.evidenceSnapshot().ownedFolds)
        assertEquals(editedSource, myFixture.editor.document.text)
        Disposer.dispose(controller)
    }

    fun testDisposeReleasesOwnedPresentation() {
        myFixture.configureByText("dispose-html.md", "Before <span>safe</span> after.\n")
        myFixture.editor.caretModel.moveToOffset(0)
        val controller = NativeRawHtmlPresentationController(myFixture.editor, SuccessRenderer)

        controller.applyPlan(plan())
        assertEquals(1, controller.evidenceSnapshot().ownedInlays)
        assertEquals(1, controller.evidenceSnapshot().ownedFolds)

        Disposer.dispose(controller)
        assertEquals(0, controller.evidenceSnapshot().ownedInlays)
        assertEquals(0, controller.evidenceSnapshot().ownedFolds)
    }

    private fun plan(): NativeProjectionPlan = NativeMarkdownProjectionPlanner.plan(
        ProjectionSnapshot.capture(myFixture.editor.document, configGeneration = 1L)
    ).also { assertEquals(ProjectionPlanStatus.READY, it.status) }

    private object SuccessRenderer : NativeRawHtmlRenderer {
        override fun render(source: String, callback: (NativeRawHtmlRenderResult) -> Unit) {
            callback(NativeRawHtmlRenderResult.Success(onePixel()))
        }
    }

    private class DeferredRenderer : NativeRawHtmlRenderer {
        val callbacks = mutableListOf<(NativeRawHtmlRenderResult) -> Unit>()

        override fun render(source: String, callback: (NativeRawHtmlRenderResult) -> Unit) {
            callbacks += callback
        }
    }

    companion object {
        private fun onePixel() = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    }
}
