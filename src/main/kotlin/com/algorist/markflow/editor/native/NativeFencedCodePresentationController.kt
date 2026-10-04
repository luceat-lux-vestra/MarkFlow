package com.algorist.markflow.editor.native

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.min

internal data class NativeFencedCodeModel(
    val sourceRange: ProjectionRange,
    val contentRange: ProjectionRange,
    val syntaxRanges: List<ProjectionRange>,
    val marker: Char,
    val fenceLength: Int,
    val info: String,
    val code: String,
)

internal data class NativeFencedCodePresentationEvidence(
    val models: Int,
    val ownedInlays: Int,
    val ownedFolds: Int,
    val fullyConcealed: Int,
    val infos: List<String>,
    val accessibilityFallbacks: Long,
    val mouseReveals: Long,
)

/**
 * Conservative parser-bounded ordinary fenced-code model for #324.
 *
 * Mermaid fences stay owned by the derived renderer. Unsupported, incomplete, ambiguous, empty,
 * or excessively large fences stay exact source rather than being reconstructed heuristically.
 */
internal object NativeFencedCodeProjectionPlanner {
    private const val MAX_CODE_CHARS = 64 * 1024
    private const val MAX_CODE_LINES = 200

    fun plan(plan: NativeProjectionPlan): List<NativeFencedCodeModel> {
        if (plan.status != ProjectionPlanStatus.READY) return emptyList()
        val source = plan.identity.source
        return plan.projections
            .asSequence()
            .filter { it.kind == NativeProjectionKind.CODE_FENCE }
            .mapNotNull { projection -> modelFor(projection, source) }
            .toList()
    }

    fun sourceRanges(basePlan: NativeProjectionPlan): List<ProjectionRange> =
        plan(basePlan).map(NativeFencedCodeModel::sourceRange)

    private fun modelFor(
        projection: NativeProjection,
        source: String,
    ): NativeFencedCodeModel? {
        val sourceRange = projection.sourceRange
        if (!sourceRange.isInside(source)) return null
        val syntax = projection.syntaxRanges.sortedBy(ProjectionRange::startOffset)
        if (syntax.size != 2) return null
        val opening = syntax[0]
        val closing = syntax[1]
        if (!opening.isInside(source) || !closing.isInside(source)) return null
        if (opening.startOffset != sourceRange.startOffset) return null
        if (opening.endOffset >= closing.startOffset) return null

        val openingMarker = source.substring(opening.startOffset, opening.endOffset)
        val closingMarker = source.substring(closing.startOffset, closing.endOffset)
        if (openingMarker.length < 3 || closingMarker.length < openingMarker.length) return null
        val marker = openingMarker.first()
        if (marker != '\u0060' && marker != '~') return null
        if (openingMarker.any { it != marker } || closingMarker.any { it != marker }) return null

        val openingLineEnd = source.indexOf('\n', opening.endOffset)
            .takeIf { it >= opening.endOffset && it < closing.startOffset }
            ?: return null
        val rawInfo = source.substring(opening.endOffset, openingLineEnd)
        val info = rawInfo.trim(' ', '\t', '\r')
        if (marker == '\u0060' && info.indexOf('\u0060') >= 0) return null
        if (info.equals("mermaid", ignoreCase = true)) return null

        val closingLineStart = source.lastIndexOf('\n', closing.startOffset - 1)
            .let { if (it < 0) sourceRange.startOffset else it + 1 }
        if (closingLineStart <= openingLineEnd) return null
        if (source.substring(closingLineStart, closing.startOffset).any { it != ' ' && it != '\t' }) return null

        val contentStart = openingLineEnd + 1
        val contentEnd = closingLineStart
        if (contentEnd <= contentStart) return null
        val contentRange = ProjectionRange(contentStart, contentEnd)
        if (!contentRange.isInside(source)) return null
        val code = source.substring(contentStart, contentEnd)
        if (code.isBlank() || code.length > MAX_CODE_CHARS) return null
        if (code.count { it == '\n' } + 1 > MAX_CODE_LINES) return null

        return NativeFencedCodeModel(
            sourceRange = sourceRange,
            contentRange = contentRange,
            syntaxRanges = syntax,
            marker = marker,
            fenceLength = openingMarker.length,
            info = info,
            code = code,
        )
    }
}

/**
 * Per-editor, source-neutral native block presentation for supported ordinary fenced code.
 *
 * Inactive fences are represented by a block inlay while exact source is concealed with
 * MarkFlow-owned folds. Caret/selection activity and direct inlay clicks remove that presentation
 * and expose the authoritative Document bytes. This owner never writes Markdown.
 */
