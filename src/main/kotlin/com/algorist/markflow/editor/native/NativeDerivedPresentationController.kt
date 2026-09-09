package com.algorist.markflow.editor.native

import com.algorist.markflow.renderer.DerivedRendererIdentity
import com.algorist.markflow.renderer.DerivedRendererPresentationArtifact
import com.algorist.markflow.renderer.DerivedRendererRuntime
import com.algorist.markflow.renderer.DerivedRendererRuntimeRequest
import com.algorist.markflow.renderer.DerivedRendererRuntimeResult
import com.algorist.markflow.settings.MarkFlowSettingsService
import com.algorist.markflow.settings.state.MarkFlowRuntimeSettings
import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.util.ui.accessibility.ScreenReader
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class NativeDerivedPresentationEvidence(
    val planIdentity: ProjectionSourceIdentity?,
    val derivedFragments: Int,
    val pendingRequests: Int,
    val decodedArtifacts: Int,
    val ownedInlays: Int,
    val ownedFolds: Int,
    val ownedErrorInlays: Int,
    val staleResultsRejected: Long,
    val rendererFailures: Long,
    val missingArtifacts: Long,
    val accessibilityFallbacks: Long,
)

/**
 * Backend-neutral #148 consumer for one native [Editor].
 *
 * It consumes only [DerivedRendererRuntime] and bounded inert PNG artifacts. It never imports JCEF,
 * Mermaid, KaTeX, browser, navigation, filesystem, or source-mutation APIs. The authoritative
 * Document stays visible until a valid artifact for the exact current source/config generation is
 * available. Active caret/selection ranges always reveal exact source. Screen-reader mode keeps the
 * authoritative Markdown visible and does not request rich presentation artifacts.
 */
