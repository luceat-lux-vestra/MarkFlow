package com.algorist.markflow.editor.native

import com.google.gson.GsonBuilder
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.CaretStateTransferableData
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandlerBean
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/** Supplemental real-IDE editing proof for #152, executed before the #146 probe exits the IDE. */
internal object NativeMarkdownEditingParityProbe {
    private const val PLATFORM_TEXT_EDITOR_TYPE_ID = "text-editor"
    private const val OUTPUT_FILE = "parity.json"
    private const val BOLD_ACTION = "org.intellij.plugins.markdown.ui.actions.styling.ToggleBoldAction"
    private const val ITALIC_ACTION = "org.intellij.plugins.markdown.ui.actions.styling.ToggleItalicAction"
    private const val CODE_ACTION = "org.intellij.plugins.markdown.ui.actions.styling.ToggleCodeSpanAction"
    private const val DEFERRED_PASTE_HANDLER_CLASS =
        "com.algorist.markflow.editor.native.NativeMarkdownDeferredPasteHandler"
    private val editorActionHandlerEp =
        ExtensionPointName.create<EditorActionHandlerBean>("com.intellij.editorActionHandler")
    private val started = AtomicBoolean(false)

    fun runIfRequested(project: Project): Boolean {
        val editingOutput = System.getProperty(NativeEditingProbe.OUTPUT_PROPERTY)
            ?.takeIf(String::isNotBlank) ?: return false
        if (!started.compareAndSet(false, true)) return true
        val output = Paths.get(editingOutput).resolveSibling(OUTPUT_FILE)
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
        private val roots = mutableListOf<Path>()

        fun run() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            try {
                check(!project.isDefault)
                check(!project.isDisposed)

                case("multicaret-single-source-markdown-platform-semantics") {
                    withFixture("A\nB\n") { fixture ->
                        val editor = fixture.editor
                        editor.caretModel.moveToOffset(0)
                        requireNotNull(editor.caretModel.addCaret(editor.offsetToVisualPosition(2)))
                        val before = editor.document.text
                        val transferable = MarkdownTransferable(
                            markdown = "**rich**",
                            plain = "plain",
                            caretState = CaretStateTransferableData(intArrayOf(0), intArrayOf(5)),
                        )
                        check(deferredPasteHandlerRegistered()) {
                            "MarkFlow deferred EditorPaste handler is absent from the runtime extension inventory"
                        }
                        val preparation = NativeDeferredMarkdownPaste.prepare(editor, transferable)
                        check(preparation.disposition == NativeDeferredPasteDisposition.TRANSFORMED) {
                            "safe multicaret payload was not classified TRANSFORMED: ${preparation.disposition}"
                        }
                        val transformedPlain = preparation.transferable.getTransferData(DataFlavor.stringFlavor) as? String
                        check(transformedPlain == "**rich**") {
                            "prepared transferable did not replace only platform stringFlavor: $transformedPlain"
                        }
                        val preparedCaretData = CaretStateTransferableData.getFrom(preparation.transferable)
                        check(preparedCaretData?.caretCount == 1) {
                            "prepared transferable lost single-source-caret metadata: ${preparedCaretData?.caretCount}"
                        }

                        performPaste(editor, transferable)
                        val after = "**rich**A\n**rich**B\n"
                        val actual = editor.document.text
                        check(actual == after) {
                            "full EditorPaste chain mismatch; actual=${actual.escapeForEvidence()} expected=${after.escapeForEvidence()} " +
                                "handlerRegistered=true policyTransformed=true transformedPlain=true sourceCarets=1"
                        }
                        check(editor.caretModel.caretCount == 2)
                        proveUndoRedo(fixture, before, after)
                        "markdownPreferred=true sourceCarets=1 destinationCarets=2 platformDuplication=true undoRedo=true " +
                            "handlerRegistered=true policyTransformed=true transformedPlain=true"
                    }
                }

                case("multicaret-ambiguous-source-delegates-platform") {
                    withFixture("A\nB\n") { fixture ->
                        val editor = fixture.editor
                        editor.caretModel.moveToOffset(0)
                        requireNotNull(editor.caretModel.addCaret(editor.offsetToVisualPosition(2)))
                        val noMetadata = MarkdownTransferable(
                            markdown = "# rich-one\n# rich-two",
                            plain = "plain-one\nplain-two",
                        )
                        val prepared = NativeDeferredMarkdownPaste.prepare(editor, noMetadata)
                        check(prepared.disposition == NativeDeferredPasteDisposition.DELEGATE_SOURCE_MULTICARET_PAYLOAD)
                        check(prepared.transferable === noMetadata)
                        performPaste(editor, noMetadata)
                        check(editor.document.text == "plain-oneA\nplain-twoB\n") {
                            "null source-caret metadata did not preserve IntelliJ newline segmentation"
                        }

                        restore(fixture, "A\nB\n")
                        editor.caretModel.moveToOffset(0)
                        requireNotNull(editor.caretModel.addCaret(editor.offsetToVisualPosition(2)))
                        val sourceMulti = MarkdownTransferable(
                            markdown = "**must-not-reindex**",
                            plain = "x\ny",
                            caretState = CaretStateTransferableData(intArrayOf(0, 2), intArrayOf(1, 3)),
                        )
                        val sourcePrepared = NativeDeferredMarkdownPaste.prepare(editor, sourceMulti)
                        check(sourcePrepared.disposition == NativeDeferredPasteDisposition.DELEGATE_SOURCE_MULTICARET_PAYLOAD)
                        check(sourcePrepared.transferable === sourceMulti)
                        performPaste(editor, sourceMulti)
                        check(editor.document.text == "xA\nyB\n") {
                            "multi-source-caret offsets were not delegated to the platform unchanged"
                        }
                        "nullMetadataDelegated=true newlineSegmentationStable=true multiSourceOffsetsDelegated=true markdownSuppressed=true"
                    }
                }

                case("column-mode-markdown-platform-semantics") {
                    val safeDetail = withFixture("first\nsecond\nthird\n") { fixture ->
                        val editor = fixture.editor as EditorEx
                        editor.isColumnMode = true
                        editor.caretModel.moveToLogicalPosition(com.intellij.openapi.editor.LogicalPosition(0, 0))
                        val transferable = MarkdownTransferable(
                            markdown = "# one\n# two",
                            plain = "plain",
                        )
                        val prepared = NativeDeferredMarkdownPaste.prepare(editor, transferable)
                        check(prepared.disposition == NativeDeferredPasteDisposition.TRANSFORMED)
                        performPaste(editor, transferable)
                        check(editor.document.text == "# onefirst\n# twosecond\nthird\n") {
                            "column-mode Markdown payload did not preserve platform per-line clone/distribution semantics"
                        }
                        check(editor.caretModel.caretCount == 2)
                        "columnMode=true markdownPreferred=true platformClones=2 perLineDistribution=true"
                    }

                    val codeDetail = withFixture("outside\n```text\ncode\n```\ntail\n") { fixture ->
                        val editor = fixture.editor as EditorEx
                        editor.isColumnMode = true
                        editor.caretModel.moveToLogicalPosition(com.intellij.openapi.editor.LogicalPosition(0, 1))
                        val transferable = MarkdownTransferable(
                            markdown = "# one\n# two\n# three",
                            plain = "plain",
                        )
                        val prepared = NativeDeferredMarkdownPaste.prepare(editor, transferable)
                        check(prepared.disposition == NativeDeferredPasteDisposition.DELEGATE_CODE_CONTEXT)
                        check(prepared.transferable === transferable)
                        "prospectiveCodeDestinationDelegated=true"
                    }
                    "$safeDetail $codeDetail"
                }

                case("bundled-markdown-actions-source-local") {
                    withFixture("prefix alpha middle beta suffix gamma tail\n") { fixture ->
                        val editor = fixture.editor
                        val document = editor.document
                        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
                            ?: error("Markdown PSI unavailable for bundled action proof")

                        val actionManager = ActionManager.getInstance()
                        val actionIds = listOf(BOLD_ACTION, ITALIC_ACTION, CODE_ACTION)
                        actionIds.forEach { id -> check(actionManager.getAction(id) != null) { "bundled Markdown action missing: $id" } }

                        val original = document.text
                        val alphaStart = original.indexOf("alpha")
                        editor.caretModel.primaryCaret.setSelection(alphaStart, alphaStart + 5)
                        performBundledAction(BOLD_ACTION, editor, fixture.file, psiFile)
                        val bold = document.text
                        assertLocalWrap(original, bold, "alpha", alphaStart, setOf("**", "__"))
                        proveUndoRedo(fixture, original, bold)

                        val betaStart = document.text.indexOf("beta")
                        val betaBefore = document.text
                        editor.caretModel.primaryCaret.setSelection(betaStart, betaStart + 4)
                        performBundledAction(ITALIC_ACTION, editor, fixture.file, psiFile)
                        val italic = document.text
                        assertLocalWrap(betaBefore, italic, "beta", betaStart, setOf("*", "_"))

                        PsiDocumentManager.getInstance(project).commitDocument(document)
                        val gammaStart = document.text.indexOf("gamma")
                        val gammaBefore = document.text
                        editor.caretModel.primaryCaret.setSelection(gammaStart, gammaStart + 5)
                        performBundledAction(CODE_ACTION, editor, fixture.file, psiFile)
                        val code = document.text
                        assertLocalWrap(gammaBefore, code, "gamma", gammaStart, setOf("`"))

                        check(document.text.contains("alpha"))
                        check(document.text.contains("beta"))
                        check(document.text.contains("gamma"))
                        "actionsRegistered=3 boldLocal=true italicLocal=true codeLocal=true boldUndoRedo=true unrelatedSourceStable=true"
                    }
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

        private fun deferredPasteHandlerRegistered(): Boolean =
            editorActionHandlerEp.extensionList.any { bean ->
                bean.action == IdeActions.ACTION_EDITOR_PASTE &&
                    bean.implementationClass == DEFERRED_PASTE_HANDLER_CLASS
            }

        private fun performPaste(editor: Editor, transferable: Transferable) {
            CopyPasteManager.getInstance().setContents(transferable)
            WriteCommandAction.writeCommandAction(project)
                .withName("MarkFlow #152 Parity Paste")
                .run<RuntimeException> {
                    EditorActionManager.getInstance()
                        .getActionHandler(IdeActions.ACTION_EDITOR_PASTE)
                        .execute(editor, null, DataContext.EMPTY_CONTEXT)
                }
        }

        private fun performBundledAction(
            actionId: String,
            editor: Editor,
            file: VirtualFile,
            psiFile: com.intellij.psi.PsiFile,
        ) {
            PsiDocumentManager.getInstance(project).commitDocument(editor.document)
            val context = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.EDITOR, editor)
                .add(CommonDataKeys.VIRTUAL_FILE, file)
                .add(CommonDataKeys.PSI_FILE, psiFile)
                .build()
            val action = ActionManager.getInstance().getAction(actionId)
                ?: error("bundled Markdown action unavailable: $actionId")
            val event = AnActionEvent.createEvent(
                context,
                action.templatePresentation.clone(),
                ActionPlaces.UNKNOWN,
                ActionUiKind.NONE,
                null,
            )
            ActionUtil.updateAction(action, event)
            check(event.presentation.isEnabled) { "bundled Markdown action disabled for native editor: $actionId" }
            ActionUtil.performAction(action, event)
        }

        private fun assertLocalWrap(
            before: String,
            after: String,
            selected: String,
            start: Int,
            allowedBounds: Set<String>,
        ) {
            check(after != before)
            val prefix = before.substring(0, start)
            val suffix = before.substring(start + selected.length)
            check(after.startsWith(prefix)) { "bundled styling action changed source before explicit selection" }
            check(after.endsWith(suffix)) { "bundled styling action changed source after explicit selection" }
            val middle = after.substring(prefix.length, after.length - suffix.length)
            val bound = allowedBounds.singleOrNull { candidate -> middle == candidate + selected + candidate }
            check(bound != null) { "unexpected local styling result for '$selected': '$middle'" }
        }

        private fun proveUndoRedo(fixture: Fixture, before: String, after: String) {
            val undo = UndoManager.getInstance(project)
            check(undo.isUndoAvailable(fixture.fileEditor))
            undo.undo(fixture.fileEditor)
            check(fixture.editor.document.text == before)
            check(undo.isRedoAvailable(fixture.fileEditor))
            undo.redo(fixture.fileEditor)
            check(fixture.editor.document.text == after)
        }

        @Suppress("UsePropertyAccessSyntax")
        private fun restore(fixture: Fixture, source: String) {
            fixture.editor.caretModel.removeSecondaryCarets()
            fixture.editor.selectionModel.removeSelection()
            WriteCommandAction.writeCommandAction(project)
                .withName("MarkFlow #152 Parity Restore")
                .run<RuntimeException> { fixture.editor.document.setText(source) }
            FileDocumentManager.getInstance().saveDocument(fixture.editor.document)
            check(!FileDocumentManager.getInstance().isDocumentUnsaved(fixture.editor.document))
        }

        private fun <T> withFixture(source: String, block: (Fixture) -> T): T {
            val base = project.basePath?.let(Paths::get) ?: error("#152 editing parity project base unavailable")
            val root = Files.createTempDirectory(base, ".markflow-editing-parity-").also(roots::add)
            val path = root.resolve("editing-parity.md")
            Files.writeString(path, source, StandardCharsets.UTF_8)
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                ?: error("editing parity VirtualFile unavailable")
            val document = FileDocumentManager.getInstance().getDocument(file)
                ?: error("editing parity Document unavailable")
            val provider = selectPlatformTextProvider(file)
            val created = provider.createEditor(project, file)
            check(created is TextEditor)
            check(created.editor.document === document)
            val fixture = Fixture(file, provider, created, created.editor)
            return try {
                block(fixture)
            } finally {
                provider.disposeEditor(created)
            }
        }

        private fun selectPlatformTextProvider(file: VirtualFile): FileEditorProvider =
            FileEditorProvider.EP_FILE_EDITOR_PROVIDER.extensionList.firstOrNull { candidate ->
                candidate.editorTypeId == PLATFORM_TEXT_EDITOR_TYPE_ID && accepts(candidate, file)
            } ?: error("platform text editor provider did not accept #152 editing parity fixture")

        private fun accepts(provider: FileEditorProvider, file: VirtualFile): Boolean = runCatching {
            if (provider.acceptRequiresReadAction()) {
                ReadAction.computeBlocking<Boolean, RuntimeException> { provider.accept(project, file) }
            } else {
                provider.accept(project, file)
            }
        }.getOrDefault(false)

        private fun case(id: String, block: () -> String) {
            check(cases.none { it.id == id })
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
                gson.toJson(Evidence(1, ApplicationInfo.getInstance().build.asString(), verdict, cases)),
                StandardCharsets.UTF_8,
            )
        }

        private fun cleanup() {
            roots.asReversed().forEach { root -> runCatching { root.toFile().deleteRecursively() } }
            roots.clear()
        }

        private fun String.escapeForEvidence(): String =
            replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n")

        private fun failureDetail(failure: Throwable): String = buildString {
            append(failure.javaClass.name)
            failure.message?.takeIf(String::isNotBlank)?.let { append(": ").append(it.take(400)) }
        }
    }

