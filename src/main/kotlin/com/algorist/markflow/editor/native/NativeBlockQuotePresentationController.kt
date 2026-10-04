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
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.util.LinkedHashMap
import kotlin.math.max

internal data class NativeBlockQuoteModel(
    val sourceRange: ProjectionRange,
    val contentRange: ProjectionRange,
    val text: String,
)

internal data class NativeBlockQuotePresentationEvidence(
    val models: Int,
    val ownedInlays: Int,
    val ownedFolds: Int,
    val fullyConcealed: Int,
    val accessibilityFallbacks: Long,
    val mouseReveals: Long,
)

/**
 * Conservative parser-bounded blockquote model for corrective #316.
 *
 * This slice intentionally accepts only one-level, single-line plain-text quotes. Multiline,
 * nested, and inline-rich quotes remain exact source until a richer retained renderer exists.
 */
internal object NativeBlockQuoteProjectionPlanner {
    private val unsupportedNestedKinds = setOf(
        NativeProjectionKind.HEADING,
        NativeProjectionKind.EMPHASIS,
        NativeProjectionKind.STRONG,
        NativeProjectionKind.LINK,
        NativeProjectionKind.UNORDERED_LIST,
        NativeProjectionKind.ORDERED_LIST,
        NativeProjectionKind.LIST_ITEM,
        NativeProjectionKind.BLOCK_QUOTE,
        NativeProjectionKind.INLINE_CODE,
        NativeProjectionKind.CODE_FENCE,
        NativeProjectionKind.CODE_BLOCK,
        NativeProjectionKind.THEMATIC_BREAK,
        NativeProjectionKind.TABLE,
        NativeProjectionKind.TABLE_HEADER,
        NativeProjectionKind.TABLE_ROW,
    )

    fun plan(plan: NativeProjectionPlan): List<NativeBlockQuoteModel> {
        if (plan.status != ProjectionPlanStatus.READY) return emptyList()
        val source = plan.identity.source
        val projections = plan.projections
        return projections
            .asSequence()
            .filter { it.kind == NativeProjectionKind.BLOCK_QUOTE }
            .mapNotNull { quote ->
                val marker = quote.syntaxRanges.singleOrNull() ?: return@mapNotNull null
                if (marker.startOffset != quote.sourceRange.startOffset) return@mapNotNull null
                if (!marker.isInside(source) || !quote.sourceRange.isInside(source)) return@mapNotNull null

                val hasUnsupportedNestedProjection = projections.any { candidate ->
                    candidate !== quote &&
                        candidate.kind in unsupportedNestedKinds &&
                        candidate.sourceRange.startOffset >= quote.sourceRange.startOffset &&
                        candidate.sourceRange.endOffset <= quote.sourceRange.endOffset
                }
                if (hasUnsupportedNestedProjection) return@mapNotNull null

                val hasContiguousQuoteSibling = projections.any { candidate ->
                    candidate !== quote &&
                        candidate.kind == NativeProjectionKind.BLOCK_QUOTE &&
                        quote.sourceRange.isContiguousWith(candidate.sourceRange, source)
                }
                if (hasContiguousQuoteSibling) return@mapNotNull null

                var start = marker.endOffset
                val endBound = quote.sourceRange.endOffset
                while (start < endBound && (source[start] == ' ' || source[start] == '\t')) start += 1

                var end = endBound
                while (
                    end > start &&
                    (source[end - 1] == ' ' || source[end - 1] == '\t' || source[end - 1] == '\n' || source[end - 1] == '\r')
                ) {
                    end -= 1
                }
                if (end <= start) return@mapNotNull null

                val contentRange = ProjectionRange(start, end)
                val text = source.substring(start, end)
                if (
                    text.isBlank() ||
                    text.contains('\n') ||
                    text.contains('\r') ||
                    text.any(::isPresentationSensitiveInlineChar)
                ) {
                    return@mapNotNull null
                }

                NativeBlockQuoteModel(
                    sourceRange = quote.sourceRange,
                    contentRange = contentRange,
                    text = text,
                )
            }
            .toList()
    }

    fun sourceRanges(plan: NativeProjectionPlan): List<ProjectionRange> =
        plan.projections
            .asSequence()
            .filter { it.kind == NativeProjectionKind.BLOCK_QUOTE }
            .map { it.sourceRange }
            .toList()

    private fun ProjectionRange.isContiguousWith(other: ProjectionRange, source: String): Boolean {
        val left = if (startOffset <= other.startOffset) this else other
        val right = if (left === this) other else this
        if (left.endOffset > right.startOffset) return true
        val gap = source.substring(left.endOffset, right.startOffset)
        return gap.isEmpty() || gap == "\n" || gap == "\r\n"
    }

    private fun isPresentationSensitiveInlineChar(char: Char): Boolean =
        char == '\\' ||
            char == '*' ||
            char == '_' ||
            char == '[' ||
            char == ']' ||
            char == '`' ||
            char == '<' ||
            char == '>' ||
            char == '&' ||
            char == '~'
}

