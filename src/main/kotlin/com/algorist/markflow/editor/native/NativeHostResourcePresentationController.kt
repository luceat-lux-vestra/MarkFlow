package com.algorist.markflow.editor.native

import com.algorist.markflow.trust.ExternalNavigationPolicy
import com.algorist.markflow.trust.NativeLocalImageArtifact
import com.algorist.markflow.trust.NativeLocalImageResolver
import com.algorist.markflow.trust.NativeLocalImageResult
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.util.ui.accessibility.ScreenReader
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.net.URI
import java.nio.file.Path
import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class NativeHostResourcePresentationEvidence(
    val planIdentity: ProjectionSourceIdentity?,
    val localImages: Int,
    val externalLinks: Int,
    val pendingImageLoads: Int,
    val decodedImages: Int,
    val ownedImageInlays: Int,
    val ownedImageFolds: Int,
    val staleImageResultsRejected: Long,
    val imageFailures: Long,
    val accessibilityFallbacks: Long,
    val navigationAccepted: Long,
    val navigationRejected: Long,
)

/**
 * Per-editor #147 owner for host-resolved local images and explicit external navigation.
 *
 * This class has no JCEF, loopback HTTP, browser-realm, filesystem-token or source-write path.
 * Local image bytes are resolved/decoded off EDT into inert host [BufferedImage] artifacts. Only an
 * exact-current projection generation may install source-neutral folds/inlays. A caret/selection in
 * an image construct reveals exact Markdown immediately. External navigation requires an explicit
 * modifier-left-click over a parser-derived link activation range and is re-authorized by the host
 * policy immediately before invoking the platform browser utility.
 */
