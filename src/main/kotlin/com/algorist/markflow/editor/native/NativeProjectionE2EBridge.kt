package com.algorist.markflow.editor.native

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor

/**
 * Narrow diagnostic seam for Starter/Driver acceptance on the #153 production opening path.
 *
 * Unlike the pre-cutover bridge, this object never creates or disposes presentation controllers.
 * Production lifecycle ownership belongs exclusively to [NativeProductionPresentationManager].
 * The legacy [attach]/[detach] method names are retained temporarily as a remote ABI for the #193
 * scenarios; both are assertion-only and return whether the production manager already owns the
 * editor. This prevents those scenarios from accidentally manufacturing their own proof surface.
 */
@Suppress("unused")
internal object NativeProjectionE2EBridge {
    /** Legacy remote ABI: assertion-only; does not attach anything. */
    fun attach(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return manager().isAttached(editor)
    }

    /** Legacy remote ABI: assertion-only; does not detach production ownership. */
    fun detach(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return manager().isAttached(editor)
    }

    fun isAttached(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return manager().isAttached(editor)
    }

    fun productionOwnedEditors(): Int {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return manager().attachedEditors()
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
     * Exercise the real typed source-fallback application path on the production-owned controller.
     * The plan retains the exact current source/config identity, so normal stale-plan and
     * source-neutrality contracts remain authoritative.
     */
    fun degradeToSource(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val controller = requireController(editor)
        val identity = requireNotNull(controller.currentPlan?.identity) {
            "production native projection controller has no current plan"
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

    fun rawHtmlBlockedPreviews(editor: Editor): Long {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return requireController(editor).rawHtmlEvidenceSnapshot()?.blockedPreviews ?: 0L
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

    private fun requireTableInlay(editor: Editor) =
        editor.inlayModel
            .getBlockElementsInRange(0, editor.document.textLength)
            .singleOrNull { inlay -> inlay.renderer is NativeTableInlayRenderer }
            ?: error("expected exactly one visible MarkFlow table inlay")

    private fun manager(): NativeProductionPresentationManager =
        NativeProductionPresentationManager.getInstance()

    private fun requireController(editor: Editor): NativePresentationController =
        checkNotNull(manager().controllerFor(editor)) {
            "production native projection controller is not attached"
        }
}
