package com.algorist.markflow.editor.native

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Narrow maintained seam for real-IDE acceptance tests before #153 makes the native controller the
 * production opening path. It exposes only semantic presentation counts; it has no Driver/Starter
 * dependency and no source-write authority.
 *
 * Delete this bridge when #153 can bind the E2E adapter to the production native editor lifecycle.
 */
class NativePresentationE2EBridge {
    private val controllers = IdentityHashMap<Editor, NativePresentationController>()

    fun attach(editor: Editor): Boolean = onEdt {
        controllers.remove(editor)?.dispose()
        controllers[editor] = NativePresentationController(editor)
        true
    }

    fun ownedFoldCount(editor: Editor): Int = onEdt {
        controller(editor).evidenceSnapshot().ownedFolds
    }

    fun collapsedFoldCount(editor: Editor): Int = onEdt {
        controller(editor).evidenceSnapshot().collapsedFolds
    }

    fun tableModelCount(editor: Editor): Int = onEdt {
        controller(editor).tableEvidenceSnapshot().tableModels
    }

    fun tableInlayCount(editor: Editor): Int = onEdt {
        controller(editor).tableEvidenceSnapshot().ownedInlays
    }

    fun tableMouseRevealCount(editor: Editor): Long = onEdt {
        controller(editor).tableEvidenceSnapshot().mouseReveals
    }

    fun dispose(editor: Editor) {
        onEdt {
            controllers.remove(editor)?.dispose()
            Unit
        }
    }

    private fun controller(editor: Editor): NativePresentationController =
        controllers[editor] ?: error("native presentation E2E bridge is not attached to this editor")

    private fun <T> onEdt(block: () -> T): T {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) return block()
        val result = AtomicReference<Result<T>>()
        application.invokeAndWait { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }
}