internal class NativeDerivedPresentationController(
    private val editor: Editor,
    private val runtime: DerivedRendererRuntime?,
    private val settingsProvider: () -> MarkFlowRuntimeSettings = {
        MarkFlowSettingsService.getInstance().runtimeSettings()
    },
    private val richPresentationEnabled: () -> Boolean = { !ScreenReader.isActive() },
) : Disposable {
    private val gson = Gson()
    private val pending = LinkedHashMap<String, PendingRequest>()
    private val decoded = LinkedHashMap<ProjectionKey, DecodedArtifact>()
    private val owned = LinkedHashMap<ProjectionKey, OwnedPresentation>()
    private val errorMessages = LinkedHashMap<ProjectionKey, String>()
    private val errorInlays = LinkedHashMap<ProjectionKey, Inlay<*>>()
    private var currentPlanIdentity: ProjectionSourceIdentity? = null
    private var currentDerived = emptyList<NativeDerivedProjection>()
    private var disposed = false
    private var staleResultsRejected = 0L
    private var rendererFailures = 0L
    private var missingArtifacts = 0L
    private var accessibilityFallbacks = 0L

    fun applyPlan(plan: NativeProjectionPlan) {
        requireAlive()
        ApplicationManager.getApplication().assertIsDispatchThread()
        cancelPending()
        clearOwnedPresentation()
        clearErrorPresentation()
        decoded.clear()
        errorMessages.clear()
        currentPlanIdentity = plan.identity
        currentDerived = NativeDerivedProjectionPlanner.plan(plan)

        if (plan.status != ProjectionPlanStatus.READY || currentDerived.isEmpty()) return
        if (!runCatching(richPresentationEnabled).getOrDefault(false)) {
            accessibilityFallbacks += currentDerived.size
            return
        }
        val selectedRuntime = runtime ?: run {
            rendererFailures += currentDerived.size
            return
        }
        val settings = runCatching(settingsProvider).getOrElse {
            rendererFailures += currentDerived.size
            return
        }

        currentDerived.forEach { projection ->
            request(selectedRuntime, plan.identity, projection, settings)
        }
    }

    fun refreshActivity(plan: NativeProjectionPlan) {
        if (disposed || editor.isDisposed) return
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (plan.identity != currentPlanIdentity || !matchesCurrentIdentity(plan.identity)) return

        currentDerived.forEach { projection ->
            val key = ProjectionKey.of(projection)
            if (isActive(projection)) {
                removeOwned(key)
                removeErrorInlay(key)
            } else {
                decoded[key]?.let { artifact -> installIfCurrent(plan.identity, projection, artifact) }
                errorMessages[key]?.let { message -> installErrorIfCurrent(plan.identity, projection, message) }
            }
        }
    }

    fun evidenceSnapshot(): NativeDerivedPresentationEvidence = NativeDerivedPresentationEvidence(
        planIdentity = currentPlanIdentity,
        derivedFragments = currentDerived.size,
        pendingRequests = pending.size,
        decodedArtifacts = decoded.size,
        ownedInlays = owned.values.count { it.inlay.isValid },
        ownedFolds = owned.values.count { it.fold.isValid },
        ownedErrorInlays = errorInlays.values.count { it.isValid },
        staleResultsRejected = staleResultsRejected,
        rendererFailures = rendererFailures,
        missingArtifacts = missingArtifacts,
        accessibilityFallbacks = accessibilityFallbacks,
    )

    private fun request(
        selectedRuntime: DerivedRendererRuntime,
        planIdentity: ProjectionSourceIdentity,
        projection: NativeDerivedProjection,
        settings: MarkFlowRuntimeSettings,
    ) {
        val requestId = "native-${REQUEST_SEQUENCE.incrementAndGet()}"
        val rendererIdentity = DerivedRendererIdentity(
            sourceGeneration = buildString {
                append(planIdentity.modificationStamp)
                append(':')
                append(projection.sourceRange.startOffset)
                append(':')
                append(projection.sourceRange.endOffset)
            },
            configGeneration = planIdentity.configGeneration.toString(),
        )
        val configJson = when (projection.kind) {
            NativeDerivedProjectionKind.MERMAID -> gson.toJson(mapOf("runtimeSettings" to settings))
            NativeDerivedProjectionKind.KATEX_INLINE,
            NativeDerivedProjectionKind.KATEX_DISPLAY,
            -> gson.toJson(
                mapOf(
                    "displayMode" to projection.block,
                    "displayDensity" to settings.katexDisplayDensity,
                    "baseFontSizePx" to settings.baseFontSizePx,
                    "foreground" to settings.ideColorScheme["foreground"],
                )
            )
        }
        val request = DerivedRendererRuntimeRequest(
            requestId = requestId,
            kind = projection.kind.rendererKind,
            source = projection.source,
            configJson = configJson,
            identity = rendererIdentity,
        )
        pending[requestId] = PendingRequest(
            planIdentity = planIdentity,
            projection = projection,
            rendererIdentity = rendererIdentity,
            mermaidErrorDisplay = settings.mermaidErrorDisplay,
            mermaidErrorMessage = settings.mermaidSyntaxErrorMessage,
        )
        selectedRuntime.render(request) { result -> onRendererResult(requestId, result) }
    }

    private fun onRendererResult(requestId: String, result: DerivedRendererRuntimeResult) {
        onEdt {
            val expected = pending.remove(requestId) ?: return@onEdt
            if (disposed || editor.isDisposed) return@onEdt
            if (result.identity != expected.rendererIdentity || !isCurrent(expected.planIdentity)) {
                staleResultsRejected += 1
                return@onEdt
            }
            if (result.status != "success") {
                rendererFailures += 1
                LOG.warn(
                    "MarkFlow native derived renderer failed kind=${expected.projection.kind} " +
                        "code=${result.code ?: "unknown"} retryable=${result.retryable}"
                )
                if (
                    expected.projection.kind == NativeDerivedProjectionKind.MERMAID &&
                    expected.mermaidErrorDisplay == MERMAID_INLINE_ERROR_BOX
                ) {
                    val key = ProjectionKey.of(expected.projection)
                    val message = expected.mermaidErrorMessage.take(MAX_ERROR_MESSAGE_CHARS)
                    errorMessages[key] = message
                    if (!isActive(expected.projection)) {
                        installErrorIfCurrent(expected.planIdentity, expected.projection, message)
                    }
                }
                return@onEdt
            }
            val artifact = result.presentationArtifact
            if (artifact == null) {
                missingArtifacts += 1
                return@onEdt
            }
            decodeArtifactAsync(expected, artifact)
        }
    }

    private fun decodeArtifactAsync(
        expected: PendingRequest,
        artifact: DerivedRendererPresentationArtifact,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val decodedArtifact = decodeArtifact(artifact)
            ApplicationManager.getApplication().invokeLater {
                if (disposed || editor.isDisposed) return@invokeLater
                if (!isCurrent(expected.planIdentity)) {
                    staleResultsRejected += 1
                    return@invokeLater
                }
                if (decodedArtifact == null) {
                    missingArtifacts += 1
                    return@invokeLater
                }
                val key = ProjectionKey.of(expected.projection)
                decoded[key] = decodedArtifact
                if (!isActive(expected.projection)) {
                    installIfCurrent(expected.planIdentity, expected.projection, decodedArtifact)
                }
            }
        }
    }

    private fun decodeArtifact(artifact: DerivedRendererPresentationArtifact): DecodedArtifact? {
        if (artifact.mediaType != "image/png") return null
        if (artifact.width !in 1..MAX_ARTIFACT_DIMENSION || artifact.height !in 1..MAX_ARTIFACT_DIMENSION) return null
        if (artifact.width.toLong() * artifact.height.toLong() > MAX_ARTIFACT_PIXELS) return null
        val bytes = runCatching { Base64.getDecoder().decode(artifact.contentBase64) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_DECODED_PNG_BYTES) return null
        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        if (image.width != artifact.width || image.height != artifact.height) return null
        return DecodedArtifact(image)
    }

    private fun installIfCurrent(
        planIdentity: ProjectionSourceIdentity,
        projection: NativeDerivedProjection,
        artifact: DecodedArtifact,
    ) {
        if (!isCurrent(planIdentity) || isActive(projection)) return
        val key = ProjectionKey.of(projection)
        if (owned[key]?.let { it.fold.isValid && it.inlay.isValid } == true) return
        removeErrorInlay(key)
        removeOwned(key)

        val sourceBefore = editor.document.immutableCharSequence.toString()
        val stampBefore = editor.document.modificationStamp
        val foldingModel = editor.foldingModel
        var fold: FoldRegion? = null
        foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            fold = foldingModel.addFoldRegion(
                projection.sourceRange.startOffset,
                projection.sourceRange.endOffset,
                ZERO_WIDTH_PLACEHOLDER,
            )
            fold?.isExpanded = false
        }
        val installedFold = fold ?: return
        if (installedFold.isExpanded) {
            foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                if (installedFold.isValid) foldingModel.removeFoldRegion(installedFold)
            }
            return
        }

        val renderer = NativeRasterInlayRenderer(
            editor = editor,
            projection = projection,
            image = artifact.image,
            settingsProvider = settingsProvider,
        )
        val inlay = if (projection.block) {
            editor.inlayModel.addBlockElement(
                projection.sourceRange.endOffset,
                true,
                true,
                0,
                renderer,
            )
        } else {
            editor.inlayModel.addInlineElement(
                projection.sourceRange.endOffset,
                true,
                renderer,
            )
        }
        if (inlay == null) {
            foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                if (installedFold.isValid) foldingModel.removeFoldRegion(installedFold)
            }
            return
        }
        owned[key] = OwnedPresentation(installedFold, inlay)

        check(editor.document.modificationStamp == stampBefore) {
            "native derived presentation changed the authoritative Document modification stamp"
        }
        check(editor.document.immutableCharSequence.toString() == sourceBefore) {
            "native derived presentation changed the authoritative Document source"
        }
    }

    private fun installErrorIfCurrent(
        planIdentity: ProjectionSourceIdentity,
        projection: NativeDerivedProjection,
        message: String,
    ) {
        if (!isCurrent(planIdentity) || isActive(projection)) return
        val key = ProjectionKey.of(projection)
        if (errorInlays[key]?.isValid == true) return
        removeErrorInlay(key)
        val inlay = editor.inlayModel.addBlockElement(
            projection.sourceRange.endOffset,
            true,
            true,
            0,
            NativeDerivedErrorInlayRenderer(editor, message),
        ) ?: return
        errorInlays[key] = inlay
    }

    private fun isCurrent(identity: ProjectionSourceIdentity): Boolean =
        currentPlanIdentity == identity && matchesCurrentIdentity(identity)

    private fun matchesCurrentIdentity(identity: ProjectionSourceIdentity): Boolean =
        ProjectionSnapshot.capture(editor.document, identity.configGeneration).identity == identity

    private fun isActive(projection: NativeDerivedProjection): Boolean {
        val range = projection.sourceRange
        return editor.caretModel.allCarets.any { caret ->
            (caret.offset >= range.startOffset && caret.offset <= range.endOffset) ||
                (caret.hasSelection() && range.intersects(caret.selectionStart, caret.selectionEnd))
        }
    }

    private fun cancelPending() {
        val selectedRuntime = runtime
        pending.keys.toList().forEach { requestId ->
            runCatching { selectedRuntime?.cancel(requestId) }
        }
        pending.clear()
    }

    private fun clearOwnedPresentation() {
        owned.keys.toList().forEach(::removeOwned)
    }

    private fun clearErrorPresentation() {
        errorInlays.keys.toList().forEach(::removeErrorInlay)
    }

    private fun removeOwned(key: ProjectionKey) {
        val presentation = owned.remove(key) ?: return
        if (presentation.inlay.isValid) presentation.inlay.dispose()
        if (presentation.fold.isValid && !editor.isDisposed) {
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                if (presentation.fold.isValid) {
                    editor.foldingModel.removeFoldRegion(presentation.fold)
                }
            }
        }
    }

    private fun removeErrorInlay(key: ProjectionKey) {
        val inlay = errorInlays.remove(key) ?: return
        if (inlay.isValid) inlay.dispose()
    }

    private fun requireAlive() {
        check(!disposed) { "native derived presentation controller is disposed" }
        check(!editor.isDisposed) { "native editor is disposed" }
    }

    override fun dispose() {
        if (disposed) return
        val application = ApplicationManager.getApplication()
        if (!application.isDispatchThread) {
            application.invokeAndWait { dispose() }
            return
        }
        cancelPending()
        clearOwnedPresentation()
        clearErrorPresentation()
        decoded.clear()
        errorMessages.clear()
        currentDerived = emptyList()
        currentPlanIdentity = null
        disposed = true
        runCatching { runtime?.dispose() }
    }

    private fun onEdt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) action() else application.invokeLater(action)
    }

    private data class PendingRequest(
        val planIdentity: ProjectionSourceIdentity,
        val projection: NativeDerivedProjection,
        val rendererIdentity: DerivedRendererIdentity,
        val mermaidErrorDisplay: String,
        val mermaidErrorMessage: String,
    )

    private data class ProjectionKey(
        val kind: NativeDerivedProjectionKind,
        val startOffset: Int,
        val endOffset: Int,
    ) {
        companion object {
            fun of(projection: NativeDerivedProjection) = ProjectionKey(
                projection.kind,
                projection.sourceRange.startOffset,
                projection.sourceRange.endOffset,
            )
        }
    }

    private data class DecodedArtifact(
        val image: BufferedImage,
    )

    private data class OwnedPresentation(
        val fold: FoldRegion,
        val inlay: Inlay<*>,
    )

    companion object {
        private val LOG = Logger.getInstance(NativeDerivedPresentationController::class.java)
        private const val ZERO_WIDTH_PLACEHOLDER = "\u200B"
        private const val MERMAID_INLINE_ERROR_BOX = "INLINE_ERROR_BOX"
        private const val MAX_ERROR_MESSAGE_CHARS = 240
        private const val MAX_ARTIFACT_DIMENSION = 4096
        private const val MAX_ARTIFACT_PIXELS = 8L * 1024L * 1024L
        private const val MAX_DECODED_PNG_BYTES = 48 * 1024 * 1024
        private val REQUEST_SEQUENCE = AtomicLong(0)
    }
}

