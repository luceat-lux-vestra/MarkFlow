package com.algorist.markflow.trust

import com.intellij.openapi.vfs.VirtualFile
import java.awt.image.BufferedImage
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream

internal data class NativeLocalImageArtifact(
    val image: BufferedImage,
    val mediaType: String,
)

internal enum class NativeLocalImageFailureCode {
    INVALID_DOCUMENT_CONTEXT,
    INVALID_TARGET,
    OUTSIDE_DOCUMENT_ROOT,
    NOT_FOUND,
    UNSUPPORTED_MEDIA,
    FILE_TOO_LARGE,
    DIMENSIONS_TOO_LARGE,
    DECODE_FAILED,
}

internal sealed interface NativeLocalImageResult {
    data class Success(val artifact: NativeLocalImageArtifact) : NativeLocalImageResult
    data class Failure(val code: NativeLocalImageFailureCode) : NativeLocalImageResult
}

/**
 * Host-only resolver for #147 document-relative raster image presentation.
 *
 * It grants no filesystem capability to document content. A target must resolve to an existing
 * regular file under the real parent directory of the real document path, and symlink escape is
 * rejected after resolution. Format, encoded size, dimensions and pixel count are bounded before
 * full decode. The returned [BufferedImage] is inert host memory; no URL/token/browser authority is
 * exposed to presentation code.
 *
 * #151 also consumes [validateLocalFile] and [validateVirtualFile] so import and presentation share
 * one media/resource admission boundary. Import still owns explicit-user-authority, destination and
 * transaction rules. The VirtualFile variant exists because IntelliJ 2026.2 may make a successful
 * VFS write visible before raw-disk persistence is observable through NIO.
 */
internal object NativeLocalImageResolver {
    internal const val MAX_TARGET_LENGTH = 4096
    internal const val MAX_FILE_BYTES: Long = 64L * 1024L * 1024L
    internal const val MAX_DIMENSION = 8192
    internal const val MAX_PIXELS: Long = 16L * 1024L * 1024L

    private val encodedSeparatorOrNul = Regex("%(?:2f|5c|00)", RegexOption.IGNORE_CASE)
    private val secondLayerPathEscape = Regex("%(?:2e|2f|5c|00)", RegexOption.IGNORE_CASE)
    private val windowsAbsolutePath = Regex("^[A-Za-z]:[\\\\/]")
    private val schemePrefix = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    fun resolve(documentPath: Path, rawTarget: String): NativeLocalImageResult {
        val root = documentRoot(documentPath)
            ?: return NativeLocalImageResult.Failure(NativeLocalImageFailureCode.INVALID_DOCUMENT_CONTEXT)
        val relative = decodeRelativeTarget(rawTarget)
            ?: return NativeLocalImageResult.Failure(NativeLocalImageFailureCode.INVALID_TARGET)
        val target = resolveContained(root, relative)
            ?: return missingOrOutside(root, relative)
        return validateLocalFile(target)
    }

    /** Validate one already-authorized local NIO path with the shared bounded raster rules. */
    internal fun validateLocalFile(target: Path): NativeLocalImageResult {
        if (!isRegularFile(target)) {
            return NativeLocalImageResult.Failure(NativeLocalImageFailureCode.NOT_FOUND)
        }
        val size = try {
            Files.size(target)
        } catch (_: IOException) {
            return NativeLocalImageResult.Failure(NativeLocalImageFailureCode.NOT_FOUND)
        } catch (_: SecurityException) {
            return NativeLocalImageResult.Failure(NativeLocalImageFailureCode.NOT_FOUND)
        }
        if (size <= 0L || size > MAX_FILE_BYTES) {
            return NativeLocalImageResult.Failure(NativeLocalImageFailureCode.FILE_TOO_LARGE)
        }

        val expectedFormat = expectedFormat(target.fileName?.toString().orEmpty())
            ?: return NativeLocalImageResult.Failure(NativeLocalImageFailureCode.UNSUPPORTED_MEDIA)
        val stream = try {
            ImageIO.createImageInputStream(target.toFile())
        } catch (_: IOException) {
            null
        } ?: return NativeLocalImageResult.Failure(NativeLocalImageFailureCode.DECODE_FAILED)
        stream.use { input ->
            return decode(input, expectedFormat)
        }
    }

    /**
     * Validate exactly the bytes visible through IntelliJ VFS. This is used for #151's post-create
     * verification so source insertion never depends on an immediate raw-filesystem flush.
     */
    internal fun validateVirtualFile(target: VirtualFile): NativeLocalImageResult {
        if (!target.isValid || !target.isInLocalFileSystem || target.isDirectory) {
            return NativeLocalImageResult.Failure(NativeLocalImageFailureCode.NOT_FOUND)
        }
        val size = target.length
        if (size <= 0L || size > MAX_FILE_BYTES) {
            return NativeLocalImageResult.Failure(NativeLocalImageFailureCode.FILE_TOO_LARGE)
        }
        val expectedFormat = expectedFormat(target.name)
            ?: return NativeLocalImageResult.Failure(NativeLocalImageFailureCode.UNSUPPORTED_MEDIA)
        return try {
            target.inputStream.use { raw ->
                val imageInput = ImageIO.createImageInputStream(raw)
                    ?: return NativeLocalImageResult.Failure(NativeLocalImageFailureCode.DECODE_FAILED)
                imageInput.use { input -> decode(input, expectedFormat) }
            }
        } catch (_: IOException) {
            NativeLocalImageResult.Failure(NativeLocalImageFailureCode.DECODE_FAILED)
        } catch (_: SecurityException) {
            NativeLocalImageResult.Failure(NativeLocalImageFailureCode.DECODE_FAILED)
        }
    }

