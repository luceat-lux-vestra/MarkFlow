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

/**
 * #153 production owner for MarkFlow's native projection architecture.
 *
 * The platform text editor remains the FileEditor and authoritative source editor. MarkFlow owns
 * only one source-neutral presentation controller per eligible native Editor. Optional derived
 * renderer creation is fail-closed: renderer absence/failure never prevents the platform editor
 * from opening or editing exact Markdown source.
 */
internal object NativeMarkFlowProductionLifecycle {
    private val controllerKey = Key.create<NativePresentationController>(
        "com.algorist.markflow.native.production.controller"
    )
    private val researchPageKey = Key.create<NativePageGeometryPilot>(
        "com.algorist.markflow.native.research.pageGeometryPilot"
    )

    fun researchPagePilot(editor: Editor): NativePageGeometryPilot? =
        editor.getUserData(researchPageKey)

    fun controller(editor: Editor): NativePresentationController? = editor.getUserData(controllerKey)

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

        var runtime: DerivedRendererRuntime? = null
        var researchPage: NativePageGeometryPilot? = null
        var hostResources: NativeHostResourcePresentationController? = null
        var derivedPresentation: NativeDerivedPresentationController? = null
        var rawHtmlPresentation: NativeRawHtmlPresentationController? = null
        return try {
            // Research-only exact B fixture, never a production Markdown editor.
            // Applied before heading custom folds so all geometry uses the same
            // editor-specific scheme/insets. No global settings are modified.
            if (System.getProperty("markflow.starter.pageBoxPilot") == "true" &&
                file.name == "heading-layout-b.md") {
                researchPage = NativePageGeometryPilot.install(editor)
                check(researchPage != null) {
                    "Research page layout pilot rejected this fixture (mixed syntax or IDE state)"
                }
            }
            runtime = createRendererRuntimeOrNull()
            hostResources = NativeHostResourcePresentationController(editor)
            derivedPresentation = NativeDerivedPresentationController(editor, runtime)
            rawHtmlPresentation = NativeRawHtmlPresentationController(editor)
            val controller = NativePresentationController(
                editor = editor,
                configGeneration = {
                    MarkFlowSettingsService.getInstance().runtimeSettings().settingsRevision.toLong()
                },
                derivedPresentation = derivedPresentation,
                hostResources = hostResources,
                rawHtmlPresentation = rawHtmlPresentation,
                // Explicit, fixture-scoped Starter IDEA opt-in only. Normal production stays
                // on legacy headings until #350/#353 differential acceptance is complete.
                headingSourceLineReplacement =
                    (System.getProperty("markflow.starter.headingRobotPilot") == "true" &&
                        file.name == "heading-robot.md") ||
                    // Independently scoped to the exact byte-identical B fixture.
                    // Product and every other Markdown file keep legacy headings.
                    (System.getProperty("markflow.starter.headingLayoutAbPilot") == "true" &&
                        file.name == "heading-layout-b.md"),
            )
            editor.putUserData(controllerKey, controller)
            editor.putUserData(researchPageKey, researchPage)
            true
        } catch (failure: ProcessCanceledException) {
            cleanupPartial(rawHtmlPresentation, derivedPresentation, hostResources, runtime)
            runCatching { researchPage?.dispose() }
            throw failure
        } catch (failure: Throwable) {
            cleanupPartial(rawHtmlPresentation, derivedPresentation, hostResources, runtime)
            runCatching { researchPage?.dispose() }
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
        val researchPage = editor.getUserData(researchPageKey)
        editor.putUserData(researchPageKey, null)
        runCatching { Disposer.dispose(controller) }
            .onFailure { failure -> LOG.warn("MarkFlow native production presentation dispose failed", failure) }
        runCatching { researchPage?.dispose() }
            .onFailure { failure -> LOG.warn("Research-only page geometry restoration failed", failure) }
        return true
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
            runCatching { controller.refreshNow() }
                .onFailure { failure ->
                    if (failure is ProcessCanceledException) throw failure
                    LOG.warn(
                        "MarkFlow native production presentation refresh failed; exact source remains authoritative",
                        failure,
                    )
                }
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
        runtime: DerivedRendererRuntime?,
    ) {
        runCatching { rawHtmlPresentation?.let(Disposer::dispose) }
        if (derivedPresentation != null) {
            runCatching { Disposer.dispose(derivedPresentation) }
        } else {
            runCatching { runtime?.dispose() }
        }
        runCatching { hostResources?.let(Disposer::dispose) }
    }

    private val LOG = Logger.getInstance(NativeMarkFlowProductionLifecycle::class.java)
}

/** Maintained IntelliJ editor lifecycle hook selected by #143 and activated by #153. */
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

/** Base-plugin presentation invalidation sink; optional renderer ownership remains separate. */
class NativeMarkFlowPresentationSettingsSink : MarkFlowPresentationSettingsSink {
    override fun presentationSettingsChanged() {
        NativeMarkFlowProductionLifecycle.refreshAll()
    }
}
