package com.algorist.markflow.editor.native

import com.algorist.markflow.renderer.DerivedRendererRuntimeFactory
import com.google.gson.GsonBuilder
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.event.InputMethodEvent
import java.awt.font.TextHitInfo
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.AttributedString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #556 production-independent real-IDE proof for the MarkFlow-owned native FileEditor hypothesis.
 *
 * The proof uses the same authoritative FileDocumentManager Document as the bundled editor.
 * Production automatic presentation attachment is suppressed by this probe's *.output property;
 * the proof-owned editor attaches the existing NativePresentationController explicitly.
 */
internal object Leap2OwnedShellProbe {
    const val OUTPUT_PROPERTY = "markflow.leap2OwnedShellProbe.output"
    const val EXPECT_JCEF_PROPERTY = "markflow.leap2OwnedShellProbe.expectJcef"

    private val started = AtomicBoolean(false)

    fun startIfRequested(project: Project): Boolean {
        val output = System.getProperty(OUTPUT_PROPERTY)?.takeIf(String::isNotBlank) ?: return false
        if (!started.compareAndSet(false, true)) return true
        AppExecutorUtil.getAppExecutorService().execute {
            Runner(
                output = Paths.get(output),
                project = project,
                expectJcef = System.getProperty(EXPECT_JCEF_PROPERTY) == "true",
            ).run()
        }
        return true
    }

