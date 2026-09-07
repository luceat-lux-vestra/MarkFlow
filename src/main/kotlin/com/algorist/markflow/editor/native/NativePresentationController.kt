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
)

/**
 * One disposable, source-neutral presentation owner for one native IntelliJ [Editor].
 *
 * It owns only highlighters, fold regions and listeners. It has no source write path and no
 * browser/JCEF dependency. Plans are accepted only for the exact current source/config identity.
 */
internal class NativePresentationController(
    private val editor: Editor,
    private val configGeneration: () -> Long = { 0L },
    private val planner: (ProjectionSnapshot) -> NativeProjectionPlan = NativeMarkdownProjectionPlanner::plan,
) : Disposable {
    private val highlighters = mutableListOf<OwnedHighlighter>()
    private val folds = mutableListOf<OwnedFold>()
    private var disposed = false
    private var refreshRequestGeneration = 0L
    private var refreshesApplied = 0L
    private var refreshesScheduled = 0L

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
        refreshNow()
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
        if (plan.status == ProjectionPlanStatus.READY) {
            installInlineHighlighters(plan)
            installHeadingFolds(plan)
        }
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
        ownedFolds = folds.count { it.region.isValid },
        collapsedFolds = folds.count { it.region.isValid && !it.region.isExpanded },
        refreshesApplied = refreshesApplied,
        refreshesScheduled = refreshesScheduled,
    )

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

    private fun installInlineHighlighters(plan: NativeProjectionPlan) {
        plan.projections
            .asSequence()
            .filter { projection -> projection.kind in INLINE_KINDS }
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

    private fun installHeadingFolds(plan: NativeProjectionPlan) {
        val foldingModel = editor.foldingModel
        foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            plan.projections
                .asSequence()
                .filter { projection -> projection.kind == NativeProjectionKind.HEADING }
                .flatMap { projection -> projection.syntaxRanges.asSequence().map { range -> projection to range } }
                .forEach { (projection, range) ->
                    val region = foldingModel.addFoldRegion(
                        range.startOffset,
                        range.endOffset,
                        ZERO_WIDTH_PLACEHOLDER,
                    ) ?: return@forEach
                    region.isExpanded = isActive(projection)
                    folds += OwnedFold(projection, region)
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
        if (plan.status == ProjectionPlanStatus.READY) {
            installInlineHighlighters(plan)
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                folds.forEach { owned ->
                    if (owned.region.isValid) {
                        owned.region.isExpanded = isActive(owned.projection)
                    }
                }
            }
        }

        check(document.modificationStamp == stampBefore)
        check(document.immutableCharSequence.toString() == sourceBefore)
    }

    private fun isActive(projection: NativeProjection): Boolean = ReadAction.computeBlocking<Boolean, RuntimeException> {
        val range = projection.sourceRange
        editor.caretModel.allCarets.any { caret ->
            range.contains(caret.offset) ||
                (caret.hasSelection() && range.intersects(caret.selectionStart, caret.selectionEnd))
        }
    }

    private fun clearOwnedPresentation() {
        clearOwnedHighlighters()
        if (folds.isNotEmpty() && !editor.isDisposed) {
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                folds.forEach { owned ->
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
        clearOwnedPresentation()
        currentPlan = null
        disposed = true
    }

    private data class OwnedHighlighter(
        val highlighter: RangeHighlighter,
    )

    private data class OwnedFold(
        val projection: NativeProjection,
        val region: FoldRegion,
    )

    companion object {
        private const val ZERO_WIDTH_PLACEHOLDER = "\u200B"

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
        private val INLINE_KINDS = setOf(
            NativeProjectionKind.EMPHASIS,
            NativeProjectionKind.STRONG,
            NativeProjectionKind.INLINE_CODE,
        )

        private fun keyFor(kind: NativeProjectionKind): TextAttributesKey = when (kind) {
            NativeProjectionKind.EMPHASIS -> EMPHASIS_KEY
            NativeProjectionKind.STRONG -> STRONG_KEY
            NativeProjectionKind.INLINE_CODE -> INLINE_CODE_KEY
            else -> error("no inline presentation key for $kind")
        }
    }
}
