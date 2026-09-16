package com.algorist.markflow.editor.native

import com.algorist.markflow.file.MarkFlowFileSupport
import com.algorist.markflow.renderer.DerivedRendererRuntimeProvider
import com.algorist.markflow.settings.MarkFlowRuntimeSettingsSink
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Production owner selected by #143/#153 for platform-text-editor augmentation.
 *
 * One controller is owned per relevant native IntelliJ [Editor]. The authoritative [Document]
 * stays platform-owned; this service owns presentation only. Renderer/JCEF availability is optional:
 * failure to create or use a derived renderer degrades rich presentation but never prevents the
 * native source editor from existing or editing/saving exact Markdown.
 */
class NativeProductionPresentationManager : Disposable {
    private val controllers = IdentityHashMap<Editor, NativePresentationController>()
    private val configGeneration = AtomicLong(1L)
    private var disposed = false

    init {
        // Cover dynamic plugin load / already-created source editors as well as the normal listener
        // path. Duplicate attachment is identity-guarded below.
        ApplicationManager.getApplication().invokeLater {
            if (!disposed) {
                EditorFactory.getInstance().allEditors.forEach(::attachIfTarget)
            }
        }
    }

    internal fun editorCreated(editor: Editor) {
        onEdt { attachIfTarget(editor) }
    }

    internal fun editorReleased(editor: Editor) {
        onEdt { detach(editor) }
    }

    internal fun runtimeSettingsChanged() {
        onEdt {
            if (disposed) return@onEdt
            configGeneration.incrementAndGet()
            controllers.entries.toList().forEach { (editor, controller) ->
                if (editor.isDisposed) {
                    controllers.remove(editor)
                    return@forEach
                }
                runCatching { controller.refreshNow() }
                    .onFailure { failure ->
                        // Presentation refresh is explicitly non-authoritative. Keep the platform
                        // editor alive and exact source visible/editable on any rich-path failure.
                        LOG.warn("MARKFLOW_NATIVE production settings refresh degraded to source", failure)
                    }
            }
        }
    }

    internal fun isAttached(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return controllers.containsKey(editor)
    }

    internal fun controllerFor(editor: Editor): NativePresentationController? {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return controllers[editor]
    }

    internal fun attachedEditors(): Int {
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

        val document = editor.document
        val sourceBefore = document.immutableCharSequence.toString()
        val stampBefore = document.modificationStamp

        var derived: NativeDerivedPresentationController? = null
        var hostResources: NativeHostResourcePresentationController? = null
        var rawHtml: NativeRawHtmlPresentationController? = null
        var controller: NativePresentationController? = null

        try {
            val runtime = runCatching { DerivedRendererRuntimeProvider.createOrNull() }
                .onFailure { failure ->
                    LOG.warn("MARKFLOW_NATIVE optional derived renderer unavailable; keeping source editing", failure)
                }
                .getOrNull()
            derived = NativeDerivedPresentationController(editor, runtime)
            hostResources = NativeHostResourcePresentationController(editor)
            rawHtml = NativeRawHtmlPresentationController(editor)
            controller = NativePresentationController(
                editor = editor,
                configGeneration = { configGeneration.get() },
                derivedPresentation = derived,
                hostResources = hostResources,
                rawHtmlPresentation = rawHtml,
                refreshOnCreate = false,
            )

            // Ownership transfers to the aggregate controller after successful construction.
            derived = null
            hostResources = null
            rawHtml = null

            controller.refreshNow()
            check(document.modificationStamp == stampBefore) {
                "production native presentation changed Document modification stamp while attaching"
            }
            check(document.immutableCharSequence.toString() == sourceBefore) {
                "production native presentation changed authoritative Markdown while attaching"
            }
            controllers[editor] = controller
        } catch (failure: Throwable) {
            // Never allow presentation startup to replace the platform source editor failure mode.
            // Dispose every resource that was successfully created, then leave the editor untouched.
            runCatching {
                controller?.let(Disposer::dispose)
                    ?: run {
                        rawHtml?.let(Disposer::dispose)
                        hostResources?.let(Disposer::dispose)
                        derived?.let(Disposer::dispose)
                    }
            }
            LOG.warn("MARKFLOW_NATIVE production presentation attach failed; exact source remains active", failure)
        }
    }

    private fun detach(editor: Editor) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        controllers.remove(editor)?.let { controller ->
            runCatching { Disposer.dispose(controller) }
                .onFailure { failure -> LOG.warn("MARKFLOW_NATIVE controller disposal failed", failure) }
        }
    }

    private fun onEdt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) action() else application.invokeLater(action)
    }

    override fun dispose() {
        if (disposed) return
        val application = ApplicationManager.getApplication()
        val cleanup = {
            if (!disposed) {
                controllers.values.toList().forEach { controller ->
                    runCatching { Disposer.dispose(controller) }
                }
                controllers.clear()
                disposed = true
            }
        }
        if (application.isDispatchThread) cleanup() else application.invokeAndWait(cleanup)
    }

    companion object {
        private val LOG = Logger.getInstance(NativeProductionPresentationManager::class.java)

        internal fun getInstance(): NativeProductionPresentationManager =
            ApplicationManager.getApplication().getService(NativeProductionPresentationManager::class.java)
    }
}

/** Maintained platform lifecycle hook selected by #143. */
class NativeProductionEditorFactoryListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        NativeProductionPresentationManager.getInstance().editorCreated(event.editor)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        NativeProductionPresentationManager.getInstance().editorReleased(event.editor)
    }
}

/**
 * Base-plugin settings sink. Browser-only state is never interpreted here; any runtime-setting
 * invalidation simply advances presentation identity and refreshes native derived presentation.
 */
class NativeProductionRuntimeSettingsSink : MarkFlowRuntimeSettingsSink {
    override fun runtimeSettingsChanged(forceReload: Boolean) {
        NativeProductionPresentationManager.getInstance().runtimeSettingsChanged()
    }
}
