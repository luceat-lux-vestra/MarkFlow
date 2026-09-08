package com.algorist.markflow.editor.native

import com.google.gson.GsonBuilder
import com.intellij.codeInsight.editorActions.CopyPastePreProcessor
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefApp
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/** Production-independent real-IDE evidence for #146. */
internal object NativeEditingProbe {
    const val OUTPUT_PROPERTY = "markflow.nativeEditingProbe.output"
    const val SELECTED_SHELL = "PLATFORM_TEXT_EDITOR_AUGMENTATION"

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
        private var tempRoot: Path? = null
        private var jcefSupported: Boolean? = null
        private lateinit var fixture: Fixture
        private lateinit var provider: FileEditorProvider
        private lateinit var first: PlatformEditorHandle
        private lateinit var second: PlatformEditorHandle

        fun run() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            try {
                check(!project.isDefault) { "#146 runtime proof requires a real opened project" }
                check(!project.isDisposed) { "#146 runtime proof project was already disposed" }

                case("jcef-disabled-native-editing") {
                    val supported = JBCefApp.isSupported()
                    jcefSupported = supported
                    check(!supported) { "JCEF must be runtime-disabled for the #146 native editing proof" }
                    "JBCefApp.isSupported=false projectDefault=false"
                }

                fixture = createFixture()
                provider = selectPlatformTextProvider(fixture.file)
                first = createPlatformTextEditor(provider, fixture.file)
                second = createPlatformTextEditor(provider, fixture.file)

                case("native-paste-preprocessor-registered") {
                    val processors = CopyPastePreProcessor.EP_NAME.extensionList
                    check(processors.count { it is NativeMarkdownPastePreProcessor } == 1) {
                        "expected exactly one NativeMarkdownPastePreProcessor, observed ${processors.map { it.javaClass.name }}"
                    }
                    check(first.editor.document === second.editor.document)
                    "registered=true sharedDocument=true"
                }

                case("markdown-mime-preferred-payload-only") {
                    val editor = first.editor
                    resetCarets(editor)
                    val offset = fixture.outsidePasteOffset
                    editor.caretModel.moveToOffset(offset)
                    val sourceBefore = fixture.document.text
                    val prefix = sourceBefore.substring(0, offset)
                    val suffix = sourceBefore.substring(offset)
                    val inserted = "**mime-preferred**\n"

                    paste(
                        editor,
                        MarkdownTransferable(
                            markdown = "\uFEFF**mime-preferred**\r\n",
                            plain = "plain-fallback",
                        ),
                    )

                    check(fixture.document.text == prefix + inserted + suffix) {
                        "Markdown MIME paste changed more than the inserted payload or did not prefer text/markdown"
                    }
                    check(FileDocumentManager.getInstance().isDocumentUnsaved(fixture.document))
                    proveUndoRedo(first.fileEditor, sourceBefore, prefix + inserted + suffix)
                    restoreAndSave(sourceBefore)
                    "markdownMimePreferred=true bomRemoved=true lineEndingsNormalized=true surroundingSourceStable=true undoRedo=true"
                }

                case("markdown-like-plain-payload-only") {
                    val editor = first.editor
                    resetCarets(editor)
                    val offset = fixture.outsidePasteOffset
                    editor.caretModel.moveToOffset(offset)
                    val sourceBefore = fixture.document.text
                    val expectedInserted = "# pasted-heading\nbody\n"
                    val expected = sourceBefore.substring(0, offset) + expectedInserted + sourceBefore.substring(offset)

                    paste(editor, PlainTransferable("\uFEFF# pasted-heading\r\nbody\r"))
                    check(fixture.document.text == expected) {
                        "Markdown-like plain paste was not normalized as inserted payload only"
                    }
                    proveUndoRedo(first.fileEditor, sourceBefore, expected)
                    restoreAndSave(sourceBefore)
                    "markdownLikePlain=true bomRemoved=true lineEndingsNormalized=true surroundingSourceStable=true"
                }

                case("code-block-paste-delegates-literal") {
                    val editor = first.editor
                    resetCarets(editor)
                    val offset = fixture.fencedCodeOffset
                    editor.caretModel.moveToOffset(offset)
                    val sourceBefore = fixture.document.text
                    val expected = sourceBefore.substring(0, offset) + "literal-default" + sourceBefore.substring(offset)

                    paste(
                        editor,
                        MarkdownTransferable(
                            markdown = "**must-not-win**",
                            plain = "literal-default",
                        ),
                    )
                    check(fixture.document.text == expected) {
                        "code-block paste did not delegate to the platform's literal/default clipboard text"
                    }
                    check("**must-not-win**" !in fixture.document.text)
                    proveUndoRedo(first.fileEditor, sourceBefore, expected)
                    restoreAndSave(sourceBefore)
                    "codeBlockDelegated=true markdownMimeSuppressed=true literalPlatformText=true"
                }

                case("multicaret-paste-delegates-platform") {
                    val editor = first.editor
                    resetCarets(editor)
                    val sourceBefore = fixture.document.text
                    val firstOffset = fixture.outsidePasteOffset
                    val secondOffset = sourceBefore.indexOf("strong-target")
                    check(firstOffset in 0 until secondOffset)

                    editor.caretModel.moveToOffset(firstOffset)
                    requireNotNull(editor.caretModel.addCaret(editor.offsetToVisualPosition(secondOffset)))
                    check(editor.caretModel.caretCount == 2)

                    val inserted = "literal-multicaret"
                    paste(
                        editor,
                        MarkdownTransferable(
                            markdown = "**must-not-win-multicaret**",
                            plain = inserted,
                        ),
                    )

                    val expected = sourceBefore.substring(0, firstOffset) +
                        inserted +
                        sourceBefore.substring(firstOffset, secondOffset) +
                        inserted +
                        sourceBefore.substring(secondOffset)
                    check(fixture.document.text == expected) {
                        "platform multicaret paste did not insert the literal/default payload at both native carets"
                    }
                    check("must-not-win-multicaret" !in fixture.document.text) {
                        "#146 unexpectedly took Markdown MIME ownership on the platform multicaret paste path"
                    }
                    check(editor.caretModel.caretCount == 2)
                    proveUndoRedo(first.fileEditor, sourceBefore, expected)
                    restoreAndSave(sourceBefore)
                    "multicaretPasteDelegated=true markdownMimeSuppressed=true platformCarets=2 undoRedo=true"
                }

                case("ordinary-plain-paste-delegates-unchanged") {
                    val editor = first.editor
                    resetCarets(editor)
                    val offset = fixture.outsidePasteOffset
                    editor.caretModel.moveToOffset(offset)
                    val sourceBefore = fixture.document.text
                    val inserted = "ordinary plain text"
                    val expected = sourceBefore.substring(0, offset) + inserted + sourceBefore.substring(offset)

                    paste(editor, PlainTransferable(inserted))
                    check(fixture.document.text == expected)
                    restoreAndSave(sourceBefore)
                    "plainDelegated=true exactPayload=true"
                }

                case("rich-edit-locality-undo-redo") {
                    val editor = first.editor
                    resetCarets(editor)
                    val sourceBefore = fixture.document.text
                    val start = sourceBefore.indexOf("strong-target")
                    val end = start + "strong-target".length
                    check(start >= 0)
                    editor.caretModel.primaryCaret.setSelection(start, end)
                    val expected = sourceBefore.substring(0, start) + "**strong-target**" + sourceBefore.substring(end)

                    check(
                        NativeMarkdownRichEdit.apply(project, editor, NativeMarkdownRichEditKind.STRONG) ==
                            NativeMarkdownRichEditResult.APPLIED
                    )
                    check(fixture.document.text == expected)
                    check(editor.selectionModel.selectedText == "strong-target")
                    proveUndoRedo(first.fileEditor, sourceBefore, expected)
                    restoreAndSave(sourceBefore)
                    "sourceLocal=true command=true dirty=true undo=true redo=true selectionPreserved=true"
                }

                case("multicaret-rich-edit-is-local-and-isolated") {
                    val editor = first.editor
                    resetCarets(editor)
                    val sourceBefore = fixture.document.text
                    val alphaStart = sourceBefore.indexOf("alpha-target")
                    val betaStart = sourceBefore.indexOf("beta-target")
                    check(alphaStart in 0 until betaStart)
                    val primary = editor.caretModel.primaryCaret
                    primary.setSelection(alphaStart, alphaStart + "alpha-target".length)
                    val secondary = requireNotNull(
                        editor.caretModel.addCaret(editor.offsetToVisualPosition(betaStart + "beta-target".length))
                    )
                    secondary.setSelection(betaStart, betaStart + "beta-target".length)
                    second.editor.caretModel.moveToOffset(fixture.secondEditorOffset)
                    val secondCaretBefore = second.editor.caretModel.offset

                    check(
                        NativeMarkdownRichEdit.apply(project, editor, NativeMarkdownRichEditKind.EMPHASIS) ==
                            NativeMarkdownRichEditResult.APPLIED
                    )
                    val expected = sourceBefore
                        .replace("alpha-target", "*alpha-target*")
                        .replace("beta-target", "*beta-target*")
                    check(fixture.document.text == expected)
                    check(editor.caretModel.allCarets.mapNotNull { it.selectedText }.toSet() == setOf("alpha-target", "beta-target"))
                    val expectedSharedSourceDelta = expected.length - sourceBefore.length
                    check(second.editor.caretModel.offset == secondCaretBefore + expectedSharedSourceDelta) {
                        "second editor did not track the shared Document mutation independently"
                    }
                    check(!second.editor.caretModel.primaryCaret.hasSelection()) {
                        "first-editor selection state leaked into the second editor"
                    }
                    restoreAndSave(sourceBefore)
                    "multicaret=2 sourceLocal=true secondEditorStateIsolated=true trackedSourceDelta=true"
                }

                case("unsafe-rich-edit-fails-without-source-change") {
                    val editor = first.editor
                    resetCarets(editor)
                    val sourceBefore = fixture.document.text
                    val start = sourceBefore.indexOf("unsafe`code")
                    check(start >= 0)
                    editor.caretModel.primaryCaret.setSelection(start, start + "unsafe`code".length)
                    check(
                        NativeMarkdownRichEdit.apply(project, editor, NativeMarkdownRichEditKind.INLINE_CODE) ==
                            NativeMarkdownRichEditResult.UNSUPPORTED_SELECTION
                    )
                    check(fixture.document.text == sourceBefore)
                    "unsupportedRejected=true sourceStable=true"
                }

                case("platform-state-roundtrip-source-stable") {
                    val sourceBefore = fixture.document.text
                    val firstEditor = first.editor
                    val secondEditor = second.editor
                    resetCarets(firstEditor)
                    resetCarets(secondEditor)

                    firstEditor.caretModel.moveToOffset(fixture.stateCaretOffset)
                    firstEditor.selectionModel.setSelection(fixture.stateSelectionStart, fixture.stateCaretOffset)
                    secondEditor.caretModel.moveToOffset(fixture.secondEditorOffset)
                    val secondCaretBefore = secondEditor.caretModel.offset

                    val scrolling = firstEditor.scrollingModel
                    scrolling.disableAnimation()
                    try {
                        scrolling.scrollVertically(240)
                        val savedScroll = scrolling.verticalScrollOffset
                        check(savedScroll > 0)
                        val state = first.fileEditor.getState(FileEditorStateLevel.FULL)
                        check(state.javaClass.name != LEGACY_MARKFLOW_STATE_CLASS) {
                            "browser-shaped MarkFlowEditorState became target native state authority"
                        }

                        firstEditor.caretModel.moveToOffset(0)
                        firstEditor.selectionModel.removeSelection()
                        scrolling.scrollVertically(0)
                        first.fileEditor.setState(state)

                        check(firstEditor.caretModel.offset == fixture.stateCaretOffset)
                        check(firstEditor.selectionModel.selectionStart == fixture.stateSelectionStart)
                        check(firstEditor.selectionModel.selectionEnd == fixture.stateCaretOffset)
                        check(secondEditor.caretModel.offset == secondCaretBefore)
                        check(fixture.document.text == sourceBefore)
                        "platformState=${state.javaClass.name} legacyStateAuthority=false splitIsolation=true sourceStable=true scrollCaptured=true"
                    } finally {
                        scrolling.enableAnimation()
                    }
                }

                disposeEditor(first)
                disposeEditor(second)
                case("final-native-editing-lifecycle") {
                    check(liveEditors.isEmpty())
                    check(EditorFactory.getInstance().getEditors(fixture.document, project).isEmpty()) {
                        "native text editors retained after #146 proof disposal"
                    }
                    "editors=0 sourceStable=true"
                }

                finish("PASS")
            } catch (failure: Throwable) {
                recordInternalFailure(failure)
                finish("INCOMPLETE")
            }
        }

        private fun createFixture(): Fixture {
            var result: Fixture? = null
            case("authoritative-editing-fixture") {
                val projectBase = project.basePath?.let(Paths::get)
                    ?: error("opened #146 proof project has no basePath")
                val root = Files.createTempDirectory(projectBase, ".markflow-native-editing-")
                tempRoot = root
                val path = root.resolve("editing-proof.md")
                val source = buildString {
                    append("# Native editing proof\n\n")
                    append("outside-paste-target\n\n")
                    append("```text\n")
                    append("fenced-code-target\n")
                    append("```\n\n")
                    append("strong-target\n")
                    append("alpha-target and beta-target\n")
                    append("unsafe`code\n\n")
                    repeat(300) { index -> append("state-line-").append(index).append(" plain text\n") }
                }
                Files.writeString(path, source, StandardCharsets.UTF_8)
                val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                    ?: error("#146 fixture VirtualFile unavailable")
                val document = FileDocumentManager.getInstance().getDocument(file)
                    ?: error("#146 fixture Document unavailable")
                check(document.text == source)
                check(!FileDocumentManager.getInstance().isDocumentUnsaved(document))

                val outside = source.indexOf("outside-paste-target") + "outside-".length
                val fenced = source.indexOf("fenced-code-target") + "fenced-".length
                val stateCaret = source.indexOf("state-line-120") + 8
                val stateSelection = source.indexOf("state-line-118")
                val secondOffset = source.indexOf("state-line-20") + 4
                check(outside >= 0 && fenced >= 0 && stateCaret > 0 && stateSelection > 0 && secondOffset > 0)
                result = Fixture(
                    path = path,
                    file = file,
                    document = document,
                    outsidePasteOffset = outside,
                    fencedCodeOffset = fenced,
                    stateCaretOffset = stateCaret,
                    stateSelectionStart = stateSelection,
                    secondEditorOffset = secondOffset,
                )
                "sourceLength=${source.length} lines=${document.lineCount} projectDefault=false insideProject=true"
            }
            return result ?: error("fixture case did not produce a fixture")
        }

        private fun selectPlatformTextProvider(file: VirtualFile): FileEditorProvider {
            var result: FileEditorProvider? = null
            case("platform-text-provider-boundary") {
                val providers = FileEditorProvider.EP_FILE_EDITOR_PROVIDER.extensionList
                val accepted = providers.filter { candidate -> accepts(candidate, file) }
                val text = accepted.firstOrNull { it.editorTypeId == PLATFORM_TEXT_EDITOR_TYPE_ID }
                    ?: error("platform text editor provider did not accept #146 Markdown fixture")
                check(text.policy == FileEditorPolicy.NONE)
                val temporary = providers.singleOrNull { it.javaClass.name == TEMPORARY_MARKFLOW_PROVIDER_CLASS }
                    ?: error("temporary MarkFlow provider missing")
                check(!accepts(temporary, file)) {
                    "temporary browser provider accepted while JCEF runtime was disabled"
                }
                result = text
                "platformText=${text.javaClass.name} temporaryBrowserAccepted=false"
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

        private fun createPlatformTextEditor(provider: FileEditorProvider, file: VirtualFile): PlatformEditorHandle {
            val fileEditor = provider.createEditor(project, file)
            check(fileEditor is TextEditor) {
                "selected provider returned ${fileEditor.javaClass.name}, not TextEditor"
            }
            return PlatformEditorHandle(provider, fileEditor, fileEditor.editor).also(liveEditors::add)
        }

        private fun disposeEditor(handle: PlatformEditorHandle) {
            if (!liveEditors.remove(handle)) return
            handle.provider.disposeEditor(handle.fileEditor)
        }

        private fun paste(editor: Editor, transferable: Transferable) {
            CopyPasteManager.getInstance().setContents(transferable)
            WriteCommandAction.writeCommandAction(project)
                .withName("MarkFlow #146 Native Paste Proof")
                .run<RuntimeException> {
                    EditorActionManager.getInstance()
                        .getActionHandler(IdeActions.ACTION_EDITOR_PASTE)
                        .execute(editor, null, DataContext.EMPTY_CONTEXT)
                }
        }

        private fun proveUndoRedo(fileEditor: TextEditor, before: String, after: String) {
            val undoManager = UndoManager.getInstance(project)
            check(undoManager.isUndoAvailable(fileEditor))
            undoManager.undo(fileEditor)
            check(fixture.document.text == before)
            check(undoManager.isRedoAvailable(fileEditor))
            undoManager.redo(fileEditor)
            check(fixture.document.text == after)
        }

        private fun restoreAndSave(source: String) {
            WriteCommandAction.writeCommandAction(project)
                .withName("MarkFlow #146 Restore Fixture")
                .run<RuntimeException> {
                    fixture.document.setText(source)
                }
            FileDocumentManager.getInstance().saveDocument(fixture.document)
            check(!FileDocumentManager.getInstance().isDocumentUnsaved(fixture.document))
            check(Files.readString(fixture.path, StandardCharsets.UTF_8) == source)
        }

        private fun resetCarets(editor: Editor) {
            editor.caretModel.removeSecondaryCarets()
            editor.selectionModel.removeSelection()
        }

        private fun case(id: String, block: () -> String) {
            check(cases.none { it.id == id }) { "duplicate native editing evidence case id: $id" }
            val result = try {
                CaseResult(id = id, outcome = "PASS", detail = block())
            } catch (failure: Throwable) {
                CaseResult(id = id, outcome = "FAIL", detail = failureDetail(failure))
            }
            cases += result
            if (result.outcome != "PASS") throw ProbeCaseFailure(id, result.detail)
        }

        private fun recordInternalFailure(failure: Throwable) {
            if (cases.none { it.id == "probe-internal-failure" }) {
                cases += CaseResult("probe-internal-failure", "INCOMPLETE", failureDetail(failure))
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
                            schemaVersion = 1,
                            selectedShell = SELECTED_SHELL,
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
            liveEditors.toList().asReversed().forEach { handle ->
                runCatching { handle.provider.disposeEditor(handle.fileEditor) }
            }
            liveEditors.clear()
            tempRoot?.let { root -> runCatching { root.toFile().deleteRecursively() } }
            tempRoot = null
        }

        private fun failureDetail(failure: Throwable): String = buildString {
            append(failure.javaClass.name)
            failure.message?.takeIf(String::isNotBlank)?.let { append(": ").append(it.take(400)) }
        }
    }

    private data class Fixture(
        val path: Path,
        val file: VirtualFile,
        val document: com.intellij.openapi.editor.Document,
        val outsidePasteOffset: Int,
        val fencedCodeOffset: Int,
        val stateCaretOffset: Int,
        val stateSelectionStart: Int,
        val secondEditorOffset: Int,
    )

    private data class PlatformEditorHandle(
        val provider: FileEditorProvider,
        val fileEditor: TextEditor,
        val editor: Editor,
    )

    private data class CaseResult(val id: String, val outcome: String, val detail: String)

    private data class Evidence(
        val schemaVersion: Int,
        val selectedShell: String,
        val ideBuild: String,
        val jcefSupported: Boolean?,
        val verdict: String,
        val cases: List<CaseResult>,
    )

    private class ProbeCaseFailure(id: String, detail: String) : RuntimeException("$id: $detail")

    private class MarkdownTransferable(
        private val markdown: String,
        private val plain: String,
    ) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(MARKDOWN_FLAVOR, DataFlavor.stringFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
            flavor == MARKDOWN_FLAVOR || flavor == DataFlavor.stringFlavor

        override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
            MARKDOWN_FLAVOR -> markdown
            DataFlavor.stringFlavor -> plain
            else -> throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
        }
    }

    private class PlainTransferable(private val plain: String) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.stringFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.stringFlavor
        override fun getTransferData(flavor: DataFlavor): Any {
            if (flavor != DataFlavor.stringFlavor) throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
            return plain
        }
    }

    private const val PLATFORM_TEXT_EDITOR_TYPE_ID = "text-editor"
    private const val TEMPORARY_MARKFLOW_PROVIDER_CLASS = "com.algorist.markflow.editor.MarkFlowEditorProvider"
    private const val LEGACY_MARKFLOW_STATE_CLASS = "com.algorist.markflow.editor.state.MarkFlowEditorState"
    private val MARKDOWN_FLAVOR = DataFlavor("text/markdown;class=java.lang.String", "Markdown")
}
