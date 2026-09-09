package com.algorist.markflow.editor.native

import com.algorist.markflow.renderer.DerivedRendererPresentationArtifact
import com.algorist.markflow.renderer.DerivedRendererRuntime
import com.algorist.markflow.renderer.DerivedRendererRuntimeRequest
import com.algorist.markflow.renderer.DerivedRendererRuntimeResult
import com.algorist.markflow.settings.state.MarkFlowRuntimeSettings
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

class NativeDerivedPresentationControllerTest : BasePlatformTestCase() {
    fun testScreenReaderFallbackKeepsExactSourceAndSkipsRenderer() {
        myFixture.configureByText("accessibility.md", representativeSource())
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("Plain body") + 2)
        val sourceBefore = myFixture.editor.document.text
        val runtime = DeferredRuntime()
        val controller = NativeDerivedPresentationController(
            editor = myFixture.editor,
            runtime = runtime,
            settingsProvider = { runtimeSettings() },
            richPresentationEnabled = { false },
        )

        controller.applyPlan(plan(configGeneration = 1L))
        val evidence = controller.evidenceSnapshot()

        assertEquals(3, evidence.derivedFragments)
        assertEquals(0, evidence.pendingRequests)
        assertEquals(3L, evidence.accessibilityFallbacks)
        assertEquals(0, evidence.ownedInlays)
        assertEquals(0, evidence.ownedFolds)
        assertEquals(0, runtime.rendered.size)
        assertEquals(sourceBefore, myFixture.editor.document.text)
        Disposer.dispose(controller)
    }

    fun testRapidDocumentEditCancelsPendingGenerationAndLateSuccessCannotOverwrite() {
        myFixture.configureByText("pending.md", representativeSource())
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("Plain body") + 2)
        val runtime = DeferredRuntime()
        val controller = NativeDerivedPresentationController(
            editor = myFixture.editor,
            runtime = runtime,
            settingsProvider = { runtimeSettings() },
            richPresentationEnabled = { true },
        )

        controller.applyPlan(plan(configGeneration = 1L))
        val oldRequests = runtime.rendered.toList()
        assertEquals(3, oldRequests.size)
        assertEquals(3, controller.evidenceSnapshot().pendingRequests)

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(myFixture.editor.document.textLength, "\nrapid edit\n")
        }
        val editedSource = myFixture.editor.document.text
        controller.applyPlan(plan(configGeneration = 1L))

        assertEquals(oldRequests.map { it.request.requestId }.toSet(), runtime.cancelled.toSet())
        assertEquals(3, controller.evidenceSnapshot().pendingRequests)
        oldRequests.forEach { rendered -> rendered.callback(successWithPng(rendered.request)) }

        val evidence = controller.evidenceSnapshot()
        assertEquals(0, evidence.decodedArtifacts)
        assertEquals(0, evidence.ownedInlays)
        assertEquals(0, evidence.ownedFolds)
        assertEquals(editedSource, myFixture.editor.document.text)
        Disposer.dispose(controller)
    }

    fun testMermaidInlineErrorBoxKeepsSourceVisibleAndUsesNativeErrorInlay() {
        myFixture.configureByText("mermaid-error.md", representativeSource())
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("Plain body") + 2)
        val sourceBefore = myFixture.editor.document.text
        val controller = NativeDerivedPresentationController(
            editor = myFixture.editor,
            runtime = FailingRuntime(),
            settingsProvider = { runtimeSettings(mermaidErrorDisplay = "INLINE_ERROR_BOX") },
            richPresentationEnabled = { true },
        )

        controller.applyPlan(plan(configGeneration = 1L))
        val evidence = controller.evidenceSnapshot()

        assertEquals(3L, evidence.rendererFailures)
        assertEquals(1, evidence.ownedErrorInlays)
        assertEquals(0, evidence.ownedInlays)
        assertEquals(0, evidence.ownedFolds)
        assertEquals(sourceBefore, myFixture.editor.document.text)
        Disposer.dispose(controller)
    }

    fun testMermaidSilentErrorModeKeepsOnlyExactSource() {
        myFixture.configureByText("mermaid-silent.md", representativeSource())
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("Plain body") + 2)
        val sourceBefore = myFixture.editor.document.text
        val controller = NativeDerivedPresentationController(
            editor = myFixture.editor,
            runtime = FailingRuntime(),
            settingsProvider = { runtimeSettings(mermaidErrorDisplay = "SILENT_LOG_ONLY") },
            richPresentationEnabled = { true },
        )

        controller.applyPlan(plan(configGeneration = 1L))
        val evidence = controller.evidenceSnapshot()

        assertEquals(3L, evidence.rendererFailures)
        assertEquals(0, evidence.ownedErrorInlays)
        assertEquals(0, evidence.ownedInlays)
        assertEquals(0, evidence.ownedFolds)
        assertEquals(sourceBefore, myFixture.editor.document.text)
        Disposer.dispose(controller)
    }

    fun testNativeRasterSizingRetainsMermaidModesZoomAndKatexInlineDisplayBounds() {
        assertEquals(
            400 to 200,
            calculateNativeRasterDimensions(
                kind = NativeDerivedProjectionKind.MERMAID,
                imageWidth = 200,
                imageHeight = 100,
                viewportWidth = 400,
                lineHeight = 20,
                mermaidSizeMode = "FIT_TO_VIEWPORT",
                mermaidZoomPercent = 100,
            )
        )
        assertEquals(
            100 to 50,
            calculateNativeRasterDimensions(
                kind = NativeDerivedProjectionKind.MERMAID,
                imageWidth = 200,
                imageHeight = 100,
                viewportWidth = 400,
                lineHeight = 20,
                mermaidSizeMode = "ACTUAL_SIZE_SCROLL",
                mermaidZoomPercent = 50,
            )
        )
        assertEquals(
            200 to 100,
            calculateNativeRasterDimensions(
                kind = NativeDerivedProjectionKind.MERMAID,
                imageWidth = 800,
                imageHeight = 400,
                viewportWidth = 400,
                lineHeight = 20,
                mermaidSizeMode = "SHRINK_TO_FIT",
                mermaidZoomPercent = 50,
            )
        )
        assertEquals(
            40 to 20,
            calculateNativeRasterDimensions(
                kind = NativeDerivedProjectionKind.KATEX_INLINE,
                imageWidth = 80,
                imageHeight = 40,
                viewportWidth = 400,
                lineHeight = 20,
            )
        )
        assertEquals(
            400 to 200,
            calculateNativeRasterDimensions(
                kind = NativeDerivedProjectionKind.KATEX_DISPLAY,
                imageWidth = 800,
                imageHeight = 400,
                viewportWidth = 400,
                lineHeight = 20,
            )
        )
    }

    private fun plan(configGeneration: Long): NativeProjectionPlan =
        NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot.capture(myFixture.editor.document, configGeneration)
        ).also { assertEquals(ProjectionPlanStatus.READY, it.status) }

    private fun representativeSource(): String = buildString {
        append("```mermaid\n")
        append("graph TD\n  A --> B\n")
        append("```\n\n")
        append("Inline ${'$'}a^2 + b^2 = c^2${'$'}.\n\n")
        append("${'$'}${'$'}\\frac{1}{2}${'$'}${'$'}\n\n")
        append("Plain body line.\n")
    }

    private fun runtimeSettings(
        mermaidErrorDisplay: String = "INLINE_ERROR_BOX",
    ) = MarkFlowRuntimeSettings(
        mermaidSizeMode = "FIT_TO_VIEWPORT",
        mermaidZoomPercent = 100,
        themeSource = "LIGHT",
        mermaidErrorDisplay = mermaidErrorDisplay,
        katexDisplayDensity = "COMFORTABLE",
        diagramSecurityLevel = "STRICT",
        previewOnlyByDefault = true,
        mermaidSyntaxErrorMessage = "Diagram syntax error",
        fontFamily = "",
        baseFontSizePx = 16,
        ideColorScheme = mapOf("foreground" to "#112233"),
        ideFontFamily = "JetBrains Mono",
        ideDark = false,
        settingsRevision = 1,
    )

    private fun successWithPng(request: DerivedRendererRuntimeRequest) = DerivedRendererRuntimeResult(
        requestId = request.requestId,
        status = "success",
        kind = request.kind.wireName,
        identity = request.identity,
        mediaType = if (request.kind.wireName == "mermaid") "image/svg+xml" else "text/html",
        content = "synthetic",
        presentationArtifact = onePixelPng(),
    )

    private fun onePixelPng(): DerivedRendererPresentationArtifact {
        val output = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", output)
        return DerivedRendererPresentationArtifact(
            mediaType = "image/png",
            contentBase64 = Base64.getEncoder().encodeToString(output.toByteArray()),
            width = 1,
            height = 1,
        )
    }

    private data class Rendered(
        val request: DerivedRendererRuntimeRequest,
        val callback: (DerivedRendererRuntimeResult) -> Unit,
    )

    private class DeferredRuntime : DerivedRendererRuntime {
        val rendered = mutableListOf<Rendered>()
        val cancelled = mutableListOf<String>()

        override fun render(request: DerivedRendererRuntimeRequest, callback: (DerivedRendererRuntimeResult) -> Unit) {
            rendered += Rendered(request, callback)
        }

        override fun cancel(requestId: String) {
            cancelled += requestId
        }

        override fun dispose() = Unit
    }

    private class FailingRuntime : DerivedRendererRuntime {
        override fun render(request: DerivedRendererRuntimeRequest, callback: (DerivedRendererRuntimeResult) -> Unit) {
            callback(
                DerivedRendererRuntimeResult(
                    requestId = request.requestId,
                    status = "failure",
                    kind = request.kind.wireName,
                    identity = request.identity,
                    code = "RENDER_FAILED",
                    retryable = false,
                    message = "synthetic failure",
                )
            )
        }

        override fun cancel(requestId: String) = Unit
        override fun dispose() = Unit
    }
}
