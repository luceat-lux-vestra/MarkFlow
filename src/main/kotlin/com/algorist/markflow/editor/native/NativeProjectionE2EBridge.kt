package com.algorist.markflow.editor.native

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Disposer
import java.util.IdentityHashMap

/**
 * Narrow, inert diagnostic seam for Starter/Driver acceptance tests before #153 production cutover.
 *
 * This object has no registration, startup hook, action, or test-framework dependency. It can only
 * affect an Editor when an external diagnostic client explicitly invokes [attach]. Production
 * editor lifecycle ownership remains unchanged until the dedicated cutover task.
 *
 * The Starter/Driver client resolves this object by its remote class name, so static analysis cannot
 * observe the call edge.
 */
@Suppress("unused")
internal object NativeProjectionE2EBridge {
    private val controllers = IdentityHashMap<Editor, NativePresentationController>()

    fun attach(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        check(!editor.isDisposed) { "cannot attach native projection to a disposed editor" }
        if (controllers.containsKey(editor)) return false
        controllers[editor] = NativePresentationController(editor)
        return true
    }

    fun detach(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val controller = controllers.remove(editor) ?: return false
        Disposer.dispose(controller)
        return true
    }

    fun isAttached(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return controllers.containsKey(editor)
    }

    fun planReady(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return requireController(editor).currentPlan?.status == ProjectionPlanStatus.READY
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

    private fun requireController(editor: Editor): NativePresentationController =
        checkNotNull(controllers[editor]) { "native projection E2E controller is not attached" }
}