    private class Runner(
        private val output: Path,
        private val project: Project,
        private val expectJcef: Boolean,
    ) {
        private val gson = GsonBuilder().setPrettyPrinting().create()
        private val cases = mutableListOf<CaseResult>()
        private val resourceSnapshots = mutableListOf<ResourceSnapshot>()
        private val openedFiles = mutableListOf<VirtualFile>()
        private val directEditors = mutableListOf<Pair<FileEditorProvider, TextEditor>>()
        private var tempRoot: Path? = null

        fun run() {
            try {
                check(!project.isDefault) { "#556 proof requires a real opened project" }
                check(!project.isDisposed) { "#556 proof project is disposed" }

                case("runtime-boundary") {
                    val state = OptionalJcefProbeSupport.read()
                    val factories = onEdt { DerivedRendererRuntimeFactory.EP_NAME.extensionList.size }
                    if (expectJcef) {
                        check(state.classPresent) { "JCEF class missing in enabled #556 proof" }
                        check(state.supported) { "JCEF runtime unsupported in enabled #556 proof" }
                        check(factories >= 1) { "retained derived renderer factory missing" }
                    } else {
                        check(!state.classPresent) { "JCEF class remained visible in plugin-absent #556 proof" }
                        check(!state.supported)
                        check(factories == 0) { "renderer factory registered without optional JCEF plugin" }
                    }
                    "expectJcef=$expectJcef classPresent=${state.classPresent} supported=${state.supported} rendererFactories=$factories"
                }

                val plain = createFixture("leap2-plain.md", plainSource())
                resourceSnapshots += resourceSnapshot("baseline", openFiles = 0)

                case("candidate-a-augmentation-baseline") {
                    val provider = requireNotNull(
                        onEdt { FileEditorProviderManager.getInstance().getProvider(PLATFORM_TEXT_EDITOR_TYPE_ID) }
                    ) { "platform text editor provider unavailable" }
                    val fileEditor = onEdt { provider.createEditor(project, plain.file) }
                    check(fileEditor is TextEditor) { "platform provider did not create TextEditor" }
                    directEditors += provider to fileEditor
                    check(fileEditor.editor.document === plain.document)
                    resourceSnapshots += resourceSnapshot("intellij-only-one", openFiles = 1)
                    check(onEdt { NativeMarkFlowProductionLifecycle.attach(fileEditor.editor) })
                    check(NativeMarkFlowProductionLifecycle.controller(fileEditor.editor) != null)
                    resourceSnapshots += resourceSnapshot("candidate-a-one", openFiles = 1)
                    onEdt {
                        NativeMarkFlowProductionLifecycle.release(fileEditor.editor)
                        provider.disposeEditor(fileEditor)
                    }
                    directEditors.remove(provider to fileEditor)
                    "sameDocument=true presentationOwner=true"
                }

                val providers = caseValue("provider-coexistence-and-ordering") {
                    val list = onEdt {
                        FileEditorProviderManager.getInstance().getProviderList(project, plain.file)
                    }
                    val proof = list.singleOrNull {
                        it.editorTypeId == MarkFlowOwnedNativeFileEditorProvider.EDITOR_TYPE_ID
                    } ?: error("dormant #556 provider was not activated by the proof JVM")
                    check(proof.policy.name == "PLACE_BEFORE_DEFAULT_EDITOR")
                    val nonProof = list.filter { it !== proof }
                    check(nonProof.isNotEmpty()) { "MarkFlow proof provider displaced all IntelliJ providers" }
                    val platformTextPresent = nonProof.any { it.editorTypeId == PLATFORM_TEXT_EDITOR_TYPE_ID }
                    val markdownPresent = nonProof.any { it.javaClass.name.contains("markdown", ignoreCase = true) }
                    check(platformTextPresent || markdownPresent) {
                        "no IntelliJ text/Markdown provider coexists with MarkFlow proof provider"
                    }
                    val ids = list.map { it.editorTypeId }
                    ProviderEvidence(
                        ids = ids,
                        proofIndex = ids.indexOf(proof.editorTypeId),
                        platformTextPresent = platformTextPresent,
                    ) to
                        "providers=${ids.joinToString(",")} proofPolicy=${proof.policy.name} " +
                        "platformTextPresent=$platformTextPresent markdownProviderPresent=$markdownPresent"
                }

                val plainEditors = openFile(plain.file)
                val owned = plainEditors.filterIsInstance<MarkFlowOwnedNativeFileEditor>().singleOrNull()
                    ?: error("FileEditorManager did not create exactly one MarkFlow-owned native editor")
                val platform = plainEditors.filterIsInstance<TextEditor>().firstOrNull {
                    it !is MarkFlowOwnedNativeFileEditor
                } ?: error("FileEditorManager did not retain an IntelliJ text/Markdown editor")

                case("same-authoritative-document") {
                    check(owned.editor.document === plain.document)
                    check(platform.editor.document === plain.document)
                    check(owned.editor !== platform.editor)
                    "sameDocument=true distinctEditors=true managerEditors=${plainEditors.size}"
                }

                var alternativeProviderId: String? = null
                case("provider-selection-and-default-order") {
                    val manager = FileEditorManager.getInstance(project)
                    val initialSelected = onEdt { manager.getSelectedEditor(plain.file) }
                    val initialMarkFlowSelected = initialSelected === owned

                    onEdt {
                        manager.setSelectedEditor(plain.file, MarkFlowOwnedNativeFileEditorProvider.EDITOR_TYPE_ID)
                    }
                    check(onEdt { manager.getSelectedEditor(plain.file) } === owned) {
                        "public setSelectedEditor could not select MarkFlow provider"
                    }

                    val alternatives = providers.ids.filter {
                        it != MarkFlowOwnedNativeFileEditorProvider.EDITOR_TYPE_ID
                    }
                    var selectedAlternative: String? = null
                    for (id in alternatives) {
                        onEdt { manager.setSelectedEditor(plain.file, id) }
                        if (onEdt { manager.getSelectedEditor(plain.file) } !== owned) {
                            selectedAlternative = id
                            break
                        }
                    }
                    check(selectedAlternative != null) {
                        "could not switch back to any coexisting IntelliJ editor provider"
                    }
                    alternativeProviderId = selectedAlternative
                    onEdt {
                        manager.setSelectedEditor(plain.file, MarkFlowOwnedNativeFileEditorProvider.EDITOR_TYPE_ID)
                    }
                    "markFlowSelectable=true intellijSelectable=true alternative=$selectedAlternative " +
                        "initialMarkFlowSelected=$initialMarkFlowSelected proofIndex=${providers.proofIndex}"
                }

                case("presentation-ownership-isolation") {
                    check(NativeMarkFlowProductionLifecycle.controller(owned.editor) != null) {
                        "MarkFlow-owned editor has no presentation owner"
                    }
                    val leaked = plainEditors.filterIsInstance<TextEditor>()
                        .filter { it !is MarkFlowOwnedNativeFileEditor }
                        .count { NativeMarkFlowProductionLifecycle.controller(it.editor) != null }
                    check(leaked == 0) { "MarkFlow presentation leaked into $leaked IntelliJ editor(s)" }
                    val derived = requireNotNull(
                        NativeMarkFlowProductionLifecycle.controller(owned.editor)?.derivedEvidenceSnapshot()
                    )
                    check(derived.derivedFragments == 0)
                    check(derived.renderRequests == 0L)
                    "ownedControllers=1 leakedControllers=0 ordinaryDerivedFragments=0 renderRequests=0"
                }

                case("bidirectional-native-editing") {
                    val before = plain.document.text
                    onEdt {
                        owned.editor.caretModel.moveToOffset(0)
                        TypedAction.getInstance().actionPerformed(owned.editor, 'M', DataContext.EMPTY_CONTEXT)
                    }
                    check(plain.document.text == "M$before")
                    check(platform.editor.document.text == plain.document.text)

                    val afterOwned = plain.document.text
                    onEdt {
                        platform.editor.caretModel.moveToOffset(platform.editor.document.textLength)
                        TypedAction.getInstance().actionPerformed(platform.editor, 'I', DataContext.EMPTY_CONTEXT)
                    }
                    check(plain.document.text == afterOwned + "I")
                    check(owned.editor.document.text == plain.document.text)
                    "markFlowToIntellij=true intellijToMarkFlow=true customSyncProtocol=false"
                }

                case("native-keymap-clipboard-multicaret") {
                    val editor = owned.editor
                    onEdt {
                        editor.caretModel.removeSecondaryCarets()
                        editor.selectionModel.removeSelection()
                        editor.caretModel.moveToOffset(0)
                        val extra = editor.caretModel.addCaret(
                            editor.offsetToVisualPosition((editor.document.textLength / 3).coerceAtLeast(2))
                        )
                        check(extra != null)
                        check(editor.caretModel.caretCount == 2)
                        editor.caretModel.removeSecondaryCarets()

                        editor.selectionModel.setSelection(0, 1)
                        val copied = editor.document.text.substring(0, 1)
                        EditorActionManager.getInstance()
                            .getActionHandler(IdeActions.ACTION_EDITOR_COPY)
                            .execute(editor, null, DataContext.EMPTY_CONTEXT)
                        editor.selectionModel.removeSelection()
                        editor.caretModel.moveToOffset(editor.document.textLength)
                        val beforePasteLength = editor.document.textLength
                        WriteCommandAction.writeCommandAction(project)
                            .withName("MarkFlow Leap2 Clipboard Paste Proof")
                            .run<RuntimeException> {
                                EditorActionManager.getInstance()
                                    .getActionHandler(IdeActions.ACTION_EDITOR_PASTE)
                                    .execute(editor, null, DataContext.EMPTY_CONTEXT)
                            }
                        check(editor.document.textLength == beforePasteLength + copied.length)
                        check(editor.document.text.endsWith(copied))
                    }
                    check(platform.editor.document.text == plain.document.text)

                    val keymap = KeymapManager.getInstance().activeKeymap
                    check(keymap.name.isNotBlank())
                    check(keymap.getShortcuts(IdeActions.ACTION_EDITOR_COPY).isNotEmpty())
                    check(keymap.getShortcuts(IdeActions.ACTION_EDITOR_PASTE).isNotEmpty())
                    "multicaret=true copy=true paste=true keymap=${keymap.name} sharedSource=true"
                }

                case("owned-split-lifecycle-isolation") {
                    val proofProvider = requireNotNull(
                        onEdt {
                            FileEditorProviderManager.getInstance().getProvider(
                                MarkFlowOwnedNativeFileEditorProvider.EDITOR_TYPE_ID
                            )
                        }
                    )
                    val split = onEdt { proofProvider.createEditor(project, plain.file) }
                    check(split is MarkFlowOwnedNativeFileEditor)
                    directEditors += proofProvider to split
                    check(split.editor.document === plain.document)
                    check(split.editor !== owned.editor)
                    onEdt {
                        owned.editor.caretModel.moveToOffset(1)
                        split.editor.caretModel.moveToOffset((plain.document.textLength / 2).coerceAtLeast(2))
                    }
                    check(onEdt { owned.editor.caretModel.offset != split.editor.caretModel.offset })
                    check(NativeMarkFlowProductionLifecycle.controller(owned.editor) != null)
                    check(NativeMarkFlowProductionLifecycle.controller(split.editor) != null)
                    check(MarkFlowOwnedNativeFileEditor.liveEditorCountForDiagnostics >= 2)
                    onEdt { proofProvider.disposeEditor(split) }
                    directEditors.remove(proofProvider to split)
                    check(NativeMarkFlowProductionLifecycle.controller(owned.editor) != null)
                    "sameDocument=true perEditorCaret=true perEditorPresentation=true splitDisposed=true"
                }

                case("file-editor-state") {
                    val target = (plain.document.textLength / 2).coerceAtLeast(1)
                    onEdt {
                        owned.editor.caretModel.moveToOffset(target)
                        owned.editor.selectionModel.setSelection(target - 1, target)
                        owned.editor.scrollingModel.disableAnimation()
                        try {
                            owned.editor.scrollingModel.scrollVertically(160)
                        } finally {
                            owned.editor.scrollingModel.enableAnimation()
                        }
                    }
                    val savedScroll = onEdt { owned.editor.scrollingModel.verticalScrollOffset }
                    check(savedScroll > 0) { "MarkFlow-owned editor could not establish scroll state" }
                    val state = onEdt { owned.getState(FileEditorStateLevel.FULL) }
                    onEdt {
                        owned.editor.caretModel.moveToOffset(0)
                        owned.editor.selectionModel.removeSelection()
                        owned.editor.scrollingModel.scrollVertically(0)
                        owned.setState(state)
                    }
                    onEdt {
                        check(owned.editor.caretModel.offset == target)
                        check(owned.editor.selectionModel.selectionStart == target - 1)
                        check(owned.editor.selectionModel.selectionEnd == target)
                    }
                    "caret=$target selection=${target - 1}..$target stateRoundTrip=true"
                }

                case("ime-undo-redo-save") {
                    val editor = owned.editor
                    onEdt {
                        FileEditorManager.getInstance(project).setSelectedEditor(
                            plain.file,
                            MarkFlowOwnedNativeFileEditorProvider.EDITOR_TYPE_ID,
                        )
                        editor.caretModel.removeSecondaryCarets()
                        editor.selectionModel.removeSelection()
                        editor.caretModel.moveToOffset(1)
                    }
                    val compositionOffset = onEdt { editor.caretModel.offset }
                    val composing = inputMethodEvent(editor, "ㅎ", committedCharacterCount = 0)
                    onEdt {
                        WriteCommandAction.writeCommandAction(project)
                            .withName("MarkFlow Leap2 IME Composition Proof")
                            .run<RuntimeException> { editor.contentComponent.dispatchEvent(composing) }
                    }
                    check(composing.isConsumed)
                    check(plain.document.text.substring(compositionOffset, compositionOffset + 1) == "ㅎ")

                    val committed = inputMethodEvent(editor, "한", committedCharacterCount = 1)
                    onEdt {
                        WriteCommandAction.writeCommandAction(project)
                            .withName("MarkFlow Leap2 IME Commit Proof")
                            .run<RuntimeException> { editor.contentComponent.dispatchEvent(committed) }
                    }
                    check(committed.isConsumed)
                    check(plain.document.text.substring(compositionOffset, compositionOffset + 1) == "한")
                    check(platform.editor.document.text == plain.document.text)

                    val beforeUndoProof = plain.document.text
                    onEdt {
                        WriteCommandAction.writeCommandAction(project)
                            .withName("MarkFlow Leap2 Undo Proof")
                            .run<RuntimeException> { plain.document.insertString(0, "undo-proof\n") }
                    }
                    val edited = plain.document.text
                    val undo = UndoManager.getInstance(project)
                    check(onEdt { undo.isUndoAvailable(owned) })
                    onEdt { undo.undo(owned) }
                    check(plain.document.text == beforeUndoProof)
                    check(onEdt { undo.isRedoAvailable(owned) })
                    onEdt { undo.redo(owned) }
                    check(plain.document.text == edited)

                    onEdt { FileDocumentManager.getInstance().saveDocument(plain.document) }
                    check(!FileDocumentManager.getInstance().isDocumentUnsaved(plain.document))
                    plain.file.fileSystem.refresh(false)
                    check(Files.readString(plain.path, StandardCharsets.UTF_8) == plain.document.text)
                    "ime=true undo=true redo=true save=true sharedSource=true"
                }

                case("save-close-reopen") {
                    val fixture = createFixture(
                        "leap2-reopen.md",
                        "# reopen\n\nsource survives native FileEditor recreation.\n",
                    )
                    val firstOpen = openFile(fixture.file)
                    val firstOwned = firstOpen.filterIsInstance<MarkFlowOwnedNativeFileEditor>().single()
                    onEdt {
                        firstOwned.editor.caretModel.moveToOffset(firstOwned.editor.document.textLength)
                        TypedAction.getInstance().actionPerformed(
                            firstOwned.editor,
                            'R',
                            DataContext.EMPTY_CONTEXT,
                        )
                        FileDocumentManager.getInstance().saveDocument(fixture.document)
                    }
                    val expected = fixture.document.text
                    closeFile(fixture.file)
                    val reopened = openFile(fixture.file)
                    val reopenedOwned = reopened.filterIsInstance<MarkFlowOwnedNativeFileEditor>().single()
                    check(reopenedOwned.editor.document === fixture.document)
                    check(reopenedOwned.editor.document.text == expected)
                    fixture.file.fileSystem.refresh(false)
                    check(Files.readString(fixture.path, StandardCharsets.UTF_8) == expected)
                    closeFile(fixture.file)
                    "sameDocumentAfterReopen=true sourceStable=true save=true"
                }

                case("provider-selection-persistence-finding") {
                    val alternative = requireNotNull(alternativeProviderId) {
                        "no IntelliJ alternative provider was selected earlier"
                    }
                    val fixture = createFixture(
                        "leap2-selection.md",
                        "# selection\n\nprovider selection persistence finding.\n",
                    )
                    val firstOpen = openFile(fixture.file)
                    check(firstOpen.any { it is MarkFlowOwnedNativeFileEditor })
                    val manager = FileEditorManager.getInstance(project)
                    onEdt { manager.setSelectedEditor(fixture.file, alternative) }
                    check(onEdt { manager.getSelectedEditor(fixture.file) } !is MarkFlowOwnedNativeFileEditor)
                    closeFile(fixture.file)

                    val reopened = openFile(fixture.file)
                    check(reopened.any { it is MarkFlowOwnedNativeFileEditor })
                    val rememberedAlternative =
                        onEdt { manager.getSelectedEditor(fixture.file) } !is MarkFlowOwnedNativeFileEditor
                    closeFile(fixture.file)
                    "alternative=$alternative rememberedAlternative=$rememberedAlternative " +
                        "publicSelectionApi=true"
                }

                resourceSnapshots += resourceSnapshot("candidate-b-one", openFiles = 1)

                val derivedFixture = createFixture("leap2-derived.md", derivedSource())
                val derivedEditors = openFile(derivedFixture.file)
                val derivedOwned = derivedEditors.filterIsInstance<MarkFlowOwnedNativeFileEditor>().single()

                case("lazy-derived-runtime-boundary") {
                    val controller = requireNotNull(NativeMarkFlowProductionLifecycle.controller(derivedOwned.editor))
                    val evidence = awaitEvidence(controller)
                    check(evidence.derivedFragments >= 2) {
                        "expected Mermaid + KaTeX derived fragments, got ${evidence.derivedFragments}"
                    }
                    val factories = onEdt { DerivedRendererRuntimeFactory.EP_NAME.extensionList.size }
                    if (expectJcef) {
                        check(factories >= 1)
                        check(evidence.renderRequests >= 1L) {
                            "JCEF-enabled derived source never reached retained renderer runtime"
                        }
                    } else {
                        check(factories == 0)
                        check(evidence.renderRequests == 0L)
                        check(evidence.rendererFailures >= evidence.derivedFragments.toLong()) {
                            "no-JCEF derived projections did not degrade locally"
                        }
                    }
                    check(derivedOwned.editor.document.text == derivedFixture.document.text)
                    "derivedFragments=${evidence.derivedFragments} renderRequests=${evidence.renderRequests} " +
                        "rendererFailures=${evidence.rendererFailures} rendererFactories=$factories sourceEditingAlive=true"
                }
                closeFile(derivedFixture.file)

                case("resource-sweep-1-5-20") {
                    if (expectJcef) {
                        "mode=jcef-enabled oneFileSnapshot=true fullSweep=no-jcef-companion"
                    } else {
                        closeFile(plain.file)
                        val files = (1..20).map { index ->
                            createFixture(
                                "leap2-resource-$index.md",
                                "# resource $index\n\nplain markdown $index\n",
                            )
                        }
                        files.take(1).forEach { openFile(it.file) }
                        resourceSnapshots += resourceSnapshot("candidate-b-1", openFiles = 1)
                        files.drop(1).take(4).forEach { openFile(it.file) }
                        resourceSnapshots += resourceSnapshot("candidate-b-5", openFiles = 5)
                        files.drop(5).take(15).forEach { openFile(it.file) }
                        resourceSnapshots += resourceSnapshot("candidate-b-20", openFiles = 20)
                        check(MarkFlowOwnedNativeFileEditor.liveEditorCountForDiagnostics >= 20)
                        check(MarkFlowOwnedNativeFileEditor.livePresentationOwnerCountForDiagnostics >= 20)
                        files.asReversed().forEach { closeFile(it.file) }
                        check(MarkFlowOwnedNativeFileEditor.liveEditorCountForDiagnostics == 0)
                        check(MarkFlowOwnedNativeFileEditor.livePresentationOwnerCountForDiagnostics == 0)
                        resourceSnapshots += resourceSnapshot("after-resource-cleanup", openFiles = 0)
                        "openCounts=1,5,20 cleanup=true"
                    }
                }

                if (expectJcef) closeFile(plain.file)

                case("final-lifecycle") {
                    cleanupOpenedFiles()
                    cleanupDirectEditors()
                    check(MarkFlowOwnedNativeFileEditor.liveEditorCountForDiagnostics == 0)
                    check(MarkFlowOwnedNativeFileEditor.livePresentationOwnerCountForDiagnostics == 0)
                    val leaked = onEdt {
                        EditorFactory.getInstance().allEditors.count {
                            it.project === project &&
                                FileDocumentManager.getInstance().getFile(it.document)
                                    ?.path
                                    ?.contains(".markflow-leap2-") == true
                        }
                    }
                    check(leaked == 0) { "proof editors leaked after cleanup: $leaked" }
                    "ownedEditors=0 ownedPresentationOwners=0 leakedProofEditors=0"
                }

                finish("PASS")
            } catch (failure: Throwable) {
                if (cases.none { it.id == "probe-internal-failure" }) {
                    cases += CaseResult("probe-internal-failure", "INCOMPLETE", failureDetail(failure))
                }
                finish("INCOMPLETE")
            }
        }

        private fun createFixture(name: String, source: String): Fixture {
            val root = tempRoot ?: run {
                val projectBase = project.basePath?.let(Paths::get)
                    ?: error("#556 proof project has no basePath")
                Files.createTempDirectory(projectBase, ".markflow-leap2-").also { tempRoot = it }
            }
            val path = root.resolve(name)
            Files.writeString(path, source, StandardCharsets.UTF_8)
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                ?: error("VirtualFile unavailable for $path")
            val document = onEdt {
                FileDocumentManager.getInstance().getDocument(file)
                    ?: error("Document unavailable for $path")
            }
            check(document.text == source)
            return Fixture(path, file, document)
        }

        @Suppress("DEPRECATION")
        private fun openFile(file: VirtualFile): List<FileEditor> {
            val editors = onEdt {
                val manager = FileEditorManager.getInstance(project)
                manager.openFile(file, false)
                manager.getEditors(file).toList()
            }
            if (file !in openedFiles) openedFiles += file
            return editors
        }

        private fun closeFile(file: VirtualFile) {
            if (openedFiles.remove(file)) {
                onEdt { FileEditorManager.getInstance(project).closeFile(file) }
            }
        }

        private fun cleanupOpenedFiles() {
            openedFiles.toList().asReversed().forEach(::closeFile)
        }

        private fun cleanupDirectEditors() {
            directEditors.toList().asReversed().forEach { (provider, editor) ->
                runCatching {
                    onEdt {
                        NativeMarkFlowProductionLifecycle.release(editor.editor)
                        provider.disposeEditor(editor)
                    }
                }
            }
            directEditors.clear()
        }

        private fun awaitEvidence(controller: NativePresentationController): NativeDerivedPresentationEvidence {
            var last = requireNotNull(onEdt { controller.derivedEvidenceSnapshot() })
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (expectJcef && last.renderRequests == 0L && System.nanoTime() < deadline) {
                Thread.sleep(100)
                last = requireNotNull(onEdt { controller.derivedEvidenceSnapshot() })
            }
            return last
        }

        private fun resourceSnapshot(name: String, openFiles: Int): ResourceSnapshot {
            val editorCounts = onEdt {
                val editors = EditorFactory.getInstance().allEditors.filter { it.project === project }
                val controllers = editors.count { NativeMarkFlowProductionLifecycle.controller(it) != null }
                Triple(
                    editors.size,
                    controllers,
                    DerivedRendererRuntimeFactory.EP_NAME.extensionList.size,
                )
            }
            val runtime = Runtime.getRuntime()
            val cefProcesses = ProcessHandle.allProcesses().use { processStream ->
                processStream.filter { handle ->
                    val command = handle.info().commandLine().orElse("")
                    command.contains("cef", ignoreCase = true) ||
                        command.contains("chromium", ignoreCase = true)
                }.count().toInt()
            }
            return ResourceSnapshot(
                name = name,
                openFiles = openFiles,
                nativeEditors = editorCounts.first,
                presentationControllers = editorCounts.second,
                ownedEditors = MarkFlowOwnedNativeFileEditor.liveEditorCountForDiagnostics,
                ownedPresentationOwners = MarkFlowOwnedNativeFileEditor.livePresentationOwnerCountForDiagnostics,
                rendererFactories = editorCounts.third,
                cefProcesses = cefProcesses,
                heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
                heapCommittedBytes = runtime.totalMemory(),
                rssKb = readRssKb(),
            )
        }

        private fun readRssKb(): Long? = runCatching {
            Files.readAllLines(Path.of("/proc/self/status"))
                .firstOrNull { it.startsWith("VmRSS:") }
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLong()
        }.getOrNull()

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

        private fun plainSource(): String = buildString {
            append("# Leap 2 plain proof\n\n")
            repeat(220) { index -> append("plain line ").append(index).append(" remains native.\n") }
        }

        private fun derivedSource(): String {
            val dollar = '$'
            return """
                # Leap 2 derived proof

                ```mermaid
                flowchart LR
                    A --> B
                ```

                Display math:

                ${dollar}${dollar}
                \int_0^1 x^2 dx = \frac{1}{3}
                ${dollar}${dollar}
            """.trimIndent() + "\n"
        }
        private fun <T> onEdt(block: () -> T): T {
            if (ApplicationManager.getApplication().isDispatchThread) return block()
            var result: Result<T>? = null
            ApplicationManager.getApplication().invokeAndWait {
                result = runCatching(block)
            }
            return requireNotNull(result).getOrThrow()
        }

        private fun case(id: String, block: () -> String) {
            check(cases.none { it.id == id }) { "duplicate #556 proof case id: $id" }
            val result = try {
                CaseResult(id, "PASS", block())
            } catch (failure: Throwable) {
                CaseResult(id, "FAIL", failureDetail(failure))
            }
            cases += result
            if (result.outcome != "PASS") throw ProbeCaseFailure(id, result.detail)
        }

        private fun <T> caseValue(id: String, block: () -> Pair<T, String>): T {
            var value: T? = null
            case(id) {
                val (resolved, detail) = block()
                value = resolved
                detail
            }
            @Suppress("UNCHECKED_CAST")
            return value as T
        }

        private fun finish(verdict: String) {
            cleanupOpenedFiles()
            cleanupDirectEditors()
            tempRoot?.let { root -> runCatching { root.toFile().deleteRecursively() } }
            tempRoot = null
            try {
                output.parent?.let(Files::createDirectories)
                Files.writeString(
                    output,
                    gson.toJson(
                        Evidence(
                            schemaVersion = 1,
                            selectedShell = "MARKFLOW_OWNED_NATIVE_FILE_EDITOR_PROOF",
                            ideBuild = ApplicationInfo.getInstance().build.asString(),
                            expectJcef = expectJcef,
                            verdict = verdict,
                            cases = cases,
                            resourceSnapshots = resourceSnapshots,
                        )
                    ),
                    StandardCharsets.UTF_8,
                )
            } finally {
                ApplicationManager.getApplication().invokeLater {
                    ApplicationManager.getApplication().exit(true, true, false)
                }
            }
        }

        private fun failureDetail(failure: Throwable): String {
            val message = failure.message?.replace(Regex("[\\r\\n]+"), " ")?.take(400)
            return if (message.isNullOrBlank()) failure.javaClass.name else "${failure.javaClass.name}: $message"
        }
    }