internal fun calculateNativeRasterDimensions(
    kind: NativeDerivedProjectionKind,
    imageWidth: Int,
    imageHeight: Int,
    viewportWidth: Int,
    lineHeight: Int,
    mermaidSizeMode: String = "FIT_TO_VIEWPORT",
    mermaidZoomPercent: Int = 100,
): Pair<Int, Int> {
    require(imageWidth > 0 && imageHeight > 0)
    val boundedViewportWidth = max(1, viewportWidth)
    val boundedLineHeight = max(1, lineHeight)
    val baseScale = when (kind) {
        NativeDerivedProjectionKind.MERMAID -> {
            val zoom = mermaidZoomPercent.coerceIn(50, 200) / 100.0
            val widthScale = when (mermaidSizeMode) {
                "ACTUAL_SIZE_SCROLL" -> 1.0
                "SHRINK_TO_FIT" -> min(1.0, boundedViewportWidth.toDouble() / imageWidth.toDouble())
                else -> boundedViewportWidth.toDouble() / imageWidth.toDouble()
            }
            widthScale * zoom
        }

        NativeDerivedProjectionKind.KATEX_INLINE ->
            min(1.0, boundedLineHeight.toDouble() / imageHeight.toDouble())

        NativeDerivedProjectionKind.KATEX_DISPLAY ->
            min(1.0, boundedViewportWidth.toDouble() / imageWidth.toDouble())
    }
    val maxHeight = if (kind.block) 1024 else boundedLineHeight
    val scaledWidth = max(1, (imageWidth * baseScale).roundToInt())
    val scaledHeight = max(1, (imageHeight * baseScale).roundToInt())
    if (scaledHeight <= maxHeight) return scaledWidth to scaledHeight
    val heightScale = maxHeight.toDouble() / scaledHeight.toDouble()
    return max(1, (scaledWidth * heightScale).roundToInt()) to maxHeight
}