    private data class Fixture(
        val file: VirtualFile,
        val provider: FileEditorProvider,
        val fileEditor: TextEditor,
        val editor: Editor,
    )

    private class MarkdownTransferable(
        private val markdown: String,
        private val plain: String,
        private val caretState: CaretStateTransferableData? = null,
    ) : Transferable {
        private val markdownFlavor = DataFlavor("text/markdown;class=java.lang.String", "Markdown")
        private val flavors = buildList {
            add(markdownFlavor)
            add(DataFlavor.stringFlavor)
            if (caretState != null) add(CaretStateTransferableData.FLAVOR)
        }.toTypedArray()

        override fun getTransferDataFlavors(): Array<DataFlavor> = flavors.copyOf()
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavors.any { it == flavor }
        override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
            markdownFlavor -> markdown
            DataFlavor.stringFlavor -> plain
            CaretStateTransferableData.FLAVOR -> caretState ?: throw UnsupportedFlavorException(flavor)
            else -> throw UnsupportedFlavorException(flavor)
        }
    }

    private data class CaseResult(val id: String, val outcome: String, val detail: String)
    private data class Evidence(
        val schemaVersion: Int,
        val ideBuild: String,
        val verdict: String,
        val cases: List<CaseResult>,
    )
    private class ProbeCaseFailure(id: String, detail: String) : RuntimeException("$id: $detail")
}
