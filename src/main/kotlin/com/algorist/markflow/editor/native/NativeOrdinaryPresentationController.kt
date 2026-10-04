package com.algorist.markflow.editor.native

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import java.util.LinkedHashMap

internal data class NativeOrdinaryPresentationEvidence(
    val inlineHighlighters: Int,
    val inlineFolds: Int,
    val inlineCollapsedFolds: Int,
    val blockHighlighters: Int,
    val blockSyntaxFolds: Int,
    val blockSyntaxCollapsedFolds: Int,
    val headingModels: Int,
    val headingInlays: Int,
    val headingFolds: Int,
    val headingFullyConcealed: Int,
    val headingLevels: List<Int>,
    val blockQuoteModels: Int,
    val blockQuoteInlays: Int,
    val blockQuoteFolds: Int,
    val blockQuoteFullyConcealed: Int,
) {
    val blockFolds: Int
        get() = blockSyntaxFolds + headingFolds + blockQuoteFolds

    val blockCollapsedFolds: Int
        get() = blockSyntaxCollapsedFolds + headingFolds + blockQuoteFolds

    val ownedHighlighters: Int
        get() = inlineHighlighters + blockHighlighters

    val ownedFolds: Int
        get() = inlineFolds + blockFolds

    val collapsedFolds: Int
        get() = inlineCollapsedFolds + blockCollapsedFolds
}

/**
 * Ordinary-Markdown presentation boundary for #312.
 *
 * Inline and block syntax have independent ownership while this coordinator preserves the previous
 * projection-order application and single folding-batch semantics. Richer block presentation can
 * therefore evolve without coupling heading/list/quote behavior to lightweight inline syntax.
 */