/**
 * Native rich presentation for one editor's supported inactive blockquotes.
 *
 * The authoritative Markdown is never rewritten. Rich presentation is installed only when the
 * exact parser-bounded quote range can be source-neutrally concealed without colliding with an
 * existing fold. Active/selected quotes remove MarkFlow-owned presentation and reveal source.
 */
internal class NativeBlockQuotePresentationController(
    private val editor: Editor,
) : Disposable {
    private var currentPlanIdentity: ProjectionSourceIdentity? = null
    private var currentModels = emptyList<NativeBlockQuoteModel>()
    private val owned = LinkedHashMap<BlockQuoteKey, OwnedBlockQuotePresentation>()
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
        currentModels = NativeBlockQuoteProjectionPlanner.plan(plan)
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
            val key = BlockQuoteKey.of(model)
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

    fun evidenceSnapshot(): NativeBlockQuotePresentationEvidence = NativeBlockQuotePresentationEvidence(
        models = currentModels.size,
        ownedInlays = owned.values.count { it.inlay.isValid },
        ownedFolds = owned.values.sumOf { presentation -> presentation.folds.count(FoldRegion::isValid) },
        fullyConcealed = owned.values.count(::isFullyConcealed),
        accessibilityFallbacks = accessibilityFallbacks,
        mouseReveals = mouseReveals,
    )

    internal fun handleMouseReveal(event: EditorMouseEvent): Boolean {
        if (disposed || editor.isDisposed) return false
        if (event.editor !== editor || event.area != EditorMouseEventArea.EDITING_AREA) return false
        if (event.mouseEvent.button != MouseEvent.BUTTON1) return false
        val renderer = event.inlay?.renderer as? NativeBlockQuoteInlayRenderer ?: return false
        val key = BlockQuoteKey(renderer.sourceRange.startOffset, renderer.sourceRange.endOffset)
        val presentation = owned[key] ?: return false
        if (!presentation.inlay.isValid || presentation.inlay.renderer !== renderer) return false

        removeOwned(key)
        editor.selectionModel.removeSelection()
        editor.caretModel.primaryCaret.moveToOffset(renderer.contentOffset)
        mouseReveals += 1
        event.consume()
        return true
    }

    private fun installIfCurrent(identity: ProjectionSourceIdentity, model: NativeBlockQuoteModel) {
        if (!isCurrent(identity) || isActive(model)) return
        val key = BlockQuoteKey.of(model)
        if (owned[key]?.let(::isFullyConcealed) == true) return
        removeOwned(key)

        val sourceBefore = editor.document.immutableCharSequence.toString()
        val stampBefore = editor.document.modificationStamp
        if (editor.foldingModel.allFoldRegions.any { fold ->
                fold.isValid &&
                    fold.startOffset < model.sourceRange.endOffset &&
                    fold.endOffset > model.sourceRange.startOffset
            }
        ) {
            return
        }

        val foldRanges = blockQuoteFoldRanges(model, sourceBefore) ?: return
        val installedFolds = mutableListOf<FoldRegion>()
        var foldsInstalled = true
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            foldRanges.forEach { range ->
                if (!foldsInstalled) return@forEach
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
                fold.isExpanded = false
                if (fold.isExpanded) foldsInstalled = false
            }
            if (!foldsInstalled) {
                installedFolds.asReversed().forEach { fold ->
                    if (fold.isValid) editor.foldingModel.removeFoldRegion(fold)
                }
                installedFolds.clear()
            }
        }
        if (!foldsInstalled || installedFolds.size != foldRanges.size) return

        val renderer = NativeBlockQuoteInlayRenderer(editor, model)
        val inlay = editor.inlayModel.addBlockElement(
            model.sourceRange.endOffset,
            true,
            true,
            0,
            renderer,
        )
        if (inlay == null) {
            removeFolds(installedFolds)
            return
        }
        owned[key] = OwnedBlockQuotePresentation(installedFolds.toList(), inlay)

        check(editor.document.modificationStamp == stampBefore) {
            "native blockquote presentation changed the authoritative Document modification stamp"
        }
        check(editor.document.immutableCharSequence.toString() == sourceBefore) {
            "native blockquote presentation changed the authoritative Document source"
        }
    }

    private fun blockQuoteFoldRanges(
        model: NativeBlockQuoteModel,
        source: String,
    ): List<ProjectionRange>? {
        val range = model.sourceRange
        if (!range.isInside(source)) return null
        val codePoints = source.codePointCount(range.startOffset, range.endOffset)
        if (codePoints < 2) return null
        val tailStart = Character.offsetByCodePoints(source, range.endOffset, -1)
        if (tailStart <= range.startOffset || tailStart >= range.endOffset) return null
        return listOf(
            ProjectionRange(range.startOffset, tailStart),
            ProjectionRange(tailStart, range.endOffset),
        )
    }

    private fun isFullyConcealed(presentation: OwnedBlockQuotePresentation): Boolean =
        presentation.inlay.isValid &&
            presentation.folds.isNotEmpty() &&
            presentation.folds.all { fold -> fold.isValid && !fold.isExpanded }

    private fun isCurrent(identity: ProjectionSourceIdentity): Boolean =
        currentPlanIdentity == identity && matchesCurrentIdentity(identity)

    private fun matchesCurrentIdentity(identity: ProjectionSourceIdentity): Boolean =
        ProjectionSnapshot.capture(editor.document, identity.configGeneration).identity == identity

    private fun isActive(model: NativeBlockQuoteModel): Boolean {
        val range = model.sourceRange
        return editor.caretModel.allCarets.any { caret ->
            range.touches(caret.offset) ||
                (caret.hasSelection() && range.intersects(caret.selectionStart, caret.selectionEnd))
        }
    }

    private fun clearOwned() {
        owned.keys.toList().forEach(::removeOwned)
    }

    private fun removeOwned(key: BlockQuoteKey) {
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
        check(!disposed) { "native blockquote presentation controller is disposed" }
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

    private data class BlockQuoteKey(
        val startOffset: Int,
        val endOffset: Int,
    ) {
        companion object {
            fun of(model: NativeBlockQuoteModel) =
                BlockQuoteKey(model.sourceRange.startOffset, model.sourceRange.endOffset)
        }
    }

    private data class OwnedBlockQuotePresentation(
        val folds: List<FoldRegion>,
        val inlay: Inlay<*>,
    )

    companion object {
        private const val ZERO_WIDTH_PLACEHOLDER = "\u200B"
    }
}

