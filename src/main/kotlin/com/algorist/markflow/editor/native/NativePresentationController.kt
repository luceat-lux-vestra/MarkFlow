package com.algorist.markflow.editor.native

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.accessibility.ScreenReader
import java.util.LinkedHashMap

internal enum class ProjectionApplyResult {
    APPLIED,
    DEGRADED_TO_SOURCE,
    STALE_REJECTED,
}

internal data class NativePresentationEvidence(
    val planIdentity: ProjectionSourceIdentity?,
    val planStatus: ProjectionPlanStatus?,
    val ownedHighlighters: Int,
    val ownedFolds: Int,
    val collapsedFolds: Int,
    val refreshesApplied: Long,
    val refreshesScheduled: Long,
    val sourceFallbacks: Long,
)

/**
 * One disposable, source-neutral presentation owner for one native IntelliJ [Editor].
 *
 * It owns only source-neutral editor presentation and listeners. Ordinary GFM tables, optional #147
 * host resources, #148 derived renderer presentation, and #149 raw-HTML presentation are composed
 * behind independent owners. This class has no browser/JCEF dependency and no source write path.
 * Plans are accepted only for the exact current source/config identity.
 */
internal class NativePresentationController(
    private val editor: Editor,
    private val configGeneration: () -> Long = { 0L },
    private val planner: (ProjectionSnapshot) -> NativeProjectionPlan = NativeMarkdownProjectionPlanner::plan,
    private val derivedPresentation: NativeDerivedPresentationController? = null,
    private val hostResources: NativeHostResourcePresentationController? = null,
    private val rawHtmlPresentation: NativeRawHtmlPresentationController? = null,
    private val richPresentationEnabled: () -> Boolean = { !ScreenReader.isActive() },
    private val tablePresentation: NativeTablePresentationController =
        NativeTablePresentationController(editor, richPresentationEnabled),
) : Disposable {
    private val highlighters = mutableListOf<OwnedHighlighter>()
    private val folds = LinkedHashMap<FoldKey, OwnedFold>()
    private var disposed = false
    private var refreshRequestGeneration = 0L
    private var refreshesApplied = 0L
    private var refreshesScheduled = 0L
    private var sourceFallbacks = 0L

    var currentPlan: NativeProjectionPlan? = null
        private set

    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            scheduleRefresh()
        }
    }

    private val caretListener = object : CaretListener {
        override fun caretPositionChanged(event: CaretEvent) {
            refreshActivityPresentation()
        }

        override fun caretAdded(event: CaretEvent) {
            refreshActivityPresentation()
        }

        override fun caretRemoved(event: CaretEvent) {
            refreshActivityPresentation()
        }
    }

    private val selectionListener = object : SelectionListener {
        override fun selectionChanged(event: SelectionEvent) {
            refreshActivityPresentation()
        }
    }

    init {
        ApplicationManager.getApplication().assertIsDispatchThread()
        editor.document.addDocumentListener(documentListener, this)
        editor.caretModel.addCaretListener(caretListener, this)
        editor.selectionModel.addSelectionListener(selectionListener, this)
        try {
            refreshNow()
        } catch (failure: Throwable) {
            // Constructor failure must not strand listeners or child presentation owners whose
            // lifetime was already registered against this controller before the initial refresh.
            runCatching { Disposer.dispose(this) }
            throw failure
        }
    }

    fun refreshNow(): ProjectionApplyResult {
        requireAlive()
        ApplicationManager.getApplication().assertIsDispatchThread()
        val snapshot = ProjectionSnapshot.capture(editor.document, configGeneration())
        return tryApply(planner(snapshot))
    }

    /**
     * Apply only if [plan] still belongs to the exact current source/config generation.
     * A stale result returns before touching any owned presentation.
     */
    fun tryApply(plan: NativeProjectionPlan): ProjectionApplyResult {
        requireAlive()
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (!matchesCurrentIdentity(plan.identity)) {
            return ProjectionApplyResult.STALE_REJECTED
        }

        val document = editor.document
        val sourceBefore = document.immutableCharSequence.toString()
        val stampBefore = document.modificationStamp

        clearOwnedPresentation()
        currentPlan = plan
        if (plan.status == ProjectionPlanStatus.READY && isRichPresentationEnabled()) {
            installRangeHighlighters(plan)
            reconcileSyntaxFolds(plan)
        } else if (plan.status == ProjectionPlanStatus.READY) {
            sourceFallbacks += 1
        }
        tablePresentation.applyPlan(plan)
        hostResources?.applyPlan(plan)
        derivedPresentation?.applyPlan(plan)
        rawHtmlPresentation?.applyPlan(plan)
        refreshesApplied += 1

        check(document.modificationStamp == stampBefore) {
            "derived presentation changed the authoritative Document modification stamp"
        }
        check(document.immutableCharSequence.toString() == sourceBefore) {
            "derived presentation changed the authoritative Document source"
        }

        return if (plan.status == ProjectionPlanStatus.READY) {
            ProjectionApplyResult.APPLIED
        } else {
            ProjectionApplyResult.DEGRADED_TO_SOURCE
        }
    }

    fun evidenceSnapshot(): NativePresentationEvidence = NativePresentationEvidence(
        planIdentity = currentPlan?.identity,
        planStatus = currentPlan?.status,
        ownedHighlighters = highlighters.count { it.highlighter.isValid },
        ownedFolds = folds.values.count { it.region.isValid },
        collapsedFolds = folds.values.count { it.region.isValid && !it.region.isExpanded },
        refreshesApplied = refreshesApplied,
        refreshesScheduled = refreshesScheduled,
        sourceFallbacks = sourceFallbacks,
    )

    fun tableEvidenceSnapshot(): NativeTablePresentationEvidence = tablePresentation.evidenceSnapshot()

    fun derivedEvidenceSnapshot(): NativeDerivedPresentationEvidence? =
        derivedPresentation?.evidenceSnapshot()

    fun rawHtmlEvidenceSnapshot(): NativeRawHtmlPresentationEvidence? =
        rawHtmlPresentation?.evidenceSnapshot()

    private fun scheduleRefresh() {
        if (disposed) return
        val request = ++refreshRequestGeneration
        refreshesScheduled += 1
        ApplicationManager.getApplication().invokeLater {
            if (disposed || editor.isDisposed || request != refreshRequestGeneration) return@invokeLater
            refreshNow()
        }
    }

    private fun matchesCurrentIdentity(identity: ProjectionSourceIdentity): Boolean =
        ProjectionSnapshot.capture(editor.document, configGeneration()).identity == identity

    private fun installRangeHighlighters(plan: NativeProjectionPlan) {
        plan.projections
            .asSequence()
            .filter { projection -> projection.kind in HIGHLIGHT_KINDS }
            .filterNot(::isActive)
            .forEach { projection ->
                val range = projection.sourceRange
                val highlighter = editor.markupModel.addRangeHighlighter(
                    keyFor(projection.kind),
                    range.startOffset,
                    range.endOffset,
                    HighlighterLayer.ADDITIONAL_SYNTAX,
                    HighlighterTargetArea.EXACT_RANGE,
                )
                highlighters += OwnedHighlighter(highlighter)
            }
    }

    /**
     * Reconciles only MarkFlow-owned syntax folds. If the platform already owns the same exact fold
     * (for example IntelliJ Markdown live preview is enabled), MarkFlow leaves it alone. If the
     * platform fold later disappears, the next caret/selection reconciliation can install the
     * public-API fallback without consulting any IntelliJ-internal live-preview implementation.
     */
    private fun reconcileSyntaxFolds(plan: NativeProjectionPlan) {
        val desired = plan.projections
            .asSequence()
            .filter { projection -> projection.kind in FOLDABLE_SYNTAX_KINDS }
            .flatMap { projection -> projection.syntaxRanges.asSequence().map { range -> projection to range } }
            .associateBy { (_, range) -> FoldKey(range.startOffset, range.endOffset) }

        val obsolete = folds.keys.filter { key -> key !in desired || folds[key]?.region?.isValid != true }
        if (obsolete.isNotEmpty()) {
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                obsolete.forEach { key ->
                    folds.remove(key)?.region?.takeIf(FoldRegion::isValid)?.let(editor.foldingModel::removeFoldRegion)
                }
            }
        }

        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            desired.forEach { (key, pair) ->
                val (projection, range) = pair
                val active = isActive(projection)
                val owned = folds[key]
                if (owned?.region?.isValid == true) {
                    owned.region.isExpanded = active
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
                folds[key] = OwnedFold(projection, region)
            }
        }
    }

    private fun refreshActivityPresentation() {
        if (disposed || editor.isDisposed) return
        ApplicationManager.getApplication().assertIsDispatchThread()
        val plan = currentPlan ?: return
        if (!matchesCurrentIdentity(plan.identity)) return

        val document = editor.document
        val sourceBefore = document.immutableCharSequence.toString()
        val stampBefore = document.modificationStamp

        clearOwnedHighlighters()
        if (plan.status == ProjectionPlanStatus.READY && isRichPresentationEnabled()) {
            installRangeHighlighters(plan)
            reconcileSyntaxFolds(plan)
        } else {
            clearOwnedFolds()
        }
        tablePresentation.refreshActivity(plan)
        hostResources?.refreshActivity(plan)
        derivedPresentation?.refreshActivity(plan)

        check(document.modificationStamp == stampBefore)
        check(document.immutableCharSequence.toString() == sourceBefore)
    }

    private fun isActive(projection: NativeProjection): Boolean = ReadAction.computeBlocking<Boolean, RuntimeException> {
        val range = projection.sourceRange
        editor.caretModel.allCarets.any { caret ->
            range.touches(caret.offset) ||
                (caret.hasSelection() && range.intersects(caret.selectionStart, caret.selectionEnd))
        }
    }

    private fun isRichPresentationEnabled(): Boolean =
        runCatching(richPresentationEnabled).getOrDefault(false)

    private fun placeholderFor(
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

    private fun clearOwnedPresentation() {
        clearOwnedHighlighters()
        clearOwnedFolds()
    }

    private fun clearOwnedFolds() {
        if (folds.isNotEmpty() && !editor.isDisposed) {
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                folds.values.forEach { owned ->
                    if (owned.region.isValid) {
                        editor.foldingModel.removeFoldRegion(owned.region)
                    }
                }
            }
        }
        folds.clear()
    }

    private fun clearOwnedHighlighters() {
        if (!editor.isDisposed) {
            highlighters.forEach { owned ->
                if (owned.highlighter.isValid) {
                    editor.markupModel.removeHighlighter(owned.highlighter)
                }
            }
        }
        highlighters.clear()
    }

    private fun requireAlive() {
        check(!disposed) { "native presentation controller is disposed" }
        check(!editor.isDisposed) { "native editor is disposed" }
    }

    override fun dispose() {
        if (disposed) return
        ApplicationManager.getApplication().assertIsDispatchThread()
        refreshRequestGeneration += 1
        tablePresentation.dispose()
        hostResources?.dispose()
        derivedPresentation?.dispose()
        rawHtmlPresentation?.dispose()
        clearOwnedPresentation()
        currentPlan = null
        disposed = true
    }

    private data class OwnedHighlighter(
        val highlighter: RangeHighlighter,
    )

    private data class FoldKey(
        val startOffset: Int,
        val endOffset: Int,
    )

    private data class OwnedFold(
        val projection: NativeProjection,
        val region: FoldRegion,
    )

    companion object {
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

        private val HIGHLIGHT_KINDS = setOf(
            NativeProjectionKind.EMPHASIS,
            NativeProjectionKind.STRONG,
            NativeProjectionKind.INLINE_CODE,
            NativeProjectionKind.LINK,
            NativeProjectionKind.BLOCK_QUOTE,
            NativeProjectionKind.CODE_FENCE,
            NativeProjectionKind.CODE_BLOCK,
            NativeProjectionKind.THEMATIC_BREAK,
        )

        private val FOLDABLE_SYNTAX_KINDS = setOf(
            NativeProjectionKind.HEADING,
            NativeProjectionKind.EMPHASIS,
            NativeProjectionKind.STRONG,
            NativeProjectionKind.LINK,
            NativeProjectionKind.LIST_ITEM,
            NativeProjectionKind.BLOCK_QUOTE,
            NativeProjectionKind.INLINE_CODE,
            NativeProjectionKind.CODE_FENCE,
            NativeProjectionKind.THEMATIC_BREAK,
        )

        private fun keyFor(kind: NativeProjectionKind): TextAttributesKey = when (kind) {
            NativeProjectionKind.EMPHASIS -> EMPHASIS_KEY
            NativeProjectionKind.STRONG -> STRONG_KEY
            NativeProjectionKind.INLINE_CODE -> INLINE_CODE_KEY
            NativeProjectionKind.LINK -> LINK_KEY
            NativeProjectionKind.BLOCK_QUOTE -> BLOCK_QUOTE_KEY
            NativeProjectionKind.CODE_FENCE,
            NativeProjectionKind.CODE_BLOCK,
            -> CODE_BLOCK_KEY
            NativeProjectionKind.THEMATIC_BREAK -> THEMATIC_BREAK_KEY
            else -> error("no presentation key for $kind")
        }
    }
}