internal class NativeOrdinaryPresentationController(
    private val editor: Editor,
) : Disposable {
    private val inline = NativeInlinePresentationController(editor)
    private val block = NativeBlockPresentationController(editor)
    private val headings = NativeHeadingPresentationController(editor)
    private val blockQuotes = NativeBlockQuotePresentationController(editor)
    private var disposed = false

    fun clearPresentation() {
        requireAlive()
        ApplicationManager.getApplication().assertIsDispatchThread()
        inline.clearHighlighters()
        block.clearHighlighters()
        headings.clearPresentation()
        blockQuotes.clearPresentation()
        clearFolds()
    }

    fun applyPlan(plan: NativeProjectionPlan, richPresentationEnabled: Boolean) {
        requireAlive()
        ApplicationManager.getApplication().assertIsDispatchThread()
        val exactSourceRanges = NativeHeadingProjectionPlanner.unsupportedSourceRanges(plan) +
            NativeBlockQuoteProjectionPlanner.sourceRanges(plan)
        if (plan.status == ProjectionPlanStatus.READY && richPresentationEnabled) {
            installRangeHighlighters(plan, exactSourceRanges)
            reconcileSyntaxFolds(plan, exactSourceRanges)
        }
        headings.applyPlan(plan, richPresentationEnabled)
        blockQuotes.applyPlan(plan, richPresentationEnabled)
    }

    fun refreshActivity(plan: NativeProjectionPlan, richPresentationEnabled: Boolean) {
        requireAlive()
        ApplicationManager.getApplication().assertIsDispatchThread()
        inline.clearHighlighters()
        block.clearHighlighters()
        val exactSourceRanges = NativeHeadingProjectionPlanner.unsupportedSourceRanges(plan) +
            NativeBlockQuoteProjectionPlanner.sourceRanges(plan)
        if (plan.status == ProjectionPlanStatus.READY && richPresentationEnabled) {
            installRangeHighlighters(plan, exactSourceRanges)
            reconcileSyntaxFolds(plan, exactSourceRanges)
        } else {
            clearFolds()
        }
        headings.refreshActivity(plan, richPresentationEnabled)
        blockQuotes.refreshActivity(plan, richPresentationEnabled)
    }

    fun evidenceSnapshot(): NativeOrdinaryPresentationEvidence {
        val inlineEvidence = inline.evidenceSnapshot()
        val blockEvidence = block.evidenceSnapshot()
        val headingEvidence = headings.evidenceSnapshot()
        val blockQuoteEvidence = blockQuotes.evidenceSnapshot()
        return NativeOrdinaryPresentationEvidence(
            inlineHighlighters = inlineEvidence.highlighters,
            inlineFolds = inlineEvidence.folds,
            inlineCollapsedFolds = inlineEvidence.collapsedFolds,
            blockHighlighters = blockEvidence.highlighters,
            blockSyntaxFolds = blockEvidence.folds,
            blockSyntaxCollapsedFolds = blockEvidence.collapsedFolds,
            headingModels = headingEvidence.models,
            headingInlays = headingEvidence.ownedInlays,
            headingFolds = headingEvidence.ownedFolds,
            headingFullyConcealed = headingEvidence.fullyConcealed,
            headingLevels = headingEvidence.levels,
            blockQuoteModels = blockQuoteEvidence.models,
            blockQuoteInlays = blockQuoteEvidence.ownedInlays,
            blockQuoteFolds = blockQuoteEvidence.ownedFolds,
            blockQuoteFullyConcealed = blockQuoteEvidence.fullyConcealed,
        )
    }

    private fun installRangeHighlighters(
        plan: NativeProjectionPlan,
        exactSourceHeadingRanges: List<ProjectionRange>,
    ) {
        plan.projections
            .filterNot { projection -> projection.isInsideAny(exactSourceHeadingRanges) }
            .forEach { projection ->
                inline.installHighlighterIfSupported(projection)
                block.installHighlighterIfSupported(projection)
            }
    }

    /**
     * Preserve the old global desired-fold ordering and one folding batch while keeping ownership
     * maps separate. Exact-range duplicates across layers retain the previous last-entry-wins
     * desired-map semantics before platform fold coexistence is checked.
     */
    private fun reconcileSyntaxFolds(
        plan: NativeProjectionPlan,
        exactSourceHeadingRanges: List<ProjectionRange>,
    ) {
        val desired = plan.projections
            .asSequence()
            .filterNot { projection -> projection.isInsideAny(exactSourceHeadingRanges) }
            .flatMap { projection ->
                val owner = foldOwner(projection.kind) ?: return@flatMap emptySequence()
                projection.syntaxRanges.asSequence().map { range ->
                    FoldEntry(owner, projection, range)
                }
            }
            .associateBy { entry -> FoldKey(entry.range.startOffset, entry.range.endOffset) }

        val inlineKeys = desired
            .filterValues { entry -> entry.owner === inline }
            .keys
        val blockKeys = desired
            .filterValues { entry -> entry.owner === block }
            .keys

        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            inline.removeObsoleteFoldsInBatch(inlineKeys)
            block.removeObsoleteFoldsInBatch(blockKeys)
            desired.values.forEach { entry ->
                entry.owner.reconcileFoldInBatch(entry.projection, entry.range, plan.identity.source)
            }
        }
    }

    private fun foldOwner(kind: NativeProjectionKind): NativeOrdinaryPresentationLayerController? = when {
        inline.supportsFold(kind) -> inline
        block.supportsFold(kind) -> block
        else -> null
    }

    private fun clearFolds() {
        if (editor.isDisposed) {
            inline.dropFoldOwnership()
            block.dropFoldOwnership()
            return
        }
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            inline.clearFoldsInBatch()
            block.clearFoldsInBatch()
        }
    }

    private fun requireAlive() {
        check(!disposed) { "ordinary Markdown presentation controller is disposed" }
        check(!editor.isDisposed) { "native editor is disposed" }
    }

    override fun dispose() {
        if (disposed) return
        ApplicationManager.getApplication().assertIsDispatchThread()
        inline.clearHighlighters()
        block.clearHighlighters()
        headings.dispose()
        blockQuotes.dispose()
        clearFolds()
        disposed = true
    }
}

