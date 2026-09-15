package com.algorist.markflow.editor.native

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class NativeRawHtmlPresentationEvidence(
    val planIdentity: ProjectionSourceIdentity?,
    val fragments: Int,
    val pendingRequests: Int,
    val decodedArtifacts: Int,
    val ownedInlays: Int,
    val ownedFolds: Int,
    val blockedPreviews: Long,
    val rendererFailures: Long,
    val staleResultsRejected: Long,
)

/**
 * Per-editor #149 owner for sanitized raw-HTML derived presentation.
 *
 * The controller owns only derived fold/inlay state. It never writes the Document, never persists a
 * second HTML source model and never grants renderer output navigation/resource authority. A source
 * mutation invalidates all owned presentation immediately; the next authoritative projection plan
 * may then be applied by the parent native presentation owner.
 */
internal class NativeRawHtmlPresentationController(
    private val editor: Editor,
    private val renderer: NativeRawHtmlRenderer = NativeSwingRawHtmlRenderer,
) : Disposable {
    private val pending = LinkedHashMap<Long, PendingRequest>()
    private val artifacts = LinkedHashMap<ProjectionKey, BufferedImage>()
    private val owned = LinkedHashMap<ProjectionKey, OwnedPresentation>()
    private var currentIdentity: ProjectionSourceIdentity? = null
    private var currentProjections = emptyList<NativeRawHtmlProjection>()
    private var disposed = false
    private var generation = 0L
    private var blockedPreviews = 0L
    private var rendererFailures = 0L
    private var staleResultsRejected = 0L

    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            invalidateForSourceChange()
        }
    }

    private val caretListener = object : CaretListener {
        override fun caretPositionChanged(event: CaretEvent) = reconcileActivity()
        override fun caretAdded(event: CaretEvent) = reconcileActivity()
        override fun caretRemoved(event: CaretEvent) = reconcileActivity()
    }

    private val selectionListener = object : SelectionListener {
        override fun selectionChanged(event: SelectionEvent) = reconcileActivity()
    }

    init {
        ApplicationManager.getApplication().assertIsDispatchThread()
        editor.document.addDocumentListener(documentListener, this)
        editor.caretModel.addCaretListener(caretListener, this)
        editor.selectionModel.addSelectionListener(selectionListener, this)
    }

    fun applyPlan(plan: NativeProjectionPlan) {
        requireAlive()
        ApplicationManager.getApplication().assertIsDispatchThread()
        check(matchesDocument(plan.identity)) { "raw HTML plan is stale before application" }

        invalidatePendingGeneration(countAsStale = false)
        clearOwnedPresentation()
        artifacts.clear()
        currentIdentity = plan.identity
        currentProjections = NativeRawHtmlProjectionPlanner.plan(plan)

        if (plan.status != ProjectionPlanStatus.READY || currentProjections.isEmpty()) return
        currentProjections.forEach { projection -> request(plan.identity, projection) }
    }

    fun evidenceSnapshot(): NativeRawHtmlPresentationEvidence = NativeRawHtmlPresentationEvidence(
        planIdentity = currentIdentity,
        fragments = currentProjections.size,
        pendingRequests = pending.size,
        decodedArtifacts = artifacts.size,
        ownedInlays = owned.values.count { it.inlay.isValid },
        ownedFolds = owned.values.count { it.fold.isValid },
        blockedPreviews = blockedPreviews,
        rendererFailures = rendererFailures,
        staleResultsRejected = staleResultsRejected,
    )

    private fun request(identity: ProjectionSourceIdentity, projection: NativeRawHtmlProjection) {
        val requestId = REQUEST_SEQUENCE.incrementAndGet()
        val request = PendingRequest(
            generation = generation,
            identity = identity,
            projection = projection,
        )
        pending[requestId] = request
        renderer.render(projection.source) { result ->
            onEdt { onRenderResult(requestId, result) }
        }
    }

    private fun onRenderResult(requestId: Long, result: NativeRawHtmlRenderResult) {
        val expected = pending.remove(requestId) ?: return
        if (disposed || editor.isDisposed) return
        if (
            expected.generation != generation ||
            currentIdentity != expected.identity ||
            !matchesDocument(expected.identity)
        ) {
            staleResultsRejected += 1
            return
        }

        when (result) {
            is NativeRawHtmlRenderResult.Blocked -> {
                blockedPreviews += 1
                return
            }
            is NativeRawHtmlRenderResult.Failure -> {
                rendererFailures += 1
                return
            }
            is NativeRawHtmlRenderResult.Success -> {
                val image = result.image
                if (
                    image.width <= 0 || image.height <= 0 ||
                    image.width > NativeSwingRawHtmlRenderer.MAX_WIDTH ||
                    image.height > NativeSwingRawHtmlRenderer.MAX_HEIGHT ||
                    image.width.toLong() * image.height.toLong() > NativeSwingRawHtmlRenderer.MAX_PIXELS
                ) {
                    rendererFailures += 1
                    return
                }
                val key = ProjectionKey.of(expected.projection)
                artifacts[key] = image
                if (!isActive(expected.projection)) {
                    installIfCurrent(expected.identity, expected.projection, image)
                }
            }
        }
    }

    private fun installIfCurrent(
        identity: ProjectionSourceIdentity,
        projection: NativeRawHtmlProjection,
        image: BufferedImage,
    ) {
        if (!isCurrent(identity) || isActive(projection)) return
        val key = ProjectionKey.of(projection)
        if (owned[key]?.let { it.fold.isValid && it.inlay.isValid } == true) return
        removeOwned(key)

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

        val inlayRenderer = NativeRawHtmlRasterInlayRenderer(editor, projection.block, image)
        val inlay = if (projection.block) {
            editor.inlayModel.addBlockElement(
                projection.sourceRange.endOffset,
                true,
                true,
                0,
                inlayRenderer,
            )
        } else {
            editor.inlayModel.addInlineElement(
                projection.sourceRange.endOffset,
                true,
                inlayRenderer,
            )
        }
        if (inlay == null) {
            foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                if (installedFold.isValid) foldingModel.removeFoldRegion(installedFold)
            }
            return
        }
        owned[key] = OwnedPresentation(installedFold, inlay)

        check(document.modificationStamp == stampBefore) {
            "raw HTML derived presentation changed the authoritative Document modification stamp"
        }
        check(document.immutableCharSequence.toString() == sourceBefore) {
            "raw HTML derived presentation changed the authoritative Document source"
        }
    }

    private fun reconcileActivity() {
        if (disposed || editor.isDisposed) return
        ApplicationManager.getApplication().assertIsDispatchThread()
        val identity = currentIdentity ?: return
        if (!isCurrent(identity)) {
            invalidateForSourceChange()
            return
        }

        currentProjections.forEach { projection ->
            val key = ProjectionKey.of(projection)
            if (isActive(projection)) {
                removeOwned(key)
            } else {
                artifacts[key]?.let { image -> installIfCurrent(identity, projection, image) }
            }
        }
    }

    private fun isActive(projection: NativeRawHtmlProjection): Boolean {
        val range = projection.sourceRange
        return editor.caretModel.allCarets.any { caret ->
            range.touches(caret.offset) ||
                (caret.hasSelection() && range.intersects(caret.selectionStart, caret.selectionEnd))
        }
    }

    private fun invalidateForSourceChange() {
        if (disposed || editor.isDisposed) return
        ApplicationManager.getApplication().assertIsDispatchThread()
        invalidatePendingGeneration(countAsStale = true)
        clearOwnedPresentation()
        artifacts.clear()
        currentProjections = emptyList()
        currentIdentity = null
    }

    private fun invalidatePendingGeneration(countAsStale: Boolean) {
        generation += 1
        if (countAsStale) staleResultsRejected += pending.size.toLong()
        pending.clear()
    }

    private fun isCurrent(identity: ProjectionSourceIdentity): Boolean =
        currentIdentity == identity && matchesDocument(identity)

    private fun matchesDocument(identity: ProjectionSourceIdentity): Boolean =
        ProjectionSnapshot.capture(editor.document, identity.configGeneration).identity == identity

    private fun clearOwnedPresentation() {
        owned.keys.toList().forEach(::removeOwned)
    }

    private fun removeOwned(key: ProjectionKey) {
        val presentation = owned.remove(key) ?: return
        if (presentation.inlay.isValid) presentation.inlay.dispose()
        if (presentation.fold.isValid && !editor.isDisposed) {
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                if (presentation.fold.isValid) editor.foldingModel.removeFoldRegion(presentation.fold)
            }
        }
    }

    private fun requireAlive() {
        check(!disposed) { "raw HTML presentation controller is disposed" }
        check(!editor.isDisposed) { "native editor is disposed" }
    }

    override fun dispose() {
        if (disposed) return
        val application = ApplicationManager.getApplication()
        if (!application.isDispatchThread) {
            application.invokeAndWait { dispose() }
            return
        }
        invalidatePendingGeneration(countAsStale = false)
        clearOwnedPresentation()
        artifacts.clear()
        currentProjections = emptyList()
        currentIdentity = null
        disposed = true
    }

    private fun onEdt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) action() else application.invokeLater(action)
    }

    private data class PendingRequest(
        val generation: Long,
        val identity: ProjectionSourceIdentity,
        val projection: NativeRawHtmlProjection,
    )

    private data class ProjectionKey(
        val kind: NativeRawHtmlProjectionKind,
        val startOffset: Int,
        val endOffset: Int,
    ) {
        companion object {
            fun of(projection: NativeRawHtmlProjection) = ProjectionKey(
                projection.kind,
                projection.sourceRange.startOffset,
                projection.sourceRange.endOffset,
            )
        }
    }

    private data class OwnedPresentation(
        val fold: FoldRegion,
        val inlay: Inlay<*>,
    )

    companion object {
        private const val ZERO_WIDTH_PLACEHOLDER = "\u200B"
        private val REQUEST_SEQUENCE = AtomicLong(0)
    }
}

private class NativeRawHtmlRasterInlayRenderer(
    private val editor: Editor,
    private val block: Boolean,
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
        val viewportWidth = max(1, editor.scrollingModel.visibleArea.width - 24)
        val maxHeight = if (block) 1024 else max(1, editor.lineHeight)
        val widthScale = if (block) min(1.0, viewportWidth.toDouble() / image.width.toDouble()) else 1.0
        val heightScale = min(1.0, maxHeight.toDouble() / image.height.toDouble())
        val scale = min(widthScale, heightScale)
        return max(1, (image.width * scale).roundToInt()) to max(1, (image.height * scale).roundToInt())
    }
}
