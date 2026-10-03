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
    val blockFolds: Int,
    val blockCollapsedFolds: Int,
) {
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
 * Inline and block syntax have independent ownership/lifecycle so richer block presentation can
 * evolve without coupling heading/list/quote behavior to lightweight inline syntax presentation.
 * Both owners remain source-neutral and operate only on parser-proven [NativeProjectionPlan] ranges.
 */
internal class NativeOrdinaryPresentationController(
    private val editor: Editor,
) : Disposable {
    private val inline = NativeInlinePresentationController(editor)
    private val block = NativeBlockPresentationController(editor)

    fun applyPlan(plan: NativeProjectionPlan, richPresentationEnabled: Boolean) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        inline.applyPlan(plan, richPresentationEnabled)
        block.applyPlan(plan, richPresentationEnabled)
    }

    fun refreshActivity(plan: NativeProjectionPlan, richPresentationEnabled: Boolean) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        inline.refreshActivity(plan, richPresentationEnabled)
        block.refreshActivity(plan, richPresentationEnabled)
    }

    fun evidenceSnapshot(): NativeOrdinaryPresentationEvidence {
        val inlineEvidence = inline.evidenceSnapshot()
        val blockEvidence = block.evidenceSnapshot()
        return NativeOrdinaryPresentationEvidence(
            inlineHighlighters = inlineEvidence.highlighters,
            inlineFolds = inlineEvidence.folds,
            inlineCollapsedFolds = inlineEvidence.collapsedFolds,
            blockHighlighters = blockEvidence.highlighters,
            blockFolds = blockEvidence.folds,
            blockCollapsedFolds = blockEvidence.collapsedFolds,
        )
    }

    override fun dispose() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        inline.dispose()
        block.dispose()
    }
}

private class NativeInlinePresentationController(
    editor: Editor,
) : Disposable {
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

    fun applyPlan(plan: NativeProjectionPlan, enabled: Boolean) = owner.applyPlan(plan, enabled)

    fun refreshActivity(plan: NativeProjectionPlan, enabled: Boolean) = owner.refreshActivity(plan, enabled)

    fun evidenceSnapshot(): NativeSyntaxPresentationEvidence = owner.evidenceSnapshot()

    override fun dispose() = owner.dispose()
}

private class NativeBlockPresentationController(
    editor: Editor,
) : Disposable {
    private val owner = NativeSyntaxPresentationOwner(
        editor = editor,
        highlightKinds = setOf(
            NativeProjectionKind.BLOCK_QUOTE,
            NativeProjectionKind.CODE_FENCE,
            NativeProjectionKind.CODE_BLOCK,
            NativeProjectionKind.THEMATIC_BREAK,
        ),
        foldableKinds = setOf(
            NativeProjectionKind.HEADING,
            NativeProjectionKind.LIST_ITEM,
            NativeProjectionKind.BLOCK_QUOTE,
            NativeProjectionKind.CODE_FENCE,
            NativeProjectionKind.THEMATIC_BREAK,
        ),
        keyFor = ::blockKeyFor,
        placeholderFor = ::blockPlaceholderFor,
    )

    fun applyPlan(plan: NativeProjectionPlan, enabled: Boolean) = owner.applyPlan(plan, enabled)

    fun refreshActivity(plan: NativeProjectionPlan, enabled: Boolean) = owner.refreshActivity(plan, enabled)

    fun evidenceSnapshot(): NativeSyntaxPresentationEvidence = owner.evidenceSnapshot()

    override fun dispose() = owner.dispose()
}

private data class NativeSyntaxPresentationEvidence(
    val highlighters: Int,
    val folds: Int,
    val collapsedFolds: Int,
)

