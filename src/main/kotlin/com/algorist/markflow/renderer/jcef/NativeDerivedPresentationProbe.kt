package com.algorist.markflow.renderer.jcef

import com.algorist.markflow.editor.native.NativeDerivedPresentationController
import com.algorist.markflow.editor.native.NativeDerivedProjectionKind
import com.algorist.markflow.editor.native.NativeDerivedProjectionPlanner
import com.algorist.markflow.editor.native.NativePresentationController
import com.algorist.markflow.editor.native.ProjectionPlanStatus
import com.algorist.markflow.editor.native.ProjectionSnapshot
import com.algorist.markflow.renderer.DerivedRendererIdentity
import com.algorist.markflow.renderer.DerivedRendererRuntime
import com.algorist.markflow.renderer.DerivedRendererRuntimeFactory
import com.algorist.markflow.renderer.DerivedRendererRuntimeProvider
import com.algorist.markflow.renderer.DerivedRendererRuntimeRequest
import com.algorist.markflow.renderer.DerivedRendererRuntimeResult
import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefApp
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real-IDE proof that #148 consumes the #144 runtime only as inert native editor presentation. */
internal object NativeDerivedPresentationProbe {
    const val OUTPUT_PROPERTY = "markflow.nativeDerivedPresentationProbe.output"
    const val SELECTED_SURFACE = "PLATFORM_TEXT_EDITOR_DERIVED_INLAYS"

    private val started = AtomicBoolean(false)

    fun startIfRequested(project: Project): Boolean {
        val output = System.getProperty(OUTPUT_PROPERTY)?.takeIf(String::isNotBlank) ?: return false
        if (!started.compareAndSet(false, true)) return true
        ApplicationManager.getApplication().invokeLater {
            Runner(Paths.get(output), project).start()
        }
        return true
    }

    private class Runner(
        private val output: Path,
        private val project: Project,
    ) {
        private val gson = GsonBuilder().setPrettyPrinting().create()
        private val cases = mutableListOf<CaseResult>()
        private val liveEditors = mutableListOf<PlatformEditorHandle>()
        private val liveControllers = mutableListOf<NativePresentationController>()
        private var tempRoot: Path? = null
        private var timeout: ScheduledFuture<*>? = null
        private var finished = false
        private var baselineLiveRuntimes = 0
        private lateinit var fixture: Fixture
        private lateinit var provider: FileEditorProvider
        private lateinit var first: PlatformEditorHandle
        private lateinit var second: PlatformEditorHandle
        private lateinit var firstController: NativePresentationController
        private lateinit var secondController: NativePresentationController
        private var sourceBefore = ""
        private var stampBefore = 0L
        private var dirtyBefore = false
        private var undoBefore = false

        fun start() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            armTimeout()
            try {
                check(!project.isDefault) { "#148 runtime proof requires a real opened project" }
                check(!project.isDisposed) { "#148 runtime proof project was already disposed" }
                baselineLiveRuntimes = JcefDerivedRendererRuntime.liveInstanceCountForDiagnostics

                case("jcef-runtime-available") {
                    check(JBCefApp.isSupported()) { "JCEF must be supported for the #148 success-path proof" }
                    val factories = DerivedRendererRuntimeFactory.EP_NAME.extensionList
                    check(factories.size == 1) { "expected exactly one derived renderer runtime factory" }
                    "jcefSupported=true factory=${factories.single().javaClass.name} baseline=$baselineLiveRuntimes"
                }

                fixture = createFixture()
                provider = selectPlatformTextProvider(fixture.file)
                first = createPlatformTextEditor(provider, fixture.file)
                second = createPlatformTextEditor(provider, fixture.file)
                check(first.editor.document === fixture.document)
                check(second.editor.document === fixture.document)
                first.editor.caretModel.moveToOffset(fixture.bodyOffset)
                second.editor.caretModel.moveToOffset(fixture.bodyOffset)

                sourceBefore = fixture.document.text
                stampBefore = fixture.document.modificationStamp
                dirtyBefore = FileDocumentManager.getInstance().isDocumentUnsaved(fixture.document)
                undoBefore = UndoManager.getInstance(project).isUndoAvailable(first.fileEditor)

                case("derived-projection-contract") {
                    val plan = com.algorist.markflow.editor.native.NativeMarkdownProjectionPlanner.plan(
                        ProjectionSnapshot.capture(fixture.document, configGeneration = 1L)
                    )
                    check(plan.status == ProjectionPlanStatus.READY)
                    val derived = NativeDerivedProjectionPlanner.plan(plan)
                    check(derived.size == 3) { "expected Mermaid + inline math + display math, observed ${derived.size}" }
                    check(derived.map { it.kind }.toSet() == setOf(
                        NativeDerivedProjectionKind.MERMAID,
                        NativeDerivedProjectionKind.KATEX_INLINE,
                        NativeDerivedProjectionKind.KATEX_DISPLAY,
                    ))
                    check(derived.all { it.sourceRange.isInside(sourceBefore) && it.contentRange.isInside(sourceBefore) })
                    "fragments=3 kinds=${derived.map { it.kind }} rangesInBounds=true"
                }

                firstController = createRealController(first)
                secondController = createRealController(second)
                awaitRealPresentation()
            } catch (failure: Throwable) {
                recordInternalFailure(failure)
                finish("INCOMPLETE")
            }
        }

