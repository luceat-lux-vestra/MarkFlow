package com.algorist.markflow.editor.native

import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefApp
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/** Production-independent real-IDE evidence for #145. */
internal object NativeProjectionProbe {
    const val OUTPUT_PROPERTY = "markflow.nativeProjectionProbe.output"
    const val SELECTED_SHELL = "PLATFORM_TEXT_EDITOR_AUGMENTATION"
    const val PARSER_STRATEGY = "JETBRAINS_BUNDLED_MARKDOWN"

    private const val PLATFORM_TEXT_EDITOR_TYPE_ID = "text-editor"
    private const val TEMPORARY_MARKFLOW_PROVIDER_CLASS = "com.algorist.markflow.editor.MarkFlowEditorProvider"

    private val started = AtomicBoolean(false)

    fun startIfRequested(project: Project): Boolean {
        val output = System.getProperty(OUTPUT_PROPERTY)?.takeIf(String::isNotBlank) ?: return false
        if (!started.compareAndSet(false, true)) return true

        ApplicationManager.getApplication().invokeLater {
            Runner(Paths.get(output), project).run()
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
        private var jcefSupported: Boolean? = null
        private lateinit var fixture: Fixture
        private lateinit var provider: FileEditorProvider
        private lateinit var first: PlatformEditorHandle
        private lateinit var second: PlatformEditorHandle
        private lateinit var firstController: NativePresentationController
        private lateinit var secondController: NativePresentationController

        fun run() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            try {
                check(!project.isDefault) { "#145 runtime proof requires a real opened project" }
                check(!project.isDisposed) { "#145 runtime proof project was already disposed" }

                case("jcef-disabled-projection-path") {
                    val supported = JBCefApp.isSupported()
                    jcefSupported = supported
                    check(!supported) { "JCEF must be runtime-disabled for the #145 projection proof" }
                    "JBCefApp.isSupported=false projectDefault=false"
                }

                fixture = createFixture()
                provider = selectPlatformTextProvider(fixture.file)
                first = createPlatformTextEditor(provider, fixture.file)
                second = createPlatformTextEditor(provider, fixture.file)

                first.editor.caretModel.moveToOffset(fixture.bodyOffset)
                second.editor.caretModel.moveToOffset(fixture.bodyOffset)

                case("attach-no-edit-source-stability") {
                    val document = fixture.document
                    val sourceBefore = document.text
                    val stampBefore = document.modificationStamp
                    val dirtyBefore = FileDocumentManager.getInstance().isDocumentUnsaved(document)
                    val undoBefore = UndoManager.getInstance(project).isUndoAvailable(first.fileEditor)

                    firstController = createController(first.editor)
                    secondController = createController(second.editor)

                    check(document.text == sourceBefore)
                    check(document.modificationStamp == stampBefore)
                    check(FileDocumentManager.getInstance().isDocumentUnsaved(document) == dirtyBefore)
                    check(UndoManager.getInstance(project).isUndoAvailable(first.fileEditor) == undoBefore)
                    "sourceStable=true stampStable=true dirtyStable=true undoAvailabilityStable=true"
                }

                case("parser-proven-projection-plan") {
                    val plan = requireNotNull(firstController.currentPlan)
                    check(plan.status == ProjectionPlanStatus.READY)
                    check(plan.identity.source == fixture.document.text)
                    val kinds = plan.projections.map { it.kind }.toSet()
                    check(kinds.containsAll(NativeProjectionKind.entries.toSet())) {
                        "missing representative projection kinds: ${NativeProjectionKind.entries.toSet() - kinds}"
                    }
                    plan.projections.forEach { projection ->
                        check(projection.sourceRange.isInside(plan.identity.source))
                        projection.syntaxRanges.forEach { range ->
                            check(range.isInside(plan.identity.source))
                            check(range.startOffset >= projection.sourceRange.startOffset)
                            check(range.endOffset <= projection.sourceRange.endOffset)
                        }
                    }
                    val heading = plan.projections.first { it.kind == NativeProjectionKind.HEADING }
                    val syntax = heading.syntaxRanges.single()
                    check(plan.identity.source.substring(syntax.startOffset, syntax.endOffset) == "#")
                    "kinds=${kinds.sortedBy { it.ordinal }} ranges=${plan.projections.size} headingSyntax=#"
                }

                case("inline-and-block-native-presentation") {
                    val evidence = firstController.evidenceSnapshot()
                    check(evidence.ownedHighlighters >= 3) {
                        "expected emphasis/strong/inline-code markup, observed ${evidence.ownedHighlighters}"
                    }
                    check(evidence.ownedFolds >= 1) {
                        "expected parser-proven heading fold, observed ${evidence.ownedFolds}"
                    }
                    check(evidence.collapsedFolds >= 1) {
                        "heading syntax marker was not visually reduced outside active context"
                    }
                    check(first.editor.document === second.editor.document)
                    "highlighters=${evidence.ownedHighlighters} folds=${evidence.ownedFolds} sharedDocument=true"
                }

                case("caret-and-selection-exact-source-reveal") {
                    val document = fixture.document
                    val sourceBefore = document.text
                    val stampBefore = document.modificationStamp

                    first.editor.caretModel.removeSecondaryCarets()
                    first.editor.selectionModel.removeSelection()
                    first.editor.caretModel.moveToOffset(fixture.headingContentOffset)
                    check(firstController.evidenceSnapshot().collapsedFolds == 0) {
                        "active heading did not reveal its exact Markdown marker"
                    }
                    first.editor.caretModel.moveToOffset(fixture.bodyOffset)
                    check(firstController.evidenceSnapshot().collapsedFolds >= 1)

                    first.editor.selectionModel.setSelection(0, fixture.headingEndOffset)
                    check(firstController.evidenceSnapshot().collapsedFolds == 0) {
                        "selection intersecting heading did not reveal exact Markdown"
                    }
                    first.editor.selectionModel.removeSelection()
                    check(firstController.evidenceSnapshot().collapsedFolds >= 1)

                    val secondaryOffset = fixture.bodyOffset + 4
                    val secondary = requireNotNull(
                        first.editor.caretModel.addCaret(first.editor.offsetToVisualPosition(secondaryOffset))
                    ) { "secondary caret unavailable for multicaret reveal proof" }
                    secondary.setSelection(0, fixture.headingEndOffset)
                    check(firstController.evidenceSnapshot().collapsedFolds == 0) {
                        "secondary-caret selection did not reveal exact Markdown"
                    }
                    secondary.removeSelection()
                    check(first.editor.caretModel.removeCaret(secondary))
                    check(firstController.evidenceSnapshot().collapsedFolds >= 1)

                    check(document.text == sourceBefore)
                    check(document.modificationStamp == stampBefore)
                    "caretReveal=true selectionReveal=true multicaretSelectionReveal=true sourceStable=true"
                }

                case("stale-plan-rejected-before-apply") {
                    val document = fixture.document
                    val stale = requireNotNull(firstController.currentPlan)
                    val presentationBefore = firstController.evidenceSnapshot()

                    WriteCommandAction.writeCommandAction(project)
                        .withName("MarkFlow #145 Stale Projection Proof")
                        .run<RuntimeException> {
                            document.insertString(document.textLength, "\n`fresh`\n")
                        }

                    check(stale.identity.modificationStamp != document.modificationStamp)
                    check(firstController.tryApply(stale) == ProjectionApplyResult.STALE_REJECTED)
                    val rejected = firstController.evidenceSnapshot()
                    check(rejected.planIdentity == presentationBefore.planIdentity)
                    check(rejected.ownedHighlighters == presentationBefore.ownedHighlighters)
                    check(rejected.ownedFolds == presentationBefore.ownedFolds)
                    "staleRejected=true presentationOwnershipUnchanged=true refreshQueued=true"
                }

                // Document listeners schedule refresh after the write event. Queue this continuation after those
                // callbacks so the next case proves the automatic real-Document refresh path rather than a test hook.
                ApplicationManager.getApplication().invokeLater {
                    runAfterDocumentRefresh()
                }
            } catch (failure: Throwable) {
                recordInternalFailure(failure)
                finish("INCOMPLETE")
            }
        }

        private fun runAfterDocumentRefresh() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            try {
                case("document-change-refresh") {
                    val document = fixture.document
                    val firstEvidence = firstController.evidenceSnapshot()
                    val secondEvidence = secondController.evidenceSnapshot()
                    val firstIdentity = requireNotNull(firstEvidence.planIdentity)
                    val secondIdentity = requireNotNull(secondEvidence.planIdentity)
                    check(firstIdentity.source == document.text)
                    check(secondIdentity.source == document.text)
                    check(firstIdentity.modificationStamp == document.modificationStamp)
                    check(secondIdentity.modificationStamp == document.modificationStamp)
                    check(firstEvidence.refreshesScheduled >= 1 && secondEvidence.refreshesScheduled >= 1)
                    check(firstEvidence.refreshesApplied >= 2 && secondEvidence.refreshesApplied >= 2)
                    check(requireNotNull(firstController.currentPlan).projections.any {
                        it.kind == NativeProjectionKind.INLINE_CODE &&
                            document.getText(com.intellij.openapi.util.TextRange(it.sourceRange.startOffset, it.sourceRange.endOffset)) == "`fresh`"
                    })
                    "automaticRefresh=true exactIdentity=true bothEditors=true"
                }

                case("config-stale-plan-rejected-before-apply") {
                    val handle = createPlatformTextEditor(provider, fixture.file)
                    handle.editor.caretModel.moveToOffset(fixture.bodyOffset)
                    var configGeneration = 1L
                    val controller = NativePresentationController(
                        editor = handle.editor,
                        configGeneration = { configGeneration },
                    ).also(liveControllers::add)
                    val stale = requireNotNull(controller.currentPlan)
                    val before = controller.evidenceSnapshot()
                    check(stale.identity.configGeneration == 1L)

                    configGeneration = 2L
                    check(controller.tryApply(stale) == ProjectionApplyResult.STALE_REJECTED)
                    val rejected = controller.evidenceSnapshot()
                    check(rejected.planIdentity == before.planIdentity)
                    check(rejected.ownedHighlighters == before.ownedHighlighters)
                    check(rejected.ownedFolds == before.ownedFolds)

                    check(controller.refreshNow() == ProjectionApplyResult.APPLIED)
                    check(controller.currentPlan?.identity?.configGeneration == 2L)
                    disposeController(controller)
                    disposeEditor(handle)
                    "configStaleRejected=true presentationOwnershipUnchanged=true recoveredGeneration=2"
                }

                case("typed-degradation-falls-back-to-source") {
                    first.editor.caretModel.removeSecondaryCarets()
                    first.editor.selectionModel.removeSelection()
                    first.editor.caretModel.moveToOffset(fixture.bodyOffset)
                    val document = fixture.document
                    val sourceBefore = document.text
                    val stampBefore = document.modificationStamp
                    val identity = requireNotNull(firstController.currentPlan).identity
                    val degraded = NativeProjectionPlan(
                        identity = identity,
                        projections = emptyList(),
                        status = ProjectionPlanStatus.DEGRADED_TO_SOURCE,
                        failureClass = "synthetic.ProbeFailure",
                    )

                    check(firstController.tryApply(degraded) == ProjectionApplyResult.DEGRADED_TO_SOURCE)
                    val degradedEvidence = firstController.evidenceSnapshot()
                    check(degradedEvidence.planStatus == ProjectionPlanStatus.DEGRADED_TO_SOURCE)
                    check(degradedEvidence.ownedHighlighters == 0)
                    check(degradedEvidence.ownedFolds == 0)
                    check(document.text == sourceBefore)
                    check(document.modificationStamp == stampBefore)

                    check(firstController.refreshNow() == ProjectionApplyResult.APPLIED)
                    val recovered = firstController.evidenceSnapshot()
                    check(recovered.planStatus == ProjectionPlanStatus.READY)
                    check(recovered.ownedHighlighters >= 3)
                    check(recovered.ownedFolds >= 1)
                    check(document.text == sourceBefore)
                    check(document.modificationStamp == stampBefore)
                    "typedDegraded=true exactSourceFallback=true recovered=true sourceStable=true"
                }

                case("split-editor-presentation-isolation") {
                    first.editor.caretModel.removeSecondaryCarets()
                    second.editor.caretModel.removeSecondaryCarets()
                    first.editor.selectionModel.removeSelection()
                    second.editor.selectionModel.removeSelection()
                    first.editor.caretModel.moveToOffset(fixture.bodyOffset)
                    second.editor.caretModel.moveToOffset(fixture.bodyOffset)
                    check(firstController.evidenceSnapshot().collapsedFolds >= 1)
                    check(secondController.evidenceSnapshot().collapsedFolds >= 1)

                    first.editor.caretModel.moveToOffset(fixture.headingContentOffset)
                    check(firstController.evidenceSnapshot().collapsedFolds == 0)
                    check(secondController.evidenceSnapshot().collapsedFolds >= 1) {
                        "first editor reveal leaked into second editor presentation"
                    }
                    check(first.editor.document === second.editor.document)
                    "sharedSource=true independentReveal=true"
                }

                case("malformed-unsupported-source-degrades-safely") {
                    val source = "# safe\n\n**unterminated\n\n<script>alert('x')</script>\n"
                    val plan = NativeMarkdownProjectionPlanner.plan(
                        ProjectionSnapshot(
                            ProjectionSourceIdentity(
                                modificationStamp = 1L,
                                source = source,
                                configGeneration = 0L,
                            )
                        )
                    )
                    plan.projections.forEach { projection ->
                        check(projection.sourceRange.isInside(source))
                        projection.syntaxRanges.forEach { range -> check(range.isInside(source)) }
                    }
                    check(plan.projections.none { projection ->
                        val text = source.substring(projection.sourceRange.startOffset, projection.sourceRange.endOffset)
                        text.contains("<script>", ignoreCase = true)
                    }) {
                        "unsupported raw HTML received guessed native Markdown projection"
                    }
                    "status=${plan.status} rangesInBounds=true rawHtmlOpaque=true"
                }

                case("refresh-recreate-dispose-source-stability") {
                    val document = fixture.document
                    val sourceBefore = document.text
                    val stampBefore = document.modificationStamp
                    val refreshBefore = firstController.evidenceSnapshot().refreshesApplied
                    check(firstController.refreshNow() == ProjectionApplyResult.APPLIED)
                    check(firstController.evidenceSnapshot().refreshesApplied == refreshBefore + 1)
                    check(document.text == sourceBefore)
                    check(document.modificationStamp == stampBefore)

                    repeat(8) {
                        val handle = createPlatformTextEditor(provider, fixture.file)
                        handle.editor.caretModel.moveToOffset(fixture.bodyOffset)
                        val controller = createController(handle.editor)
                        check(controller.evidenceSnapshot().ownedFolds >= 1)
                        disposeController(controller)
                        check(controller.evidenceSnapshot().ownedHighlighters == 0)
                        check(controller.evidenceSnapshot().ownedFolds == 0)
                        disposeEditor(handle)
                    }
                    check(document.text == sourceBefore)
                    check(document.modificationStamp == stampBefore)
                    "refreshStable=true recreateLoops=8 ownedPresentationAfterDispose=0"
                }

                disposeController(firstController)
                disposeController(secondController)
                disposeEditor(first)
                disposeEditor(second)

                case("final-lifecycle-ownership") {
                    check(liveControllers.isEmpty())
                    check(liveEditors.isEmpty())
                    check(EditorFactory.getInstance().getEditors(fixture.document, project).isEmpty()) {
                        "native editors retained after projection proof disposal"
                    }
                    "controllers=0 editors=0"
                }

                finish("PASS")
            } catch (failure: Throwable) {
                recordInternalFailure(failure)
                finish("INCOMPLETE")
            }
        }