private class NativeSyntaxPresentationOwner(
    private val editor: Editor,
    private val highlightKinds: Set<NativeProjectionKind>,
    private val foldableKinds: Set<NativeProjectionKind>,
    private val keyFor: (NativeProjectionKind) -> TextAttributesKey,
    private val placeholderFor: (NativeProjection, ProjectionRange, String) -> String,
) : Disposable {
    private val highlighters = mutableListOf<RangeHighlighter>()
    private val folds = LinkedHashMap<FoldKey, FoldRegion>()
    private var disposed = false

    fun applyPlan(plan: NativeProjectionPlan, enabled: Boolean) {
        requireAlive()
        clear()
        if (plan.status != ProjectionPlanStatus.READY || !enabled) return
        installRangeHighlighters(plan)
        reconcileSyntaxFolds(plan)
    }

    fun refreshActivity(plan: NativeProjectionPlan, enabled: Boolean) {
        requireAlive()
        clearHighlighters()
        if (plan.status == ProjectionPlanStatus.READY && enabled) {
            installRangeHighlighters(plan)
            reconcileSyntaxFolds(plan)
        } else {
            clearFolds()
        }
    }

    fun evidenceSnapshot(): NativeSyntaxPresentationEvidence = NativeSyntaxPresentationEvidence(
        highlighters = highlighters.count { it.isValid },
        folds = folds.values.count { it.isValid },
        collapsedFolds = folds.values.count { it.isValid && !it.isExpanded },
    )

    private fun installRangeHighlighters(plan: NativeProjectionPlan) {
        plan.projections
            .asSequence()
            .filter { projection -> projection.kind in highlightKinds }
            .filterNot(::isActive)
            .forEach { projection ->
                val range = projection.sourceRange
                highlighters += editor.markupModel.addRangeHighlighter(
                    keyFor(projection.kind),
                    range.startOffset,
                    range.endOffset,
                    HighlighterLayer.ADDITIONAL_SYNTAX,
                    HighlighterTargetArea.EXACT_RANGE,
                )
            }
    }

    /**
     * Reconcile only folds owned by this ordinary-presentation layer. If the platform already owns
     * the exact same range, leave it alone; a later refresh can install MarkFlow's fallback after
     * that foreign fold disappears.
     */
    private fun reconcileSyntaxFolds(plan: NativeProjectionPlan) {
        val desired = plan.projections
            .asSequence()
            .filter { projection -> projection.kind in foldableKinds }
            .flatMap { projection -> projection.syntaxRanges.asSequence().map { range -> projection to range } }
            .associateBy { (_, range) -> FoldKey(range.startOffset, range.endOffset) }

        val obsolete = folds.keys.filter { key -> key !in desired || folds[key]?.isValid != true }
        if (obsolete.isNotEmpty()) {
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                obsolete.forEach { key ->
                    folds.remove(key)?.takeIf(FoldRegion::isValid)?.let(editor.foldingModel::removeFoldRegion)
                }
            }
        }

        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            desired.forEach { (key, pair) ->
                val (projection, range) = pair
                val active = isActive(projection)
                val owned = folds[key]
                if (owned?.isValid == true) {
                    owned.isExpanded = active
                    return@forEach
                }
                if (editor.foldingModel.getFoldRegion(range.startOffset, range.endOffset) != null) {
                    return@forEach
                }
                val region = editor.foldingModel.addFoldRegion(
                    range.startOffset,
                    range.endOffset,
                    placeholderFor(projection, range, plan.identity.source),
                ) ?: return@forEach
                region.isExpanded = active
                folds[key] = region
            }
        }
    }

    private fun isActive(projection: NativeProjection): Boolean =
        ReadAction.computeBlocking<Boolean, RuntimeException> {
            val range = projection.sourceRange
            editor.caretModel.allCarets.any { caret ->
                range.touches(caret.offset) ||
                    (caret.hasSelection() && range.intersects(caret.selectionStart, caret.selectionEnd))
            }
        }

    private fun clear() {
        clearHighlighters()
        clearFolds()
    }

    private fun clearFolds() {
        if (folds.isNotEmpty() && !editor.isDisposed) {
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                folds.values.forEach { fold ->
                    if (fold.isValid) editor.foldingModel.removeFoldRegion(fold)
                }
            }
        }
        folds.clear()
    }

    private fun clearHighlighters() {
        if (!editor.isDisposed) {
            highlighters.forEach { highlighter ->
                if (highlighter.isValid) editor.markupModel.removeHighlighter(highlighter)
            }
        }
        highlighters.clear()
    }

    private fun requireAlive() {
        check(!disposed) { "ordinary syntax presentation owner is disposed" }
        check(!editor.isDisposed) { "native editor is disposed" }
    }

    override fun dispose() {
        if (disposed) return
        ApplicationManager.getApplication().assertIsDispatchThread()
        clear()
        disposed = true
    }

    private data class FoldKey(
        val startOffset: Int,
        val endOffset: Int,
    )
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