        private fun awaitRealPresentation() {
            if (finished) return
            ApplicationManager.getApplication().assertIsDispatchThread()
            val firstEvidence = firstController.derivedEvidenceSnapshot()
            val secondEvidence = secondController.derivedEvidenceSnapshot()
            if (firstEvidence == null || secondEvidence == null) {
                failAsyncCase("real-mermaid-katex-inert-inlays", "derived presentation owner missing")
                return
            }
            val failed = listOf(firstEvidence, secondEvidence).any {
                it.pendingRequests == 0 && (it.rendererFailures > 0 || it.missingArtifacts > 0)
            }
            if (failed) {
                failAsyncCase(
                    "real-mermaid-katex-inert-inlays",
                    "renderer completed without complete artifacts first=$firstEvidence second=$secondEvidence",
                )
                return
            }
            val ready = listOf(firstEvidence, secondEvidence).all {
                it.pendingRequests == 0 &&
                    it.derivedFragments == 3 &&
                    it.decodedArtifacts == 3 &&
                    it.ownedInlays == 3 &&
                    it.ownedFolds == 3
            }
            if (ready) {
                runAfterRealPresentationReady()
                return
            }
            AppExecutorUtil.getAppScheduledExecutorService().schedule(
                { ApplicationManager.getApplication().invokeLater(::awaitRealPresentation) },
                POLL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }

        private fun runAfterRealPresentationReady() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            try {
                case("real-mermaid-katex-inert-inlays") {
                    val firstEvidence = requireNotNull(firstController.derivedEvidenceSnapshot())
                    val secondEvidence = requireNotNull(secondController.derivedEvidenceSnapshot())
                    check(firstEvidence.decodedArtifacts == 3 && firstEvidence.ownedInlays == 3 && firstEvidence.ownedFolds == 3)
                    check(secondEvidence.decodedArtifacts == 3 && secondEvidence.ownedInlays == 3 && secondEvidence.ownedFolds == 3)
                    check(JcefDerivedRendererRuntime.liveInstanceCountForDiagnostics == baselineLiveRuntimes + 2)
                    checkSourceStable()
                    "decodedPng=6 inlays=6 folds=6 liveRuntimeDelta=2 sourceStable=true"
                }

                case("caret-selection-exact-source-reveal") {
                    val secondBefore = requireNotNull(secondController.derivedEvidenceSnapshot())
                    check(secondBefore.ownedInlays == 3)

                    first.editor.caretModel.moveToOffset(fixture.inlineMathOffset)
                    val inlineReveal = requireNotNull(firstController.derivedEvidenceSnapshot())
                    check(inlineReveal.ownedInlays == 2 && inlineReveal.ownedFolds == 2) {
                        "inline math source did not reveal exactly: $inlineReveal"
                    }
                    check(requireNotNull(secondController.derivedEvidenceSnapshot()).ownedInlays == 3)

                    first.editor.caretModel.moveToOffset(fixture.bodyOffset)
                    check(requireNotNull(firstController.derivedEvidenceSnapshot()).ownedInlays == 3)

                    first.editor.selectionModel.setSelection(fixture.displayMathStart, fixture.displayMathEnd)
                    val displayReveal = requireNotNull(firstController.derivedEvidenceSnapshot())
                    check(displayReveal.ownedInlays == 2 && displayReveal.ownedFolds == 2) {
                        "display math source did not reveal exactly: $displayReveal"
                    }
                    first.editor.selectionModel.removeSelection()
                    check(requireNotNull(firstController.derivedEvidenceSnapshot()).ownedInlays == 3)
                    checkSourceStable()
                    "inlineCaretReveal=true displaySelectionReveal=true restored=true sourceStable=true"
                }

                case("split-editor-presentation-isolation") {
                    first.editor.caretModel.moveToOffset(fixture.inlineMathOffset)
                    check(requireNotNull(firstController.derivedEvidenceSnapshot()).ownedInlays == 2)
                    check(requireNotNull(secondController.derivedEvidenceSnapshot()).ownedInlays == 3) {
                        "first editor reveal leaked into second editor"
                    }
                    check(first.editor.document === second.editor.document)
                    first.editor.caretModel.moveToOffset(fixture.bodyOffset)
                    check(requireNotNull(firstController.derivedEvidenceSnapshot()).ownedInlays == 3)
                    "sharedDocument=true independentInlays=true independentReveal=true"
                }

                runFallbackCases()

                case("final-source-and-lifecycle-stability") {
                    checkSourceStable()
                    check(FileDocumentManager.getInstance().isDocumentUnsaved(fixture.document) == dirtyBefore)
                    check(UndoManager.getInstance(project).isUndoAvailable(first.fileEditor) == undoBefore)
                    disposeController(firstController)
                    disposeController(secondController)
                    disposeEditor(first)
                    disposeEditor(second)
                    check(liveControllers.isEmpty())
                    check(liveEditors.isEmpty())
                    check(EditorFactory.getInstance().getEditors(fixture.document, project).isEmpty()) {
                        "native editors retained after #148 proof disposal"
                    }
                    check(JcefDerivedRendererRuntime.liveInstanceCountForDiagnostics == baselineLiveRuntimes) {
                        "derived renderer runtime count did not return to baseline"
                    }
                    "sourceStable=true dirtyStable=true undoStable=true controllers=0 editors=0 runtimeBaselineRestored=true"
                }

                finish("PASS")
            } catch (failure: Throwable) {
                recordInternalFailure(failure)
                finish("INCOMPLETE")
            }
        }