internal class NativeHostResourcePresentationController(
    private val editor: Editor,
    private val documentPathProvider: () -> Path? = {
        FileDocumentManager.getInstance().getFile(editor.document)?.path?.let(Path::of)
    },
    private val imageResolver: (Path, String) -> NativeLocalImageResult = NativeLocalImageResolver::resolve,
    private val externalNavigator: (URI) -> Unit = { uri -> BrowserUtil.browse(uri.toString()) },
    private val richPresentationEnabled: () -> Boolean = { !ScreenReader.isActive() },
) : Disposable {
    private var currentPlanIdentity: ProjectionSourceIdentity? = null
    private var currentResources = emptyList<NativeHostResourceProjection>()
    private val decodedImages = LinkedHashMap<ResourceKey, NativeLocalImageArtifact>()
    private val ownedImages = LinkedHashMap<ResourceKey, OwnedImagePresentation>()
    private var disposed = false
    private var requestGeneration = 0L
    private var pendingImageLoads = 0
    private var staleImageResultsRejected = 0L
    private var imageFailures = 0L
    private var accessibilityFallbacks = 0L
    private var navigationAccepted = 0L
    private var navigationRejected = 0L

    private val mouseListener = object : EditorMouseListener {
        override fun mouseClicked(event: EditorMouseEvent) {
            handleExplicitNavigation(event.mouseEvent)
        }
    }

    init {
        ApplicationManager.getApplication().assertIsDispatchThread()
        editor.addEditorMouseListener(mouseListener)
    }

    fun applyPlan(plan: NativeProjectionPlan) {
        requireAlive()
        ApplicationManager.getApplication().assertIsDispatchThread()
        requestGeneration += 1
        pendingImageLoads = 0
        clearOwnedImages()
        decodedImages.clear()
        currentPlanIdentity = plan.identity
        currentResources = NativeHostResourceProjectionPlanner.plan(plan)
        if (plan.status != ProjectionPlanStatus.READY || currentResources.isEmpty()) return

        val imageProjections = currentResources.filter { it.kind == NativeHostResourceKind.LOCAL_IMAGE }
        if (imageProjections.isEmpty()) return
        if (!runCatching(richPresentationEnabled).getOrDefault(false)) {
            accessibilityFallbacks += imageProjections.size
            return
        }
        val documentPath = runCatching(documentPathProvider).getOrNull()
        if (documentPath == null) {
            imageFailures += imageProjections.size
            return
        }

        val generation = requestGeneration
        imageProjections.forEach { projection ->
            pendingImageLoads += 1
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = runCatching { imageResolver(documentPath, projection.target) }
                    .getOrElse { NativeLocalImageResult.Failure(com.algorist.markflow.trust.NativeLocalImageFailureCode.DECODE_FAILED) }
                ApplicationManager.getApplication().invokeLater {
                    onImageResult(generation, plan.identity, projection, result)
                }
            }
        }
    }

    fun refreshActivity(plan: NativeProjectionPlan) {
        if (disposed || editor.isDisposed) return
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (plan.identity != currentPlanIdentity || !matchesCurrentIdentity(plan.identity)) return

        currentResources
            .asSequence()
            .filter { it.kind == NativeHostResourceKind.LOCAL_IMAGE }
            .forEach { projection ->
                val key = ResourceKey.of(projection)
                if (isActive(projection)) {
                    removeOwnedImage(key)
                } else {
                    decodedImages[key]?.let { artifact -> installIfCurrent(plan.identity, projection, artifact) }
                }
            }
    }

    fun evidenceSnapshot(): NativeHostResourcePresentationEvidence = NativeHostResourcePresentationEvidence(
        planIdentity = currentPlanIdentity,
        localImages = currentResources.count { it.kind == NativeHostResourceKind.LOCAL_IMAGE },
        externalLinks = currentResources.count { it.kind == NativeHostResourceKind.EXTERNAL_LINK },
        pendingImageLoads = pendingImageLoads,
        decodedImages = decodedImages.size,
        ownedImageInlays = ownedImages.values.count { it.inlay.isValid },
        ownedImageFolds = ownedImages.values.count { it.fold.isValid },
        staleImageResultsRejected = staleImageResultsRejected,
        imageFailures = imageFailures,
        accessibilityFallbacks = accessibilityFallbacks,
        navigationAccepted = navigationAccepted,
        navigationRejected = navigationRejected,
    )

    internal fun activateExternalLinkAt(offset: Int, explicitUserGesture: Boolean): Boolean {
        if (disposed || editor.isDisposed || !explicitUserGesture) {
            navigationRejected += 1
            return false
        }
        val identity = currentPlanIdentity
        if (identity == null || !matchesCurrentIdentity(identity)) {
            navigationRejected += 1
            return false
        }
        val projection = currentResources.firstOrNull { candidate ->
            candidate.kind == NativeHostResourceKind.EXTERNAL_LINK &&
                candidate.activationRange?.containsStrict(offset) == true
        }
        if (projection == null) {
            navigationRejected += 1
            return false
        }
        val uri = ExternalNavigationPolicy.validateHttpUrl(projection.target)
        if (uri == null) {
            navigationRejected += 1
            return false
        }
        return try {
            externalNavigator(uri)
            navigationAccepted += 1
            true
        } catch (_: RuntimeException) {
            navigationRejected += 1
            false
        }
    }

    internal fun handleExplicitNavigation(mouse: MouseEvent) {
        if (disposed || editor.isDisposed) return
        if (mouse.button != MouseEvent.BUTTON1 || (!mouse.isControlDown && !mouse.isMetaDown)) return
        val offset = editor.logicalPositionToOffset(editor.xyToLogicalPosition(mouse.point))
        if (activateExternalLinkAt(offset, explicitUserGesture = true)) {
            mouse.consume()
        }
    }

    private fun onImageResult(
        generation: Long,
        planIdentity: ProjectionSourceIdentity,
        projection: NativeHostResourceProjection,
        result: NativeLocalImageResult,
    ) {
        if (disposed || editor.isDisposed) return
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (generation == requestGeneration && pendingImageLoads > 0) {
            pendingImageLoads -= 1
        }
        if (generation != requestGeneration || !isCurrent(planIdentity)) {
            staleImageResultsRejected += 1
            return
        }
        when (result) {
            is NativeLocalImageResult.Failure -> {
                imageFailures += 1
                return
            }

            is NativeLocalImageResult.Success -> {
                val key = ResourceKey.of(projection)
                decodedImages[key] = result.artifact
                if (!isActive(projection)) {
                    installIfCurrent(planIdentity, projection, result.artifact)
                }
            }
        }
    }

    private fun installIfCurrent(
        planIdentity: ProjectionSourceIdentity,
        projection: NativeHostResourceProjection,
        artifact: NativeLocalImageArtifact,
    ) {
        if (!isCurrent(planIdentity) || isActive(projection)) return
        val key = ResourceKey.of(projection)
        if (ownedImages[key]?.let { it.fold.isValid && it.inlay.isValid } == true) return
        removeOwnedImage(key)

        val document = editor.document
        val sourceBefore = document.immutableCharSequence.toString()
        val stampBefore = document.modificationStamp
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

        val inlay = editor.inlayModel.addBlockElement(
            projection.sourceRange.endOffset,
            true,
            true,
            0,
            NativeLocalImageInlayRenderer(editor, artifact.image),
        )
        if (inlay == null) {
            foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                if (installedFold.isValid) foldingModel.removeFoldRegion(installedFold)
            }
            return
        }
        ownedImages[key] = OwnedImagePresentation(installedFold, inlay)

        check(document.modificationStamp == stampBefore) {
            "native local-image presentation changed the authoritative Document modification stamp"
        }
        check(document.immutableCharSequence.toString() == sourceBefore) {
            "native local-image presentation changed the authoritative Document source"
        }
    }

    private fun isCurrent(identity: ProjectionSourceIdentity): Boolean =
        currentPlanIdentity == identity && matchesCurrentIdentity(identity)

    private fun matchesCurrentIdentity(identity: ProjectionSourceIdentity): Boolean =
        ProjectionSnapshot.capture(editor.document, identity.configGeneration).identity == identity

    private fun isActive(projection: NativeHostResourceProjection): Boolean {
        val range = projection.sourceRange
        return editor.caretModel.allCarets.any { caret ->
            (caret.offset >= range.startOffset && caret.offset <= range.endOffset) ||
                (caret.hasSelection() && range.intersects(caret.selectionStart, caret.selectionEnd))
        }
    }

    private fun clearOwnedImages() {
        ownedImages.keys.toList().forEach(::removeOwnedImage)
    }

    private fun removeOwnedImage(key: ResourceKey) {
        val presentation = ownedImages.remove(key) ?: return
        if (presentation.inlay.isValid) presentation.inlay.dispose()
        if (presentation.fold.isValid && !editor.isDisposed) {
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                if (presentation.fold.isValid) editor.foldingModel.removeFoldRegion(presentation.fold)
            }
        }
    }

    private fun requireAlive() {
        check(!disposed) { "native host-resource presentation controller is disposed" }
        check(!editor.isDisposed) { "native editor is disposed" }
    }

    override fun dispose() {
        if (disposed) return
        val application = ApplicationManager.getApplication()
        if (!application.isDispatchThread) {
            application.invokeAndWait { dispose() }
            return
        }
        requestGeneration += 1
        pendingImageLoads = 0
        editor.removeEditorMouseListener(mouseListener)
        clearOwnedImages()
        decodedImages.clear()
        currentResources = emptyList()
        currentPlanIdentity = null
        disposed = true
    }

    private data class ResourceKey(
        val startOffset: Int,
        val endOffset: Int,
        val target: String,
    ) {
        companion object {
            fun of(projection: NativeHostResourceProjection): ResourceKey = ResourceKey(
                projection.sourceRange.startOffset,
                projection.sourceRange.endOffset,
                projection.target,
            )
        }
    }

    private data class OwnedImagePresentation(
        val fold: FoldRegion,
        val inlay: Inlay<*>,
    )

    companion object {
        private const val ZERO_WIDTH_PLACEHOLDER = "\u200B"
    }
}

