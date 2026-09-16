package com.algorist.markflow.editor.native

import com.algorist.markflow.file.MarkFlowFileSupport
import com.algorist.markflow.renderer.DerivedRendererRuntime
import com.algorist.markflow.renderer.DerivedRendererRuntimeProvider
import com.algorist.markflow.settings.MarkFlowRuntimeSettingsSink
import com.algorist.markflow.settings.MarkFlowSettingsService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import java.util.IdentityHashMap

/**
 * Production #153 owner for MarkFlow presentation on the platform text editor.
 *
 * The platform [Editor] and its authoritative IntelliJ Document remain the editing authority. This
 * service only attaches source-neutral presentation owners. Optional renderer/JCEF construction is
 * isolated below [DerivedRendererRuntimeProvider]; failure to create it degrades derived previews
 * without preventing the native editor or ordinary Markdown presentation from attaching.
 */
class NativeProductionEditingService : Disposable {
    private val controllers = IdentityHashMap<Editor, NativePresentationController>()
    private var disposed = false

    fun editorCreated(editor: Editor) {
        onEdt { attachIfTarget(editor) }
    }

    fun editorReleased(editor: Editor) {
        onEdt { detach(editor) }
    }

    fun refreshRuntimeSettings() {
        onEdt {
            if (disposed) return@onEdt
            controllers.entries.toList().forEach { (editor, controller) ->
                if (editor.isDisposed) {
                    controllers.remove(editor)
                    runCatching { Disposer.dispose(controller) }
                } else {
                    runCatching { controller.refreshNow() }
                        .onFailure { failure ->
                            LOG.warn("MarkFlow native production settings refresh failed; exact source remains authoritative", failure)
                        }
                }
            }
        }
    }

    internal fun isAttached(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return controllers.containsKey(editor)
    }

    internal fun controllerForDiagnostics(editor: Editor): NativePresentationController? {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return controllers[editor]
    }

    internal fun attachedEditorsForDiagnostics(): Int {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return controllers.size
    }

    private fun attachIfTarget(editor: Editor) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (disposed || editor.isDisposed || controllers.containsKey(editor)) return
        val project = editor.project ?: return
        if (project.isDisposed) return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (!MarkFlowFileSupport.isMarkFlowTarget(file)) return

        val settings = MarkFlowSettingsService.getInstance()
        val controller = runCatching {
            createFullController(editor, settings)
        }.getOrElse { failure ->
            LOG.warn(
                "MarkFlow native optional presentation failed to attach for ${file.path}; falling back to ordinary exact-source-safe presentation",
                failure,
            )
            runCatching {
                NativePresentationController(
                    editor = editor,
                    configGeneration = { settings.runtimeSettings().settingsRevision.toLong() },
                )
            }.getOrElse { fallbackFailure ->
                LOG.warn(
                    "MarkFlow native presentation could not attach for ${file.path}; platform source editor remains available",
                    fallbackFailure,
                )
                return
            }
        }
        controllers[editor] = controller
    }

    private fun createFullController(
        editor: Editor,
        settings: MarkFlowSettingsService,
    ): NativePresentationController {
        val hostResources = NativeHostResourcePresentationController(editor)
        val rawHtml = NativeRawHtmlPresentationController(editor)
        var runtime: DerivedRendererRuntime? = null
        var derived: NativeDerivedPresentationController? = null
        try {
            runtime = runCatching { DerivedRendererRuntimeProvider.createOrNull() }
                .onFailure { failure ->
                    LOG.warn("MarkFlow optional derived renderer runtime unavailable; native source editing remains active", failure)
                }
                .getOrNull()
            derived = NativeDerivedPresentationController(
                editor = editor,
                runtime = runtime,
                settingsProvider = settings::runtimeSettings,
            )
            return NativePresentationController(
                editor = editor,
                configGeneration = { settings.runtimeSettings().settingsRevision.toLong() },
                derivedPresentation = derived,
                hostResources = hostResources,
                rawHtmlPresentation = rawHtml,
            )
        } catch (failure: Throwable) {
            if (derived != null) {
                runCatching { Disposer.dispose(derived) }
            } else {
                runCatching { runtime?.dispose() }
            }
            runCatching { Disposer.dispose(rawHtml) }
            runCatching { Disposer.dispose(hostResources) }
            throw failure
        }
    }

    private fun detach(editor: Editor) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        controllers.remove(editor)?.let { controller ->
            runCatching { Disposer.dispose(controller) }
                .onFailure { failure -> LOG.warn("MarkFlow native production controller disposal failed", failure) }
        }
    }

    private fun onEdt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) action() else application.invokeLater(action)
    }

    override fun dispose() {
        val application = ApplicationManager.getApplication()
        if (!application.isDispatchThread) {
            application.invokeAndWait { dispose() }
            return
        }
        if (disposed) return
        disposed = true
        controllers.values.toList().asReversed().forEach { controller ->
            runCatching { Disposer.dispose(controller) }
        }
        controllers.clear()
    }

    companion object {
        private val LOG = Logger.getInstance(NativeProductionEditingService::class.java)

        fun getInstance(): NativeProductionEditingService =
            ApplicationManager.getApplication().getService(NativeProductionEditingService::class.java)
    }
}

/** Maintained editor lifecycle hook selected by #143 and activated for production by #153. */
class NativeProductionEditorFactoryListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        NativeProductionEditingService.getInstance().editorCreated(event.editor)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        NativeProductionEditingService.getInstance().editorReleased(event.editor)
    }
}

/** Rebuilds source-neutral native presentation when product renderer/presentation settings change. */
class NativeProductionRuntimeSettingsSink : MarkFlowRuntimeSettingsSink {
    override fun runtimeSettingsChanged(forceReload: Boolean) {
        NativeProductionEditingService.getInstance().refreshRuntimeSettings()
    }
}