        private fun createFixture(): Fixture {
            var result: Fixture? = null
            case("authoritative-document-fixture") {
                val projectBase = project.basePath?.let(Paths::get)
                    ?: error("opened #145 proof project has no basePath")
                val root = Files.createTempDirectory(projectBase, ".markflow-native-projection-")
                tempRoot = root
                val path = root.resolve("projection-proof.md")
                val source = """# Heading

Paragraph with *emphasis*, **strong**, and `code`.

```kotlin
val value = 1
```

Plain body line for inactive caret state.
"""
                Files.writeString(path, source, StandardCharsets.UTF_8)
                val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                    ?: error("projection fixture VirtualFile unavailable")
                val document = FileDocumentManager.getInstance().getDocument(file)
                    ?: error("projection fixture Document unavailable")
                check(document.text == source)
                check(!FileDocumentManager.getInstance().isDocumentUnsaved(document))

                val headingEnd = source.indexOf('\n')
                val bodyOffset = source.indexOf("Plain body") + 2
                result = Fixture(
                    file = file,
                    document = document,
                    headingContentOffset = 2,
                    headingEndOffset = headingEnd,
                    bodyOffset = bodyOffset,
                )
                "sourceLength=${source.length} projectDefault=false insideProject=true"
            }
            return result ?: error("fixture case did not produce a fixture")
        }

