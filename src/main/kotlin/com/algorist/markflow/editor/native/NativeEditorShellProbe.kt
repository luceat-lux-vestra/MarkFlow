package com.algorist.markflow.editor.native

import com.google.gson.GsonBuilder
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefApp
import java.awt.event.InputMethodEvent
import java.awt.font.TextHitInfo
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.AttributedString
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production-independent real-IDE proof for #143.
 *
 * The probe exercises the platform text editor through public FileEditor/TextEditor APIs in a real
 * opened project. It does not register or cut over a production MarkFlow editor. The current
 * browser editor remains temporary migration code until #153/#154.
 */
internal object NativeEditorShellProbe {
    const val OUTPUT_PROPERTY = "markflow.nativeEditorShellProbe.output"
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
        private val providerInventory = mutableListOf<ProviderResult>()
        private val liveEditors = mutableListOf<PlatformEditorHandle>()
        private val controllers = IdentityHashMap<Editor, ProbePresentationController>()
        private val lifecycleDisposable = Disposer.newDisposable()
        private var tempRoot: Path? = null
        private var lifecycleCreated = 0
        private var lifecycleReleased = 0
        private var neutralAttachments = 0
        private var jcefSupported: Boolean? = null

        fun run() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            try {
                check(!project.isDefault) { "#143 runtime proof requires a real opened project" }
                check(!project.isDisposed) { "#143 runtime proof project was already disposed" }

                case("jcef-disabled-runtime") {
                    val supported = JBCefApp.isSupported()
                    jcefSupported = supported
                    check(!supported) { "JCEF must be disabled for the #143 native-shell proof run" }
                    "JBCefApp.isSupported=false projectDefault=false"
                }

                val fixture = createFixture(project)
                val textProvider = proveProviderCoexistence(project, fixture.file)

                installLifecycleProbe(fixture.document)
                val first = createPlatformTextEditor(textProvider, project, fixture.file)
                val second = createPlatformTextEditor(textProvider, project, fixture.file)

                case("editor-factory-presentation-lifecycle") {
                    check(lifecycleCreated == 2) {
                        "expected two source-document editorCreated events, observed $lifecycleCreated"
                    }
                    check(neutralAttachments == 2) {
                        "expected two source/input-neutral controller attachments, observed $neutralAttachments"
                    }
                    check(controllers.size == 2) {
                        "expected one presentation controller per editor, observed ${controllers.size}"
                    }
                    check(controllers.values.all { !it.isDisposed })
                    "created=$lifecycleCreated controllers=${controllers.size} neutralAttachments=$neutralAttachments"
                }

                case("shared-document-splits") {
                    val authoritative = fixture.document
                    check(first.editor.document === authoritative)
                    check(second.editor.document === authoritative)
                    check(first.editor !== second.editor)

                    first.editor.caretModel.moveToOffset(4)
                    first.editor.selectionModel.setSelection(1, 4)
                    second.editor.caretModel.moveToOffset(40)
                    second.editor.selectionModel.setSelection(35, 40)
                    check(first.editor.caretModel.offset == 4)
                    check(second.editor.caretModel.offset == 40)
                    check(first.editor.selectionModel.selectionStart == 1)
                    check(second.editor.selectionModel.selectionStart == 35)

                    val extraCaret = first.editor.caretModel.addCaret(first.editor.offsetToVisualPosition(70))
                    check(extraCaret != null)
                    check(first.editor.caretModel.caretCount == 2)
                    check(second.editor.caretModel.caretCount == 1)
                    "sameDocument=true firstCarets=${first.editor.caretModel.caretCount} secondCarets=${second.editor.caretModel.caretCount}"
                }

                case("platform-keyboard-and-keymap") {
                    val editor = first.editor
                    val document = fixture.document
                    editor.caretModel.removeSecondaryCarets()
                    editor.selectionModel.removeSelection()
                    editor.caretModel.moveToOffset(0)

                    val typedBefore = document.text
                    TypedAction.getInstance().actionPerformed(editor, 'K', DataContext.EMPTY_CONTEXT)
                    check(document.text == "K$typedBefore") {
                        "platform TypedAction did not update the authoritative Document"
                    }
                    check(second.editor.document.text == document.text)

                    val activeKeymap = KeymapManager.getInstance().activeKeymap
                    check(activeKeymap.name.isNotBlank())
                    check(activeKeymap.getShortcuts(IdeActions.ACTION_EDITOR_COPY).isNotEmpty()) {
                        "active keymap does not expose a copy shortcut"
                    }
                    check(activeKeymap.getShortcuts(IdeActions.ACTION_EDITOR_PASTE).isNotEmpty()) {
                        "active keymap does not expose a paste shortcut"
                    }
                    "typed=true keymap=${activeKeymap.name} copyShortcut=true pasteShortcut=true"
                }

                case("platform-clipboard") {
                    val editor = first.editor
                    val document = fixture.document
                    editor.caretModel.removeSecondaryCarets()
                    editor.selectionModel.setSelection(0, 1)
                    val copied = document.getText(com.intellij.openapi.util.TextRange(0, 1))
                    EditorActionManager.getInstance()
                        .getActionHandler(IdeActions.ACTION_EDITOR_COPY)
                        .execute(editor, null, DataContext.EMPTY_CONTEXT)
                    editor.selectionModel.removeSelection()
                    editor.caretModel.moveToOffset(document.textLength)
                    val beforePasteLength = document.textLength

                    WriteCommandAction.writeCommandAction(project)
                        .withName("MarkFlow #143 Clipboard Paste Proof")
                        .run<RuntimeException> {
                            EditorActionManager.getInstance()
                                .getActionHandler(IdeActions.ACTION_EDITOR_PASTE)
                                .execute(editor, null, DataContext.EMPTY_CONTEXT)
                        }

                    check(document.textLength == beforePasteLength + copied.length)
                    check(document.text.endsWith(copied)) {
                        "platform copy/paste handlers did not round-trip the selected source"
                    }
                    check(second.editor.document.text == document.text)
                    "copy=true paste=true sharedSource=true"
                }

                case("platform-ime-composition") {
                    val editor = first.editor
                    val document = fixture.document
                    editor.caretModel.removeSecondaryCarets()
                    editor.selectionModel.removeSelection()
                    editor.caretModel.moveToOffset(1)
                    val compositionOffset = editor.caretModel.offset

                    val composing = inputMethodEvent(editor, "ㅎ", committedCharacterCount = 0)
                    WriteCommandAction.writeCommandAction(project)
                        .withName("MarkFlow #143 IME Composition Proof")
                        .run<RuntimeException> {
                            editor.contentComponent.dispatchEvent(composing)
                        }
                    check(composing.isConsumed) {
                        "native editor did not consume the composition InputMethodEvent"
                    }
                    check(document.textLength > compositionOffset)
                    check(document.text.substring(compositionOffset, compositionOffset + 1) == "ㅎ") {
                        "native composition was not reflected in the authoritative Document"
                    }

                    val committed = inputMethodEvent(editor, "한", committedCharacterCount = 1)
                    WriteCommandAction.writeCommandAction(project)
                        .withName("MarkFlow #143 IME Commit Proof")
                        .run<RuntimeException> {
                            editor.contentComponent.dispatchEvent(committed)
                        }
                    check(committed.isConsumed) {
                        "native editor did not consume the committed InputMethodEvent"
                    }
                    check(document.text.substring(compositionOffset, compositionOffset + 1) == "한") {
                        "committed IME text did not replace the active composition"
                    }
                    check(second.editor.document.text == document.text)

                    val fileDocumentManager = FileDocumentManager.getInstance()
                    check(fileDocumentManager.isDocumentUnsaved(document))
                    fileDocumentManager.saveDocument(document)
                    check(!fileDocumentManager.isDocumentUnsaved(document))
                    check(Files.readString(fixture.path, StandardCharsets.UTF_8) == document.text)
                    "compositionConsumed=true commitConsumed=true committedText=한 save=true"
                }

                case("caret-selection-scroll-file-editor-state") {
                    val editor = first.editor
                    editor.caretModel.removeSecondaryCarets()
                    editor.caretModel.moveToOffset(240)
                    editor.selectionModel.setSelection(180, 240)

                    val scrolling = editor.scrollingModel
                    scrolling.disableAnimation()
                    try {
                        scrolling.scrollVertically(240)
                        val savedScroll = scrolling.verticalScrollOffset
                        check(savedScroll > 0) {
                            "native editor could not establish a non-zero scroll offset"
                        }
                        val scrolledState = first.fileEditor.getState(FileEditorStateLevel.FULL)

                        scrolling.scrollVertically(0)
                        check(scrolling.verticalScrollOffset == 0)
                        val topState = first.fileEditor.getState(FileEditorStateLevel.FULL)
                        check(scrolledState != topState) {
                            "opaque FileEditor state did not observe a changed scroll position with stable caret/selection"
                        }

                        scrolling.scrollVertically(savedScroll)
                        check(scrolling.verticalScrollOffset == savedScroll)
                        editor.caretModel.moveToOffset(1)
                        editor.selectionModel.removeSelection()
                        first.fileEditor.setState(scrolledState)
                        check(editor.caretModel.offset == 240)
                        check(editor.selectionModel.selectionStart == 180)
                        check(editor.selectionModel.selectionEnd == 240)
                        check(first.fileEditor.preferredFocusedComponent === editor.contentComponent)
                        "caret=240 selection=180..240 scroll=$savedScroll scrollCapturedByFileEditorState=true focus=nativeContent"
                    } finally {
                        scrolling.enableAnimation()
                    }
                }

                case("dirty-save-undo-redo") {
                    val sourceBefore = fixture.document.text
                    val fileDocumentManager = FileDocumentManager.getInstance()
                    check(!fileDocumentManager.isDocumentUnsaved(fixture.document)) {
                        "fixture document unexpectedly dirty before native edit"
                    }

                    WriteCommandAction.writeCommandAction(project)
                        .withName("MarkFlow #143 Native Edit Proof")
                        .run<RuntimeException> {
                            fixture.document.insertString(0, "native-edit\n")
                        }

                    val edited = fixture.document.text
                    check(edited != sourceBefore) { "native write command did not mutate the authoritative Document" }
                    check(fileDocumentManager.isDocumentUnsaved(fixture.document)) {
                        "native write command did not mark the authoritative Document dirty"
                    }
                    check(first.editor.document.text == edited) { "first native editor did not observe authoritative edit" }
                    check(second.editor.document.text == edited) { "second native editor did not observe authoritative edit" }

                    val undoManager = UndoManager.getInstance(project)
                    check(undoManager.isUndoAvailable(first.fileEditor)) {
                        "project undo unavailable for the native TextEditor after the write command"
                    }
                    undoManager.undo(first.fileEditor)
                    check(fixture.document.text == sourceBefore) {
                        "project undo did not restore the pre-command authoritative source"
                    }
                    check(undoManager.isRedoAvailable(first.fileEditor)) {
                        "project redo unavailable immediately after successful undo"
                    }
                    undoManager.redo(first.fileEditor)
                    check(fixture.document.text == edited) {
                        "project redo did not restore the native write command"
                    }

                    fileDocumentManager.saveDocument(fixture.document)
                    check(!fileDocumentManager.isDocumentUnsaved(fixture.document)) {
                        "FileDocumentManager save left the authoritative Document dirty"
                    }
                    check(Files.readString(fixture.path, StandardCharsets.UTF_8) == edited) {
                        "saved backing file does not equal authoritative Document"
                    }
                    "dirty=true undoScope=project-text-editor undo=true redo=true save=true sharedSource=true"
                }

                case("exact-source-fallback") {
                    val source = fixture.document.text
                    check(first.editor.document.text == source)
                    check(second.editor.document.text == source)
                    check(controllers.values.all { !it.isDisposed })
                    "native TextEditor exposes exact authoritative source; no alternate editable model"
                }

                disposeEditor(first)
                disposeEditor(second)
                case("create-recreate-dispose") {
                    check(lifecycleReleased == 2) {
                        "expected two source-document editorReleased events, observed $lifecycleReleased"
                    }
                    check(controllers.isEmpty()) {
                        "presentation controllers retained after editor release: ${controllers.size}"
                    }
                    check(EditorFactory.getInstance().getEditors(fixture.document, project).isEmpty()) {
                        "source-document editors retained after disposal"
                    }

                    val recreated = createPlatformTextEditor(textProvider, project, fixture.file)
                    check(recreated.editor.document === fixture.document)
                    disposeEditor(recreated)
                    check(lifecycleCreated == 3)
                    check(lifecycleReleased == 3)
                    check(neutralAttachments == 3)
                    check(controllers.isEmpty())
                    check(EditorFactory.getInstance().getEditors(fixture.document, project).isEmpty())
                    "created=$lifecycleCreated released=$lifecycleReleased neutralAttachments=$neutralAttachments retainedControllers=0"
                }

                case("owned-file-editor-rejection-boundary") {
                    val platformProvider = providerInventory.singleOrNull { it.selectedPlatformTextProvider }
                        ?: error("selected platform text provider missing from evidence")
                    check(platformProvider.policy == FileEditorPolicy.NONE.name)
                    check(platformProvider.accepted)
                    check(providerInventory.any { it.markFlowTemporaryProvider && !it.accepted }) {
                        "temporary JCEF-gated MarkFlow provider must not be required when JCEF is disabled"
                    }
                    "platform TextEditor already owns FileEditor/input/state semantics; target adds presentation only"
                }

                finish("PASS")
            } catch (failure: Throwable) {
                if (cases.none { it.id == "probe-internal-failure" }) {
                    cases += CaseResult(
                        id = "probe-internal-failure",
                        outcome = "INCOMPLETE",
                        detail = failureDetail(failure),
                    )
                }
                finish("INCOMPLETE")
            }
        }

        private fun createFixture(project: Project): Fixture {
            var result: Fixture? = null
            case("authoritative-document-fixture") {
                val projectBase = project.basePath?.let(Paths::get)
                    ?: error("opened #143 proof project has no basePath")
                val root = Files.createTempDirectory(projectBase, ".markflow-native-shell-")
                tempRoot = root
                val path = root.resolve("shell-proof.md")
                val source = buildString {
                    append("# Native shell proof\n\n")
                    repeat(2_000) { index ->
                        append("line ").append(index)
                            .append(" [link](https://example.invalid/) **strong** `code`\n")
                    }
                }
                Files.writeString(path, source, StandardCharsets.UTF_8)
                val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                    ?: error("fixture VirtualFile unavailable")
                val authoritative = FileDocumentManager.getInstance().getDocument(file)
                    ?: error("fixture Document unavailable")
                check(authoritative.text == source)
                check(!FileDocumentManager.getInstance().isDocumentUnsaved(authoritative))
                result = Fixture(path, file, authoritative)
                "documentLength=${source.length} lines=${authoritative.lineCount} projectDefault=${project.isDefault} insideProject=true"
            }
            return result ?: error("fixture case did not produce a fixture")
        }

        private fun proveProviderCoexistence(project: Project, file: VirtualFile): FileEditorProvider {
            var selected: FileEditorProvider? = null
            case("provider-and-bundled-markdown-coexistence") {
                val providers = FileEditorProvider.EP_FILE_EDITOR_PROVIDER.extensionList
                check(providers.isNotEmpty())
                check(providers.any { it.javaClass.name.contains("markdown", ignoreCase = true) }) {
                    "bundled Markdown FileEditor provider not visible"
                }

                for (provider in providers) {
                    val accepted = runCatching {
                        if (provider.acceptRequiresReadAction()) {
                            ReadAction.computeBlocking<Boolean, RuntimeException> { provider.accept(project, file) }
                        } else {
                            provider.accept(project, file)
                        }
                    }.getOrElse { false }
                    val isText = provider.editorTypeId == PLATFORM_TEXT_EDITOR_TYPE_ID
                    val isTemporaryMarkFlow = provider.javaClass.name == TEMPORARY_MARKFLOW_PROVIDER_CLASS
                    providerInventory += ProviderResult(
                        className = provider.javaClass.name,
                        editorTypeId = provider.editorTypeId,
                        policy = provider.policy.name,
                        accepted = accepted,
                        selectedPlatformTextProvider = isText && accepted,
                        markFlowTemporaryProvider = isTemporaryMarkFlow,
                    )
                    if (isText && accepted && selected == null) {
                        selected = provider
                    }
                }

                val text = selected ?: error("platform text editor provider did not accept Markdown fixture")
                check(text.policy == FileEditorPolicy.NONE) {
                    "platform text editor policy unexpectedly displaces other editors: ${text.policy}"
                }
                val temporary = providerInventory.singleOrNull { it.markFlowTemporaryProvider }
                    ?: error("temporary MarkFlow provider missing from extension inventory")
                check(!temporary.accepted) {
                    "temporary browser provider still accepted Markdown while JCEF was disabled"
                }
                "registered=${providers.size} accepted=${providerInventory.count { it.accepted }} platformText=${text.javaClass.name}"
            }
            return selected ?: error("provider coexistence case did not select a text provider")
        }

        private fun installLifecycleProbe(authoritative: Document) {
            EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    if (event.editor.document !== authoritative) return
                    lifecycleCreated += 1
                    check(controllers[event.editor] == null) {
                        "duplicate presentation controller ownership for one editor"
                    }

                    val sourceBefore = authoritative.text
                    val stampBefore = authoritative.modificationStamp
                    val inputBefore = InputOwnershipSnapshot.capture(event.editor)
                    val controller = ProbePresentationController(event.editor)
                    val inputAfter = InputOwnershipSnapshot.capture(event.editor)
                    check(authoritative.text == sourceBefore)
                    check(authoritative.modificationStamp == stampBefore)
                    check(inputAfter == inputBefore) {
                        "presentation-controller attachment intercepted native input ownership"
                    }
                    controllers[event.editor] = controller
                    neutralAttachments += 1
                }

                override fun editorReleased(event: EditorFactoryEvent) {
                    if (event.editor.document !== authoritative) return
                    lifecycleReleased += 1
                    controllers.remove(event.editor)?.dispose()
                        ?: error("editor released without owned presentation controller")
                }
            }, lifecycleDisposable)
        }

        private fun createPlatformTextEditor(
            provider: FileEditorProvider,
            project: Project,
            file: VirtualFile,
        ): PlatformEditorHandle {
            val fileEditor = provider.createEditor(project, file)
            check(fileEditor is TextEditor) {
                "selected provider returned ${fileEditor.javaClass.name}, not TextEditor"
            }
            val handle = PlatformEditorHandle(provider, fileEditor, fileEditor.editor)
            liveEditors += handle
            return handle
        }

        private fun disposeEditor(handle: PlatformEditorHandle) {
            if (!liveEditors.remove(handle)) return
            handle.provider.disposeEditor(handle.fileEditor)
        }

        private fun inputMethodEvent(
            editor: Editor,
            text: String,
            committedCharacterCount: Int,
        ): InputMethodEvent = InputMethodEvent(
            editor.contentComponent,
            InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
            System.currentTimeMillis(),
            AttributedString(text).iterator,
            committedCharacterCount,
            TextHitInfo.afterOffset(0),
            TextHitInfo.afterOffset(0),
        )

        private fun case(id: String, block: () -> String) {
            check(cases.none { it.id == id }) { "duplicate native shell evidence case id: $id" }
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

        private fun finish(verdict: String) {
            cleanup()
            try {
                output.parent?.let(Files::createDirectories)
                Files.writeString(
                    output,
                    gson.toJson(
                        Evidence(
                            schemaVersion = 5,
                            selectedShell = SELECTED_SHELL,
                            ideBuild = ApplicationInfo.getInstance().build.asString(),
                            jcefSupported = jcefSupported,
                            verdict = verdict,
                            providerInventory = providerInventory,
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
            runCatching { Disposer.dispose(lifecycleDisposable) }
            tempRoot?.let { root -> runCatching { root.toFile().deleteRecursively() } }
            tempRoot = null
        }
    }

    private class ProbePresentationController(@Suppress("unused") private val editor: Editor) {
        var isDisposed: Boolean = false
            private set

        fun dispose() {
            check(!isDisposed) { "presentation controller disposed more than once" }
            isDisposed = true
        }
    }

    private data class InputOwnershipSnapshot(
        val keyListenerClasses: List<String>,
        val inputMethodListenerClasses: List<String>,
        val transferHandlerIdentity: Int?,
    ) {
        companion object {
            fun capture(editor: Editor): InputOwnershipSnapshot {
                val component = editor.contentComponent
                return InputOwnershipSnapshot(
                    keyListenerClasses = component.keyListeners.map { it.javaClass.name },
                    inputMethodListenerClasses = component.inputMethodListeners.map { it.javaClass.name },
                    transferHandlerIdentity = component.transferHandler?.let { System.identityHashCode(it) },
                )
            }
        }
    }

    private data class Fixture(
        val path: Path,
        val file: VirtualFile,
        val document: Document,
    )

    private data class PlatformEditorHandle(
        val provider: FileEditorProvider,
        val fileEditor: TextEditor,
        val editor: Editor,
    )

    private data class ProviderResult(
        val className: String,
        val editorTypeId: String,
        val policy: String,
        val accepted: Boolean,
        val selectedPlatformTextProvider: Boolean,
        val markFlowTemporaryProvider: Boolean,
    )

    private data class CaseResult(
        val id: String,
        val outcome: String,
        val detail: String,
    )

    private data class Evidence(
        val schemaVersion: Int,
        val selectedShell: String,
        val ideBuild: String,
        val jcefSupported: Boolean?,
        val verdict: String,
        val providerInventory: List<ProviderResult>,
        val cases: List<CaseResult>,
    )

    private class ProbeCaseFailure(id: String, detail: String) : RuntimeException("$id: $detail")

    private fun failureDetail(failure: Throwable): String {
        val message = failure.message?.replace(Regex("[\\r\\n]+"), " ")?.take(240)
        return if (message.isNullOrBlank()) failure.javaClass.name else "${failure.javaClass.name}: $message"
    }

    private const val PLATFORM_TEXT_EDITOR_TYPE_ID = "text-editor"
    private const val TEMPORARY_MARKFLOW_PROVIDER_CLASS = "com.algorist.markflow.editor.MarkFlowEditorProvider"
}