        private fun runFallbackCases() {
            case("renderer-unavailable-source-fallback") {
                val handle = createPlatformTextEditor(provider, fixture.file)
                handle.editor.caretModel.moveToOffset(fixture.bodyOffset)
                val controller = createController(handle, runtime = null)
                val evidence = requireNotNull(controller.derivedEvidenceSnapshot())
                check(evidence.derivedFragments == 3)
                check(evidence.rendererFailures == 3L)
                check(evidence.ownedInlays == 0 && evidence.ownedFolds == 0)
                checkSourceStable()
                disposeController(controller)
                disposeEditor(handle)
                "fragments=3 rendererFailures=3 inlays=0 exactSourceFallback=true"
            }

            case("renderer-failure-source-fallback") {
                val handle = createPlatformTextEditor(provider, fixture.file)
                handle.editor.caretModel.moveToOffset(fixture.bodyOffset)
                val controller = createController(handle, FailingRuntime())
                val evidence = requireNotNull(controller.derivedEvidenceSnapshot())
                check(evidence.rendererFailures == 3L)
                check(evidence.ownedInlays == 0 && evidence.ownedFolds == 0)
                checkSourceStable()
                disposeController(controller)
                disposeEditor(handle)
                "rendererFailures=3 inlays=0 exactSourceFallback=true"
            }

            case("missing-artifact-source-fallback") {
                val handle = createPlatformTextEditor(provider, fixture.file)
                handle.editor.caretModel.moveToOffset(fixture.bodyOffset)
                val controller = createController(handle, MissingArtifactRuntime())
                val evidence = requireNotNull(controller.derivedEvidenceSnapshot())
                check(evidence.missingArtifacts == 3L)
                check(evidence.ownedInlays == 0 && evidence.ownedFolds == 0)
                checkSourceStable()
                disposeController(controller)
                disposeEditor(handle)
                "missingArtifacts=3 inlays=0 exactSourceFallback=true"
            }

            case("stale-renderer-identity-rejected") {
                val handle = createPlatformTextEditor(provider, fixture.file)
                handle.editor.caretModel.moveToOffset(fixture.bodyOffset)
                val controller = createController(handle, IdentityMismatchRuntime())
                val evidence = requireNotNull(controller.derivedEvidenceSnapshot())
                check(evidence.staleResultsRejected == 3L)
                check(evidence.ownedInlays == 0 && evidence.ownedFolds == 0)
                checkSourceStable()
                disposeController(controller)
                disposeEditor(handle)
                "staleResultsRejected=3 inlays=0 staleOverwrite=false sourceStable=true"
            }
        }

