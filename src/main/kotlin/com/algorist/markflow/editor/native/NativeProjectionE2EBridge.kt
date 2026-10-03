package com.algorist.markflow.editor.native

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor

/**
 * Narrow diagnostic seam for Starter/Driver acceptance after #153 production cutover.
 *
 * The bridge never creates, attaches, detaches, or owns presentation controllers. Every query
 * resolves the controller installed by [NativeMarkFlowProductionLifecycle] through the normal
 * platform-editor opening path. Starter/Driver tests therefore observe production ownership rather
 * than constructing a parallel test-only editor lifecycle.
 */
@Suppress("unused")
internal object NativeProjectionE2EBridge {
    fun isAttached(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return NativeMarkFlowProductionLifecycle.controller(editor) != null
    }

    fun planReady(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return requireController(editor).currentPlan?.status == ProjectionPlanStatus.READY
    }

    fun planDegradedToSource(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return requireController(editor).currentPlan?.status == ProjectionPlanStatus.DEGRADED_TO_SOURCE
    }

    /**
     * Exercise the real typed source-fallback application path without constructing a parallel
     * renderer or mutating the authoritative Document. The plan retains the production controller's
     * exact current source/config identity, so normal stale-plan and source-neutrality gates apply.
     */
    fun degradeToSource(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val controller = requireController(editor)
        val identity = requireNotNull(controller.currentPlan?.identity) {
            "production native MarkFlow controller has no current plan"
        }
        val degraded = NativeProjectionPlan(
            identity = identity,
            projections = emptyList(),
            status = ProjectionPlanStatus.DEGRADED_TO_SOURCE,
            failureClass = "synthetic.StarterRendererFailure",
        )
        return controller.tryApply(degraded) == ProjectionApplyResult.DEGRADED_TO_SOURCE
    }

    fun ownedHighlighters(editor: Editor): Int {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return requireController(editor).evidenceSnapshot().ownedHighlighters
    }

    fun ownedFolds(editor: Editor): Int {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return requireController(editor).evidenceSnapshot().ownedFolds
    }

    fun hasProjection(editor: Editor, kind: String, startOffset: Int, endOffset: Int): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val projectionKind = runCatching { NativeProjectionKind.valueOf(kind) }.getOrNull() ?: return false
        return requireController(editor).currentPlan?.projections?.any { projection ->
            projection.kind == projectionKind &&
                projection.sourceRange.startOffset == startOffset &&
                projection.sourceRange.endOffset == endOffset
        } == true
    }

    fun sameDocument(first: Editor, second: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return first.document === second.document
    }

    fun tableModels(editor: Editor): Int {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return requireController(editor).tableEvidenceSnapshot().tableModels
    }

    fun tableOwnedInlays(editor: Editor): Int {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return requireController(editor).tableEvidenceSnapshot().ownedInlays
    }

    fun tableOwnedFolds(editor: Editor): Int {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return requireController(editor).tableEvidenceSnapshot().ownedFolds
    }

    fun tableMouseReveals(editor: Editor): Long {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return requireController(editor).tableEvidenceSnapshot().mouseReveals
    }

    fun tableInlayCenterX(editor: Editor): Int {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val bounds = requireTableInlay(editor).bounds ?: return -1
        return bounds.x + bounds.width / 2
    }

    fun tableInlayCenterY(editor: Editor): Int {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val bounds = requireTableInlay(editor).bounds ?: return -1
        return bounds.y + bounds.height / 2
    }

    fun hostLocalImages(editor: Editor): Int {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return requireController(editor).hostResourceEvidenceSnapshot()?.localImages ?: 0
    }

    fun hostExternalLinks(editor: Editor): Int {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return requireController(editor).hostResourceEvidenceSnapshot()?.externalLinks ?: 0
    }

    fun derivedFragments(editor: Editor): Int {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return requireController(editor).derivedEvidenceSnapshot()?.derivedFragments ?: 0
    }

    fun derivedPendingRequests(editor: Editor): Int {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return requireController(editor).derivedEvidenceSnapshot()?.pendingRequests ?: 0
    }

    fun derivedDecodedArtifacts(editor: Editor): Int {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return requireController(editor).derivedEvidenceSnapshot()?.decodedArtifacts ?: 0
    }

    fun derivedOwnedInlays(editor: Editor): Int {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return requireController(editor).derivedEvidenceSnapshot()?.ownedInlays ?: 0
    }

    fun derivedOwnedFolds(editor: Editor): Int {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return requireController(editor).derivedEvidenceSnapshot()?.ownedFolds ?: 0
    }

    fun derivedRendererFailures(editor: Editor): Long {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return requireController(editor).derivedEvidenceSnapshot()?.rendererFailures ?: 0L
    }

    fun derivedMissingArtifacts(editor: Editor): Long {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return requireController(editor).derivedEvidenceSnapshot()?.missingArtifacts ?: 0L
    }

    fun rawHtmlFragments(editor: Editor): Int {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return requireController(editor).rawHtmlEvidenceSnapshot()?.fragments ?: 0
    }

    private fun requireTableInlay(editor: Editor) =
        editor.inlayModel
            .getBlockElementsInRange(0, editor.document.textLength)
            .singleOrNull { inlay -> inlay.renderer is NativeTableInlayRenderer }
            ?: error("expected exactly one visible MarkFlow table inlay")

    private fun requireController(editor: Editor): NativePresentationController =
        checkNotNull(NativeMarkFlowProductionLifecycle.controller(editor)) {
            "production native MarkFlow controller is not attached"
        }
}