internal class NativeFencedCodePresentationController(
    private val editor: Editor,
) : Disposable {
    private var currentPlanIdentity: ProjectionSourceIdentity? = null
    private var currentModels = emptyList<NativeFencedCodeModel>()
    private val owned = LinkedHashMap<FenceKey, OwnedFencePresentation>()
    private var accessibilityFallbacks = 0L
    private var mouseReveals = 0L
    private var disposed = false

    private val mouseListener = object : EditorMouseListener {
        override fun mouseClicked(event: EditorMouseEvent) {
            handleMouseReveal(event)
        }
    }

    init {
        ApplicationManager.getApplication().assertIsDispatchThread()
        editor.addEditorMouseListener(mouseListener)
    }

    fun applyPlan(plan: NativeProjectionPlan, richPresentationEnabled: Boolean) {
        requireAlive()
        ApplicationManager.getApplication().assertIsDispatchThread()
        clearOwned()
        currentPlanIdentity = plan.identity
        currentModels = NativeFencedCodeProjectionPlanner.plan(plan)
        if (plan.status != ProjectionPlanStatus.READY || currentModels.isEmpty()) return
        if (!richPresentationEnabled) {
            accessibilityFallbacks += currentModels.size
            return
        }
        currentModels.filterNot(::isActive).forEach { model -> installIfCurrent(plan.identity, model) }
    }

    fun refreshActivity(plan: NativeProjectionPlan, richPresentationEnabled: Boolean) {
        if (disposed || editor.isDisposed) return
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (plan.identity != currentPlanIdentity || !matchesCurrentIdentity(plan.identity)) return
        if (!richPresentationEnabled) {
            clearOwned()
            return
        }
        currentModels.forEach { model ->
            val key = FenceKey.of(model)
            if (isActive(model)) removeOwned(key) else installIfCurrent(plan.identity, model)
        }
    }

    fun clearPresentation() {
        if (disposed) return
        ApplicationManager.getApplication().assertIsDispatchThread()
        clearOwned()
        currentModels = emptyList()
        currentPlanIdentity = null
    }

    fun evidenceSnapshot(): NativeFencedCodePresentationEvidence =
        NativeFencedCodePresentationEvidence(
            models = currentModels.size,
            ownedInlays = owned.values.count { it.inlay.isValid },
            ownedFolds = owned.values.sumOf { presentation -> presentation.folds.count(FoldRegion::isValid) },
            fullyConcealed = owned.values.count(::isFullyConcealed),
            infos = currentModels.map(NativeFencedCodeModel::info),
            accessibilityFallbacks = accessibilityFallbacks,
            mouseReveals = mouseReveals,
        )

    internal fun handleMouseReveal(event: EditorMouseEvent): Boolean {
        if (disposed || editor.isDisposed) return false
        if (event.editor !== editor || event.area != EditorMouseEventArea.EDITING_AREA) return false
        if (event.mouseEvent.button != MouseEvent.BUTTON1) return false
        val renderer = event.inlay?.renderer as? NativeFencedCodeInlayRenderer ?: return false
        val key = FenceKey(renderer.sourceRange.startOffset, renderer.sourceRange.endOffset)
        val presentation = owned[key] ?: return false
        if (!presentation.inlay.isValid || presentation.inlay.renderer !== renderer) return false

        removeOwned(key)
        editor.selectionModel.removeSelection()
        editor.caretModel.primaryCaret.moveToOffset(renderer.contentOffset)
        mouseReveals += 1
        event.consume()
        return true
    }

    private fun installIfCurrent(identity: ProjectionSourceIdentity, model: NativeFencedCodeModel) {
        if (!isCurrent(identity) || isActive(model)) return
        val key = FenceKey.of(model)
        if (owned[key]?.let(::isFullyConcealed) == true) return
        removeOwned(key)

        val sourceBefore = editor.document.immutableCharSequence.toString()
        val stampBefore = editor.document.modificationStamp
        val foldRanges = fenceFoldRanges(model, sourceBefore) ?: return
        val installedFolds = mutableListOf<FoldRegion>()
        val coverageFolds = mutableListOf<FoldRegion>()
        var foldsInstalled = true

        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            foldRanges.forEach { range ->
                if (!foldsInstalled) return@forEach

                val collapsedForeignSyntaxFold = editor.foldingModel.allFoldRegions.firstOrNull { fold ->
                    fold.isValid &&
                        !fold.isExpanded &&
                        fold.startOffset <= range.startOffset &&
                        fold.endOffset >= range.endOffset &&
                        model.syntaxRanges.any { syntax ->
                            range.startOffset >= syntax.startOffset && range.endOffset <= syntax.endOffset
                        }
                }
                if (collapsedForeignSyntaxFold != null) {
                    coverageFolds += collapsedForeignSyntaxFold
                    return@forEach
                }

                val blockingForeignFold = editor.foldingModel.allFoldRegions.any { fold ->
                    if (!fold.isValid || fold.startOffset >= range.endOffset || fold.endOffset <= range.startOffset) {
                        return@any false
                    }
                    val containsRange =
                        fold.startOffset <= range.startOffset && fold.endOffset >= range.endOffset
                    val exactRange =
                        fold.startOffset == range.startOffset && fold.endOffset == range.endOffset
                    !fold.isExpanded || !containsRange || exactRange
                }
                if (blockingForeignFold) {
                    foldsInstalled = false
                    return@forEach
                }

                val fold = editor.foldingModel.addFoldRegion(
                    range.startOffset,
                    range.endOffset,
                    ZERO_WIDTH_PLACEHOLDER,
                )
                if (fold == null) {
                    foldsInstalled = false
                    return@forEach
                }
                installedFolds += fold
                coverageFolds += fold
                fold.isExpanded = false
                if (fold.isExpanded) foldsInstalled = false
            }

            if (!foldsInstalled) {
                installedFolds.asReversed().forEach { fold ->
                    if (fold.isValid) editor.foldingModel.removeFoldRegion(fold)
                }
                installedFolds.clear()
                coverageFolds.clear()
            }
        }
        if (!foldsInstalled || coverageFolds.size != foldRanges.size) return

        val inlay = editor.inlayModel.addBlockElement(
            model.sourceRange.endOffset,
            true,
            true,
            0,
            NativeFencedCodeInlayRenderer(editor, model),
        )
        if (inlay == null) {
            removeFolds(installedFolds)
            return
        }
        owned[key] = OwnedFencePresentation(installedFolds.toList(), coverageFolds.toList(), inlay)

        check(editor.document.modificationStamp == stampBefore) {
            "native fenced-code presentation changed the authoritative Document modification stamp"
        }
        check(editor.document.immutableCharSequence.toString() == sourceBefore) {
            "native fenced-code presentation changed the authoritative Document source"
        }
    }

    private fun fenceFoldRanges(
        model: NativeFencedCodeModel,
        source: String,
    ): List<ProjectionRange>? {
        val range = model.sourceRange
        if (!range.isInside(source)) return null
        if (source.codePointCount(range.startOffset, range.endOffset) < 2) return null

        val boundaries = linkedSetOf(range.startOffset, range.endOffset)
        val tailStart = Character.offsetByCodePoints(source, range.endOffset, -1)
        if (tailStart > range.startOffset && tailStart < range.endOffset) boundaries += tailStart
        model.syntaxRanges.forEach { syntax ->
            if (syntax.startOffset > range.startOffset && syntax.startOffset < range.endOffset) boundaries += syntax.startOffset
            if (syntax.endOffset > range.startOffset && syntax.endOffset < range.endOffset) boundaries += syntax.endOffset
        }
        editor.foldingModel.allFoldRegions
            .asSequence()
            .filter(FoldRegion::isValid)
            .filter { fold -> fold.startOffset < range.endOffset && fold.endOffset > range.startOffset }
            .forEach { fold ->
                if (fold.startOffset > range.startOffset && fold.startOffset < range.endOffset) boundaries += fold.startOffset
                if (fold.endOffset > range.startOffset && fold.endOffset < range.endOffset) boundaries += fold.endOffset
            }

        return boundaries
            .sorted()
            .zipWithNext()
            .mapNotNull { (start, end) -> if (end > start) ProjectionRange(start, end) else null }
    }

    private fun isFullyConcealed(presentation: OwnedFencePresentation): Boolean =
        presentation.inlay.isValid &&
            presentation.coverageFolds.isNotEmpty() &&
            presentation.coverageFolds.all { fold -> fold.isValid && !fold.isExpanded }

    private fun isCurrent(identity: ProjectionSourceIdentity): Boolean =
        currentPlanIdentity == identity && matchesCurrentIdentity(identity)

    private fun matchesCurrentIdentity(identity: ProjectionSourceIdentity): Boolean =
        ProjectionSnapshot.capture(editor.document, identity.configGeneration).identity == identity

    private fun isActive(model: NativeFencedCodeModel): Boolean {
        val range = model.sourceRange
        return editor.caretModel.allCarets.any { caret ->
            range.touches(caret.offset) ||
                (caret.hasSelection() && range.intersects(caret.selectionStart, caret.selectionEnd))
        }
    }

    private fun clearOwned() {
        owned.keys.toList().forEach(::removeOwned)
    }

    private fun removeOwned(key: FenceKey) {
        val presentation = owned.remove(key) ?: return
        if (presentation.inlay.isValid) presentation.inlay.dispose()
        removeFolds(presentation.folds)
    }

    private fun removeFolds(folds: List<FoldRegion>) {
        if (editor.isDisposed || folds.none(FoldRegion::isValid)) return
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            folds.asReversed().forEach { fold ->
                if (fold.isValid) editor.foldingModel.removeFoldRegion(fold)
            }
        }
    }

    private fun requireAlive() {
        check(!disposed) { "native fenced-code presentation controller is disposed" }
        check(!editor.isDisposed) { "native editor is disposed" }
    }

    override fun dispose() {
        if (disposed) return
        val application = ApplicationManager.getApplication()
        if (!application.isDispatchThread) {
            application.invokeAndWait { dispose() }
            return
        }
        editor.removeEditorMouseListener(mouseListener)
        clearOwned()
        currentModels = emptyList()
        currentPlanIdentity = null
        disposed = true
    }

    private data class FenceKey(val startOffset: Int, val endOffset: Int) {
        companion object {
            fun of(model: NativeFencedCodeModel) =
                FenceKey(model.sourceRange.startOffset, model.sourceRange.endOffset)
        }
    }

    private data class OwnedFencePresentation(
        val folds: List<FoldRegion>,
        val coverageFolds: List<FoldRegion>,
        val inlay: Inlay<*>,
    )

    companion object {
        private const val ZERO_WIDTH_PLACEHOLDER = "\u200B"
    }
}

