package com.algorist.markflow.browser

import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale

/** Pure path/type/size policy for one source-native document-local image capability. */
internal object SourceNativeLocalImagePolicy {
    const val MAX_BYTES: Long = 64L * 1024L * 1024L

    private val ENCODED_SEPARATOR_OR_NUL = Regex("%(?:2f|5c|00)", RegexOption.IGNORE_CASE)
    private val SECOND_LAYER_PATH_ESCAPE = Regex("%(?:2e|2f|5c|00)", RegexOption.IGNORE_CASE)
    private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[\\\\/]")

    fun decodeRawRelativePath(rawRelativePath: String): String? {
        if (rawRelativePath.isBlank() || ENCODED_SEPARATOR_OR_NUL.containsMatchIn(rawRelativePath)) {
            return null
        }

        return try {
            // URLDecoder treats '+' as space for form data. URL paths do not, so preserve literal '+'.
            val decoded = URLDecoder.decode(rawRelativePath.replace("+", "%2B"), StandardCharsets.UTF_8)
            if (decoded.isBlank() || decoded.indexOf('\u0000') >= 0) {
                return null
            }
            if (SECOND_LAYER_PATH_ESCAPE.containsMatchIn(decoded)) {
                return null
            }
            decoded
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun resolve(documentDirectory: Path, relativePath: String): Path? {
        if (relativePath.isBlank()) return null
        if (relativePath.startsWith('/') || relativePath.startsWith('\\') || WINDOWS_ABSOLUTE_PATH.containsMatchIn(relativePath)) {
            return null
        }

        val slashNormalized = relativePath.replace('\\', '/')
        if (slashNormalized.split('/').any { it == ".." }) {
            return null
        }

        return try {
            val relative = Path.of(relativePath)
            if (relative.isAbsolute) return null

            val root = documentDirectory.toAbsolutePath().normalize().toRealPath()
            val candidate = root.resolve(relative).normalize()
            if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) {
                return null
            }

            candidate.toRealPath().takeIf { it.startsWith(root) }
        } catch (_: InvalidPathException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    fun contentType(target: Path): String? {
        val fileName = target.fileName?.toString() ?: return null
        return when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> null
        }
    }

    fun isAllowedSize(size: Long): Boolean = size in 0..MAX_BYTES
}
