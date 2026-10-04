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

/** Real-IDE native projection regression proof retained across the #153 production cutover. */
internal object NativeProjectionProbe {
    const val OUTPUT_PROPERTY = "markflow.nativeProjectionProbe.output"
    const val SELECTED_SHELL = "PLATFORM_TEXT_EDITOR_AUGMENTATION"
    const val PARSER_STRATEGY = "JETBRAINS_BUNDLED_MARKDOWN"

    private const val PLATFORM_TEXT_EDITOR_TYPE_ID = "text-editor"
    private const val LEGACY_MARKFLOW_PROVIDER_CLASS = "com.algorist.markflow.editor.MarkFlowEditorProvider"

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
                check(!project.isDefault) { "native projection runtime proof requires a real opened project" }
                check(!project.isDisposed) { "native projection runtime proof project was already disposed" }

                case("jcef-disabled-projection-path") {
                    val supported = JBCefApp.isSupported()
                    jcefSupported = supported
                    check(!supported) { "JCEF must be runtime-disabled for the native projection proof" }
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
                    plan.projections.forEach { projection -> validateProjectionRanges(plan.identity.source, projection) }

                    val heading = plan.projections.first { it.kind == NativeProjectionKind.HEADING }
                    val syntax = heading.syntaxRanges.single()
                    check(plan.identity.source.substring(syntax.startOffset, syntax.endOffset) == "#")

                    val representativeSource = """# Heading

Paragraph with *emphasis*, **strong**, ~~struck~~, `code`, and [link](https://example.invalid/).

> quoted paragraph

- unordered item

1. ordered item

Indented block follows:

    val indented = true

```kotlin
val fenced = true
```

---

| Name | Value |
| --- | --- |
| alpha | beta |
"""
                    val representativePlan = NativeMarkdownProjectionPlanner.plan(
                        ProjectionSnapshot(
                            ProjectionSourceIdentity(
                                modificationStamp = 1L,
                                source = representativeSource,
                                configGeneration = 0L,
                            )
                        )
                    )
                    check(representativePlan.status == ProjectionPlanStatus.READY)
                    val representativeKinds = representativePlan.projections.map { it.kind }.toSet()
                    check(representativeKinds.containsAll(NativeProjectionKind.entries.toSet())) {
                        "missing representative projection kinds: ${NativeProjectionKind.entries.toSet() - representativeKinds}"
                    }
                    representativePlan.projections.forEach { projection ->
                        validateProjectionRanges(representativeSource, projection)
                    }
                    "kinds=${representativeKinds.sortedBy { it.ordinal }} ranges=${representativePlan.projections.size} headingSyntax=#"
                }

