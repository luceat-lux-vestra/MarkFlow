package com.algorist.markflow.editor.native

import com.algorist.markflow.file.MarkFlowFileSupport
import com.algorist.markflow.trust.NativeLocalImageFailureCode
import com.algorist.markflow.trust.NativeLocalImageResolver
import com.algorist.markflow.trust.NativeLocalImageResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Graphics2D
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Locale
import javax.imageio.ImageIO

internal enum class NativeImageImportFailureCode {
    INVALID_DOCUMENT_CONTEXT,
    READ_ONLY,
    MULTICARET_UNSUPPORTED,
    INVALID_SOURCE,
    UNSUPPORTED_MEDIA,
    FILE_TOO_LARGE,
    DIMENSIONS_TOO_LARGE,
    DESTINATION_UNAVAILABLE,
    COPY_FAILED,
    SOURCE_CHANGED,
    SOURCE_EDIT_FAILED,
    ROLLBACK_FAILED,
}

internal sealed interface NativeImageImportResult {
    data class Success(
        val markdownTargets: List<String>,
        val createdRelativePaths: List<String>,
    ) : NativeImageImportResult

    data class Failure(
        val code: NativeImageImportFailureCode,
        val message: String,
        val orphanRelativePaths: List<String> = emptyList(),
    ) : NativeImageImportResult
}

internal sealed interface NativeImageImportInput {
    data class LocalFile(val path: Path) : NativeImageImportInput
    data class ClipboardImage(val image: Image) : NativeImageImportInput
}

/**
 * #151 host/VFS image-file import transaction.
 *
 * The caller must enter through an explicit chooser/drop/paste gesture. This service never derives
 * filesystem authority from Markdown text. All input and destination decisions are completed before
 * the one authoritative Document command. Newly-created assets are intentionally outside that
 * command so editor Undo/Redo owns Markdown only.
 */
internal object NativeImageImportService {
    private const val ASSETS_DIRECTORY = "assets"
    private const val COMMAND_NAME = "Import Images into MarkFlow"