    internal fun decodeRelativeTarget(rawTarget: String): String? {
        if (rawTarget.isBlank() || rawTarget.length > MAX_TARGET_LENGTH) return null
        if (rawTarget.any(Character::isISOControl)) return null
        if ('?' in rawTarget || '#' in rawTarget) return null
        if ('\\' in rawTarget) return null
        if (rawTarget.startsWith('/') || rawTarget.startsWith("//")) return null
        if (windowsAbsolutePath.containsMatchIn(rawTarget) || schemePrefix.containsMatchIn(rawTarget)) return null
        if (encodedSeparatorOrNul.containsMatchIn(rawTarget)) return null

        val decoded = try {
            // URLDecoder has form semantics for '+'. Preserve literal '+' for path targets.
            URLDecoder.decode(rawTarget.replace("+", "%2B"), StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (decoded.isBlank() || decoded.any(Character::isISOControl)) return null
        if ('?' in decoded || '#' in decoded || '\\' in decoded) return null
        if (decoded.startsWith('/') || decoded.startsWith("//")) return null
        if (windowsAbsolutePath.containsMatchIn(decoded) || schemePrefix.containsMatchIn(decoded)) return null
        if (secondLayerPathEscape.containsMatchIn(decoded)) return null
        if (decoded.split('/').any { segment -> segment == ".." }) return null
        return decoded
    }

    internal fun documentRoot(documentPath: Path): Path? {
        return try {
            val document = documentPath.toAbsolutePath().normalize().toRealPath()
            if (!Files.isRegularFile(document)) null else document.parent?.toRealPath()
        } catch (_: InvalidPathException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun resolveContained(root: Path, relativeTarget: String): Path? {
        return try {
            val relative = Path.of(relativeTarget)
            if (relative.isAbsolute) {
                null
            } else {
                val candidate = root.resolve(relative).normalize()
                if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) {
                    null
                } else {
                    candidate.toRealPath().takeIf { resolved -> resolved.startsWith(root) && Files.isRegularFile(resolved) }
                }
            }
        } catch (_: InvalidPathException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /** Distinguish a syntactic containment violation from an ordinary missing file without resolving it. */
    private fun missingOrOutside(root: Path, relativeTarget: String): NativeLocalImageResult.Failure = try {
        val normalized = root.resolve(Path.of(relativeTarget)).normalize()
        if (!normalized.startsWith(root)) {
            NativeLocalImageResult.Failure(NativeLocalImageFailureCode.OUTSIDE_DOCUMENT_ROOT)
        } else {
            NativeLocalImageResult.Failure(NativeLocalImageFailureCode.NOT_FOUND)
        }
    } catch (_: Exception) {
        NativeLocalImageResult.Failure(NativeLocalImageFailureCode.INVALID_TARGET)
    }

    private fun isRegularFile(target: Path): Boolean = try {
        Files.isRegularFile(target)
    } catch (_: SecurityException) {
        false
    }

    private fun expectedFormat(filename: String): ExpectedFormat? {
        val extension = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (extension) {
            "png" -> ExpectedFormat("png", "image/png")
            "jpg", "jpeg" -> ExpectedFormat("jpeg", "image/jpeg")
            "gif" -> ExpectedFormat("gif", "image/gif")
            "bmp" -> ExpectedFormat("bmp", "image/bmp")
            else -> null
        }
    }

    private fun decode(input: ImageInputStream, expected: ExpectedFormat): NativeLocalImageResult {
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) {
            return NativeLocalImageResult.Failure(NativeLocalImageFailureCode.UNSUPPORTED_MEDIA)
        }
        val reader = readers.next()
        try {
            reader.input = input
            val actualFormat = runCatching { reader.formatName.lowercase(Locale.ROOT) }.getOrNull()
                ?: return NativeLocalImageResult.Failure(NativeLocalImageFailureCode.UNSUPPORTED_MEDIA)
            if (!formatMatches(expected.format, actualFormat)) {
                return NativeLocalImageResult.Failure(NativeLocalImageFailureCode.UNSUPPORTED_MEDIA)
            }
            val width = runCatching { reader.getWidth(0) }.getOrNull()
                ?: return NativeLocalImageResult.Failure(NativeLocalImageFailureCode.DECODE_FAILED)
            val height = runCatching { reader.getHeight(0) }.getOrNull()
                ?: return NativeLocalImageResult.Failure(NativeLocalImageFailureCode.DECODE_FAILED)
            if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) {
                return NativeLocalImageResult.Failure(NativeLocalImageFailureCode.DIMENSIONS_TOO_LARGE)
            }
            if (width.toLong() * height.toLong() > MAX_PIXELS) {
                return NativeLocalImageResult.Failure(NativeLocalImageFailureCode.DIMENSIONS_TOO_LARGE)
            }
            val image = runCatching { reader.read(0) }.getOrNull()
                ?: return NativeLocalImageResult.Failure(NativeLocalImageFailureCode.DECODE_FAILED)
            if (image.width != width || image.height != height) {
                return NativeLocalImageResult.Failure(NativeLocalImageFailureCode.DECODE_FAILED)
            }
            return NativeLocalImageResult.Success(NativeLocalImageArtifact(image, expected.mediaType))
        } finally {
            reader.dispose()
        }
    }

    private fun formatMatches(expected: String, actual: String): Boolean = when (expected) {
        "jpeg" -> actual == "jpeg" || actual == "jpg"
        else -> actual == expected
    }

    private data class ExpectedFormat(
        val format: String,
        val mediaType: String,
    )
}
