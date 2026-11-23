package com.algorist.markflow.editor.native

import com.algorist.markflow.file.MarkFlowFileSupport
import com.algorist.markflow.renderer.DerivedRendererRuntime
import com.algorist.markflow.renderer.DerivedRendererRuntimeProvider
import com.algorist.markflow.settings.MarkFlowPresentationSettingsSink
import com.algorist.markflow.settings.MarkFlowSettingsService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

/** #563 production presentation owner. Only the owned FileEditor is registered for attachment. */
internal object NativeMarkFlowProductionLifecycle {
    private val controllerKey = Key.create<NativePresentationController>(
        "com.algorist.markflow.native.production.controller"
    )
    private val ownedControllers = IdentityHashMap<Editor, NativePresentationController>()
    private val liveOwnedControllers = AtomicInteger(0)

    private fun controller(editor: Editor): NativePresentationController? = editor.getUserData(controllerKey)

    internal fun hasControllerForDiagnostics(editor: Editor): Boolean = controller(editor) != null

    fun ownedController(editor: Editor): NativePresentationController? {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return ownedControllers[editor]
    }

    internal val liveOwnedControllerCountForDiagnostics: Int
        get() = liveOwnedControllers.get()

    private fun attachController(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (editor.isDisposed || editor.isViewer || controller(editor) != null) return false
        val project = editor.project ?: return false
        if (project.isDisposed) return false
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return false
        if (!MarkFlowFileSupport.isMarkFlowTarget(file)) return false

        var hostResources: NativeHostResourcePresentationController? = null
        var derivedPresentation: NativeDerivedPresentationController? = null
        var rawHtmlPresentation: NativeRawHtmlPresentationController? = null
        return try {
            hostResources = NativeHostResourcePresentationController(editor)
            derivedPresentation = NativeDerivedPresentationController(
                editor = editor,
                runtime = null,
                runtimeProvider = ::createRendererRuntimeOrNull,
            )
            rawHtmlPresentation = NativeRawHtmlPresentationController(editor)
            val controller = NativePresentationController(
                editor = editor,
                configGeneration = {
                    MarkFlowSettingsService.getInstance().runtimeSettings().settingsRevision.toLong()
                },
                derivedPresentation = derivedPresentation,
                hostResources = hostResources,
                rawHtmlPresentation = rawHtmlPresentation,
            )
            editor.putUserData(controllerKey, controller)
            true
        } catch (failure: ProcessCanceledException) {
            cleanupPartial(rawHtmlPresentation, derivedPresentation, hostResources)
            throw failure
        } catch (failure: Throwable) {
            cleanupPartial(rawHtmlPresentation, derivedPresentation, hostResources)
            LOG.warn(
                "MarkFlow native production presentation could not attach; exact source editor remains available",
                failure,
            )
            false
        }
    }

    private fun releaseController(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val controller = controller(editor) ?: return false
        editor.putUserData(controllerKey, null)
        runCatching { Disposer.dispose(controller) }
            .onFailure { failure -> LOG.warn("MarkFlow native production presentation dispose failed", failure) }
        return true
    }

    /**
     * Attach presentation under the MarkFlow-owned FileEditor lifecycle.
     *
     * The owned registry is the only production attachment surface, so a bundled IntelliJ editor
     * can never acquire MarkFlow presentation merely by being eligible Markdown.
     */
    fun attachOwned(editor: Editor): Boolean {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater { attachOwned(editor) }
            return false
        }
        if (ownedControllers.containsKey(editor)) return false
        if (!attachController(editor)) return false
        val controller = controller(editor) ?: return false
        ownedControllers[editor] = controller
        liveOwnedControllers.incrementAndGet()
        return true
    }

    fun releaseOwned(editor: Editor): Boolean {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater { releaseOwned(editor) }
            return false
        }
        val owned = ownedControllers[editor] ?: return false
        if (controller(editor) !== owned) return false
        if (!releaseController(editor)) return false
        ownedControllers.remove(editor)
        liveOwnedControllers.decrementAndGet()
        return true
    }

    /** Refresh only live MarkFlow-owned presentation controllers. */
    fun refreshOwned() {
        val application = ApplicationManager.getApplication()
        if (!application.isDispatchThread) {
            application.invokeLater(::refreshOwned)
            return
        }
        ownedControllers.values.toList().forEach(::refreshController)
    }

    private fun refreshController(controller: NativePresentationController) {
        runCatching { controller.refreshNow() }
            .onFailure { failure ->
                if (failure is ProcessCanceledException) throw failure
                LOG.warn(
                    "MarkFlow native production presentation refresh failed; exact source remains authoritative",
                    failure,
                )
            }
    }

    private fun createRendererRuntimeOrNull(): DerivedRendererRuntime? = try {
        DerivedRendererRuntimeProvider.createOrNull()
    } catch (failure: ProcessCanceledException) {
        throw failure
    } catch (failure: Throwable) {
        LOG.warn(
            "MarkFlow optional derived renderer unavailable; native source editing continues without rich derived previews",
            failure,
        )
        null
    }

    private fun cleanupPartial(
        rawHtmlPresentation: NativeRawHtmlPresentationController?,
        derivedPresentation: NativeDerivedPresentationController?,
        hostResources: NativeHostResourcePresentationController?,
    ) {
        runCatching { rawHtmlPresentation?.let(Disposer::dispose) }
        runCatching { derivedPresentation?.let(Disposer::dispose) }
        runCatching { hostResources?.let(Disposer::dispose) }
    }

    private val LOG = Logger.getInstance(NativeMarkFlowProductionLifecycle::class.java)
}

/** Base-plugin presentation invalidation sink; optional renderer ownership remains separate. */
class NativeMarkFlowPresentationSettingsSink : MarkFlowPresentationSettingsSink {
    override fun presentationSettingsChanged() {
        NativeMarkFlowProductionLifecycle.refreshOwned()
    }
}