internal class NativeBlockQuoteInlayRenderer(
    private val editor: Editor,
    private val model: NativeBlockQuoteModel,
) : EditorCustomElementRenderer {
    val sourceRange: ProjectionRange
        get() = model.sourceRange

    val contentOffset: Int
        get() = model.contentRange.startOffset

    val displayText: String
        get() = model.text

    override fun calcWidthInPixels(inlay: Inlay<*>): Int =
        max(MIN_WIDTH, editor.scrollingModel.visibleArea.width - OUTER_HORIZONTAL_MARGIN * 2)

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        val metrics = editor.contentComponent.getFontMetrics(editor.contentComponent.font)
        return max(editor.lineHeight + VERTICAL_PADDING * 2, metrics.height + VERTICAL_PADDING * 2)
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
        val bar = Color(foreground.red, foreground.green, foreground.blue, BAR_ALPHA)
        val font = editor.contentComponent.font
        val metrics = g2.getFontMetrics(font)
        val textX = targetRegion.x + OUTER_HORIZONTAL_MARGIN + BAR_LEFT_PADDING + BAR_WIDTH + CONTENT_PADDING
        val availableTextWidth = max(1, targetRegion.x + targetRegion.width - OUTER_HORIZONTAL_MARGIN - textX)
        val rendered = clip(model.text, metrics, availableTextWidth)

        g2.color = background
        g2.fillRoundRect(
            targetRegion.x + OUTER_HORIZONTAL_MARGIN,
            targetRegion.y,
            max(1, targetRegion.width - OUTER_HORIZONTAL_MARGIN * 2),
            targetRegion.height,
            ARC,
            ARC,
        )
        g2.color = bar
        g2.fillRect(
            targetRegion.x + OUTER_HORIZONTAL_MARGIN + BAR_LEFT_PADDING,
            targetRegion.y + VERTICAL_PADDING,
            BAR_WIDTH,
            max(1, targetRegion.height - VERTICAL_PADDING * 2),
        )
        g2.font = font
        g2.color = foreground
        val baseline = targetRegion.y +
            max(metrics.ascent + VERTICAL_PADDING, (targetRegion.height + metrics.ascent - metrics.descent) / 2)
        g2.drawString(rendered, textX, baseline)
    }

    private fun clip(text: String, metrics: java.awt.FontMetrics, maxWidth: Int): String {
        if (metrics.stringWidth(text) <= maxWidth) return text
        val ellipsis = "…"
        val target = max(0, maxWidth - metrics.stringWidth(ellipsis))
        var end = text.length
        while (end > 0 && metrics.stringWidth(text.substring(0, end)) > target) end -= 1
        return text.substring(0, end) + ellipsis
    }

    companion object {
        private const val MIN_WIDTH = 160
        private const val OUTER_HORIZONTAL_MARGIN = 4
        private const val BAR_LEFT_PADDING = 4
        private const val BAR_WIDTH = 3
        private const val CONTENT_PADDING = 10
        private const val VERTICAL_PADDING = 6
        private const val ARC = 8
        private const val BACKGROUND_ALPHA = 20
        private const val BAR_ALPHA = 150
    }
}
