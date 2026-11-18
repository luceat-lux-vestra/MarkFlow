package com.algorist.markflow.editor.native

import com.algorist.markflow.renderer.DerivedRendererRuntimeFactory
import com.algorist.markflow.settings.MarkFlowIdeThemeService
import com.algorist.markflow.settings.MarkFlowPresentationSettingsNotifier
import com.algorist.markflow.settings.MarkFlowPresentationSettingsSink
import com.algorist.markflow.settings.MarkFlowSettingsService
import com.google.gson.GsonBuilder
import com.intellij.codeInsight.editorActions.CopyPastePreProcessor
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real-IDE package proof with com.intellij.modules.jcef disabled in the sandbox.
 *
 * This class deliberately has no JCEF/browser imports. It proves the production MarkFlow-owned native
 * FileEditor remains fully usable while optional JCEF renderer registrations stay absent, including
 * settings, authoritative source editing, undo/redo/save, and owned lifecycle cleanup.
 */
internal object NoJcefNativeEditingProbe {
    const val OUTPUT_PROPERTY = "markflow.noJcefNativeEditingProbe.output"
    const val SELECTED_SHELL = "MARKFLOW_OWNED_NATIVE_FILE_EDITOR_PRODUCTION"

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
        private var fileEditor: MarkFlowOwnedNativeFileEditor? = null
        private var provider: FileEditorProvider? = null

        fun run() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            try {
                check(!project.isDefault) { "no-JCEF package proof requires a real opened project" }
                check(!project.isDisposed) { "no-JCEF package proof project was already disposed" }

                case("jcef-plugin-class-absent") {
                    val state = OptionalJcefProbeSupport.read()
                    check(!state.classPresent) {
                        "JCEF class remained visible while com.intellij.modules.jcef was disabled"
                    }
                    check(!state.supported)
                    "classPresent=false supported=false"
                }

                val fixture = createFixture()
                val ownedProvider = selectOwnedFileEditorProvider(fixture)
                provider = ownedProvider
                val created = ownedProvider.createEditor(project, fixture.file)
                check(created is MarkFlowOwnedNativeFileEditor) {
                    "production MarkFlow provider did not create MarkFlowOwnedNativeFileEditor"
                }
                check(NativeMarkFlowProductionLifecycle.ownedController(created.editor) != null) {
                    "production owned FileEditor did not attach its owned presentation controller"
                }
                fileEditor = created

                case("optional-jcef-renderer-registrations-absent") {
                    check(
                        FileEditorProviderManager.getInstance()
                            .getProvider(TEMPORARY_MARKFLOW_PROVIDER_TYPE_ID) == null
                    ) {
                        "legacy JCEF-backed MarkFlow provider was registered without JCEF"
                    }
                    val sinks = MarkFlowPresentationSettingsSink.EP_NAME.extensionList
                    check(sinks.count { it is NativeMarkFlowPresentationSettingsSink } == 1) {
                        "base native presentation-settings sink missing or duplicated without JCEF"
                    }
                    check(DerivedRendererRuntimeFactory.EP_NAME.extensionList.isEmpty()) {
                        "isolated JCEF renderer runtime factory was registered without JCEF"
                    }
                    "legacyBrowserProvider=false nativePresentationSettingsSinks=1 rendererRuntimeFactories=0"
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
                    MarkFlowPresentationSettingsNotifier.notifyChanged()
                    val sinks = MarkFlowPresentationSettingsSink.EP_NAME.extensionList
                    check(sinks.count { it is NativeMarkFlowPresentationSettingsSink } == 1)
                    check(DerivedRendererRuntimeFactory.EP_NAME.extensionList.isEmpty())
                    "settingsAvailable=true themeAvailable=true nativePresentationSinkCount=1 rendererRuntimeFactoryCount=0"
                }

                case("authoritative-native-edit-undo-redo-save") {
                    val editor = requireNotNull(fileEditor)
                    val document = fixture.document
                    check(editor.editor.document === document)
                    val sourceBefore = document.text
                    val inserted = "native-without-jcef "
                    WriteCommandAction.writeCommandAction(project)
                        .withName("MarkFlow No-JCEF Native Edit Proof")
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
                    check(NativeMarkFlowProductionLifecycle.ownedController(editor.editor) != null)
                    "sameDocument=true ownedController=true dirty=true undo=true redo=true save=true"
                }

                cleanupEditor()
                case("final-native-lifecycle") {
                    check(fileEditor == null)
                    check(EditorFactory.getInstance().getEditors(fixture.document, project).isEmpty()) {
                        "native editor retained after no-JCEF proof cleanup"
                    }
                    check(NativeMarkFlowProductionLifecycle.liveOwnedControllerCountForDiagnostics == 0)
                    check(MarkFlowOwnedNativeFileEditor.liveEditorCountForDiagnostics == 0)
                    "editors=0 ownedControllers=0 ownedFileEditors=0"
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

        private fun selectOwnedFileEditorProvider(fixture: Fixture): FileEditorProvider {
            var selected: FileEditorProvider? = null
            case("owned-file-editor-provider-owns-source") {
                val providerManager = FileEditorProviderManager.getInstance()
                val owned = providerManager.getProvider(MarkFlowOwnedNativeFileEditorProvider.EDITOR_TYPE_ID)
                    ?: error("production MarkFlow owned FileEditor provider is not registered")
                check(providerManager.getProviderList(project, fixture.file).any { it === owned }) {
                    "production MarkFlow owned FileEditor provider did not accept no-JCEF Markdown fixture"
                }
                check(owned.policy == FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR)
                check(providerManager.getProvider(TEMPORARY_MARKFLOW_PROVIDER_TYPE_ID) == null)
                selected = owned
                "ownedProvider=${owned.javaClass.name} policy=${owned.policy.name} legacyBrowserProvider=false"
            }
            return selected ?: error("production MarkFlow owned FileEditor provider was not selected")
        }

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

    private const val TEMPORARY_MARKFLOW_PROVIDER_TYPE_ID = "MarkFlowEditor"
}