        private fun selectPlatformTextProvider(file: VirtualFile): FileEditorProvider {
            var result: FileEditorProvider? = null
            case("platform-text-and-markdown-coexistence") {
                val providers = FileEditorProvider.EP_FILE_EDITOR_PROVIDER.extensionList
                val accepted = providers.filter { candidate -> accepts(candidate, file) }
                val text = accepted.firstOrNull { it.editorTypeId == PLATFORM_TEXT_EDITOR_TYPE_ID }
                    ?: error("platform text editor provider did not accept Markdown fixture")
                check(text.policy == FileEditorPolicy.NONE)
                check(accepted.any { it.javaClass.name.contains("markdown", ignoreCase = true) }) {
                    "bundled Markdown provider did not coexist with platform text editor"
                }
                val temporary = providers.singleOrNull { it.javaClass.name == TEMPORARY_MARKFLOW_PROVIDER_CLASS }
                    ?: error("temporary MarkFlow provider missing")
                check(!accepts(temporary, file)) {
                    "temporary JCEF-gated MarkFlow provider accepted while JCEF runtime was disabled"
                }
                result = text
                "platformText=${text.javaClass.name} bundledMarkdown=true temporaryBrowserAccepted=false"
            }
            return result ?: error("provider case did not select platform text editor")
        }