private interface NativeOrdinaryPresentationLayerController {
    fun supportsFold(kind: NativeProjectionKind): Boolean

    fun installHighlighterIfSupported(projection: NativeProjection)

    fun removeObsoleteFoldsInBatch(desiredKeys: Set<FoldKey>)

    fun reconcileFoldInBatch(
        projection: NativeProjection,
        range: ProjectionRange,
        source: String,
    )

    fun clearHighlighters()

    fun clearFoldsInBatch()

    fun dropFoldOwnership()

    fun evidenceSnapshot(): NativeSyntaxPresentationEvidence
}

private class NativeInlinePresentationController(
    editor: Editor,
) : NativeOrdinaryPresentationLayerController {
    private val owner = NativeSyntaxPresentationOwner(
        editor = editor,
        highlightKinds = setOf(
            NativeProjectionKind.EMPHASIS,
            NativeProjectionKind.STRONG,
            NativeProjectionKind.INLINE_CODE,
            NativeProjectionKind.LINK,
        ),
        foldableKinds = setOf(
            NativeProjectionKind.EMPHASIS,
            NativeProjectionKind.STRONG,
            NativeProjectionKind.LINK,
            NativeProjectionKind.INLINE_CODE,
        ),
        keyFor = ::inlineKeyFor,
        placeholderFor = { _, _, _ -> ZERO_WIDTH_PLACEHOLDER },
    )

    override fun supportsFold(kind: NativeProjectionKind): Boolean = owner.supportsFold(kind)

    override fun installHighlighterIfSupported(projection: NativeProjection) =
        owner.installHighlighterIfSupported(projection)

    override fun removeObsoleteFoldsInBatch(desiredKeys: Set<FoldKey>) =
        owner.removeObsoleteFoldsInBatch(desiredKeys)

    override fun reconcileFoldInBatch(
        projection: NativeProjection,
        range: ProjectionRange,
        source: String,
    ) = owner.reconcileFoldInBatch(projection, range, source)

    override fun clearHighlighters() = owner.clearHighlighters()

    override fun clearFoldsInBatch() = owner.clearFoldsInBatch()

    override fun dropFoldOwnership() = owner.dropFoldOwnership()

    override fun evidenceSnapshot(): NativeSyntaxPresentationEvidence = owner.evidenceSnapshot()
}

private class NativeBlockPresentationController(
    editor: Editor,
) : NativeOrdinaryPresentationLayerController {
    private val owner = NativeSyntaxPresentationOwner(
        editor = editor,
        highlightKinds = setOf(
            NativeProjectionKind.BLOCK_QUOTE,
            NativeProjectionKind.CODE_FENCE,
            NativeProjectionKind.CODE_BLOCK,
            NativeProjectionKind.THEMATIC_BREAK,
        ),
        foldableKinds = setOf(
            NativeProjectionKind.LIST_ITEM,
            NativeProjectionKind.BLOCK_QUOTE,
            NativeProjectionKind.CODE_FENCE,
            NativeProjectionKind.THEMATIC_BREAK,
        ),
        keyFor = ::blockKeyFor,
        placeholderFor = ::blockPlaceholderFor,
    )

    override fun supportsFold(kind: NativeProjectionKind): Boolean = owner.supportsFold(kind)

    override fun installHighlighterIfSupported(projection: NativeProjection) =
        owner.installHighlighterIfSupported(projection)

    override fun removeObsoleteFoldsInBatch(desiredKeys: Set<FoldKey>) =
        owner.removeObsoleteFoldsInBatch(desiredKeys)

    override fun reconcileFoldInBatch(
        projection: NativeProjection,
        range: ProjectionRange,
        source: String,
    ) = owner.reconcileFoldInBatch(projection, range, source)

    override fun clearHighlighters() = owner.clearHighlighters()

    override fun clearFoldsInBatch() = owner.clearFoldsInBatch()

    override fun dropFoldOwnership() = owner.dropFoldOwnership()

    override fun evidenceSnapshot(): NativeSyntaxPresentationEvidence = owner.evidenceSnapshot()
}

