package com.algorist.markflow.editor.native

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import java.util.IdentityHashMap

/**
 * Narrow, inert diagnostic seam for Starter/Driver acceptance tests before #153 production cutover.
 *
 * This object has no registration, startup hook, action, or test-framework dependency. It can only
 * affect an Editor when an external diagnostic client explicitly invokes [attach]. Production
 * editor lifecycle ownership remains unchanged until the dedicated cutover task.
 */
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
        controller.dispose()
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

    private fun requireController(editor: Editor): NativePresentationController =
        checkNotNull(controllers[editor]) { "native projection E2E controller is not attached" }
}