private class NativeRasterInlayRenderer(
    private val editor: Editor,
    private val projection: NativeDerivedProjection,
    private val image: BufferedImage,
    private val settingsProvider: () -> MarkFlowRuntimeSettings,
) : EditorCustomElementRenderer {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int = dimensions().first

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = dimensions().second

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) {
        val (width, height) = dimensions()
        val x = targetRegion.x
        val y = targetRegion.y + max(0, (targetRegion.height - height) / 2)
        val graphics2D = g as? Graphics2D
        val previousInterpolation = graphics2D?.getRenderingHint(RenderingHints.KEY_INTERPOLATION)
        graphics2D?.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(image, x, y, width, height, null)
        if (previousInterpolation != null) {
            graphics2D.setRenderingHint(RenderingHints.KEY_INTERPOLATION, previousInterpolation)
        }
    }

    private fun dimensions(): Pair<Int, Int> {
        val settings = runCatching(settingsProvider).getOrNull()
        return calculateNativeRasterDimensions(
            kind = projection.kind,
            imageWidth = image.width,
            imageHeight = image.height,
            viewportWidth = max(1, editor.scrollingModel.visibleArea.width - 24),
            lineHeight = editor.lineHeight,
            mermaidSizeMode = settings?.mermaidSizeMode ?: "FIT_TO_VIEWPORT",
            mermaidZoomPercent = settings?.mermaidZoomPercent ?: 100,
        )
    }
}

private class NativeDerivedErrorInlayRenderer(
    private val editor: Editor,
    message: String,
) : EditorCustomElementRenderer {
    private val displayText = "Mermaid: $message"

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val font = editor.contentComponent.font
        val metrics = editor.contentComponent.getFontMetrics(font)
        val available = max(1, editor.scrollingModel.visibleArea.width - 24)
        return min(available, max(1, metrics.stringWidth(displayText) + 16))
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = editor.lineHeight + 8

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) {
        val font = editor.contentComponent.font
        g.font = font
        g.color = textAttributes.foregroundColor ?: editor.colorsScheme.defaultForeground
        val metrics = g.getFontMetrics(font)
        val baseline = targetRegion.y + max(metrics.ascent, (targetRegion.height + metrics.ascent - metrics.descent) / 2)
        g.drawString(displayText, targetRegion.x + 8, baseline)
    }
}
