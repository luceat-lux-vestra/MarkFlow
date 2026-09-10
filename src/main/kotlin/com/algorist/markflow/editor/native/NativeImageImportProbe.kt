package com.algorist.markflow.editor.native

import com.algorist.markflow.trust.NativeLocalImageResolver
import com.algorist.markflow.trust.NativeLocalImageResult
import com.google.gson.GsonBuilder
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.FileDropEvent
import com.intellij.openapi.editor.actions.PasteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.Producer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import javax.imageio.ImageIO

/** Production-independent real-IDE evidence for the accepted #150 / implemented #151 image-import contract. */
internal object NativeImageImportProbe {
    const val OUTPUT_PROPERTY = "markflow.nativeImageImportProbe.output"
    const val SELECTED_SURFACE = "PLATFORM_TEXT_EDITOR_NATIVE_IMAGE_IMPORT"

    private const val PLATFORM_TEXT_EDITOR_TYPE_ID = "text-editor"
    private val started = AtomicBoolean(false)

    suspend fun runIfRequested(project: Project): Boolean {
        val output = System.getProperty(OUTPUT_PROPERTY)?.takeIf(String::isNotBlank) ?: return false
        if (!started.compareAndSet(false, true)) return true
        withContext(Dispatchers.EDT) {
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
        private val roots = mutableListOf<Path>()
        private val baselineEditors = EditorFactory.getInstance().allEditors.size

        suspend fun run() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            try {
                case("jcef-plugin-absent-import-path") {
                    val state = OptionalJcefProbeSupport.read()
                    check(!state.classPresent) { "JCEF class remained visible in #151 no-JCEF proof" }
                    check(!state.supported)
                    "jcefClassPresent=false jcefSupported=false"
                }
                case("authoritative-saved-local-document") {
                    withFixture("before\nslot\nafter\n") { fixture ->
                        check(fixture.file.isInLocalFileSystem)
                        check(FileDocumentManager.getInstance().getFile(fixture.document) === fixture.file)
                        check(fixture.editor.project === project)
                        "local=true fileBacked=true platformTextEditor=true"
                    }
                }
                case("chooser-single-multi-raster-matrix-and-order") { chooserAndRasterMatrixCase() }
                case("contained-link-and-unrepresentable-copy") { containedLinkCase() }
                case("collision-no-overwrite") { collisionCase() }
                case("clipboard-file-and-image-transferables") { clipboardCase() }
                case("file-drop-platform-caret-position") { fileDropCase() }
                case("validation-failures-source-stable") { validationFailureCase() }
                case("context-rejections-and-multicaret") { contextFailureCase() }
                case("hostile-authority-and-symlink-rejections") { hostileAuthorityCase() }
                case("vfs-and-source-failure-rollback") { rollbackCase() }
                case("rollback-failure-orphan-diagnostic") { rollbackDiagnosticCase() }
                case("undo-redo-assets-persist") { undoRedoCase() }
                case("reopen-rename-and-host-presentation-feed") { reopenPresentationCase() }
                case("final-lifecycle-stability") {
                    check(EditorFactory.getInstance().allEditors.size == baselineEditors) {
                        "editor leak: baseline=$baselineEditors current=${EditorFactory.getInstance().allEditors.size}"
                    }
                    "editors=$baselineEditors leak=false"
                }
            } finally {
                finish()
            }
        }

        private suspend fun chooserAndRasterMatrixCase(): String {
            val external = newRoot("sources-")
            val png = external.resolve("single.png").also { writeImage(it, "png", 9, 7) }
            val jpeg = external.resolve("photo.jpg").also { writeImage(it, "jpg", 8, 6) }
            val gif = external.resolve("anim.gif").also { writeImage(it, "gif", 7, 5) }
            val bmp = external.resolve("bitmap.bmp").also { writeImage(it, "bmp", 6, 4) }

            withFixture("single:\n") { fixture ->
                fixture.editor.caretModel.moveToOffset(fixture.document.textLength)
                val result = importChosenFiles(fixture.editor, listOf(vFile(png)))
                check(result is NativeImageImportResult.Success)
                check(result.markdownTargets == listOf("assets/single.png"))
                check(fixture.document.text.endsWith("![single](assets/single.png)"))
                check(NativeLocalImageResolver.resolve(fixture.path, "assets/single.png") is NativeLocalImageResult.Success)
            }

            withFixture("before\nREPLACE\nafter\n") { fixture ->
                val selectionStart = fixture.document.text.indexOf("REPLACE")
                val selectionEnd = selectionStart + "REPLACE".length
                fixture.editor.selectionModel.setSelection(selectionStart, selectionEnd)
                val chosen = listOf(png, jpeg, gif, bmp).map(::vFile)
                val result = importChosenFiles(fixture.editor, chosen)
                check(result is NativeImageImportResult.Success)
                check(result.markdownTargets == listOf(
                    "assets/single.png",
                    "assets/photo.jpg",
                    "assets/anim.gif",
                    "assets/bitmap.bmp",
                ))
                val expected = "before\n" + listOf(
                    "![single](assets/single.png)",
                    "![photo](assets/photo.jpg)",
                    "![anim](assets/anim.gif)",
                    "![bitmap](assets/bitmap.bmp)",
                ).joinToString("\n") + "\nafter\n"
                check(fixture.document.text == expected) { "chooser order/selection replacement changed lexical source" }
                result.markdownTargets.forEach { target ->
                    check(NativeLocalImageResolver.resolve(fixture.path, target) is NativeLocalImageResult.Success) {
                        "imported target does not feed #147: $target"
                    }
                }
            }
            return "chooserSingle=true chooserMulti=4 stableOrder=true formats=PNG,JPEG,GIF,BMP lexicalOutsideRegion=true"
        }

        private suspend fun containedLinkCase(): String = withFixture("# images\n") { fixture ->
            val images = fixture.root.resolve("images")
            Files.createDirectories(images)
            val inside = images.resolve("in project.png")
            writeImage(inside, "png", 5, 5)
            fixture.editor.caretModel.moveToOffset(fixture.document.textLength)
            val linked = NativeImageImportService.import(
                fixture.editor,
                listOf(NativeImageImportInput.LocalFile(inside)),
            )
            check(linked is NativeImageImportResult.Success)
            check(linked.markdownTargets == listOf("images/in%20project.png"))
            check(linked.createdRelativePaths.isEmpty())

            val awkward = fixture.root.resolve("bad%2ename.png")
            writeImage(awkward, "png", 4, 4)
            val encodedExisting = NativeImageImportPolicy.encodeRelativeTarget("bad%2ename.png")
            check(NativeLocalImageResolver.decodeRelativeTarget(encodedExisting) == null) {
                "unrepresentable contained fixture unexpectedly became #147-representable"
            }
            fixture.editor.caretModel.moveToOffset(fixture.document.textLength)
            val copied = NativeImageImportService.import(
                fixture.editor,
                listOf(NativeImageImportInput.LocalFile(awkward)),
            )
            check(copied is NativeImageImportResult.Success)
            check(copied.createdRelativePaths.size == 1)
            check(copied.markdownTargets.single().startsWith("assets/"))
            check(copied.markdownTargets.single() != encodedExisting)
            check(NativeLocalImageResolver.resolve(fixture.path, copied.markdownTargets.single()) is NativeLocalImageResult.Success)
            "containedLinked=true copiedWhenDecoderRejects=true createdForLink=0 finalTargetRoundTrip=true"
        }

        private suspend fun collisionCase(): String {
            val external = newRoot("collision-source-")
            val source = external.resolve("diagram.png").also { writeImage(it, "png", 5, 4) }
            return withFixture("# collision\n") { fixture ->
                val assets = fixture.root.resolve("assets")
                Files.createDirectories(assets)
                val existing = assets.resolve("diagram.png")
                writeImage(existing, "png", 2, 2)
                val existingBytes = Files.readAllBytes(existing)
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(assets)
                fixture.editor.caretModel.moveToOffset(fixture.document.textLength)
                val result = NativeImageImportService.import(
                    fixture.editor,
                    listOf(NativeImageImportInput.LocalFile(source)),
                )
                check(result is NativeImageImportResult.Success)
                check(result.markdownTargets == listOf("assets/diagram-2.png"))
                check(existingBytes.contentEquals(Files.readAllBytes(existing))) { "pre-existing collision target was overwritten" }
                check(NativeLocalImageResolver.resolve(fixture.path, "assets/diagram-2.png") is NativeLocalImageResult.Success)
                "suffix=-2 overwrite=false preexistingBytesStable=true"
            }
        }

        private suspend fun clipboardCase(): String {
            val external = newRoot("clipboard-source-")
            val first = external.resolve("clip-a.png").also { writeImage(it, "png", 4, 3) }
            val second = external.resolve("clip-b.jpg").also { writeImage(it, "jpg", 5, 4) }
            withFixture("files:\n") { fixture ->
                val transferable = FileListTransferable(listOf(first.toFile(), second.toFile()))
                val context = pasteContext(fixture, transferable)
                val provider = NativeImageImportPasteProvider()
                check(provider.isPasteEnabled(context))
                fixture.editor.caretModel.moveToOffset(fixture.document.textLength)
                provider.performPaste(context)
                check(fixture.document.text.endsWith(
                    "![clip-a](assets/clip-a.png)\n![clip-b](assets/clip-b.jpg)",
                ))
            }
            withFixture("image:\n") { fixture ->
                val image = BufferedImage(7, 6, BufferedImage.TYPE_INT_ARGB)
                val transferable = ImageTransferable(image)
                val context = pasteContext(fixture, transferable)
                val provider = NativeImageImportPasteProvider()
                check(provider.isPasteEnabled(context))
                fixture.editor.caretModel.moveToOffset(fixture.document.textLength)
                provider.performPaste(context)
                check(fixture.document.text.endsWith("![image](assets/image.png)"))
                check(NativeLocalImageResolver.resolve(fixture.path, "assets/image.png") is NativeLocalImageResult.Success)
            }
            check(!NativeImageImportTransferable.isImageImportPayload(StringSelection("/tmp/looks-like-image.png")))
            return "activeFileTransferable=true activeImageTransferable=true imageMaterializedPng=true plainTextAuthority=false"
        }

        private suspend fun fileDropCase(): String {
            val external = newRoot("drop-source-")
            val source = external.resolve("dropped.png").also { writeImage(it, "png", 5, 5) }
            return withFixture("LEFT\nRIGHT\n") { fixture ->
                val insertion = "LEFT\n".length
                fixture.editor.caretModel.moveToOffset(insertion)
                val transferable = FileListTransferable(listOf(source.toFile()))
                val handled = NativeImageFileDropHandler().handleDrop(
                    FileDropEvent(project, transferable, listOf(source.toFile()), fixture.editor),
                )
                check(handled)
                check(fixture.document.text == "LEFT\n![dropped](assets/dropped.png)RIGHT\n") {
                    "drop handler did not insert at the platform-resolved current caret"
                }
                "handled=true platformCaretOffset=$insertion insertedAtResolvedCaret=true"
            }
        }

        private suspend fun validationFailureCase(): String {
            val external = newRoot("invalid-source-")
            val unsupported = external.resolve("vector.svg").also {
                Files.writeString(it, "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>")
            }
            val mismatch = external.resolve("mismatch.png").also { writeImage(it, "jpg", 4, 4) }
            val oversized = external.resolve("oversized.png").also {
                RandomAccessFile(it.toFile(), "rw").use { file -> file.setLength(NativeLocalImageResolver.MAX_FILE_BYTES + 1) }
            }
            val dimensionBomb = external.resolve("dimension.png").also {
                Files.write(it, pngHeader(NativeLocalImageResolver.MAX_DIMENSION + 1, 1))
            }
            val pixelBomb = external.resolve("pixels.png").also { Files.write(it, pngHeader(4097, 4097)) }

            return withFixture("stable\n") { fixture ->
                val before = fixture.document.text
                val cases = listOf(
                    unsupported to NativeImageImportFailureCode.UNSUPPORTED_MEDIA,
                    mismatch to NativeImageImportFailureCode.UNSUPPORTED_MEDIA,
                    oversized to NativeImageImportFailureCode.FILE_TOO_LARGE,
                    dimensionBomb to NativeImageImportFailureCode.DIMENSIONS_TOO_LARGE,
                    pixelBomb to NativeImageImportFailureCode.DIMENSIONS_TOO_LARGE,
                )
                cases.forEach { (path, expected) ->
                    val result = NativeImageImportService.import(
                        fixture.editor,
                        listOf(NativeImageImportInput.LocalFile(path)),
                    )
                    check(result is NativeImageImportResult.Failure && result.code == expected) {
                        "unexpected failure for ${path.fileName}: $result"
                    }
                    check(fixture.document.text == before)
                    check(fixture.file.parent.findChild("assets") == null)
                }
                "unsupported=true mismatch=true oversize=true dimensionBomb=true pixelBomb=true sourceStable=true filesCreated=0"
            }
        }

        private suspend fun contextFailureCase(): String {
            val external = newRoot("context-source-")
            val source = external.resolve("context.png").also { writeImage(it, "png", 3, 3) }

            withFixture("read only\n") { fixture ->
                fixture.document.setReadOnly(true)
                try {
                    val result = NativeImageImportService.import(fixture.editor, listOf(NativeImageImportInput.LocalFile(source)))
                    check(result is NativeImageImportResult.Failure && result.code == NativeImageImportFailureCode.READ_ONLY)
                    check(fixture.file.parent.findChild("assets") == null)
                } finally {
                    fixture.document.setReadOnly(false)
                }
            }

            withFixture("multi caret\n") { fixture ->
                checkNotNull(fixture.editor.caretModel.addCaret(fixture.editor.offsetToVisualPosition(1)))
                val result = NativeImageImportService.import(fixture.editor, listOf(NativeImageImportInput.LocalFile(source)))
                check(result is NativeImageImportResult.Failure && result.code == NativeImageImportFailureCode.MULTICARET_UNSUPPORTED)
                check(fixture.file.parent.findChild("assets") == null)
            }

            val unsavedDocument = EditorFactory.getInstance().createDocument("unsaved\n")
            val unsavedEditor = EditorFactory.getInstance().createEditor(unsavedDocument, project)
            try {
                val result = NativeImageImportService.import(unsavedEditor, listOf(NativeImageImportInput.LocalFile(source)))
                check(result is NativeImageImportResult.Failure && result.code == NativeImageImportFailureCode.INVALID_DOCUMENT_CONTEXT)
            } finally {
                EditorFactory.getInstance().releaseEditor(unsavedEditor)
            }

            val nonLocal = LightVirtualFile("remote.md", "remote\n")
            check(!nonLocal.isInLocalFileSystem)
            val nonLocalDocument = FileDocumentManager.getInstance().getDocument(nonLocal)
                ?: error("LightVirtualFile did not expose a Document")
            val nonLocalEditor = EditorFactory.getInstance().createEditor(nonLocalDocument, project)
            try {
                val result = NativeImageImportService.import(nonLocalEditor, listOf(NativeImageImportInput.LocalFile(source)))
                check(result is NativeImageImportResult.Failure && result.code == NativeImageImportFailureCode.INVALID_DOCUMENT_CONTEXT)
            } finally {
                EditorFactory.getInstance().releaseEditor(nonLocalEditor)
            }
            return "readOnlyRejected=true unsavedRejected=true nonLocalRejected=true multicaretRejected=true filesCreated=0"
        }

        private suspend fun hostileAuthorityCase(): String {
            val external = newRoot("hostile-source-")
            val valid = external.resolve("valid.png").also { writeImage(it, "png", 3, 3) }
            return withFixture("hostile\n") { fixture ->
                check(!NativeImageImportTransferable.isImageImportPayload(StringSelection(valid.toString())))
                val link = fixture.root.resolve("source-link.png")
                var symlinkSupported = true
                try {
                    Files.createSymbolicLink(link, valid)
                } catch (_: UnsupportedOperationException) {
                    symlinkSupported = false
                } catch (_: SecurityException) {
                    symlinkSupported = false
                } catch (_: IOException) {
                    symlinkSupported = false
                }
                if (symlinkSupported) {
                    val sourceResult = NativeImageImportService.import(
                        fixture.editor,
                        listOf(NativeImageImportInput.LocalFile(link)),
                    )
                    check(sourceResult is NativeImageImportResult.Failure && sourceResult.code == NativeImageImportFailureCode.INVALID_SOURCE)
                    Files.deleteIfExists(link)

                    val outsideAssets = newRoot("outside-assets-")
                    val assetsLink = fixture.root.resolve("assets")
                    Files.createSymbolicLink(assetsLink, outsideAssets)
                    val destinationResult = NativeImageImportService.import(
                        fixture.editor,
                        listOf(NativeImageImportInput.LocalFile(valid)),
                    )
                    check(
                        destinationResult is NativeImageImportResult.Failure &&
                            destinationResult.code == NativeImageImportFailureCode.DESTINATION_UNAVAILABLE
                    )
                }
                check(fixture.document.text == "hostile\n")
                "plainTextAuthority=false symlinkSupported=$symlinkSupported symlinkSourceRejected=$symlinkSupported symlinkDestinationRejected=$symlinkSupported sourceStable=true"
            }
        }

        private suspend fun rollbackCase(): String {
            val external = newRoot("rollback-source-")
            val source = external.resolve("failure.png").also { writeImage(it, "png", 4, 4) }
            withFixture("copy failure\n") { fixture ->
                val before = fixture.document.text
                val result = NativeImageImportService.import(
                    fixture.editor,
                    listOf(NativeImageImportInput.LocalFile(source)),
                    hooks = NativeImageImportTestHooks(
                        beforeAssetContentWrite = { throw IOException("injected VFS write failure") },
                    ),
                )
                check(result is NativeImageImportResult.Failure && result.code == NativeImageImportFailureCode.COPY_FAILED)
                check(fixture.document.text == before)
                check(fixture.file.parent.findChild("assets") == null) { "partial VFS file/directory survived rollback" }
            }
            withFixture("source failure\n") { fixture ->
                val before = fixture.document.text
                val result = NativeImageImportService.import(
                    fixture.editor,
                    listOf(NativeImageImportInput.LocalFile(source)),
                    hooks = NativeImageImportTestHooks(
                        beforeSourceEdit = { throw IllegalStateException("injected source command failure") },
                    ),
                )
                check(result is NativeImageImportResult.Failure && result.code == NativeImageImportFailureCode.SOURCE_EDIT_FAILED)
                check(fixture.document.text == before)
                check(fixture.file.parent.findChild("assets") == null) { "asset survived failed source command rollback" }
            }
            return "vfsWriteFailureRolledBack=true sourceCommandFailureRolledBack=true sourceStable=true partialAssets=false"
        }

        private suspend fun rollbackDiagnosticCase(): String {
            val external = newRoot("orphan-source-")
            val source = external.resolve("orphan.png").also { writeImage(it, "png", 4, 4) }
            return withFixture("orphan proof\n") { fixture ->
                val before = fixture.document.text
                val result = NativeImageImportService.import(
                    fixture.editor,
                    listOf(NativeImageImportInput.LocalFile(source)),
                    hooks = NativeImageImportTestHooks(
                        beforeSourceEdit = { throw IllegalStateException("injected source failure") },
                        beforeRollbackDelete = { target ->
                            if (target == "assets/orphan.png") throw IOException("injected cleanup failure")
                        },
                    ),
                )
                check(result is NativeImageImportResult.Failure && result.code == NativeImageImportFailureCode.ROLLBACK_FAILED)
                check(result.orphanRelativePaths == listOf("assets/orphan.png"))
                check(fixture.document.text == before)
                check(fixture.file.parent.findChild("assets")?.findChild("orphan.png")?.isValid == true)
                check(result.message.contains("cleanup", ignoreCase = true))
                "rollbackFailureVisible=true safeOrphan=assets/orphan.png sourceStable=true inconsistencySilent=false"
            }
        }

        private suspend fun undoRedoCase(): String {
            val external = newRoot("undo-source-")
            val source = external.resolve("undo.png").also { writeImage(it, "png", 5, 5) }
            return withFixture("before undo\n") { fixture ->
                val before = fixture.document.text
                fixture.editor.caretModel.moveToOffset(fixture.document.textLength)
                val result = NativeImageImportService.import(
                    fixture.editor,
                    listOf(NativeImageImportInput.LocalFile(source)),
                )
                check(result is NativeImageImportResult.Success)
                val after = fixture.document.text
                val asset = fixture.file.parent.findChild("assets")?.findChild("undo.png")
                    ?: error("imported VFS asset missing")
                val undo = UndoManager.getInstance(project)
                check(undo.isUndoAvailable(fixture.fileEditor))
                undo.undo(fixture.fileEditor)
                check(fixture.document.text == before)
                check(asset.isValid) { "editor undo deleted imported asset" }
                check(undo.isRedoAvailable(fixture.fileEditor))
                undo.redo(fixture.fileEditor)
                check(fixture.document.text == after)
                check(asset.isValid)
                check(fixture.file.parent.findChild("assets")?.findChild("undo-2.png") == null) { "redo recopied asset" }
                "undoMarkdownOnly=true redoSamePayload=true assetPersists=true recopy=false"
            }
        }

        private suspend fun reopenPresentationCase(): String {
            val external = newRoot("reopen-source-")
            val source = external.resolve("reopen.png").also { writeImage(it, "png", 6, 5) }
            return withFixture("reopen\n") { fixture ->
                fixture.editor.caretModel.moveToOffset(fixture.document.textLength)
                val result = NativeImageImportService.import(
                    fixture.editor,
                    listOf(NativeImageImportInput.LocalFile(source)),
                )
                check(result is NativeImageImportResult.Success)
                val target = result.markdownTargets.single()
                val expectedSource = fixture.document.text
                FileDocumentManager.getInstance().saveDocument(fixture.document)
                fixture.reopen()
                check(fixture.document.text == expectedSource)
                val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(fixture.document, 1L))
                check(plan.status == ProjectionPlanStatus.READY)
                val resources = NativeHostResourceProjectionPlanner.plan(plan)
                val imported = resources.singleOrNull { resource ->
                    resource.kind == NativeHostResourceKind.LOCAL_IMAGE && resource.target == target
                } ?: error("imported reference did not feed #147 host-resource projection")
                check(imported.sourceRange.startOffset >= 0)
                check(NativeLocalImageResolver.resolve(fixture.file.toNioPath(), target) is NativeLocalImageResult.Success)

                ApplicationManager.getApplication().runWriteAction {
                    fixture.file.rename(this, "renamed.md")
                }
                val renamedPath = fixture.file.toNioPath()
                check(fixture.document.text == expectedSource) { "same-parent rename rewrote source" }
                check(NativeLocalImageResolver.resolve(renamedPath, target) is NativeLocalImageResult.Success)
                "reopenSourceExact=true hostProjectionTarget=true resolverSuccess=true sameParentRenameSourceStable=true"
            }
        }