        private fun createFixture(): Fixture {
            var result: Fixture? = null
            case("authoritative-document-fixture") {
                val projectBase = project.basePath?.let(Paths::get)
                    ?: error("opened #148 proof project has no basePath")
                val root = Files.createTempDirectory(projectBase, ".markflow-native-derived-")
                tempRoot = root
                val path = root.resolve("derived-presentation-proof.md")
                val source = "# Derived presentation\n\n```mermaid\ngraph TD\n  A --> B\n```\n\nInline \$a^2 + b^2 = c^2\$.\n\n\$\$\\frac{1}{2}\$\$\n\nPlain body line for inactive caret state.\n"
                Files.writeString(path, source, StandardCharsets.UTF_8)
                val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                    ?: error("#148 fixture VirtualFile unavailable")
                val document = FileDocumentManager.getInstance().getDocument(file)
                    ?: error("#148 fixture Document unavailable")
                check(document.text == source)
                check(!FileDocumentManager.getInstance().isDocumentUnsaved(document))

                val inlineStart = source.indexOf("\$a^2 + b^2 = c^2\$")
                val displayStart = source.indexOf("\$\$\\frac{1}{2}\$\$")
                check(inlineStart >= 0 && displayStart >= 0)
                result = Fixture(
                    file = file,
                    document = document,
                    inlineMathOffset = inlineStart + 2,
                    displayMathStart = displayStart,
                    displayMathEnd = displayStart + "\$\$\\frac{1}{2}\$\$".length,
                    bodyOffset = source.indexOf("Plain body") + 2,
                )
                "sourceLength=${source.length} projectDefault=false insideProject=true"
            }
            return result ?: error("fixture case did not produce fixture")
        }

        private fun selectPlatformTextProvider(file: VirtualFile): FileEditorProvider {
            var result: FileEditorProvider? = null
            case("platform-text-editor-selected") {
                val provider = FileEditorProvider.EP_FILE_EDITOR_PROVIDER.extensionList.firstOrNull { candidate ->
                    candidate.editorTypeId == PLATFORM_TEXT_EDITOR_TYPE_ID && accepts(candidate, file)
                } ?: error("platform text editor provider did not accept #148 Markdown fixture")
                result = provider
                "editorTypeId=${provider.editorTypeId} implementation=${provider.javaClass.name}"
            }
            return result ?: error("provider case did not select platform TextEditor")
        }

