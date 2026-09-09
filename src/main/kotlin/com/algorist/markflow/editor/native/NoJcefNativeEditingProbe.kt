package com.algorist.markflow.editor.native

import com.algorist.markflow.renderer.DerivedRendererRuntimeFactory
import com.algorist.markflow.settings.MarkFlowIdeThemeService
import com.algorist.markflow.settings.MarkFlowRuntimeSettingsNotifier
import com.algorist.markflow.settings.MarkFlowRuntimeSettingsSink
import com.algorist.markflow.settings.MarkFlowSettingsService
import com.google.gson.GsonBuilder
import com.intellij.codeInsight.editorActions.CopyPastePreProcessor
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #144 real-IDE package proof with com.intellij.modules.jcef disabled in the sandbox.
 *
 * This class deliberately has no JCEF/browser imports. It proves the base plugin loads, optional
 * browser registrations stay absent, settings degrade to zero renderer sinks, and authoritative
 * native source editing/undo/redo/save remains operational.
 */
internal object NoJcefNativeEditingProbe {
    const val OUTPUT_PROPERTY = "markflow.noJcefNativeEditingProbe.output"
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
        private var tempRoot: Path? = null
        private var fileEditor: TextEditor? = null
        private var provider: FileEditorProvider? = null

        fun run() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            try {
                check(!project.isDefault) { "#144 package proof requires a real opened project" }
                check(!project.isDisposed) { "#144 package proof project was already disposed" }

                case("jcef-plugin-class-absent") {
                    val state = OptionalJcefProbeSupport.read()
                    check(!state.classPresent) {
                        "JCEF class remained visible while com.intellij.modules.jcef was disabled"
                    }
                    check(!state.supported)
                    "classPresent=false supported=false"
                }

                val fixture = createFixture()
                val textProvider = selectPlatformTextProvider(fixture)
                provider = textProvider
                val created = textProvider.createEditor(project, fixture.file)
                check(created is TextEditor) { "platform text provider did not create TextEditor" }
                fileEditor = created

                case("optional-browser-registrations-absent") {
                    val providers = FileEditorProvider.EP_FILE_EDITOR_PROVIDER.extensionList
                    check(providers.none { it.javaClass.name == TEMPORARY_MARKFLOW_PROVIDER_CLASS }) {
                        "temporary JCEF-backed MarkFlow provider was registered without JCEF"
                    }
                    check(MarkFlowRuntimeSettingsSink.EP_NAME.extensionList.isEmpty()) {
                        "JCEF runtime-settings sink was registered without JCEF"
                    }
                    check(DerivedRendererRuntimeFactory.EP_NAME.extensionList.isEmpty()) {
                        "isolated JCEF renderer runtime factory was registered without JCEF"
                    }
                    "temporaryProvider=false runtimeSettingsSinks=0 rendererRuntimeFactories=0"
                }

                case("base-native-paste-extension-present") {
                    val processors = CopyPastePreProcessor.EP_NAME.extensionList
                    check(processors.count { it is NativeMarkdownPastePreProcessor } == 1) {
                        "base native paste preprocessor missing or duplicated"
                    }
                    "nativePastePreProcessor=1"
                }

                case("settings-degrade-without-renderer") {
                    val settings = MarkFlowSettingsService.getInstance().runtimeSettings()
                    check(settings.settingsRevision >= 1)
                    check(MarkFlowIdeThemeService.getInstance().getSnapshot().fonts.isNotEmpty())
                    MarkFlowRuntimeSettingsNotifier.notifyChanged(forceReload = false)
                    check(MarkFlowRuntimeSettingsSink.EP_NAME.extensionList.isEmpty())
                    check(DerivedRendererRuntimeFactory.EP_NAME.extensionList.isEmpty())
                    "settingsAvailable=true themeAvailable=true rendererSinkCount=0 rendererRuntimeFactoryCount=0"
                }

                case("authoritative-native-edit-undo-redo-save") {
                    val editor = requireNotNull(fileEditor)
                    val document = fixture.document
                    check(editor.editor.document === document)
                    val sourceBefore = document.text
                    val inserted = "native-without-jcef "
                    WriteCommandAction.writeCommandAction(project)
                        .withName("MarkFlow #144 no-JCEF native edit proof")
                        .run<RuntimeException> {
                            document.insertString(0, inserted)
                        }
                    val sourceAfter = inserted + sourceBefore
                    check(document.text == sourceAfter)
                    check(FileDocumentManager.getInstance().isDocumentUnsaved(document))

                    val undoManager = UndoManager.getInstance(project)
                    check(undoManager.isUndoAvailable(editor))
                    undoManager.undo(editor)
                    check(document.text == sourceBefore)
                    check(undoManager.isRedoAvailable(editor))
                    undoManager.redo(editor)
                    check(document.text == sourceAfter)

                    FileDocumentManager.getInstance().saveDocument(document)
                    check(!FileDocumentManager.getInstance().isDocumentUnsaved(document))
                    check(Files.readString(fixture.path, StandardCharsets.UTF_8) == sourceAfter)
                    "sameDocument=true dirty=true undo=true redo=true save=true"
                }

                cleanupEditor()
                case("final-native-lifecycle") {
                    check(fileEditor == null)
                    check(EditorFactory.getInstance().getEditors(fixture.document, project).isEmpty()) {
                        "native editor retained after no-JCEF proof cleanup"
                    }
                    "editors=0"
                }

                finish("PASS")
            } catch (failure: Throwable) {
                if (cases.none { it.id == "probe-internal-failure" }) {
                    cases += CaseResult("probe-internal-failure", "INCOMPLETE", failureDetail(failure))
                }
                finish("INCOMPLETE")
            }
        }

        private fun createFixture(): Fixture {
            var result: Fixture? = null
            case("real-project-authoritative-document") {
                val projectBase = project.basePath?.let(Paths::get)
                    ?: error("no-JCEF proof project has no basePath")
                val root = Files.createTempDirectory(projectBase, ".markflow-no-jcef-")
                tempRoot = root
                val path = root.resolve("no-jcef-proof.md")
                val source = "# no JCEF package proof\n\nexact source remains editable\n"
                Files.writeString(path, source, StandardCharsets.UTF_8)
                val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                    ?: error("no-JCEF fixture VirtualFile unavailable")
                val document = FileDocumentManager.getInstance().getDocument(file)
                    ?: error("no-JCEF fixture Document unavailable")
                check(document.text == source)
                result = Fixture(path, file, document)
                "projectDefault=false sourceLength=${source.length}"
            }
            return result ?: error("no-JCEF fixture was not created")
        }

        private fun selectPlatformTextProvider(fixture: Fixture): FileEditorProvider {
            var selected: FileEditorProvider? = null
            case("platform-text-provider-owns-source") {
                val providers = FileEditorProvider.EP_FILE_EDITOR_PROVIDER.extensionList
                val accepted = providers.filter { accepts(it, fixture.file) }
                val text = accepted.firstOrNull { it.editorTypeId == PLATFORM_TEXT_EDITOR_TYPE_ID }
                    ?: error("platform text editor provider did not accept no-JCEF Markdown fixture")
                check(text.policy == FileEditorPolicy.NONE)
                check(providers.none { it.javaClass.name == TEMPORARY_MARKFLOW_PROVIDER_CLASS })
                selected = text
                "platformText=${text.javaClass.name} temporaryBrowserProvider=false"
            }
            return selected ?: error("platform text provider was not selected")
        }

        private fun accepts(provider: FileEditorProvider, file: com.intellij.openapi.vfs.VirtualFile): Boolean = runCatching {
            if (provider.acceptRequiresReadAction()) {
                ReadAction.computeBlocking<Boolean, RuntimeException> { provider.accept(project, file) }
            } else {
                provider.accept(project, file)
            }
        }.getOrDefault(false)

        private fun cleanupEditor() {
            val editor = fileEditor ?: return
            fileEditor = null
            runCatching { provider?.disposeEditor(editor) }
            provider = null
        }

        private fun case(id: String, block: () -> String) {
            check(cases.none { it.id == id }) { "duplicate no-JCEF evidence case id: $id" }
            val result = try {
                CaseResult(id, "PASS", block())
            } catch (failure: Throwable) {
                CaseResult(id, "FAIL", failureDetail(failure))
            }
            cases += result
            if (result.outcome != "PASS") error("$id: ${result.detail}")
        }

        private fun finish(verdict: String) {
            cleanupEditor()
            cleanupFiles()
            try {
                output.parent?.let(Files::createDirectories)
                Files.writeString(
                    output,
                    gson.toJson(
                        Evidence(
                            schemaVersion = 1,
                            selectedShell = SELECTED_SHELL,
                            ideBuild = ApplicationInfo.getInstance().build.asString(),
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

        private fun cleanupFiles() {
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
        val file: com.intellij.openapi.vfs.VirtualFile,
        val document: com.intellij.openapi.editor.Document,
    )

    private data class CaseResult(val id: String, val outcome: String, val detail: String)

    private data class Evidence(
        val schemaVersion: Int,
        val selectedShell: String,
        val ideBuild: String,
        val verdict: String,
        val cases: List<CaseResult>,
    )

    private const val PLATFORM_TEXT_EDITOR_TYPE_ID = "text-editor"
    private const val TEMPORARY_MARKFLOW_PROVIDER_CLASS = "com.algorist.markflow.editor.MarkFlowEditorProvider"
}
