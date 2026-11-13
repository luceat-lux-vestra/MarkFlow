package com.algorist.markflow.editor.native

import com.algorist.markflow.file.MarkFlowFileSupport
import com.algorist.markflow.renderer.DerivedRendererRuntime
import com.algorist.markflow.renderer.DerivedRendererRuntimeProvider
import com.algorist.markflow.settings.MarkFlowPresentationSettingsSink
import com.algorist.markflow.settings.MarkFlowSettingsService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production presentation lifecycle for ADR 0003.
 *
 * #563 makes [attachOwned]/[releaseOwned] the production ownership boundary through the
 * MarkFlow-owned FileEditor. Generic [attach]/[release] and the old EditorFactory listener remain
 * temporarily as rollback/comparison code only and are no longer registered production attachment
 * paths.
 *
 * Optional derived renderer creation is fail-closed: renderer absence/failure never prevents exact
 * Markdown source editing.
 */
internal object NativeMarkFlowProductionLifecycle {
    private val controllerKey = Key.create<NativePresentationController>(
        "com.algorist.markflow.native.production.controller"
    )
    private val ownedControllers = IdentityHashMap<Editor, NativePresentationController>()
    private val liveOwnedControllers = AtomicInteger(0)

    fun controller(editor: Editor): NativePresentationController? = editor.getUserData(controllerKey)

    fun ownedController(editor: Editor): NativePresentationController? {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return ownedControllers[editor]
    }

    internal val liveOwnedControllerCountForDiagnostics: Int
        get() = liveOwnedControllers.get()

    fun attach(editor: Editor): Boolean {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater { attach(editor) }
            return false
        }
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
                // Product now replaces parser-proven Heading source lines instead
                // of adding a second block-inlay height. The old additive A
                // baseline is limited to a named IDE evidence-only fixture.
                headingSourceLineReplacement = !(
                    System.getProperty("markflow.starter.headingLegacyBaselinePilot") == "true" &&
                        file.name == "md-atx-headings-h1-h6.md"
                ),
                // Reuse the actual B layout typography as the product 13px
                // Preview baseline. Text scales with editor font size; the
                // section gap and fold padding are editor-relative em tokens.
                // Normal product does NOT downsize H1 to the 22px base line.
                headingNativeEditorTypography = false,
                headingPreviewPilotGap = true,
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

    fun release(editor: Editor): Boolean {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater { release(editor) }
            return false
        }
        val controller = controller(editor) ?: return false
        editor.putUserData(controllerKey, null)
        runCatching { Disposer.dispose(controller) }
            .onFailure { failure -> LOG.warn("MarkFlow native production presentation dispose failed", failure) }
        return true
    }

    /**
     * Attach presentation under the MarkFlow-owned FileEditor lifecycle.
     *
     * This registry is intentionally separate from generic production attachment so a bundled
     * IntelliJ editor can never become an owned MarkFlow editor merely by being eligible Markdown.
     */
    fun attachOwned(editor: Editor): Boolean {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater { attachOwned(editor) }
            return false
        }
        if (ownedControllers.containsKey(editor)) return false
        if (!attach(editor)) return false
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
        if (!release(editor)) return false
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

    fun refreshAll() {
        val application = ApplicationManager.getApplication()
        if (!application.isDispatchThread) {
            application.invokeLater(::refreshAll)
            return
        }
        EditorFactory.getInstance().allEditors.forEach { editor ->
            val controller = controller(editor) ?: return@forEach
            if (editor.isDisposed) return@forEach
            refreshController(controller)
        }
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

/**
 * Historical #153 platform-augmentation hook retained temporarily for rollback/comparison.
 * Slice 4 owns deletion after the owned-shell replacement evidence is stable on main.
 */
@Suppress("unused")
class NativeMarkFlowEditorFactoryListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        if (skipAutomaticProductionAttachment()) return
        NativeMarkFlowProductionLifecycle.attach(event.editor)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        if (skipAutomaticProductionAttachment()) return
        NativeMarkFlowProductionLifecycle.release(event.editor)
    }

    private fun skipAutomaticProductionAttachment(): Boolean {
        val application = ApplicationManager.getApplication()
        if (application.isUnitTestMode) return true
        return System.getProperties().stringPropertyNames().any { property ->
            property.startsWith("markflow.") && property.endsWith("Probe.output")
        }
    }
}

/** Production settings invalidation reaches MarkFlow-owned presentation only. */
class NativeMarkFlowPresentationSettingsSink : MarkFlowPresentationSettingsSink {
    override fun presentationSettingsChanged() {
        NativeMarkFlowProductionLifecycle.refreshOwned()
    }
}