        private fun accepts(provider: FileEditorProvider, file: VirtualFile): Boolean = runCatching {
            if (provider.acceptRequiresReadAction()) {
                ReadAction.computeBlocking<Boolean, RuntimeException> { provider.accept(project, file) }
            } else {
                provider.accept(project, file)
            }
        }.getOrDefault(false)

        private fun createPlatformTextEditor(
            provider: FileEditorProvider,
            file: VirtualFile,
        ): PlatformEditorHandle {
            val fileEditor = provider.createEditor(project, file)
            check(fileEditor is TextEditor) {
                "selected provider returned ${fileEditor.javaClass.name}, not TextEditor"
            }
            return PlatformEditorHandle(provider, fileEditor, fileEditor.editor).also(liveEditors::add)
        }

        private fun createController(editor: Editor): NativePresentationController =
            NativePresentationController(editor).also(liveControllers::add)

        private fun disposeController(controller: NativePresentationController) {
            if (!liveControllers.remove(controller)) return
            Disposer.dispose(controller)
        }

        private fun disposeEditor(handle: PlatformEditorHandle) {
            if (!liveEditors.remove(handle)) return
            handle.provider.disposeEditor(handle.fileEditor)
        }

        private fun case(id: String, block: () -> String) {
            check(cases.none { it.id == id }) { "duplicate native projection evidence case id: $id" }
            val result = try {
                CaseResult(id = id, outcome = "PASS", detail = block())
            } catch (failure: Throwable) {
                CaseResult(id = id, outcome = "FAIL", detail = failureDetail(failure))
            }
            cases += result
            if (result.outcome != "PASS") {
                throw ProbeCaseFailure(id, result.detail)
            }
        }

        private fun recordInternalFailure(failure: Throwable) {
            if (cases.none { it.id == "probe-internal-failure" }) {
                cases += CaseResult(
                    id = "probe-internal-failure",
                    outcome = "INCOMPLETE",
                    detail = failureDetail(failure),
                )
            }
        }

        private fun finish(verdict: String) {
            cleanup()
            try {
                output.parent?.let(Files::createDirectories)
                Files.writeString(
                    output,
                    gson.toJson(
                        Evidence(
                            schemaVersion = 2,
                            selectedShell = SELECTED_SHELL,
                            parserStrategy = PARSER_STRATEGY,
                            ideBuild = ApplicationInfo.getInstance().build.asString(),
                            jcefSupported = jcefSupported,
                            verdict = verdict,
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

    private data class Fixture(
        val file: VirtualFile,
        val document: com.intellij.openapi.editor.Document,
        val headingContentOffset: Int,
        val headingEndOffset: Int,
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
        val selectedShell: String,
        val parserStrategy: String,
        val ideBuild: String,
        val jcefSupported: Boolean?,
        val verdict: String,
        val cases: List<CaseResult>,
    )

    private class ProbeCaseFailure(id: String, detail: String) : RuntimeException("$id: $detail")
}