private data class NativeSyntaxPresentationEvidence(
    val highlighters: Int,
    val folds: Int,
    val collapsedFolds: Int,
)

private data class FoldKey(
    val startOffset: Int,
    val endOffset: Int,
)

private data class FoldEntry(
    val owner: NativeOrdinaryPresentationLayerController,
    val projection: NativeProjection,
    val range: ProjectionRange,
)

private class NativeSyntaxPresentationOwner(
    private val editor: Editor,
    private val highlightKinds: Set<NativeProjectionKind>,
    private val foldableKinds: Set<NativeProjectionKind>,
    private val keyFor: (NativeProjectionKind) -> TextAttributesKey,
    private val placeholderFor: (NativeProjection, ProjectionRange, String) -> String,
) {
    private val highlighters = mutableListOf<RangeHighlighter>()
    private val folds = LinkedHashMap<FoldKey, FoldRegion>()

    fun supportsFold(kind: NativeProjectionKind): Boolean = kind in foldableKinds

    fun installHighlighterIfSupported(projection: NativeProjection) {
        if (projection.kind !in highlightKinds || isActive(projection)) return
        val range = projection.sourceRange
        highlighters += editor.markupModel.addRangeHighlighter(
            keyFor(projection.kind),
            range.startOffset,
            range.endOffset,
            HighlighterLayer.ADDITIONAL_SYNTAX,
            HighlighterTargetArea.EXACT_RANGE,
        )
    }

    fun removeObsoleteFoldsInBatch(desiredKeys: Set<FoldKey>) {
        val obsolete = folds.keys.filter { key -> key !in desiredKeys || folds[key]?.isValid != true }
        obsolete.forEach { key ->
            folds.remove(key)?.takeIf(FoldRegion::isValid)?.let(editor.foldingModel::removeFoldRegion)
        }
    }

    fun reconcileFoldInBatch(
        projection: NativeProjection,
        range: ProjectionRange,
        source: String,
    ) {
        check(projection.kind in foldableKinds) {
            "projection ${projection.kind} does not belong to this ordinary presentation owner"
        }
        val key = FoldKey(range.startOffset, range.endOffset)
        val active = isActive(projection)
        val owned = folds[key]
        if (owned?.isValid == true) {
            owned.isExpanded = active
            return
        }
        if (editor.foldingModel.getFoldRegion(range.startOffset, range.endOffset) != null) {
            return
        }
        val region = editor.foldingModel.addFoldRegion(
            range.startOffset,
            range.endOffset,
            placeholderFor(projection, range, source),
        ) ?: return
        region.isExpanded = active
        folds[key] = region
    }

    fun clearHighlighters() {
        if (!editor.isDisposed) {
            highlighters.forEach { highlighter ->
                if (highlighter.isValid) editor.markupModel.removeHighlighter(highlighter)
            }
        }
        highlighters.clear()
    }

    fun clearFoldsInBatch() {
        folds.values.forEach { fold ->
            if (fold.isValid) editor.foldingModel.removeFoldRegion(fold)
        }
        folds.clear()
    }

    fun dropFoldOwnership() {
        folds.clear()
    }

    fun evidenceSnapshot(): NativeSyntaxPresentationEvidence = NativeSyntaxPresentationEvidence(
        highlighters = highlighters.count { it.isValid },
        folds = folds.values.count { it.isValid },
        collapsedFolds = folds.values.count { it.isValid && !it.isExpanded },
    )

    private fun isActive(projection: NativeProjection): Boolean =
        ReadAction.computeBlocking<Boolean, RuntimeException> {
            val range = projection.sourceRange
            editor.caretModel.allCarets.any { caret ->
                range.touches(caret.offset) ||
                    (caret.hasSelection() && range.intersects(caret.selectionStart, caret.selectionEnd))
            }
        }
}

