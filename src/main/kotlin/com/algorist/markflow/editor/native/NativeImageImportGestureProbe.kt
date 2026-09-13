package com.algorist.markflow.editor.native

import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.FileDropEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO

/** Supplemental real-IDE proof for #150's multi-gesture/order and collision edge matrix. */
internal object NativeImageImportGestureProbe {
    private const val PLATFORM_TEXT_EDITOR_TYPE_ID = "text-editor"

    suspend fun runIfRequested(project: Project) {
        val primaryOutput = System.getProperty(NativeImageImportProbe.OUTPUT_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?.let(Paths::get)
            ?: return
        val output = primaryOutput.resolveSibling("gesture-hardening.json")
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

        suspend fun run() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            try {
                case("file-drop-single-multi-stable-order") { fileDropSingleMultiCase() }
                case("mixed-file-list-atomic-rejection") { mixedFileListCase() }
                case("case-insensitive-multi-collision-reservation") { multiCollisionCase() }
            } finally {
                finish()
            }
        }

        private suspend fun fileDropSingleMultiCase(): String {
            val external = newRoot("gesture-drop-source-")
            val first = external.resolve("first.png").also { writeImage(it, 5, 5) }
            val second = external.resolve("second.png").also { writeImage(it, 6, 4) }
            val insertion = "LEFT\n".length

            withFixture("LEFT\nRIGHT\n") { fixture ->
                fixture.editor.caretModel.moveToOffset(insertion)
                val files = listOf(first.toFile())
                val handled = NativeImageFileDropHandler().handleDrop(
                    FileDropEvent(project, FileListTransferable(files), files, fixture.editor),
                )
                check(handled)
                check(fixture.document.text == "LEFT\n![first](assets/first.png)RIGHT\n") {
                    "single file drop did not insert at the platform-resolved caret"
                }
            }

            withFixture("LEFT\nRIGHT\n") { fixture ->
                fixture.editor.caretModel.moveToOffset(insertion)
                val files = listOf(first.toFile(), second.toFile())
                val handled = NativeImageFileDropHandler().handleDrop(
                    FileDropEvent(project, FileListTransferable(files), files, fixture.editor),
                )
                check(handled)
                check(
                    fixture.document.text ==
                        "LEFT\n![first](assets/first.png)\n![second](assets/second.png)RIGHT\n"
                ) { "multi file drop did not preserve input order at the platform-resolved caret" }
            }

            return "dropSingle=true dropMulti=2 stableOrder=true insertedAtResolvedCaret=true"
        }

        private suspend fun mixedFileListCase(): String {
            val external = newRoot("gesture-mixed-source-")
            val image = external.resolve("image.png").also { writeImage(it, 4, 4) }
            val text = external.resolve("notes.txt")
            withContext(Dispatchers.IO) {
                Files.writeString(text, "not an image", StandardCharsets.UTF_8)
            }
            val transferable = FileListTransferable(listOf(image.toFile(), text.toFile()))
            check(NativeImageImportTransferable.isImageImportPayload(transferable)) {
                "mixed file list with an explicit image was not recognized as an image-import gesture"
            }
            val inputs = NativeImageImportTransferable.inputsOrNull(transferable)
                ?: error("mixed file list did not preserve the active file-list authority")

            return withFixture("mixed\n") { fixture ->
                val before = fixture.document.text
                fixture.editor.caretModel.moveToOffset(fixture.document.textLength)
                val result = NativeImageImportService.import(fixture.editor, inputs)
                check(result is NativeImageImportResult.Failure && result.code == NativeImageImportFailureCode.UNSUPPORTED_MEDIA) {
                    "mixed supported/unsupported file list did not fail atomically: $result"
                }
                check(fixture.document.text == before)
                check(fixture.file.parent.findChild("assets") == null) {
                    "mixed file-list rejection created a partial destination"
                }
                "mixedFileListRecognized=true atomicReject=true sourceStable=true filesCreated=0"
            }
        }

        private suspend fun multiCollisionCase(): String {
            val firstRoot = newRoot("gesture-collision-a-")
            val secondRoot = newRoot("gesture-collision-b-")
            val first = firstRoot.resolve("diagram.png").also { writeImage(it, 5, 4) }
            val second = secondRoot.resolve("diagram.png").also { writeImage(it, 6, 5) }
            val existingBytes = pngBytes(2, 2)

            return withFixture("collision\n") { fixture ->
                val existing = ApplicationManager.getApplication().runWriteAction<VirtualFile> {
                    val assets = fixture.file.parent.createChildDirectory(this, "assets")
                    val child = assets.createChildData(this, "Diagram.PNG")
                    child.getOutputStream(this).use { output -> output.write(existingBytes) }
                    child
                }
                fixture.editor.caretModel.moveToOffset(fixture.document.textLength)
                val result = NativeImageImportService.import(
                    fixture.editor,
                    listOf(
                        NativeImageImportInput.LocalFile(first),
                        NativeImageImportInput.LocalFile(second),
                    ),
                )
                check(result is NativeImageImportResult.Success)
                check(result.markdownTargets == listOf("assets/diagram-2.png", "assets/diagram-3.png")) {
                    "case-insensitive/same-basename collision reservation was not deterministic: $result"
                }
                check(existing.inputStream.use { it.readBytes() }.contentEquals(existingBytes)) {
                    "case-insensitive pre-existing collision target was overwritten"
                }
                val assets = fixture.file.parent.findChild("assets") ?: error("assets directory disappeared")
                check(assets.findChild("diagram-2.png")?.isValid == true)
                check(assets.findChild("diagram-3.png")?.isValid == true)
                "caseInsensitiveReservation=true sameBasenameMulti=true suffixes=-2,-3 overwrite=false preexistingBytesStable=true"
            }
        }

        private suspend fun <T> withFixture(source: String, block: suspend (Fixture) -> T): T {
            val root = newRoot("gesture-document-")
            val path = root.resolve("document.md")
            withContext(Dispatchers.IO) {
                Files.writeString(path, source, StandardCharsets.UTF_8)
            }
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                ?: error("gesture fixture VirtualFile unavailable")
            val document = ReadAction.computeBlocking<Document, RuntimeException> {
                FileDocumentManager.getInstance().getDocument(file)
                    ?: error("gesture fixture Document unavailable")
            }
            val provider = FileEditorProvider.EP_FILE_EDITOR_PROVIDER.extensionList.firstOrNull { candidate ->
                candidate.editorTypeId == PLATFORM_TEXT_EDITOR_TYPE_ID && accepts(candidate, file)
            } ?: error("platform text editor provider did not accept gesture fixture")
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

        private suspend fun newRoot(prefix: String): Path {
            val base = project.basePath?.let(Paths::get)
                ?: error("#151 gesture probe project base path unavailable")
            val root = withContext(Dispatchers.IO) {
                Files.createTempDirectory(base, ".markflow-$prefix")
            }
            roots += root
            return root
        }

        private suspend fun writeImage(path: Path, width: Int, height: Int) {
            val bytes = pngBytes(width, height)
            withContext(Dispatchers.IO) {
                Files.write(path, bytes)
            }
        }

        private fun pngBytes(width: Int, height: Int): ByteArray {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            return ByteArrayOutputStream().use { output ->
                check(ImageIO.write(image, "png", output))
                output.toByteArray()
            }
        }

        private suspend fun case(id: String, block: suspend () -> String) {
            cases += try {
                CaseResult(id, "PASS", block())
            } catch (failure: Throwable) {
                CaseResult(id, "FAIL", "${failure.javaClass.simpleName}: ${failure.message ?: "no message"}")
            }
        }

        private suspend fun finish() {
            val verdict = if (cases.all { it.outcome == "PASS" }) "PASS" else "FAIL"
            val payload = gson.toJson(Evidence(schemaVersion = 1, verdict = verdict, cases = cases.toList()))
            withContext(Dispatchers.IO) {
                output.parent?.let(Files::createDirectories)
                Files.writeString(output, payload, StandardCharsets.UTF_8)
                roots.asReversed().forEach { root -> runCatching { root.toFile().deleteRecursively() } }
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

    private class FileListTransferable(private val files: List<File>) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.javaFileListFlavor
        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
            return files
        }
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
