package com.algorist.markflow.editor.native

import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.awt.event.MouseEvent
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/** Supplemental real-IDE proof for #152 ordinary Markdown/table parity. */
internal object NativeMarkdownParityProbe {
    private const val PLATFORM_TEXT_EDITOR_TYPE_ID = "text-editor"
    private const val OUTPUT_FILE = "parity.json"
    private val started = AtomicBoolean(false)

    fun runIfRequested(project: Project): Boolean {
        val projectionOutput = System.getProperty(NativeProjectionProbe.OUTPUT_PROPERTY)
            ?.takeIf(String::isNotBlank) ?: return false
        if (!started.compareAndSet(false, true)) return true
        val output = Paths.get(projectionOutput).resolveSibling(OUTPUT_FILE)
        ApplicationManager.getApplication().invokeLater {
            Runner(output, project).run()
        }
        return true
    }

    private class Runner(
        private val output: Path,
        private val project: Project,
    ) {
        private val gson = GsonBuilder().setPrettyPrinting().create()
        private val cases = mutableListOf<CaseResult>()
        private var tempRoot: Path? = null
        private var provider: FileEditorProvider? = null
        private var fileEditor: TextEditor? = null

        fun run() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            try {
                check(!project.isDefault) { "#152 parity proof requires a real opened project" }
                check(!project.isDisposed) { "#152 parity proof project was already disposed" }
                val fixture = createFixture()
                val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(fixture.editor.document, 0L))
                check(plan.status == ProjectionPlanStatus.READY)
                val tableModel = NativeTableProjectionPlanner.plan(plan).single()

                case("fidelity-corpus-runtime-mapping") {
                    val root = repositoryRoot().resolve("fixtures/markdown-fidelity/cases")
                    check(Files.isDirectory(root)) { "#78 fidelity corpus root unavailable: $root" }
                    var tableModels = 0
                    FIDELITY_FIXTURES.forEachIndexed { index, id ->
                        val source = Files.readString(root.resolve("$id.md"), StandardCharsets.UTF_8)
                        val fixturePlan = NativeMarkdownProjectionPlanner.plan(
                            ProjectionSnapshot(
                                ProjectionSourceIdentity(index.toLong() + 1L, source, 0L)
                            )
                        )
                        check(fixturePlan.status == ProjectionPlanStatus.READY) { "$id degraded unexpectedly" }
                        check(fixturePlan.identity.source == source) { "$id lost exact source identity" }
                        fixturePlan.projections.forEach { projection ->
                            check(projection.sourceRange.isInside(source)) { "$id source range escaped fixture" }
                            projection.syntaxRanges.forEach { check(it.isInside(source)) { "$id syntax range escaped fixture" } }
                            projection.contentRanges.forEach { check(it.isInside(source)) { "$id content range escaped fixture" } }
                        }
                        if (id == "table-lexical-variants") {
                            tableModels = NativeTableProjectionPlanner.plan(fixturePlan).size
                            check(tableModels > 0) { "table fidelity fixture produced no parser-proven model" }
                        }
                        if (id == "links-and-references") {
                            val links = fixturePlan.projections.filter { it.kind == NativeProjectionKind.LINK }
                            check(links.isNotEmpty())
                            check(links.none { link ->
                                source.substring(link.sourceRange.startOffset, link.sourceRange.endOffset).startsWith("![")
                            }) { "image link subtree leaked into ordinary link projection" }
                        }
                    }
                    "fixtures=${FIDELITY_FIXTURES.size} exactIdentity=true rangesInBounds=true tableModels=$tableModels imageBoundary=true"
                }

                case("ordinary-parser-table-boundary") {
                    val source = fixture.editor.document.text
                    val kinds = plan.projections.map { it.kind }.toSet()
                    val requiredKinds = setOf(
                        NativeProjectionKind.PARAGRAPH,
                        NativeProjectionKind.HEADING,
                        NativeProjectionKind.EMPHASIS,
                        NativeProjectionKind.STRONG,
                        NativeProjectionKind.LINK,
                        NativeProjectionKind.UNORDERED_LIST,
                        NativeProjectionKind.ORDERED_LIST,
                        NativeProjectionKind.LIST_ITEM,
                        NativeProjectionKind.BLOCK_QUOTE,
                        NativeProjectionKind.INLINE_CODE,
                        NativeProjectionKind.CODE_FENCE,
                        NativeProjectionKind.CODE_BLOCK,
                        NativeProjectionKind.THEMATIC_BREAK,
                        NativeProjectionKind.TABLE,
                        NativeProjectionKind.TABLE_HEADER,
                        NativeProjectionKind.TABLE_ROW,
                    )
                    check(kinds.containsAll(requiredKinds)) {
                        "missing parity projection kinds: ${requiredKinds - kinds}"
                    }
                    plan.projections.forEach { projection ->
                        check(projection.sourceRange.isInside(source))
                        projection.syntaxRanges.forEach { check(it.isInside(source)) }
                        projection.contentRanges.forEach { check(it.isInside(source)) }
                    }
                    val link = plan.projections.single { it.kind == NativeProjectionKind.LINK }
                    check(link.syntaxRanges.size == 2)
                    check(link.contentRanges.size == 1)
                    check(tableModel.sourceRange.contains(tableModel.sourceRange.startOffset))
                    check(tableModel.sourceRange.contains(tableModel.sourceRange.endOffset - 1))
                    check(!tableModel.sourceRange.contains(tableModel.sourceRange.endOffset)) {
                        "table parser range end must remain outside the active construct"
                    }
                    "allKinds=true parserRanges=true linkSyntax=true halfOpenBoundary=true tableRows=${tableModel.rows.size}"
                }

                val sourceBefore = fixture.editor.document.text
                val stampBefore = fixture.editor.document.modificationStamp

                case("ordinary-native-presentation-source-neutral") {
                    fixture.editor.caretModel.moveToOffset(fixture.afterTableOffset)
                    fixture.editor.selectionModel.removeSelection()
                    val tableFallback = NativeTablePresentationController(
                        fixture.editor,
                        richPresentationEnabled = { false },
                    )
                    val controller = NativePresentationController(
                        editor = fixture.editor,
                        richPresentationEnabled = { true },
                        tablePresentation = tableFallback,
                    )
                    try {
                        val evidence = controller.evidenceSnapshot()
                        check(evidence.planStatus == ProjectionPlanStatus.READY)
                        check(evidence.ownedFolds > 0) { "ordinary fallback installed no owned folds" }
                        check(evidence.ownedHighlighters > 0) { "ordinary fallback installed no highlighters" }
                        val placeholders = fixture.editor.foldingModel.allFoldRegions
                            .filter { it.isValid && !it.isExpanded }
                            .map { it.placeholderText }
                        check("•" in placeholders) { "unordered list marker was not projected" }
                        check("│" in placeholders) { "blockquote marker was not projected" }
                        check("────────" in placeholders) { "thematic break was not projected" }

                        val link = requireNotNull(controller.currentPlan)
                            .projections
                            .single { it.kind == NativeProjectionKind.LINK }
                        check(link.syntaxRanges.size == 2)
                        link.syntaxRanges.forEach { range ->
                            check(fixture.editor.foldingModel.getFoldRegion(range.startOffset, range.endOffset) != null) {
                                "link syntax range was not concealed: $range"
                            }
                        }
                        fixture.editor.caretModel.moveToOffset(link.sourceRange.endOffset)
                        link.syntaxRanges.forEach { range ->
                            check(fixture.editor.foldingModel.getFoldRegion(range.startOffset, range.endOffset)?.isExpanded == true) {
                                "link boundary caret did not reveal exact source: $range"
                            }
                        }
                        check(fixture.editor.document.text == sourceBefore)
                        check(fixture.editor.document.modificationStamp == stampBefore)
                        "ownedFolds=${evidence.ownedFolds} ownedHighlighters=${evidence.ownedHighlighters} markers=true linkConceal=true boundaryReveal=true sourceStable=true"
                    } finally {
                        controller.dispose()
                    }
                }

                case("ordinary-accessibility-source-fallback") {
                    fixture.editor.caretModel.moveToOffset(fixture.afterTableOffset)
                    fixture.editor.selectionModel.removeSelection()
                    val tableFallback = NativeTablePresentationController(
                        fixture.editor,
                        richPresentationEnabled = { false },
                    )
                    val controller = NativePresentationController(
                        editor = fixture.editor,
                        richPresentationEnabled = { false },
                        tablePresentation = tableFallback,
                    )
                    try {
                        val evidence = controller.evidenceSnapshot()
                        check(evidence.planStatus == ProjectionPlanStatus.READY)
                        check(evidence.ownedFolds == 0)
                        check(evidence.ownedHighlighters == 0)
                        check(evidence.sourceFallbacks == 1L)
                        check(fixture.editor.document.text == sourceBefore)
                        check(fixture.editor.document.modificationStamp == stampBefore)
                        "sourceFallback=true folds=0 highlighters=0 sourceStable=true stampStable=true"
                    } finally {
                        controller.dispose()
                    }
                }

                fixture.editor.caretModel.moveToOffset(fixture.afterTableOffset)
                val table = NativeTablePresentationController(fixture.editor)
                try {
                    case("table-native-presentation-source-neutral") {
                        table.applyPlan(plan)
                        val evidence = table.evidenceSnapshot()
                        check(evidence.tableModels == 1)
                        check(evidence.ownedInlays == 1)
                        check(evidence.ownedFolds == 1)
                        check(fixture.editor.document.text == sourceBefore)
                        check(fixture.editor.document.modificationStamp == stampBefore)
                        "models=1 inlays=1 folds=1 sourceStable=true stampStable=true"
                    }

                    case("table-caret-selection-and-end-boundary-reveal") {
                        fixture.editor.caretModel.moveToOffset(tableModel.firstContentOffset)
                        table.refreshActivity(plan)
                        check(table.evidenceSnapshot().ownedInlays == 0)
                        check(table.evidenceSnapshot().ownedFolds == 0)

                        fixture.editor.caretModel.moveToOffset(tableModel.sourceRange.endOffset)
                        fixture.editor.selectionModel.removeSelection()
                        table.refreshActivity(plan)
                        check(table.evidenceSnapshot().ownedInlays == 1) {
                            "caret at half-open table end incorrectly kept source revealed"
                        }
                        check(table.evidenceSnapshot().ownedFolds == 1)

                        fixture.editor.selectionModel.setSelection(
                            tableModel.sourceRange.startOffset + 1,
                            tableModel.sourceRange.endOffset,
                        )
                        table.refreshActivity(plan)
                        check(table.evidenceSnapshot().ownedInlays == 0)
                        check(table.evidenceSnapshot().ownedFolds == 0)
                        fixture.editor.selectionModel.removeSelection()
                        fixture.editor.caretModel.moveToOffset(fixture.afterTableOffset)
                        table.refreshActivity(plan)
                        check(fixture.editor.document.text == sourceBefore)
                        check(fixture.editor.document.modificationStamp == stampBefore)
                        "caretReveal=true selectionReveal=true endBoundaryInactive=true sourceStable=true"
                    }

                    case("table-mouse-inlay-reveal") {
                        fixture.editor.caretModel.moveToOffset(fixture.afterTableOffset)
                        fixture.editor.selectionModel.removeSelection()
                        table.refreshActivity(plan)
                        val inlays = fixture.editor.inlayModel
                            .getBlockElementsInRange(0, fixture.editor.document.textLength)
                            .filter { it.renderer is NativeTableInlayRenderer }
                        check(inlays.size == 1) { "expected one native table block inlay, observed ${inlays.size}" }
                        val inlay = inlays.single()
                        inlay.update()
                        val bounds = inlay.bounds ?: error("native table inlay has no runtime bounds")
                        val click = MouseEvent(
                            fixture.editor.contentComponent,
                            MouseEvent.MOUSE_CLICKED,
                            System.currentTimeMillis(),
                            0,
                            bounds.x + bounds.width / 2,
                            bounds.y + bounds.height / 2,
                            1,
                            false,
                            MouseEvent.BUTTON1,
                        )
                        val inlayOffset = inlay.offset.coerceIn(0, fixture.editor.document.textLength)
                        val editorEvent = EditorMouseEvent(
                            fixture.editor,
                            click,
                            EditorMouseEventArea.EDITING_AREA,
                            inlayOffset,
                            fixture.editor.offsetToLogicalPosition(inlayOffset),
                            fixture.editor.offsetToVisualPosition(inlayOffset),
                            false,
                            null,
                            inlay,
                            null,
                        )
                        check(table.handleMouseReveal(editorEvent)) {
                            "registered table mouse handler path did not reveal the runtime inlay"
                        }
                        val evidence = table.evidenceSnapshot()
                        check(click.isConsumed) { "table mouse handler did not consume the handled left click" }
                        check(evidence.mouseReveals == 1L)
                        check(evidence.ownedInlays == 0 && evidence.ownedFolds == 0)
                        check(fixture.editor.caretModel.primaryCaret.offset == tableModel.firstContentOffset)
                        check(fixture.editor.document.text == sourceBefore)
                        check(fixture.editor.document.modificationStamp == stampBefore)
                        "mouseEvent=true runtimeInlay=true listenerHandlerPath=true eventConsumed=true mouseReveal=true caretAtFirstCell=true sourceStable=true"
                    }
                } finally {
                    table.dispose()
                }

                case("table-accessibility-source-fallback") {
                    fixture.editor.caretModel.moveToOffset(fixture.afterTableOffset)
                    val accessible = NativeTablePresentationController(
                        fixture.editor,
                        richPresentationEnabled = { false },
                    )
                    try {
                        accessible.applyPlan(plan)
                        val evidence = accessible.evidenceSnapshot()
                        check(evidence.tableModels == 1)
                        check(evidence.ownedInlays == 0 && evidence.ownedFolds == 0)
                        check(evidence.accessibilityFallbacks == 1L)
                        check(fixture.editor.document.text == sourceBefore)
                        check(fixture.editor.document.modificationStamp == stampBefore)
                        "sourceVisible=true inlays=0 folds=0 accessibilityFallback=1 sourceStable=true"
                    } finally {
                        accessible.dispose()
                    }
                }

                case("rapid-edit-caret-regeneration") {
                    val document = fixture.editor.document
                    val original = document.text
                    val controller = NativePresentationController(fixture.editor)
                    try {
                        repeat(20) { index ->
                            WriteCommandAction.writeCommandAction(project)
                                .withName("MarkFlow #152 Rapid Projection Proof")
                                .run<RuntimeException> {
                                    document.insertString(document.textLength, "\nrapid-$index *value*\n")
                                }
                            fixture.editor.caretModel.moveToOffset(document.textLength)
                            check(controller.refreshNow() == ProjectionApplyResult.APPLIED)
                            val identity = requireNotNull(controller.currentPlan).identity
                            check(identity.source == document.text)
                            check(identity.modificationStamp == document.modificationStamp)
                        }
                        check(document.text.startsWith(original))
                        "iterations=20 exactIdentity=true caretMovement=true projectionApplied=true"
                    } finally {
                        controller.dispose()
                    }
                }

                case("large-representative-document-baseline") {
                    val largeSource = buildString {
                        repeat(750) { index ->
                            append("## Heading ").append(index).append('\n')
                            append("Paragraph with *emphasis*, **strong**, `code`, and [link](https://example.invalid/")
                                .append(index).append(").\n\n")
                            append("- item ").append(index).append("\n\n")
                        }
                        append("| Name | Value |\n| --- | --- |\n| tail | 1 |\n")
                    }
                    val largePlan = NativeMarkdownProjectionPlanner.plan(
                        ProjectionSnapshot(
                            ProjectionSourceIdentity(1L, largeSource, 0L)
                        )
                    )
                    check(largePlan.status == ProjectionPlanStatus.READY)
                    check(largePlan.projections.size > 4_000) {
                        "large representative corpus produced unexpectedly few projections: ${largePlan.projections.size}"
                    }
                    largePlan.projections.forEach { projection ->
                        check(projection.sourceRange.isInside(largeSource))
                        projection.syntaxRanges.forEach { check(it.isInside(largeSource)) }
                        projection.contentRanges.forEach { check(it.isInside(largeSource)) }
                    }
                    check(NativeTableProjectionPlanner.plan(largePlan).size == 1)
                    "sourceLength=${largeSource.length} projections=${largePlan.projections.size} rangesInBounds=true table=1"
                }

                finish("PASS")
            } catch (failure: Throwable) {
                if (cases.none { it.id == "probe-internal-failure" }) {
                    cases += CaseResult("probe-internal-failure", "INCOMPLETE", failureDetail(failure))
                }
                finish("INCOMPLETE")
            } finally {
                cleanup()
            }
        }

        private fun repositoryRoot(): Path {
            val absoluteOutput = output.toAbsolutePath().normalize()
            return absoluteOutput.parent?.parent?.parent
                ?: error("cannot derive repository root from parity output path: $absoluteOutput")
        }

        private fun createFixture(): Fixture {
            val base = project.basePath?.let(Paths::get) ?: error("#152 parity project base unavailable")
            val root = Files.createTempDirectory(base, ".markflow-native-parity-")
            tempRoot = root
            val path = root.resolve("parity.md")
            val source = """# Heading

Paragraph with *emphasis*, **strong**, `code`, and [link](https://example.invalid/).

> quote

- unordered

1. ordered

```kotlin
val fenced = true
```

    val indented = 2

---

| Name | Value |
| --- | --- |
| alpha | beta |

After table
"""
            Files.writeString(path, source, StandardCharsets.UTF_8)
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                ?: error("#152 parity VirtualFile unavailable")
            val document = FileDocumentManager.getInstance().getDocument(file)
                ?: error("#152 parity Document unavailable")
            check(document.text == source)
            val selectedProvider = selectPlatformTextProvider(file)
            provider = selectedProvider
            val created = selectedProvider.createEditor(project, file)
            check(created is TextEditor) { "platform provider returned ${created.javaClass.name}" }
            fileEditor = created
            val editor = created.editor
            check(editor.document === document)
            editor.contentComponent.setSize(1200, 900)
            editor.contentComponent.doLayout()
            return Fixture(
                file = file,
                editor = editor,
                afterTableOffset = source.indexOf("After table") + 2,
            )
        }

        private fun selectPlatformTextProvider(file: VirtualFile): FileEditorProvider =
            FileEditorProvider.EP_FILE_EDITOR_PROVIDER.extensionList.firstOrNull { candidate ->
                candidate.editorTypeId == PLATFORM_TEXT_EDITOR_TYPE_ID && accepts(candidate, file)
            } ?: error("platform text editor provider did not accept #152 parity fixture")

        private fun accepts(provider: FileEditorProvider, file: VirtualFile): Boolean = runCatching {
            if (provider.acceptRequiresReadAction()) {
                ReadAction.computeBlocking<Boolean, RuntimeException> { provider.accept(project, file) }
            } else {
                provider.accept(project, file)
            }
        }.getOrDefault(false)

        private fun case(id: String, block: () -> String) {
            check(cases.none { it.id == id }) { "duplicate #152 parity case id: $id" }
            val result = try {
                CaseResult(id, "PASS", block())
            } catch (failure: Throwable) {
                CaseResult(id, "FAIL", failureDetail(failure))
            }
            cases += result
            if (result.outcome != "PASS") throw ProbeCaseFailure(id, result.detail)
        }

        private fun finish(verdict: String) {
            output.parent?.let(Files::createDirectories)
            Files.writeString(
                output,
                gson.toJson(
                    Evidence(
                        schemaVersion = 1,
                        ideBuild = ApplicationInfo.getInstance().build.asString(),
                        verdict = verdict,
                        cases = cases,
                    )
                ),
                StandardCharsets.UTF_8,
            )
        }

        private fun cleanup() {
            val editor = fileEditor
            val owner = provider
            if (editor != null && owner != null) runCatching { owner.disposeEditor(editor) }
            fileEditor = null
            provider = null
            tempRoot?.let { root -> runCatching { root.toFile().deleteRecursively() } }
            tempRoot = null
        }

        private fun failureDetail(failure: Throwable): String = buildString {
            append(failure.javaClass.name)
            failure.message?.takeIf(String::isNotBlank)?.let { append(": ").append(it.take(400)) }
        }
    }

    private data class Fixture(
        val file: VirtualFile,
        val editor: Editor,
        val afterTableOffset: Int,
    )

    private data class CaseResult(val id: String, val outcome: String, val detail: String)
    private data class Evidence(
        val schemaVersion: Int,
        val ideBuild: String,
        val verdict: String,
        val cases: List<CaseResult>,
    )

    private class ProbeCaseFailure(id: String, detail: String) : RuntimeException("$id: $detail")

    private val FIDELITY_FIXTURES = listOf(
        "mixed-list-markers",
        "headings-and-delimiters",
        "code-forms-and-fences",
        "thematic-break-variants",
        "links-and-references",
        "table-lexical-variants",
        "whitespace-and-blank-lines",
        "line-endings-lf",
        "line-endings-crlf",
        "line-endings-no-trailing-newline",
        "repeated-similar-blocks",
        "paste-boundaries",
    )
}