    fun import(
        editor: Editor,
        inputs: List<NativeImageImportInput>,
        insertionOffsetOverride: Int? = null,
    ): NativeImageImportResult {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (inputs.isEmpty()) {
            return failure(NativeImageImportFailureCode.INVALID_SOURCE, "No image was supplied.")
        }
        if (editor.isDisposed) {
            return failure(NativeImageImportFailureCode.INVALID_DOCUMENT_CONTEXT, "The editor is no longer available.")
        }
        if (editor.caretModel.caretCount != 1) {
            return failure(
                NativeImageImportFailureCode.MULTICARET_UNSUPPORTED,
                "Image import requires a single caret.",
            )
        }
        val project = editor.project
            ?: return failure(NativeImageImportFailureCode.INVALID_DOCUMENT_CONTEXT, "A project-backed editor is required.")
        val document = editor.document
        val documentFile = FileDocumentManager.getInstance().getFile(document)
            ?: return failure(
                NativeImageImportFailureCode.INVALID_DOCUMENT_CONTEXT,
                "Save the Markdown document before importing images.",
            )
        if (!MarkFlowFileSupport.isMarkFlowTarget(documentFile) || !documentFile.isInLocalFileSystem) {
            return failure(
                NativeImageImportFailureCode.INVALID_DOCUMENT_CONTEXT,
                "Image import is available only for saved local Markdown documents.",
            )
        }
        if (!document.isWritable || !documentFile.isWritable) {
            return failure(NativeImageImportFailureCode.READ_ONLY, "The Markdown document is read-only.")
        }

        val documentPath = runCatching { documentFile.toNioPath() }.getOrNull()
            ?: return failure(
                NativeImageImportFailureCode.INVALID_DOCUMENT_CONTEXT,
                "The Markdown document does not expose a local filesystem path.",
            )
        val root = NativeLocalImageResolver.documentRoot(documentPath)
            ?: return failure(
                NativeImageImportFailureCode.INVALID_DOCUMENT_CONTEXT,
                "The Markdown document no longer has a stable local parent directory.",
            )
        val parentFile = documentFile.parent
            ?: return failure(
                NativeImageImportFailureCode.INVALID_DOCUMENT_CONTEXT,
                "The Markdown document has no writable parent directory.",
            )
        if (!parentFile.isInLocalFileSystem || !parentFile.isWritable) {
            return failure(NativeImageImportFailureCode.READ_ONLY, "The document directory is not writable.")
        }
        val parentReal = runCatching { parentFile.toNioPath().toRealPath() }.getOrNull()
        if (parentReal == null || parentReal != root) {
            return failure(
                NativeImageImportFailureCode.INVALID_DOCUMENT_CONTEXT,
                "The document directory changed before image import could start.",
            )
        }

        val caret = editor.caretModel.currentCaret
        val insertionStart = insertionOffsetOverride ?: if (caret.hasSelection()) caret.selectionStart else caret.offset
        val insertionEnd = if (insertionOffsetOverride != null) insertionStart else if (caret.hasSelection()) caret.selectionEnd else insertionStart
        if (insertionStart !in 0..document.textLength || insertionEnd !in insertionStart..document.textLength) {
            return failure(NativeImageImportFailureCode.INVALID_DOCUMENT_CONTEXT, "The insertion position is no longer valid.")
        }
        val sourceIdentity = SourceIdentity(
            modificationStamp = document.modificationStamp,
            startOffset = insertionStart,
            endOffset = insertionEnd,
            replacedText = document.getText(com.intellij.openapi.util.TextRange(insertionStart, insertionEnd)),
        )

        val assetsPath = root.resolve(ASSETS_DIRECTORY)
        when {
            Files.isSymbolicLink(assetsPath) -> return failure(
                NativeImageImportFailureCode.DESTINATION_UNAVAILABLE,
                "The document assets path is a symbolic link and cannot be used safely.",
            )
            Files.exists(assetsPath, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(assetsPath, LinkOption.NOFOLLOW_LINKS) ->
                return failure(
                    NativeImageImportFailureCode.DESTINATION_UNAVAILABLE,
                    "The document assets path is not a directory.",
                )
        }

        val preparedInputs = ArrayList<PreparedInput>(inputs.size)
        for (input in inputs) {
            val prepared = when (input) {
                is NativeImageImportInput.LocalFile -> prepareLocalFile(documentPath, root, input.path)
                is NativeImageImportInput.ClipboardImage -> prepareClipboardImage(input.image)
            }
            if (prepared is Preparation.Failure) return prepared.result
            preparedInputs += (prepared as Preparation.Success).input
        }

        val plans = try {
            planDestinations(root, preparedInputs)
        } catch (_: IOException) {
            return failure(
                NativeImageImportFailureCode.DESTINATION_UNAVAILABLE,
                "The image destination could not be inspected safely.",
            )
        } catch (_: SecurityException) {
            return failure(
                NativeImageImportFailureCode.DESTINATION_UNAVAILABLE,
                "The image destination is not accessible.",
            )
        }

        val created = mutableListOf<CreatedAsset>()
        try {
            ApplicationManager.getApplication().runWriteAction {
                val assetsDir = if (plans.any { it.copyRequired }) {
                    ensureAssetsDirectory(parentFile)
                } else {
                    null
                }
                for (plan in plans) {
                    if (!plan.copyRequired) continue
                    val directory = assetsDir ?: error("assets directory missing for copy plan")
                    val createdFile = when (val prepared = plan.input) {
                        is PreparedInput.File -> {
                            val source = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(prepared.realPath)
                                ?: throw IOException("authorized source disappeared")
                            if (!source.isInLocalFileSystem || !source.isValid || source.isDirectory) {
                                throw IOException("authorized source is no longer a local regular file")
                            }
                            VfsUtil.copyFile(this, source, directory, plan.destinationName!!)
                        }
                        is PreparedInput.PngBytes -> {
                            val child = directory.createChildData(this, plan.destinationName!!)
                            child.setBinaryContent(prepared.bytes)
                            child
                        }
                    }
                    created += CreatedAsset(plan.target, createdFile)
                }
            }
        } catch (_: Exception) {
            val rollback = rollbackCreated(created)
            return if (rollback.isEmpty()) {
                failure(NativeImageImportFailureCode.COPY_FAILED, "The image file could not be copied into the document assets directory.")
            } else {
                NativeImageImportResult.Failure(
                    NativeImageImportFailureCode.ROLLBACK_FAILED,
                    "Image import failed and one or more newly-created assets could not be removed.",
                    rollback,
                )
            }
        }

        // Revalidate the exact final presentation targets after VFS creation but before source edit.
        for (plan in plans) {
            if (NativeLocalImageResolver.resolve(documentPath, plan.target) !is NativeLocalImageResult.Success) {
                val rollback = rollbackCreated(created)
                return if (rollback.isEmpty()) {
                    failure(
                        NativeImageImportFailureCode.DESTINATION_UNAVAILABLE,
                        "A generated image target did not pass the native presentation boundary.",
                    )
                } else {
                    NativeImageImportResult.Failure(
                        NativeImageImportFailureCode.ROLLBACK_FAILED,
                        "Generated image validation failed and cleanup left an orphan asset.",
                        rollback,
                    )
                }
            }
        }

        if (!sourceIdentity.matches(editor)) {
            val rollback = rollbackCreated(created)
            return if (rollback.isEmpty()) {
                failure(
                    NativeImageImportFailureCode.SOURCE_CHANGED,
                    "The Markdown source changed while the images were being prepared.",
                )
            } else {
                NativeImageImportResult.Failure(
                    NativeImageImportFailureCode.ROLLBACK_FAILED,
                    "The Markdown source changed and cleanup left an orphan asset.",
                    rollback,
                )
            }
        }

        val payload = plans.joinToString("\n") { plan -> "![${plan.input.altText}](${plan.target})" }
        try {
            WriteCommandAction.writeCommandAction(project)
                .withName(COMMAND_NAME)
                .run<RuntimeException> {
                    check(sourceIdentity.matches(editor)) { "source identity changed before image import command" }
                    document.replaceString(sourceIdentity.startOffset, sourceIdentity.endOffset, payload)
                    caret.removeSelection()
                    caret.moveToOffset(sourceIdentity.startOffset + payload.length)
                }
        } catch (_: Exception) {
            val rollback = rollbackCreated(created)
            return if (rollback.isEmpty()) {
                failure(
                    NativeImageImportFailureCode.SOURCE_EDIT_FAILED,
                    "The image files were prepared, but the Markdown reference could not be inserted.",
                )
            } else {
                NativeImageImportResult.Failure(
                    NativeImageImportFailureCode.ROLLBACK_FAILED,
                    "The Markdown reference was not inserted and cleanup left an orphan asset.",
                    rollback,
                )
            }
        }

        return NativeImageImportResult.Success(
            markdownTargets = plans.map { it.target },
            createdRelativePaths = created.map { it.relativeTarget },
        )
    }

    private fun prepareLocalFile(documentPath: Path, root: Path, suppliedPath: Path): Preparation {
        val absolute = try {
            suppliedPath.toAbsolutePath().normalize()
        } catch (_: Exception) {
            return Preparation.Failure(failure(NativeImageImportFailureCode.INVALID_SOURCE, "The selected image path is invalid."))
        }
        if (Files.isSymbolicLink(absolute) || !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            return Preparation.Failure(
                failure(NativeImageImportFailureCode.INVALID_SOURCE, "The selected image is not a safe regular file."),
            )
        }
        val real = try {
            absolute.toRealPath()
        } catch (_: IOException) {
            return Preparation.Failure(failure(NativeImageImportFailureCode.INVALID_SOURCE, "The selected image is no longer available."))
        } catch (_: SecurityException) {
            return Preparation.Failure(failure(NativeImageImportFailureCode.INVALID_SOURCE, "The selected image cannot be accessed."))
        }
        val validated = NativeLocalImageResolver.validateLocalFile(real)
        if (validated is NativeLocalImageResult.Failure) {
            return Preparation.Failure(mapRasterFailure(validated.code))
        }

        val originalName = suppliedPath.fileName?.toString().orEmpty()
        val alt = NativeImageImportPolicy.altTextFromFilename(originalName)
        val containedTarget = if (real.startsWith(root)) {
            val relative = root.relativize(real).joinToString("/") { it.toString() }
            val encoded = NativeImageImportPolicy.encodeRelativeTarget(relative)
            encoded.takeIf { target ->
                NativeLocalImageResolver.decodeRelativeTarget(target) == relative &&
                    NativeLocalImageResolver.resolve(documentPath, target) is NativeLocalImageResult.Success
            }
        } else {
            null
        }
        return Preparation.Success(
            PreparedInput.File(
                realPath = real,
                originalName = originalName,
                altText = alt,
                containedTarget = containedTarget,
            ),
        )
    }

    private fun prepareClipboardImage(image: Image): Preparation {
        val width = image.getWidth(null)
        val height = image.getHeight(null)
        if (width !in 1..NativeLocalImageResolver.MAX_DIMENSION || height !in 1..NativeLocalImageResolver.MAX_DIMENSION) {
            return Preparation.Failure(
                failure(NativeImageImportFailureCode.DIMENSIONS_TOO_LARGE, "The clipboard image dimensions are too large."),
            )
        }
        if (width.toLong() * height.toLong() > NativeLocalImageResolver.MAX_PIXELS) {
            return Preparation.Failure(
                failure(NativeImageImportFailureCode.DIMENSIONS_TOO_LARGE, "The clipboard image contains too many pixels."),
            )
        }
        val buffered = if (image is BufferedImage) {
            image
        } else {
            BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { target ->
                val graphics: Graphics2D = target.createGraphics()
                try {
                    graphics.drawImage(image, 0, 0, null)
                } finally {
                    graphics.dispose()
                }
            }
        }
        val bytes = ByteArrayOutputStream().use { output ->
            if (!ImageIO.write(buffered, "png", output)) {
                return Preparation.Failure(
                    failure(NativeImageImportFailureCode.UNSUPPORTED_MEDIA, "The clipboard image could not be encoded as PNG."),
                )
            }
            output.toByteArray()
        }
        if (bytes.isEmpty() || bytes.size.toLong() > NativeLocalImageResolver.MAX_FILE_BYTES) {
            return Preparation.Failure(
                failure(NativeImageImportFailureCode.FILE_TOO_LARGE, "The encoded clipboard image is too large."),
            )
        }
        return Preparation.Success(
            PreparedInput.PngBytes(
                bytes = bytes,
                originalName = "image.png",
                altText = "image",
                containedTarget = null,
            ),
        )
    }

    @Throws(IOException::class)
    private fun planDestinations(root: Path, inputs: List<PreparedInput>): List<ImportPlan> {
        val assetsPath = root.resolve(ASSETS_DIRECTORY)
        val reserved = linkedSetOf<String>()
        if (Files.isDirectory(assetsPath, LinkOption.NOFOLLOW_LINKS)) {
            Files.newDirectoryStream(assetsPath).use { stream ->
                stream.forEach { child -> reserved += child.fileName.toString().lowercase(Locale.ROOT) }
            }
        }

        return inputs.map { input ->
            val containedTarget = input.containedTarget
            if (containedTarget != null) {
                ImportPlan(input, containedTarget, copyRequired = false, destinationName = null)
            } else {
                val sanitized = NativeImageImportPolicy.sanitizeFilename(input.originalName)
                val destination = NativeImageImportPolicy.firstAvailableName(sanitized, reserved)
                reserved += destination.lowercase(Locale.ROOT)
                val target = "$ASSETS_DIRECTORY/${NativeImageImportPolicy.encodePathSegment(destination)}"
                check(NativeLocalImageResolver.decodeRelativeTarget(target) == "$ASSETS_DIRECTORY/$destination") {
                    "generated target does not round-trip through #147 decoder"
                }
                ImportPlan(input, target, copyRequired = true, destinationName = destination)
            }
        }
    }

    private fun ensureAssetsDirectory(parent: VirtualFile): VirtualFile {
        val existing = parent.findChild(ASSETS_DIRECTORY)
        if (existing != null) {
            if (!existing.isValid || !existing.isDirectory || !existing.isWritable || existing.is(VirtualFile.PROP_SYMLINK)) {
                throw IOException("unsafe assets directory")
            }
            return existing
        }
        return parent.createChildDirectory(this, ASSETS_DIRECTORY)
    }

    private fun rollbackCreated(created: List<CreatedAsset>): List<String> {
        if (created.isEmpty()) return emptyList()
        val orphaned = mutableListOf<String>()
        ApplicationManager.getApplication().runWriteAction {
            created.asReversed().forEach { asset ->
                try {
                    if (asset.file.isValid) asset.file.delete(this)
                } catch (_: Exception) {
                    orphaned += asset.relativeTarget
                }
            }
        }
        return orphaned
    }

    private fun mapRasterFailure(code: NativeLocalImageFailureCode): NativeImageImportResult.Failure = when (code) {
        NativeLocalImageFailureCode.FILE_TOO_LARGE ->
            failure(NativeImageImportFailureCode.FILE_TOO_LARGE, "The selected image is larger than 64 MiB.")
        NativeLocalImageFailureCode.DIMENSIONS_TOO_LARGE ->
            failure(NativeImageImportFailureCode.DIMENSIONS_TOO_LARGE, "The selected image dimensions are too large.")
        NativeLocalImageFailureCode.UNSUPPORTED_MEDIA,
        NativeLocalImageFailureCode.DECODE_FAILED ->
            failure(NativeImageImportFailureCode.UNSUPPORTED_MEDIA, "The selected file is not a supported PNG, JPEG, GIF, or BMP image.")
        else -> failure(NativeImageImportFailureCode.INVALID_SOURCE, "The selected image is not available as a safe local file.")
    }

    private fun failure(code: NativeImageImportFailureCode, message: String) =
        NativeImageImportResult.Failure(code, message)

    private sealed interface Preparation {
        data class Success(val input: PreparedInput) : Preparation
        data class Failure(val result: NativeImageImportResult.Failure) : Preparation
    }

    private sealed interface PreparedInput {
        val originalName: String
        val altText: String
        val containedTarget: String?

        data class File(
            val realPath: Path,
            override val originalName: String,
            override val altText: String,
            override val containedTarget: String?,
        ) : PreparedInput

        data class PngBytes(
            val bytes: ByteArray,
            override val originalName: String,
            override val altText: String,
            override val containedTarget: String?,
        ) : PreparedInput
    }

    private data class ImportPlan(
        val input: PreparedInput,
        val target: String,
        val copyRequired: Boolean,
        val destinationName: String?,
    )

    private data class CreatedAsset(
        val relativeTarget: String,
        val file: VirtualFile,
    )

    private data class SourceIdentity(
        val modificationStamp: Long,
        val startOffset: Int,
        val endOffset: Int,
        val replacedText: String,
    ) {
        fun matches(editor: Editor): Boolean {
            val document = editor.document
            if (editor.isDisposed || document.modificationStamp != modificationStamp) return false
            if (startOffset !in 0..document.textLength || endOffset !in startOffset..document.textLength) return false
            return document.getText(com.intellij.openapi.util.TextRange(startOffset, endOffset)) == replacedText
        }
    }
}

/** Pure path/source formatting rules from the accepted #150 contract. */
internal object NativeImageImportPolicy {
    private val unsafeFilenameChar = Regex("[<>:\"/\\\\|?*#%\\p{Cc}]")
    private val trailingDotsOrSpaces = Regex("[. ]+$")
    private val windowsReserved = Regex("(?i)^(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$")

