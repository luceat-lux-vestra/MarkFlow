package com.algorist.markflow.editor.native

import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.BorderLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFrame

class NativeCodeViewportRelayoutTest : BasePlatformTestCase() {
    fun testManualCodeInlaysRelayoutToCurrentShowingViewportWithoutSourceMutation() {
        val source = Files.readString(Path.of("examples/markdown-core.md"))
        myFixture.configureByText("markdown-core-code-viewport-relayout.md", source)
        val editor = myFixture.editor as EditorImpl
        val document = editor.document
        val stampBefore = document.modificationStamp
        editor.caretModel.moveToOffset(document.textLength)

        val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(document, 0L))
        val fencedModels = NativeFencedCodeProjectionPlanner.plan(plan)
        val indentedModels = NativeIndentedCodeProjectionPlanner.plan(plan)
        assertTrue("manual Markdown fixture must contain ordinary fenced-code models", fencedModels.isNotEmpty())
        assertTrue("manual Markdown fixture must contain an indented-code model", indentedModels.isNotEmpty())

        val visibleCodeLines =
            (fencedModels.flatMap { model -> model.code.lineSequence().toList() } +
                indentedModels.flatMap { model -> model.code.lineSequence().toList() })
                .filter(String::isNotBlank)
        assertTrue(visibleCodeLines.isNotEmpty())

        val metrics = editor.contentComponent.getFontMetrics(editor.contentComponent.font)
        val shortestCodeLineWidth = visibleCodeLines.minOf(metrics::stringWidth)
        val longestCodeLineWidth = visibleCodeLines.maxOf(metrics::stringWidth)
        assertTrue(longestCodeLineWidth > shortestCodeLineWidth)

        val originalParent = editor.component.parent
        val testWindow = JFrame("MarkFlow code viewport relayout")
        var controller: NativePresentationController? = null
        try {
            testWindow.contentPane.add(editor.component, BorderLayout.CENTER)
            testWindow.pack()
            testWindow.isVisible = true
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertTrue(
                "Code viewport relayout must be tested in an attached showing editor",
                editor.contentComponent.isShowing,
            )

            val packedViewportWidth = editor.scrollingModel.visibleArea.width
            val frameChromeWidth = (testWindow.width - packedViewportWidth).coerceAtLeast(0)
            val narrowFrameWidth = frameChromeWidth + shortestCodeLineWidth
            testWindow.setSize(narrowFrameWidth, testWindow.height)
            testWindow.validate()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            val narrowViewportWidth = editor.scrollingModel.visibleArea.width
            assertTrue(narrowViewportWidth > 0)

            controller = NativePresentationController(
                editor = editor,
                richPresentationEnabled = { true },
            )

            val fencedInlays = codeInlays(editor) { renderer -> renderer is NativeFencedCodeInlayRenderer }
            val indentedInlays = codeInlays(editor) { renderer -> renderer is NativeIndentedCodeInlayRenderer }
            assertEquals(fencedModels.size, fencedInlays.size)
            assertEquals(indentedModels.size, indentedInlays.size)

            val allCodeInlays = fencedInlays + indentedInlays
            allCodeInlays.forEach { inlay -> inlay.update() }
            val narrowWidths = allCodeInlays.associateWith { inlay -> inlay.widthInPixels }

            val expandedViewportTarget = longestCodeLineWidth + shortestCodeLineWidth
            val expandedFrameWidth = frameChromeWidth + expandedViewportTarget
            testWindow.setSize(expandedFrameWidth, testWindow.height)
            testWindow.validate()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            val expandedViewportWidth = editor.scrollingModel.visibleArea.width
            assertTrue(
                "Showing editor viewport did not expand: narrow=$narrowViewportWidth expanded=$expandedViewportWidth",
                expandedViewportWidth > narrowViewportWidth,
            )

            allCodeInlays.forEach { inlay ->
                assertTrue("code inlay became invalid during viewport relayout", inlay.isValid)
                val expectedWidth = when (val renderer = inlay.renderer) {
                    is NativeFencedCodeInlayRenderer -> renderer.calcWidthInPixels(inlay)
                    is NativeIndentedCodeInlayRenderer -> renderer.calcWidthInPixels(inlay)
                    else -> error("unexpected code inlay renderer: ${renderer::class.java.name}")
                }
                assertTrue(
                    "renderer must request enough width for the maintained fixture after expansion",
                    expectedWidth > longestCodeLineWidth,
                )
                assertTrue(
                    "code inlay width did not grow after showing viewport expansion",
                    expectedWidth > requireNotNull(narrowWidths[inlay]),
                )
                assertEquals(
                    "owned code inlay retained stale narrow width after viewport expansion",
                    expectedWidth,
                    inlay.widthInPixels,
                )
            }

            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)
        } finally {
            controller?.dispose()
            testWindow.contentPane.remove(editor.component)
            testWindow.dispose()
            originalParent?.add(editor.component)
            originalParent?.revalidate()
        }

        assertEquals(source, document.text)
        assertEquals(stampBefore, document.modificationStamp)
    }

    private fun codeInlays(
        editor: EditorImpl,
        predicate: (Any) -> Boolean,
    ): List<Inlay<*>> =
        editor.inlayModel
            .getBlockElementsInRange(0, editor.document.textLength)
            .filter { inlay -> predicate(inlay.renderer) }
}