                case("inline-and-block-native-presentation") {
                    val evidence = firstController.evidenceSnapshot()
                    check(evidence.inlineOwnedHighlighters >= 3) {
                        "expected inline emphasis/strong/code markup, observed ${evidence.inlineOwnedHighlighters}"
                    }
                    check(evidence.inlineOwnedFolds >= 1) {
                        "expected inline parser-proven syntax folds, observed ${evidence.inlineOwnedFolds}"
                    }
                    check(evidence.blockOwnedHighlighters == 0) {
                        "supported rich blocks must not retain generic block highlighting: ${evidence.blockOwnedHighlighters}"
                    }
                    check(evidence.blockSyntaxOwnedFolds == 0) {
                        "supported rich blocks must not retain generic syntax folds: ${evidence.blockSyntaxOwnedFolds}"
                    }
                    check(evidence.ownedHighlighters == evidence.inlineOwnedHighlighters + evidence.blockOwnedHighlighters)
                    check(evidence.ownedFolds == evidence.inlineOwnedFolds + evidence.blockOwnedFolds)
                    check(
                        evidence.headingModels == 1 &&
                            evidence.headingInlays == 1 &&
                            evidence.headingFolds > 0 &&
                            evidence.headingFullyConcealed == 1
                    ) {
                        "inactive heading did not install one fully concealed native heading presentation: " +
                            "models=${evidence.headingModels} inlays=${evidence.headingInlays} " +
                            "ownedFolds=${evidence.headingFolds} concealed=${evidence.headingFullyConcealed}"
                    }
                    check(
                        evidence.blockQuoteModels == 1 &&
                            evidence.blockQuoteInlays == 1 &&
                            evidence.blockQuoteFolds == 2 &&
                            evidence.blockQuoteFullyConcealed == 1
                    ) {
                        "inactive blockquote did not install one fully concealed native quote block: " +
                            "models=${evidence.blockQuoteModels} inlays=${evidence.blockQuoteInlays} " +
                            "ownedFolds=${evidence.blockQuoteFolds} concealed=${evidence.blockQuoteFullyConcealed}"
                    }
                    check(
                        evidence.fencedCodeModels == 1 &&
                            evidence.fencedCodeInlays == 1 &&
                            evidence.fencedCodeFolds > 0 &&
                            evidence.fencedCodeFullyConcealed == 1 &&
                            evidence.fencedCodeInfos == listOf("kotlin")
                    ) {
                        "inactive fenced code did not install one fully concealed native code block: " +
                            "models=${evidence.fencedCodeModels} inlays=${evidence.fencedCodeInlays} " +
                            "folds=${evidence.fencedCodeFolds} concealed=${evidence.fencedCodeFullyConcealed} " +
                            "infos=${evidence.fencedCodeInfos}"
                    }
                    check(
                        evidence.listModels == 2 &&
                            evidence.listRows == 5 &&
                            evidence.listInlays == 2 &&
                            evidence.listFolds > 0 &&
                            evidence.listFullyConcealed == 2 &&
                            evidence.listMaxDepth == 1 &&
                            evidence.listDepths == listOf(0, 1, 0, 0, 0) &&
                            evidence.listMarkers == listOf("-", "-", "-", "-", "-") &&
                            evidence.taskRows == 2 &&
                            evidence.checkedTasks == 1
                    ) {
                        "inactive nested list did not install one fully concealed hierarchy: " +
                            "models=${evidence.listModels} rows=${evidence.listRows} inlays=${evidence.listInlays} " +
                            "folds=${evidence.listFolds} depth=${evidence.listMaxDepth} markers=${evidence.listMarkers} " +
                            "taskRows=${evidence.taskRows} checkedTasks=${evidence.checkedTasks}"
                    }
                    val secondEvidence = secondController.evidenceSnapshot()
                    check(
                        secondEvidence.blockQuoteModels == 1 &&
                            secondEvidence.blockQuoteInlays == 1 &&
                            secondEvidence.blockQuoteFullyConcealed == 1
                    ) {
                        "split editor did not own an independent native blockquote presentation"
                    }
                    check(
                        secondEvidence.fencedCodeModels == 1 &&
                            secondEvidence.fencedCodeInlays == 1 &&
                            secondEvidence.fencedCodeFullyConcealed == 1
                    ) {
                        "split editor did not own an independent native fenced-code presentation"
                    }
                    check(
                        secondEvidence.listModels == 2 &&
                            secondEvidence.listInlays == 2 &&
                            secondEvidence.listFullyConcealed == 2 &&
                            secondEvidence.taskRows == 2 &&
                            secondEvidence.checkedTasks == 1
                    ) {
                        "split editor did not own an independent native list presentation"
                    }
                    check(first.editor.document === second.editor.document)
                    "inlineHighlighters=${evidence.inlineOwnedHighlighters} inlineFolds=${evidence.inlineOwnedFolds} " +
                        "blockHighlighters=${evidence.blockOwnedHighlighters} blockFolds=${evidence.blockOwnedFolds} " +
                        "blockSyntaxFolds=${evidence.blockSyntaxOwnedFolds} " +
                        "highlighters=${evidence.ownedHighlighters} folds=${evidence.ownedFolds} " +
                        "headingInlays=${evidence.headingInlays} headingFolds=${evidence.headingFolds} " +
                        "headingConcealed=${evidence.headingFullyConcealed} " +
                        "quoteInlays=${evidence.blockQuoteInlays} quoteFolds=${evidence.blockQuoteFolds} " +
                        "quoteConcealed=${evidence.blockQuoteFullyConcealed} " +
                        "fenceInlays=${evidence.fencedCodeInlays} fenceFolds=${evidence.fencedCodeFolds} " +
                        "fenceConcealed=${evidence.fencedCodeFullyConcealed} " +
                        "listInlays=${evidence.listInlays} listRows=${evidence.listRows} listMaxDepth=${evidence.listMaxDepth} " +
                        "listConcealed=${evidence.listFullyConcealed} taskRows=${evidence.taskRows} " +
                        "checkedTasks=${evidence.checkedTasks} splitQuote=true splitList=true sharedDocument=true"
                }