    private data class Fixture(
        val path: Path,
        val file: VirtualFile,
        val document: com.intellij.openapi.editor.Document,
    )

    private data class ProviderEvidence(
        val ids: List<String>,
        val proofIndex: Int,
        val platformTextPresent: Boolean,
    )

    private data class CaseResult(
        val id: String,
        val outcome: String,
        val detail: String,
    )

    private data class ResourceSnapshot(
        val name: String,
        val openFiles: Int,
        val nativeEditors: Int,
        val presentationControllers: Int,
        val ownedEditors: Int,
        val ownedPresentationOwners: Int,
        val rendererFactories: Int,
        val cefProcesses: Int,
        val heapUsedBytes: Long,
        val heapCommittedBytes: Long,
        val rssKb: Long?,
    )

    private data class Evidence(
        val schemaVersion: Int,
        val selectedShell: String,
        val ideBuild: String,
        val expectJcef: Boolean,
        val verdict: String,
        val cases: List<CaseResult>,
        val resourceSnapshots: List<ResourceSnapshot>,
    )

    private class ProbeCaseFailure(id: String, detail: String) : RuntimeException("$id: $detail")

    private const val PLATFORM_TEXT_EDITOR_TYPE_ID = "text-editor"
}

/** Starts #556 only when the dedicated proof JVM supplies its output property. */
class Leap2OwnedShellProbeProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        Leap2OwnedShellProbe.startIfRequested(project)
    }
}
