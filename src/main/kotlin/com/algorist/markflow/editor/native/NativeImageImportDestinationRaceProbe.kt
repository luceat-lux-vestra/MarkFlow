package com.algorist.markflow.editor.native

import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
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

/** Adversarial real-IDE proof that the final VFS create boundary rejects post-preflight collisions. */
internal object NativeImageImportDestinationRaceProbe {
    private const val PLATFORM_TEXT_EDITOR_TYPE_ID = "text-editor"

    suspend fun runIfRequested(project: Project) {
        val primaryOutput = System.getProperty(NativeImageImportProbe.OUTPUT_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?.let(Paths::get)
            ?: return
        val output = primaryOutput.resolveSibling("destination-race.json")
        val prepared = withContext(Dispatchers.IO) { prepare(project) }
        val result = try {
            withContext(Dispatchers.EDT) { runCase(project, prepared) }
        } finally {
            withContext(Dispatchers.IO) {
                prepared.documentRoot.toFile().deleteRecursively()
                prepared.sourceRoot.toFile().deleteRecursively()
            }
        }
        withContext(Dispatchers.IO) {
            output.parent?.let(Files::createDirectories)
            Files.writeString(
                output,
                GsonBuilder().setPrettyPrinting().create().toJson(
                    Evidence(
                        schemaVersion = 1,
                        verdict = if (result.outcome == "PASS") "PASS" else "FAIL",
                        cases = listOf(result),
                    ),
                ),
                StandardCharsets.UTF_8,
            )
        }
    }

    private fun prepare(project: Project): Prepared {
        val base = project.basePath?.let(Paths::get)
            ?: error("#151 destination-race probe project base path unavailable")
        val documentRoot = Files.createTempDirectory(base, ".markflow-destination-race-document-")
        val sourceRoot = Files.createTempDirectory(base, ".markflow-destination-race-source-")
        val documentPath = documentRoot.resolve("document.md")
        val sourcePath = sourceRoot.resolve("diagram.png")
        Files.writeString(documentPath, "race proof\n", StandardCharsets.UTF_8)
        Files.write(sourcePath, pngBytes(5, 4))
        return Prepared(documentRoot, sourceRoot, documentPath, sourcePath)
    }

    private fun runCase(project: Project, prepared: Prepared): CaseResult {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return try {
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(prepared.documentPath)
                ?: error("destination-race fixture VirtualFile unavailable")
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(prepared.sourcePath)
                ?: error("destination-race source VirtualFile unavailable")
            val document = ReadAction.computeBlocking<Document, RuntimeException> {
                FileDocumentManager.getInstance().getDocument(file)
                    ?: error("destination-race fixture Document unavailable")
            }
            val provider = FileEditorProvider.EP_FILE_EDITOR_PROVIDER.extensionList.firstOrNull { candidate ->
                candidate.editorTypeId == PLATFORM_TEXT_EDITOR_TYPE_ID && accepts(candidate, project, file)
            } ?: error("platform text editor provider did not accept destination-race fixture")
            val created = provider.createEditor(project, file)
            check(created is TextEditor)
            try {
                val before = document.text
                val assets = ApplicationManager.getApplication().runWriteAction<VirtualFile> {
                    file.parent.createChildDirectory(this, "assets")
                }
                val raceBytes = pngBytes(2, 2)
                var injected = false
                created.editor.caretModel.moveToOffset(document.textLength)
                val result = NativeImageImportService.import(
                    created.editor,
                    listOf(NativeImageImportInput.LocalFile(prepared.sourcePath)),
                    hooks = NativeImageImportTestHooks(
                        beforeDestinationCreate = { destinationName ->
                            check(destinationName == "diagram.png")
                            check(!injected) { "destination race hook invoked more than once" }
                            val competing = assets.createChildData(this, "Diagram.PNG")
                            competing.getOutputStream(this).use { output -> output.write(raceBytes) }
                            injected = true
                        },
                    ),
                )
                check(injected) { "destination race was not injected at the final create boundary" }
                check(result is NativeImageImportResult.Failure && result.code == NativeImageImportFailureCode.COPY_FAILED) {
                    "case-insensitive post-preflight collision was not rejected: $result"
                }
                check(document.text == before) { "destination collision race modified Markdown source" }
                val competing = assets.findChild("Diagram.PNG")
                    ?: error("competing case-variant file was removed")
                check(competing.inputStream.use { it.readBytes() }.contentEquals(raceBytes)) {
                    "competing file bytes changed during rejected import"
                }
                check(assets.children.none { child -> child.name == "diagram.png" }) {
                    "operation-owned destination was created despite final collision recheck"
                }
                CaseResult(
                    id = "case-insensitive-post-preflight-collision",
                    outcome = "PASS",
                    detail = "raceInjected=true caseInsensitiveRecheck=true copyRejected=true sourceStable=true competingFilePreserved=true operationFileCreated=false",
                )
            } finally {
                provider.disposeEditor(created)
            }
        } catch (failure: Throwable) {
            CaseResult(
                id = "case-insensitive-post-preflight-collision",
                outcome = "FAIL",
                detail = "${failure.javaClass.simpleName}: ${failure.message ?: "no message"}",
            )
        }
    }

    private fun accepts(provider: FileEditorProvider, project: Project, file: VirtualFile): Boolean = runCatching {
        if (provider.acceptRequiresReadAction()) {
            ReadAction.computeBlocking<Boolean, RuntimeException> { provider.accept(project, file) }
        } else {
            provider.accept(project, file)
        }
    }.getOrElse { false }

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output))
            output.toByteArray()
        }
    }

    private data class Prepared(
        val documentRoot: Path,
        val sourceRoot: Path,
        val documentPath: Path,
        val sourcePath: Path,
    )

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