        private fun accepts(provider: FileEditorProvider, file: VirtualFile): Boolean = runCatching {
            if (provider.acceptRequiresReadAction()) {
                ReadAction.computeBlocking<Boolean, RuntimeException> { provider.accept(project, file) }
            } else {
                provider.accept(project, file)
            }
        }.getOrDefault(false)

        private fun createPlatformTextEditor(provider: FileEditorProvider, file: VirtualFile): PlatformEditorHandle {
            val fileEditor = provider.createEditor(project, file)
            check(fileEditor is TextEditor) {
                "selected provider returned ${fileEditor.javaClass.name}, not TextEditor"
            }
            return PlatformEditorHandle(provider, fileEditor, fileEditor.editor).also(liveEditors::add)
        }

        private fun createRealController(handle: PlatformEditorHandle): NativePresentationController {
            val runtime = DerivedRendererRuntimeProvider.createOrNull(createImmediatelyForDiagnostics = true)
                ?: error("real derived renderer runtime unavailable")
            return createController(handle, runtime)
        }

        private fun createController(
            handle: PlatformEditorHandle,
            runtime: DerivedRendererRuntime?,
        ): NativePresentationController {
            val derived = NativeDerivedPresentationController(handle.editor, runtime)
            return NativePresentationController(
                editor = handle.editor,
                configGeneration = { 1L },
                derivedPresentation = derived,
            ).also(liveControllers::add)
        }

        private fun disposeController(controller: NativePresentationController) {
            if (!liveControllers.remove(controller)) return
            Disposer.dispose(controller)
        }

        private fun disposeEditor(handle: PlatformEditorHandle) {
            if (!liveEditors.remove(handle)) return
            handle.provider.disposeEditor(handle.fileEditor)
        }

        private fun checkSourceStable() {
            check(fixture.document.text == sourceBefore)
            check(fixture.document.modificationStamp == stampBefore)
        }

        private fun case(id: String, block: () -> String) {
            check(cases.none { it.id == id }) { "duplicate #148 evidence case id: $id" }
            val result = try {
                CaseResult(id, "PASS", block())
            } catch (failure: Throwable) {
                CaseResult(id, "FAIL", failureDetail(failure))
            }
            cases += result
            if (result.outcome != "PASS") throw ProbeCaseFailure(id, result.detail)
        }

        private fun failAsyncCase(id: String, detail: String) {
            if (cases.none { it.id == id }) cases += CaseResult(id, "FAIL", detail.take(400))
            finish("INCOMPLETE")
        }

        private fun recordInternalFailure(failure: Throwable) {
            if (cases.none { it.id == "probe-internal-failure" }) {
                cases += CaseResult("probe-internal-failure", "INCOMPLETE", failureDetail(failure))
            }
        }

        private fun armTimeout() {
            timeout = AppExecutorUtil.getAppScheduledExecutorService().schedule(
                {
                    ApplicationManager.getApplication().invokeLater {
                        if (!finished) {
                            if (cases.none { it.id == "probe-timeout" }) {
                                cases += CaseResult("probe-timeout", "INCOMPLETE", "#148 real-IDE probe timed out")
                            }
                            finish("INCOMPLETE")
                        }
                    }
                },
                PROBE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
        }

        private fun finish(verdict: String) {
            if (finished) return
            finished = true
            timeout?.cancel(false)
            timeout = null
            cleanup()
            val finalLiveRuntimes = JcefDerivedRendererRuntime.liveInstanceCountForDiagnostics
            val effectiveVerdict = if (verdict == "PASS" && finalLiveRuntimes == baselineLiveRuntimes) "PASS" else "INCOMPLETE"
            try {
                output.parent?.let(Files::createDirectories)
                Files.writeString(
                    output,
                    gson.toJson(
                        Evidence(
                            schemaVersion = 1,
                            selectedSurface = SELECTED_SURFACE,
                            ideBuild = ApplicationInfo.getInstance().build.asString(),
                            jcefSupported = JBCefApp.isSupported(),
                            verdict = effectiveVerdict,
                            baselineLiveRuntimes = baselineLiveRuntimes,
                            finalLiveRuntimes = finalLiveRuntimes,
                            cases = cases,
                        )
                    ),
                    StandardCharsets.UTF_8,
                )
            } finally {
                ApplicationManager.getApplication().exit(true, true, false)
            }
        }

        private fun cleanup() {
            liveControllers.toList().asReversed().forEach { controller -> runCatching { Disposer.dispose(controller) } }
            liveControllers.clear()
            liveEditors.toList().asReversed().forEach { handle -> runCatching { handle.provider.disposeEditor(handle.fileEditor) } }
            liveEditors.clear()
            tempRoot?.let { root -> runCatching { root.toFile().deleteRecursively() } }
            tempRoot = null
        }

        private fun failureDetail(failure: Throwable): String = buildString {
            append(failure.javaClass.name)
            failure.message?.takeIf(String::isNotBlank)?.let { message -> append(": ").append(message.take(400)) }
        }
    }