private fun NativeProjection.isInsideAny(ranges: List<ProjectionRange>): Boolean =
    ranges.any { range ->
        sourceRange.startOffset >= range.startOffset && sourceRange.endOffset <= range.endOffset
    }

private fun blockPlaceholderFor(
    projection: NativeProjection,
    range: ProjectionRange,
    source: String,
): String = when (projection.kind) {
    NativeProjectionKind.LIST_ITEM -> {
        val marker = source.substring(range.startOffset, range.endOffset)
        val digits = marker.takeWhile(Char::isDigit)
        if (digits.isNotEmpty()) "$digits." else BULLET_PLACEHOLDER
    }
    NativeProjectionKind.BLOCK_QUOTE -> QUOTE_PLACEHOLDER
    NativeProjectionKind.THEMATIC_BREAK -> THEMATIC_BREAK_PLACEHOLDER
    else -> ZERO_WIDTH_PLACEHOLDER
}

private fun inlineKeyFor(kind: NativeProjectionKind): TextAttributesKey = when (kind) {
    NativeProjectionKind.EMPHASIS -> EMPHASIS_KEY
    NativeProjectionKind.STRONG -> STRONG_KEY
    NativeProjectionKind.INLINE_CODE -> INLINE_CODE_KEY
    NativeProjectionKind.LINK -> LINK_KEY
    else -> error("no inline presentation key for $kind")
}

private fun blockKeyFor(kind: NativeProjectionKind): TextAttributesKey = when (kind) {
    NativeProjectionKind.BLOCK_QUOTE -> BLOCK_QUOTE_KEY
    NativeProjectionKind.CODE_FENCE,
    NativeProjectionKind.CODE_BLOCK,
    -> CODE_BLOCK_KEY
    NativeProjectionKind.THEMATIC_BREAK -> THEMATIC_BREAK_KEY
    else -> error("no block presentation key for $kind")
}

private const val ZERO_WIDTH_PLACEHOLDER = "\u200B"
private const val BULLET_PLACEHOLDER = "•"
private const val QUOTE_PLACEHOLDER = "│"
private const val THEMATIC_BREAK_PLACEHOLDER = "────────"

private val EMPHASIS_KEY = TextAttributesKey.createTextAttributesKey(
    "MARKFLOW.PROJECTION.EMPHASIS",
    DefaultLanguageHighlighterColors.MARKUP_ATTRIBUTE,
)
private val STRONG_KEY = TextAttributesKey.createTextAttributesKey(
    "MARKFLOW.PROJECTION.STRONG",
    DefaultLanguageHighlighterColors.KEYWORD,
)
private val INLINE_CODE_KEY = TextAttributesKey.createTextAttributesKey(
    "MARKFLOW.PROJECTION.INLINE_CODE",
    DefaultLanguageHighlighterColors.STRING,
)
private val LINK_KEY = TextAttributesKey.createTextAttributesKey(
    "MARKFLOW.PROJECTION.LINK",
    DefaultLanguageHighlighterColors.STRING,
)
private val BLOCK_QUOTE_KEY = TextAttributesKey.createTextAttributesKey(
    "MARKFLOW.PROJECTION.BLOCK_QUOTE",
    DefaultLanguageHighlighterColors.MARKUP_ATTRIBUTE,
)
private val CODE_BLOCK_KEY = TextAttributesKey.createTextAttributesKey(
    "MARKFLOW.PROJECTION.CODE_BLOCK",
    DefaultLanguageHighlighterColors.STRING,
)
private val THEMATIC_BREAK_KEY = TextAttributesKey.createTextAttributesKey(
    "MARKFLOW.PROJECTION.THEMATIC_BREAK",
    DefaultLanguageHighlighterColors.KEYWORD,
)
