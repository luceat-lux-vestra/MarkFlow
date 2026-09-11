package com.algorist.markflow.editor.native

import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO

/** Supplemental real-IDE proof for #151 transaction invariants that are easy to regress subtly. */
internal object NativeImageImportTransactionProbe {
    private const val PLATFORM_TEXT_EDITOR_TYPE_ID = "text-editor"

    suspend fun runIfRequested(project: Project) {
        val primaryOutput = System.getProperty(NativeImageImportProbe.OUTPUT_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?.let(Paths::get)
            ?: return
        val output = primaryOutput.resolveSibling("transaction-hardening.json")
        withContext(Dispatchers.EDT) {
            Runner(project, output).run()
        }
    }

    private class Runner(
        private val project: Project,
        private val output: Path,
    ) {
        private val gson = GsonBuilder().setPrettyPrinting().create()
        private val cases = mutableListOf<CaseResult>()
        private val roots = mutableListOf<Path>()

        fun run() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            case("vfs-visible-collision-reservation") { vfsCollisionCase() }
            case("post-commit-ui-failure-does-not-rollback") { postCommitFailureCase() }
            case("listener-pce-after-commit-preserves-transaction") { listenerPceAfterCommitCase() }
            case("precommit-pce-propagates-after-cleanup") { precommitPceCase() }
            val verdict = if (cases.all { it.outcome == "PASS" }) "PASS" else "FAIL"
            output.parent?.let(Files::createDirectories)
            Files.writeString(
                output,
                gson.toJson(Evidence(schemaVersion = 1, verdict = verdict, cases = cases.toList())),
                StandardCharsets.UTF_8,
            )
            roots.asReversed().forEach { root -> runCatching { root.toFile().deleteRecursively() } }
        }

        private fun vfsCollisionCase(): String {
            val external = newRoot("transaction-source-")
            val source = external.resolve("diagram.png")
            writeImage(source, 5, 4)
            return withFixture("# collision\n") { fixture ->
                val originalBytes = pngBytes(2, 2)
                val existing = ApplicationManager.getApplication().runWriteAction<VirtualFile> {
                    val assets = fixture.file.parent.findChild("assets")
                        ?: fixture.file.parent.createChildDirectory(this, "assets")
                    val child = assets.createChildData(this, "diagram.png")
                    child.getOutputStream(this).use { it.write(originalBytes) }
                    child
                }
                check(existing.isValid)

                fixture.editor.caretModel.moveToOffset(fixture.document.textLength)
                val result = NativeImageImportService.import(
                    fixture.editor,
                    listOf(NativeImageImportInput.LocalFile(source)),
                )
                check(result is NativeImageImportResult.Success)
                check(result.markdownTargets == listOf("assets/diagram-2.png")) {
                    "VFS-visible collision was not reserved before destination planning: $result"
                }
                check(existing.inputStream.use { it.readBytes() }.contentEquals(originalBytes)) {
                    "pre-existing VFS collision target was modified"
                }
                check(fixture.file.parent.findChild("assets")?.findChild("diagram-2.png")?.isValid == true)
                "vfsVisibleCollision=true suffix=-2 overwrite=false preexistingBytesStable=true"
            }
        }

        private fun postCommitFailureCase(): String {
            val external = newRoot("post-commit-source-")
            val source = external.resolve("committed.png")
            writeImage(source, 4, 4)
            return withFixture("before\n") { fixture ->
                val before = fixture.document.text
                fixture.editor.caretModel.moveToOffset(fixture.document.textLength)
                val result = NativeImageImportService.import(
                    fixture.editor,
                    listOf(NativeImageImportInput.LocalFile(source)),
                    hooks = NativeImageImportTestHooks(
                        afterSourceEdit = { error("injected post-commit UI failure") },
                    ),
                )
                check(result is NativeImageImportResult.Success) {
                    "post-commit UI failure was incorrectly reported as transaction failure: $result"
                }
                check(fixture.document.text == before + "![committed](assets/committed.png)") {
                    "authoritative source edit did not remain committed"
                }
                check(fixture.file.parent.findChild("assets")?.findChild("committed.png")?.isValid == true) {
                    "committed asset was rolled back after source edit succeeded"
                }
                "postCommitFailureResultSuccess=true sourceReferencePresent=true assetPersists=true rollbackAfterCommit=false"
            }
        }

        private fun listenerPceAfterCommitCase(): String {
            val external = newRoot("listener-pce-source-")
            val source = external.resolve("cancelled.png")
            writeImage(source, 4, 4)
            return withFixture("before pce\n") { fixture ->
                val before = fixture.document.text
                val expectedPayload = "![cancelled](assets/cancelled.png)"
                val insertionOffset = fixture.document.textLength
                fixture.editor.caretModel.moveToOffset(insertionOffset)

                val throwingListener = object : DocumentListener {
                    override fun documentChanged(event: DocumentEvent) {
                        if (
                            event.document === fixture.document &&
                            event.offset == insertionOffset &&
                            event.oldFragment.isEmpty() &&
                            event.newFragment.toString() == expectedPayload
                        ) {
                            throw ProcessCanceledException()
                        }
                    }
                }
                fixture.document.addDocumentListener(throwingListener)
                try {
                    expectProcessCanceled("delayed listener ProcessCanceledException was swallowed") {
                        NativeImageImportService.import(
                            fixture.editor,
                            listOf(NativeImageImportInput.LocalFile(source)),
                        )
                    }
                } finally {
                    fixture.document.removeDocumentListener(throwingListener)
                }

                check(fixture.document.text == before + expectedPayload) {
                    "Document listener PCE escaped before the authoritative source snapshot remained committed"
                }
                check(fixture.file.parent.findChild("assets")?.findChild("cancelled.png")?.isValid == true) {
                    "asset was rolled back after an observed committed source event"
                }
                "pcePropagated=true sourceCommitted=true assetPersists=true rollbackAfterObservedCommit=false"
            }
        }

        private fun precommitPceCase(): String {
            val external = newRoot("precommit-pce-source-")
            val copySource = external.resolve("copy-cancelled.png")
            val sourceEditSource = external.resolve("source-cancelled.png")
            writeImage(copySource, 4, 4)
            writeImage(sourceEditSource, 4, 4)

            withFixture("copy pce\n") { fixture ->
                val before = fixture.document.text
                expectProcessCanceled("copy-phase ProcessCanceledException was swallowed") {
                    NativeImageImportService.import(
                        fixture.editor,
                        listOf(NativeImageImportInput.LocalFile(copySource)),
                        hooks = NativeImageImportTestHooks(
                            beforeAssetContentWrite = { throw ProcessCanceledException() },
                        ),
                    )
                }
                check(fixture.document.text == before)
                check(fixture.file.parent.findChild("assets") == null) {
                    "copy-phase cancellation left a created asset or directory"
                }
            }

            withFixture("source pce\n") { fixture ->
                val before = fixture.document.text
                expectProcessCanceled("pre-source-edit ProcessCanceledException was swallowed") {
                    NativeImageImportService.import(
                        fixture.editor,
                        listOf(NativeImageImportInput.LocalFile(sourceEditSource)),
                        hooks = NativeImageImportTestHooks(
                            beforeSourceEdit = { throw ProcessCanceledException() },
                        ),
                    )
                }
                check(fixture.document.text == before)
                check(fixture.file.parent.findChild("assets") == null) {
                    "source-phase cancellation left a created asset or directory"
                }
            }

            return "copyPcePropagated=true copyRollbackComplete=true sourcePcePropagated=true sourceRollbackComplete=true sourceStable=true"
        }

        private fun <T> withFixture(source: String, block: (Fixture) -> T): T {
            val root = newRoot("transaction-document-")
            val path = root.resolve("document.md")
            Files.writeString(path, source, StandardCharsets.UTF_8)
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                ?: error("transaction fixture VirtualFile unavailable")
            val document = ReadAction.computeBlocking<Document, RuntimeException> {
                FileDocumentManager.getInstance().getDocument(file)
                    ?: error("transaction fixture Document unavailable")
            }
            val provider = FileEditorProvider.EP_FILE_EDITOR_PROVIDER.extensionList.firstOrNull { candidate ->
                candidate.editorTypeId == PLATFORM_TEXT_EDITOR_TYPE_ID && accepts(candidate, file)
            } ?: error("platform text editor provider did not accept transaction fixture")
            val created = provider.createEditor(project, file)
            check(created is TextEditor)
            val fixture = Fixture(file, document, created)
            return try {
                block(fixture)
            } finally {
                provider.disposeEditor(created)
            }
        }

        private fun accepts(provider: FileEditorProvider, file: VirtualFile): Boolean = runCatching {
            if (provider.acceptRequiresReadAction()) {
                ReadAction.computeBlocking<Boolean, RuntimeException> { provider.accept(project, file) }
            } else {
                provider.accept(project, file)
            }
        }.getOrElse { false }

        private fun newRoot(prefix: String): Path {
            val base = project.basePath?.let(Paths::get)
                ?: error("#151 transaction probe project base path unavailable")
            return Files.createTempDirectory(base, ".markflow-$prefix").also(roots::add)
        }

        private fun writeImage(path: Path, width: Int, height: Int) {
            Files.write(path, pngBytes(width, height))
        }

        private fun pngBytes(width: Int, height: Int): ByteArray {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            return ByteArrayOutputStream().use { output ->
                check(ImageIO.write(image, "png", output))
                output.toByteArray()
            }
        }

        private fun expectProcessCanceled(message: String, block: () -> Unit) {
            try {
                block()
            } catch (_: ProcessCanceledException) {
                return
            }
            error(message)
        }

        private fun case(id: String, block: () -> String) {
            cases += try {
                CaseResult(id, "PASS", block())
            } catch (failure: Throwable) {
                CaseResult(id, "FAIL", "${failure.javaClass.simpleName}: ${failure.message ?: "no message"}")
            }
        }
    }

    private data class Fixture(
        val file: VirtualFile,
        val document: Document,
        val fileEditor: TextEditor,
    ) {
        val editor get() = fileEditor.editor
    }

    private data class CaseResult(
        val id: String,
        val outcome: String,
        val detail: String,
    )

    private data class Evidence(
        val schemaVersion: Int,
        val verdict: String,
        val cases: List<CaseResult>,
    )
}