internal class NativeFencedCodeInlayRenderer(
    private val editor: Editor,
    private val model: NativeFencedCodeModel,
) : EditorCustomElementRenderer {
    val sourceRange: ProjectionRange
        get() = model.sourceRange

    val contentOffset: Int
        get() = model.contentRange.startOffset

    val displayInfo: String
        get() = model.info

    val displayCode: String
        get() = model.code

    private val lines: List<String>
        get() = model.code
            .removeSuffix("\n")
            .split('\n')
            .map { line -> line.removeSuffix("\r") }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int =
        max(MIN_WIDTH, editor.scrollingModel.visibleArea.width - OUTER_MARGIN * 2)

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        val metrics = editor.contentComponent.getFontMetrics(editor.contentComponent.font)
        val header = if (model.info.isBlank()) 0 else metrics.height + HEADER_GAP
        return VERTICAL_PADDING * 2 + header + max(1, lines.size) * max(editor.lineHeight, metrics.height)
    }

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) {
        val g2 = g as? Graphics2D ?: return
        val foreground = textAttributes.foregroundColor ?: editor.colorsScheme.defaultForeground
        val background = Color(foreground.red, foreground.green, foreground.blue, BACKGROUND_ALPHA)
        val font = editor.contentComponent.font
        val metrics = g2.getFontMetrics(font)
        val lineHeight = max(editor.lineHeight, metrics.height)
        val left = targetRegion.x + OUTER_MARGIN
        val width = max(1, targetRegion.width - OUTER_MARGIN * 2)

        g2.color = background
        g2.fillRoundRect(left, targetRegion.y, width, targetRegion.height, ARC, ARC)

        var y = targetRegion.y + VERTICAL_PADDING
        if (model.info.isNotBlank()) {
            val infoFont = font.deriveFont(Font.BOLD, max(9f, font.size2D * 0.9f))
            g2.font = infoFont
            g2.color = Color(foreground.red, foreground.green, foreground.blue, INFO_ALPHA)
            val infoMetrics = g2.getFontMetrics(infoFont)
            g2.drawString(clip(model.info, infoMetrics, width - HORIZONTAL_PADDING * 2), left + HORIZONTAL_PADDING, y + infoMetrics.ascent)
            y += infoMetrics.height + HEADER_GAP
        }

        g2.font = font
        g2.color = foreground
        val available = max(1, width - HORIZONTAL_PADDING * 2)
        lines.forEach { line ->
            g2.drawString(clip(line, metrics, available), left + HORIZONTAL_PADDING, y + metrics.ascent)
            y += lineHeight
        }
    }

    private fun clip(text: String, metrics: java.awt.FontMetrics, maxWidth: Int): String {
        if (metrics.stringWidth(text) <= maxWidth) return text
        val suffix = "..."
        val target = max(0, maxWidth - metrics.stringWidth(suffix))

        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (metrics.stringWidth(text.substring(0, mid)) <= target) {
                low = mid
            } else {
                high = mid - 1
            }
        }

        var end = low
        if (
            end in 1 until text.length &&
            Character.isHighSurrogate(text[end - 1]) &&
            Character.isLowSurrogate(text[end])
        ) {
            end -= 1
        }
        return text.substring(0, end) + suffix
    }

    companion object {
        private const val MIN_WIDTH = 180
        private const val OUTER_MARGIN = 6
        private const val HORIZONTAL_PADDING = 10
        private const val VERTICAL_PADDING = 8
        private const val HEADER_GAP = 4
        private const val ARC = 8
        private const val BACKGROUND_ALPHA = 18
        private const val INFO_ALPHA = 150
    }
}