                case("caret-and-selection-exact-source-reveal") {
                    val document = fixture.document
                    val sourceBefore = document.text
                    val stampBefore = document.modificationStamp

                    first.editor.caretModel.removeSecondaryCarets()
                    first.editor.selectionModel.removeSelection()
                    first.editor.caretModel.moveToOffset(fixture.headingContentOffset)
                    check(!headingRichPresentationVisible(firstController)) {
                        "active heading retained MarkFlow rich presentation"
                    }
                    check(headingExactSourceVisible(first.editor, firstController)) {
                        "active heading remained concealed by a collapsed fold"
                    }
                    first.editor.caretModel.moveToOffset(fixture.bodyOffset)
                    check(headingRichPresentationVisible(firstController))

                    first.editor.selectionModel.setSelection(0, fixture.headingEndOffset)
                    check(!headingRichPresentationVisible(firstController)) {
                        "selection intersecting heading retained MarkFlow rich presentation"
                    }
                    check(headingExactSourceVisible(first.editor, firstController)) {
                        "selection intersecting heading remained concealed by a collapsed fold"
                    }
                    first.editor.selectionModel.removeSelection()
                    check(headingRichPresentationVisible(firstController))

                    val secondaryOffset = fixture.bodyOffset + 4
                    val secondary = requireNotNull(
                        first.editor.caretModel.addCaret(first.editor.offsetToVisualPosition(secondaryOffset))
                    ) { "secondary caret unavailable for multicaret reveal proof" }
                    secondary.setSelection(0, fixture.headingEndOffset)
                    check(!headingRichPresentationVisible(firstController)) {
                        "secondary-caret selection retained MarkFlow rich presentation"
                    }
                    check(headingExactSourceVisible(first.editor, firstController)) {
                        "secondary-caret selection left heading source concealed"
                    }
                    secondary.removeSelection()
                    check(first.editor.caretModel.removeCaret(secondary))
                    check(headingRichPresentationVisible(firstController))

                    val fenceRange = NativeFencedCodeProjectionPlanner
                        .sourceRanges(requireNotNull(firstController.currentPlan))
                        .single()
                    first.editor.caretModel.moveToOffset(document.text.indexOf("val value = 1") + 4)
                    check(firstController.evidenceSnapshot().fencedCodeInlays == 0) {
                        "active fenced code retained its rich block presentation"
                    }
                    check(exactSourceVisible(first.editor, fenceRange)) {
                        "active fenced code remained concealed by a collapsed fold"
                    }
                    first.editor.caretModel.moveToOffset(fixture.bodyOffset)
                    check(firstController.evidenceSnapshot().fencedCodeInlays == 1) {
                        "inactive fenced code did not restore native rich presentation"
                    }

                    val quote = requireNotNull(firstController.currentPlan)
                        .projections
                        .single { projection -> projection.kind == NativeProjectionKind.BLOCK_QUOTE }
                    first.editor.caretModel.moveToOffset(quote.sourceRange.startOffset + 2)
                    check(!blockQuoteRichPresentationVisible(firstController)) {
                        "active blockquote retained MarkFlow rich presentation"
                    }
                    check(exactSourceVisible(first.editor, quote.sourceRange)) {
                        "active blockquote remained concealed by a collapsed fold"
                    }
                    first.editor.caretModel.moveToOffset(fixture.bodyOffset)
                    check(blockQuoteRichPresentationVisible(firstController)) {
                        "inactive blockquote did not restore native rich presentation"
                    }

                    val listRanges = NativeListProjectionPlanner.sourceRanges(requireNotNull(firstController.currentPlan))
                    check(listRanges.size == 2)
                    val ordinaryListRange = listRanges.first { range ->
                        document.text.substring(range.startOffset, range.endOffset).contains("Parent item")
                    }
                    first.editor.caretModel.moveToOffset(ordinaryListRange.startOffset + 2)
                    check(firstController.evidenceSnapshot().listInlays == 1) {
                        "active ordinary list retained its rich presentation"
                    }
                    check(exactSourceVisible(first.editor, ordinaryListRange)) {
                        "active ordinary list remained concealed by a collapsed fold"
                    }
                    first.editor.caretModel.moveToOffset(fixture.bodyOffset)
                    check(firstController.evidenceSnapshot().listInlays == 2) {
                        "inactive ordinary list did not restore native rich presentation"
                    }

                    val taskListRange = listRanges.first { range ->
                        document.text.substring(range.startOffset, range.endOffset).contains("[ ]")
                    }
                    first.editor.caretModel.moveToOffset(taskListRange.startOffset + 4)
                    check(firstController.evidenceSnapshot().listInlays == 1) {
                        "active task list retained its rich checkbox presentation"
                    }
                    check(exactSourceVisible(first.editor, taskListRange)) {
                        "active task list remained concealed by a collapsed fold"
                    }
                    first.editor.caretModel.moveToOffset(fixture.bodyOffset)
                    val restoredLists = firstController.evidenceSnapshot()
                    check(restoredLists.listInlays == 2 && restoredLists.taskRows == 2 && restoredLists.checkedTasks == 1) {
                        "inactive task list did not restore native checkbox presentation"
                    }

                    check(document.text == sourceBefore)
                    check(document.modificationStamp == stampBefore)
                    "caretReveal=true selectionReveal=true multicaretSelectionReveal=true fencedCodeReveal=true sourceStable=true"
                }

