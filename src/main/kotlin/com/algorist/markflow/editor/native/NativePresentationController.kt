package com.algorist.markflow.editor.native

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.accessibility.ScreenReader

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
    val inlineOwnedHighlighters: Int,
    val inlineOwnedFolds: Int,
    val inlineCollapsedFolds: Int,
    val blockOwnedHighlighters: Int,
    val blockOwnedFolds: Int,
    val blockCollapsedFolds: Int,
    val headingModels: Int,
    val headingInlays: Int,
    val headingFolds: Int,
    val headingFullyConcealed: Int,
    val headingLevels: List<Int>,
    val blockQuoteModels: Int,
    val blockQuoteInlays: Int,
    val blockQuoteFolds: Int,
    val blockQuoteFullyConcealed: Int,
    val fencedCodeModels: Int,
    val fencedCodeInlays: Int,
    val fencedCodeFolds: Int,
    val fencedCodeFullyConcealed: Int,
    val fencedCodeInfos: List<String>,
    val listModels: Int,
    val listRows: Int,
    val listInlays: Int,
    val listFolds: Int,
    val listFullyConcealed: Int,
    val listMaxDepth: Int,
    val listDepths: List<Int>,
    val listMarkers: List<String>,
    val taskRows: Int,
    val checkedTasks: Int,
    val taskToggles: Long,
    val refreshesApplied: Long,
    val refreshesScheduled: Long,
    val sourceFallbacks: Long,
)

/**
 * One disposable, source-neutral presentation owner for one native IntelliJ [Editor].
 *
 * It owns lifecycle/listener coordination and composes independent ordinary inline/block, GFM table,
 * #147 host-resource, #148 derived-renderer, and #149 raw-HTML presentation owners. This class has
 * no browser/JCEF dependency and no source write path. Plans are accepted only for the exact current
 * source/config identity.
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
    private val ordinaryPresentation = NativeOrdinaryPresentationController(editor)
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

        ordinaryPresentation.clearPresentation()
        currentPlan = plan
        val richEnabled = isRichPresentationEnabled()
        ordinaryPresentation.applyPlan(plan, richEnabled)
        if (plan.status == ProjectionPlanStatus.READY && !richEnabled) {
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

    fun evidenceSnapshot(): NativePresentationEvidence {
        val ordinary = ordinaryPresentation.evidenceSnapshot()
        return NativePresentationEvidence(
            planIdentity = currentPlan?.identity,
            planStatus = currentPlan?.status,
            ownedHighlighters = ordinary.ownedHighlighters,
            ownedFolds = ordinary.ownedFolds,
            collapsedFolds = ordinary.collapsedFolds,
            inlineOwnedHighlighters = ordinary.inlineHighlighters,
            inlineOwnedFolds = ordinary.inlineFolds,
            inlineCollapsedFolds = ordinary.inlineCollapsedFolds,
            blockOwnedHighlighters = ordinary.blockHighlighters,
            blockOwnedFolds = ordinary.blockFolds,
            blockCollapsedFolds = ordinary.blockCollapsedFolds,
            headingModels = ordinary.headingModels,
            headingInlays = ordinary.headingInlays,
            headingFolds = ordinary.headingFolds,
            headingFullyConcealed = ordinary.headingFullyConcealed,
            headingLevels = ordinary.headingLevels,
            blockQuoteModels = ordinary.blockQuoteModels,
            blockQuoteInlays = ordinary.blockQuoteInlays,
            blockQuoteFolds = ordinary.blockQuoteFolds,
            blockQuoteFullyConcealed = ordinary.blockQuoteFullyConcealed,
            fencedCodeModels = ordinary.fencedCodeModels,
            fencedCodeInlays = ordinary.fencedCodeInlays,
            fencedCodeFolds = ordinary.fencedCodeFolds,
            fencedCodeFullyConcealed = ordinary.fencedCodeFullyConcealed,
            fencedCodeInfos = ordinary.fencedCodeInfos,
            listModels = ordinary.listModels,
            listRows = ordinary.listRows,
            listInlays = ordinary.listInlays,
            listFolds = ordinary.listFolds,
            listFullyConcealed = ordinary.listFullyConcealed,
            listMaxDepth = ordinary.listMaxDepth,
            listDepths = ordinary.listDepths,
            listMarkers = ordinary.listMarkers,
            taskRows = ordinary.taskRows,
            checkedTasks = ordinary.checkedTasks,
            taskToggles = ordinary.taskToggles,
            refreshesApplied = refreshesApplied,
            refreshesScheduled = refreshesScheduled,
            sourceFallbacks = sourceFallbacks,
        )
    }

    fun tableEvidenceSnapshot(): NativeTablePresentationEvidence = tablePresentation.evidenceSnapshot()

    fun hostResourceEvidenceSnapshot(): NativeHostResourcePresentationEvidence? =
        hostResources?.evidenceSnapshot()

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

    private fun refreshActivityPresentation() {
        if (disposed || editor.isDisposed) return
        ApplicationManager.getApplication().assertIsDispatchThread()
        val plan = currentPlan ?: return
        if (!matchesCurrentIdentity(plan.identity)) return

        val document = editor.document
        val sourceBefore = document.immutableCharSequence.toString()
        val stampBefore = document.modificationStamp

        ordinaryPresentation.refreshActivity(plan, isRichPresentationEnabled())
        tablePresentation.refreshActivity(plan)
        hostResources?.refreshActivity(plan)
        derivedPresentation?.refreshActivity(plan)

        check(document.modificationStamp == stampBefore)
        check(document.immutableCharSequence.toString() == sourceBefore)
    }

    private fun isRichPresentationEnabled(): Boolean =
        runCatching(richPresentationEnabled).getOrDefault(false)

    private fun requireAlive() {
        check(!disposed) { "native presentation controller is disposed" }
        check(!editor.isDisposed) { "native editor is disposed" }
    }

    override fun dispose() {
        if (disposed) return
        ApplicationManager.getApplication().assertIsDispatchThread()
        refreshRequestGeneration += 1
        ordinaryPresentation.dispose()
        tablePresentation.dispose()
        hostResources?.dispose()
        derivedPresentation?.dispose()
        rawHtmlPresentation?.dispose()
        currentPlan = null
        disposed = true
    }
}