    private class FailingRuntime : DerivedRendererRuntime {
        override fun render(request: DerivedRendererRuntimeRequest, callback: (DerivedRendererRuntimeResult) -> Unit) {
            callback(
                DerivedRendererRuntimeResult(
                    requestId = request.requestId,
                    status = "failure",
                    kind = request.kind.wireName,
                    identity = request.identity,
                    code = "RENDER_FAILED",
                    retryable = false,
                    message = "synthetic #148 renderer failure",
                )
            )
        }

        override fun cancel(requestId: String) = Unit
        override fun dispose() = Unit
    }

    private class MissingArtifactRuntime : DerivedRendererRuntime {
        override fun render(request: DerivedRendererRuntimeRequest, callback: (DerivedRendererRuntimeResult) -> Unit) {
            callback(
                DerivedRendererRuntimeResult(
                    requestId = request.requestId,
                    status = "success",
                    kind = request.kind.wireName,
                    identity = request.identity,
                    mediaType = if (request.kind.wireName == "mermaid") "image/svg+xml" else "text/html",
                    content = "synthetic-core-result",
                )
            )
        }

        override fun cancel(requestId: String) = Unit
        override fun dispose() = Unit
    }

    private class IdentityMismatchRuntime : DerivedRendererRuntime {
        override fun render(request: DerivedRendererRuntimeRequest, callback: (DerivedRendererRuntimeResult) -> Unit) {
            callback(
                DerivedRendererRuntimeResult(
                    requestId = request.requestId,
                    status = "success",
                    kind = request.kind.wireName,
                    identity = DerivedRendererIdentity(
                        sourceGeneration = request.identity.sourceGeneration + "-stale",
                        configGeneration = request.identity.configGeneration,
                    ),
                    mediaType = if (request.kind.wireName == "mermaid") "image/svg+xml" else "text/html",
                    content = "synthetic-stale-result",
                )
            )
        }

        override fun cancel(requestId: String) = Unit
        override fun dispose() = Unit
    }

    private data class Fixture(
        val file: VirtualFile,
        val document: com.intellij.openapi.editor.Document,
        val inlineMathOffset: Int,
        val displayMathStart: Int,
        val displayMathEnd: Int,
        val bodyOffset: Int,
    )

    private data class PlatformEditorHandle(
        val provider: FileEditorProvider,
        val fileEditor: TextEditor,
        val editor: Editor,
    )

    private data class CaseResult(
        val id: String,
        val outcome: String,
        val detail: String,
    )

    private data class Evidence(
        val schemaVersion: Int,
        val selectedSurface: String,
        val ideBuild: String,
        val jcefSupported: Boolean,
        val verdict: String,
        val baselineLiveRuntimes: Int,
        val finalLiveRuntimes: Int,
        val cases: List<CaseResult>,
    )

    private class ProbeCaseFailure(id: String, detail: String) : RuntimeException("$id: $detail")

    private const val PLATFORM_TEXT_EDITOR_TYPE_ID = "text-editor"
    private const val POLL_MILLIS = 100L
    private const val PROBE_TIMEOUT_SECONDS = 90L
}