                case("stale-plan-rejected-before-apply") {
                    val document = fixture.document
                    val stale = requireNotNull(firstController.currentPlan)
                    val presentationBefore = firstController.evidenceSnapshot()

                    WriteCommandAction.writeCommandAction(project)
                        .withName("MarkFlow Native Stale Projection Proof")
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
                    check(headingRichPresentationVisible(firstController))
                    check(headingRichPresentationVisible(secondController))

                    first.editor.caretModel.moveToOffset(fixture.headingContentOffset)
                    check(!headingRichPresentationVisible(firstController))
                    check(headingRichPresentationVisible(secondController)) {
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
                        validateProjectionRanges(source, projection)
                    }
                    check(plan.projections.none { projection ->
                        val text = source.substring(projection.sourceRange.startOffset, projection.sourceRange.endOffset)
                        text.contains("<script>", ignoreCase = true)
                    }) {
                        "unsupported raw HTML received guessed native Markdown projection"
                    }
                    "status=${plan.status} rangesInBounds=true rawHtmlOpaque=true"
                }

                case("fenced-code-disposition-exact-source-runtime") {
                    val root = requireNotNull(tempRoot) { "native projection temp root unavailable" }
                    val path = root.resolve("fenced-disposition-proof.md")
                    val fence = "\u0060\u0060\u0060"
                    val oversizedInfo = "x".repeat(1025)
                    val source = fence + "text\n\n" + fence + "\n\n" +
                        fence + oversizedInfo + "\npayload\n" + fence + "\n\n" +
                        fence + "mermaid\ngraph TD; A-->B;\n" + fence + "\n\n" +
                        "Tail\n"
                    Files.writeString(path, source, StandardCharsets.UTF_8)
                    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                        ?: error("fenced disposition proof VirtualFile unavailable")
                    val document = FileDocumentManager.getInstance().getDocument(file)
                        ?: error("fenced disposition proof Document unavailable")
                    val stampBefore = document.modificationStamp
                    val handle = createPlatformTextEditor(provider, file)
                    handle.editor.caretModel.moveToOffset(source.indexOf("Tail") + 1)
                    val controller = createController(handle.editor)

                    val plan = requireNotNull(controller.currentPlan)
                    val ownership = NativeFencedCodeProjectionPlanner.dispositions(plan)
                    check(
                        ownership.map(NativeFencedCodeOwnership::disposition) ==
                            listOf(
                                NativeFencedCodeDisposition.EXACT_SOURCE,
                                NativeFencedCodeDisposition.EXACT_SOURCE,
                                NativeFencedCodeDisposition.MERMAID_DERIVED,
                            )
                    ) {
                        "unexpected fenced-code ownership dispositions: ${ownership.map(NativeFencedCodeOwnership::disposition)}"
                    }
                    check(
                        NativeDerivedProjectionPlanner.plan(plan)
                            .count { projection -> projection.kind == NativeDerivedProjectionKind.MERMAID } == 1
                    ) {
                        "Mermaid fence did not remain owned by the derived projection path"
                    }
                    val evidence = controller.evidenceSnapshot()
                    check(evidence.fencedCodeModels == 0)
                    check(evidence.fencedCodeInlays == 0)
                    check(evidence.fencedCodeFolds == 0)
                    check(evidence.blockOwnedHighlighters == 0) {
                        "exact-source/derived fences leaked into generic block highlighters"
                    }
                    check(evidence.blockSyntaxOwnedFolds == 0) {
                        "exact-source/derived fences leaked into generic block folds"
                    }
                    ownership.forEach { entry ->
                        check(exactSourceVisible(handle.editor, entry.sourceRange)) {
                            "fence disposition ${entry.disposition} retained collapsed source concealment"
                        }
                    }
                    check(document.text == source)
                    check(document.modificationStamp == stampBefore)

                    disposeController(controller)
                    disposeEditor(handle)
                    "exactFallbacks=2 mermaidDerived=1 genericHighlighters=0 genericFolds=0 sourceStable=true"
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
                    ?: error("opened native projection proof project has no basePath")
                val root = Files.createTempDirectory(projectBase, ".markflow-native-projection-")
                tempRoot = root
                val path = root.resolve("projection-proof.md")
                val source = """# Heading

Paragraph with *emphasis*, **strong**, and `code`.

```kotlin
val value = 1
```

> Runtime quote

- Parent item
  - Nested item
- Sibling item

Task list follows.

- [ ] Pending runtime task
- [x] Completed runtime task

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
                check(providers.none { it.javaClass.name == LEGACY_MARKFLOW_PROVIDER_CLASS }) {
                    "legacy MarkFlow browser provider remained registered after native production cutover"
                }
                val accepted = providers.filter { candidate -> accepts(candidate, file) }
                val text = accepted.firstOrNull { it.editorTypeId == PLATFORM_TEXT_EDITOR_TYPE_ID }
                    ?: error("platform text editor provider did not accept Markdown fixture")
                check(text.policy == FileEditorPolicy.NONE)
                check(accepted.any { it.javaClass.name.contains("markdown", ignoreCase = true) }) {
                    "bundled Markdown provider did not coexist with platform text editor"
                }
                result = text
                "platformText=${text.javaClass.name} bundledMarkdown=true legacyBrowserProvider=false"
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

        private fun headingRichPresentationVisible(
            controller: NativePresentationController,
        ): Boolean {
            val evidence = controller.evidenceSnapshot()
            return evidence.headingModels == 1 &&
                evidence.headingInlays == 1 &&
                evidence.headingFolds > 0 &&
                evidence.headingFullyConcealed == 1
        }

        private fun headingExactSourceVisible(
            editor: Editor,
            controller: NativePresentationController,
        ): Boolean {
            val heading = controller.currentPlan
                ?.projections
                ?.singleOrNull { projection -> projection.kind == NativeProjectionKind.HEADING }
                ?: return false
            val range = heading.sourceRange
            return editor.foldingModel.allFoldRegions.none { fold ->
                fold.isValid &&
                    !fold.isExpanded &&
                    fold.startOffset < range.endOffset &&
                    fold.endOffset > range.startOffset
            }
        }

        private fun blockQuoteRichPresentationVisible(
            controller: NativePresentationController,
        ): Boolean {
            val evidence = controller.evidenceSnapshot()
            return evidence.blockQuoteModels == 1 &&
                evidence.blockQuoteInlays == 1 &&
                evidence.blockQuoteFolds == 2 &&
                evidence.blockQuoteFullyConcealed == 1
        }

        private fun exactSourceVisible(
            editor: Editor,
            range: ProjectionRange,
        ): Boolean = editor.foldingModel.allFoldRegions.none { fold ->
            fold.isValid &&
                !fold.isExpanded &&
                fold.startOffset < range.endOffset &&
                fold.endOffset > range.startOffset
        }

        private fun validateProjectionRanges(source: String, projection: NativeProjection) {
            check(projection.sourceRange.isInside(source))
            projection.syntaxRanges.forEach { range ->
                check(range.isInside(source))
                check(range.startOffset >= projection.sourceRange.startOffset)
                check(range.endOffset <= projection.sourceRange.endOffset)
            }
            projection.contentRanges.forEach { range ->
                check(range.isInside(source))
                check(range.startOffset >= projection.sourceRange.startOffset)
                check(range.endOffset <= projection.sourceRange.endOffset)
            }
        }

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