    fun sanitizeFilename(original: String): String {
        val candidate = original
            .replace(unsafeFilenameChar, "_")
            .replace(trailingDotsOrSpaces, "")
            .trim()
            .take(180)
        val nonEmpty = candidate.takeUnless { it.isBlank() || it == "." || it == ".." } ?: fallbackName(original)
        return if (windowsReserved.matches(nonEmpty)) "image-$nonEmpty" else nonEmpty
    }

    fun firstAvailableName(preferred: String, reservedLowercase: Set<String>): String {
        if (preferred.lowercase(Locale.ROOT) !in reservedLowercase) return preferred
        val dot = preferred.lastIndexOf('.')
        val hasExtension = dot > 0 && dot < preferred.lastIndex
        val stem = if (hasExtension) preferred.substring(0, dot) else preferred
        val extension = if (hasExtension) preferred.substring(dot) else ""
        var suffix = 2
        while (true) {
            val candidate = "$stem-$suffix$extension"
            if (candidate.lowercase(Locale.ROOT) !in reservedLowercase) return candidate
            suffix++
        }
    }

    fun altTextFromFilename(filename: String): String {
        val stem = filename.substringBeforeLast('.', filename)
        val normalized = buildString {
            stem.forEach { ch ->
                when {
                    ch == '\r' || ch == '\n' || Character.isISOControl(ch) -> Unit
                    ch == '\\' || ch == '[' || ch == ']' -> append('\\').append(ch)
                    else -> append(ch)
                }
            }
        }.trim()
        return normalized.ifBlank { "image" }
    }

    fun encodeRelativeTarget(relative: String): String =
        relative.split('/').joinToString("/") { segment -> encodePathSegment(segment) }

    fun encodePathSegment(segment: String): String {
        val bytes = segment.toByteArray(StandardCharsets.UTF_8)
        return buildString(bytes.size) {
            bytes.forEach { raw ->
                val value = raw.toInt() and 0xff
                if (
                    value in 'a'.code..'z'.code || value in 'A'.code..'Z'.code || value in '0'.code..'9'.code ||
                    value == '-'.code || value == '.'.code || value == '_'.code || value == '~'.code
                ) {
                    append(value.toChar())
                } else {
                    append('%')
                    append(HEX[value ushr 4])
                    append(HEX[value and 0x0f])
                }
            }
        }
    }

    private fun fallbackName(original: String): String {
        val extension = original.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (extension) {
            "png", "jpg", "jpeg", "gif", "bmp" -> "image.$extension"
            else -> "image"
        }
    }

    private const val HEX = "0123456789ABCDEF"
}