internal fun calculateNativeLocalImageDimensions(
    imageWidth: Int,
    imageHeight: Int,
    viewportWidth: Int,
): Pair<Int, Int> {
    require(imageWidth > 0 && imageHeight > 0)
    val availableWidth = max(1, viewportWidth)
    val scale = min(
        1.0,
        min(
            availableWidth.toDouble() / imageWidth.toDouble(),
            MAX_LOCAL_IMAGE_PRESENTATION_HEIGHT.toDouble() / imageHeight.toDouble(),
        )
    )
    return max(1, (imageWidth * scale).roundToInt()) to max(1, (imageHeight * scale).roundToInt())
}

private class NativeLocalImageInlayRenderer(
    private val editor: Editor,
    private val image: BufferedImage,
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
        val graphics2D = g as? Graphics2D
        val previousInterpolation = graphics2D?.getRenderingHint(RenderingHints.KEY_INTERPOLATION)
        graphics2D?.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(image, targetRegion.x, targetRegion.y, width, height, null)
        if (previousInterpolation != null) {
            graphics2D.setRenderingHint(RenderingHints.KEY_INTERPOLATION, previousInterpolation)
        }
    }

    private fun dimensions(): Pair<Int, Int> = calculateNativeLocalImageDimensions(
        imageWidth = image.width,
        imageHeight = image.height,
        viewportWidth = max(1, editor.scrollingModel.visibleArea.width - 24),
    )
}

private fun ProjectionRange.containsStrict(offset: Int): Boolean = offset >= startOffset && offset < endOffset

private const val MAX_LOCAL_IMAGE_PRESENTATION_HEIGHT = 1024