        private fun pasteContext(fixture: Fixture, transferable: Transferable) =
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.EDITOR, fixture.editor)
                .add(CommonDataKeys.VIRTUAL_FILE, fixture.file)
                .add(PasteAction.TRANSFERABLE_PROVIDER, Producer<Transferable> { transferable })
                .build()

        private suspend fun <T> withFixture(source: String, block: suspend (Fixture) -> T): T {
            val root = newRoot("document-")
            val path = root.resolve("document.md")
            Files.writeString(path, source, StandardCharsets.UTF_8)
            val file = vFile(path)
            val document = ReadAction.computeBlocking<Document, RuntimeException> {
                FileDocumentManager.getInstance().getDocument(file)
                    ?: error("Markdown fixture did not expose a Document")
            }
            val provider = selectPlatformTextProvider(file)
            val created = provider.createEditor(project, file)
            check(created is TextEditor) { "platform text provider returned ${created.javaClass.name}" }
            val fixture = Fixture(root, path, file, document, provider, created, created.editor)
            return try {
                block(fixture)
            } finally {
                fixture.dispose()
            }
        }

        private fun selectPlatformTextProvider(file: VirtualFile): FileEditorProvider =
            FileEditorProvider.EP_FILE_EDITOR_PROVIDER.extensionList.firstOrNull { candidate ->
                candidate.editorTypeId == PLATFORM_TEXT_EDITOR_TYPE_ID && accepts(candidate, file)
            } ?: error("platform text editor provider did not accept #151 Markdown fixture")

        private fun accepts(provider: FileEditorProvider, file: VirtualFile): Boolean = runCatching {
            if (provider.acceptRequiresReadAction()) {
                ReadAction.computeBlocking<Boolean, RuntimeException> { provider.accept(project, file) }
            } else {
                provider.accept(project, file)
            }
        }.getOrElse { false }

        private fun vFile(path: Path): VirtualFile =
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                ?: error("VirtualFile unavailable for $path")

        private fun newRoot(prefix: String): Path {
            val base = project.basePath?.let(Paths::get) ?: error("#151 probe project base path unavailable")
            return Files.createTempDirectory(base, ".markflow-$prefix").also(roots::add)
        }

        private fun writeImage(path: Path, format: String, width: Int, height: Int) {
            val imageType = if (format.equals("png", true)) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
            val image = BufferedImage(width, height, imageType)
            check(ImageIO.write(image, format, path.toFile())) { "ImageIO writer unavailable for $format" }
        }

        private fun pngHeader(width: Int, height: Int): ByteArray {
            val output = ByteArrayOutputStream()
            DataOutputStream(output).use { data ->
                data.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
                val ihdr = ByteArrayOutputStream()
                DataOutputStream(ihdr).use { chunk ->
                    chunk.writeInt(width)
                    chunk.writeInt(height)
                    chunk.writeByte(8)
                    chunk.writeByte(2)
                    chunk.writeByte(0)
                    chunk.writeByte(0)
                    chunk.writeByte(0)
                }
                writeChunk(data, "IHDR", ihdr.toByteArray())
            }
            return output.toByteArray()
        }

        private fun writeChunk(output: DataOutputStream, type: String, payload: ByteArray) {
            val typeBytes = type.toByteArray(StandardCharsets.US_ASCII)
            output.writeInt(payload.size)
            output.write(typeBytes)
            output.write(payload)
            val crc = CRC32()
            crc.update(typeBytes)
            crc.update(payload)
            output.writeInt(crc.value.toInt())
        }

        private suspend fun case(id: String, block: suspend () -> String) {
            val result = try {
                CaseResult(id, "PASS", block())
            } catch (failure: Throwable) {
                CaseResult(id, "FAIL", "${failure.javaClass.simpleName}: ${failure.message ?: "no message"}")
            }
            cases += result
        }

        private fun finish() {
            val verdict = if (cases.isNotEmpty() && cases.all { it.outcome == "PASS" }) "PASS" else "FAIL"
            val evidence = Evidence(
                schemaVersion = 1,
                selectedSurface = SELECTED_SURFACE,
                verdict = verdict,
                ideBuild = ApplicationInfo.getInstance().build.asString(),
                cases = cases.toList(),
            )
            try {
                output.parent?.let(Files::createDirectories)
                Files.writeString(output, gson.toJson(evidence), StandardCharsets.UTF_8)
            } finally {
                roots.asReversed().forEach { root -> runCatching { root.toFile().deleteRecursively() } }
                ApplicationManager.getApplication().exit(true, true, false)
            }
        }
    }

    private class Fixture(
        val root: Path,
        val path: Path,
        val file: VirtualFile,
        val document: Document,
        private val provider: FileEditorProvider,
        var fileEditor: TextEditor,
        var editor: Editor,
    ) {
        private var disposed = false

        fun reopen() {
            provider.disposeEditor(fileEditor)
            val reopened = provider.createEditor(editor.project ?: error("project lost during reopen"), file)
            check(reopened is TextEditor)
            fileEditor = reopened
            editor = reopened.editor
        }

        fun dispose() {
            if (disposed) return
            disposed = true
            provider.disposeEditor(fileEditor)
        }
    }

    private class FileListTransferable(private val files: List<File>) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.javaFileListFlavor
        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
            return files
        }
    }

    private class ImageTransferable(private val image: Image) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
            return image
        }
    }

    private data class CaseResult(
        val id: String,
        val outcome: String,
        val detail: String,
    )

    private data class Evidence(
        val schemaVersion: Int,
        val selectedSurface: String,
        val verdict: String,
        val ideBuild: String,
        val cases: List<CaseResult>,
    )
}
