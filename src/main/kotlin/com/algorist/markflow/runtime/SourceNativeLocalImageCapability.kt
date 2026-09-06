package com.algorist.markflow.runtime

import com.intellij.openapi.Disposable
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One document-scoped, runtime-owned loopback capability for source-native Markdown image preview.
 *
 * This is intentionally narrower than the legacy shared webview resource path: one capability
 * serves raster images only from the real directory containing one Markdown document. It grants no
 * generic filesystem, raw-HTML, remote-network or navigation capability and stops synchronously
 * when its owner is disposed.
 */
internal class SourceNativeLocalImageCapability private constructor(
    private val server: HttpServer,
    private val documentDirectory: Path,
    private val token: String,
) : Disposable {
    private val disposed = AtomicBoolean(false)
    private val requestPrefix = "/$RESOURCE_PREFIX/$token/"

    val baseUrl: String = "http://127.0.0.1:${server.address.port}$requestPrefix"

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.executor = null
        server.start()
    }

    internal val isDisposed: Boolean
        get() = disposed.get()

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        server.stop(0)
    }

    private fun handle(exchange: HttpExchange) {
        if (disposed.get()) {
            sendStatus(exchange, 404)
            return
        }

        val headOnly = when (exchange.requestMethod.uppercase(Locale.ROOT)) {
            "GET" -> false
            "HEAD" -> true
            else -> {
                exchange.responseHeaders["Allow"] = "GET, HEAD"
                sendStatus(exchange, 405)
                return
            }
        }

        val requestPath = exchange.requestURI?.path.orEmpty()
        if (!requestPath.startsWith(requestPrefix)) {
            sendStatus(exchange, 404)
            return
        }
        val relativePath = requestPath.removePrefix(requestPrefix)
        val target = resolveLocalImagePath(documentDirectory, relativePath)
        if (target == null) {
            sendStatus(exchange, 404)
            return
        }

        try {
            val size = Files.size(target)
            if (!isAllowedLocalImageSize(size)) {
                sendStatus(exchange, 413)
                return
            }

            val contentType = Files.probeContentType(target)
                ?: URLConnection.guessContentTypeFromName(target.fileName.toString())
            if (!isAllowedLocalImageContentType(contentType)) {
                sendStatus(exchange, 404)
                return
            }

            exchange.responseHeaders["Content-Type"] = contentType
            exchange.responseHeaders["Cache-Control"] = "no-store"
            exchange.responseHeaders["X-Content-Type-Options"] = "nosniff"
            exchange.responseHeaders["Referrer-Policy"] = "no-referrer"

            if (headOnly) {
                exchange.responseHeaders["Content-Length"] = size.toString()
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
                return
            }

            exchange.sendResponseHeaders(200, size)
            Files.newInputStream(target).use { input ->
                exchange.responseBody.use { output -> input.copyTo(output) }
            }
        } catch (_: IOException) {
            // Do not put the local path, token, or exception message into normal diagnostics.
            try {
                sendStatus(exchange, 404)
            } catch (_: IOException) {
                exchange.close()
            }
        } catch (_: SecurityException) {
            sendStatus(exchange, 404)
        }
    }

    private fun sendStatus(exchange: HttpExchange, status: Int) {
        try {
            exchange.sendResponseHeaders(status, -1)
        } finally {
            exchange.close()
        }
    }

    companion object {
        internal const val MAX_LOCAL_IMAGE_BYTES: Long = 64L * 1024L * 1024L
        private const val RESOURCE_PREFIX = "__markflow_source_native_image__"

        private val ALLOWED_CONTENT_TYPES = setOf(
            "image/avif",
            "image/bmp",
            "image/gif",
            "image/jpeg",
            "image/png",
            "image/vnd.microsoft.icon",
            "image/webp",
            "image/x-icon",
        )

        fun create(documentPath: String): SourceNativeLocalImageCapability? {
            var server: HttpServer? = null
            return try {
                val document = Path.of(documentPath).toAbsolutePath().normalize().toRealPath()
                if (!Files.isRegularFile(document)) return null
                val documentDirectory = document.parent?.toRealPath() ?: return null
                server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                SourceNativeLocalImageCapability(
                    server = server,
                    documentDirectory = documentDirectory,
                    token = UUID.randomUUID().toString(),
                )
            } catch (_: Exception) {
                server?.stop(0)
                null
            }
        }

        internal fun resolveLocalImagePath(documentDirectory: Path, relativePath: String): Path? {
            if (relativePath.isBlank()) return null

            return try {
                val relative = Path.of(relativePath)
                if (relative.isAbsolute) return null

                val root = documentDirectory.toAbsolutePath().normalize().toRealPath()
                val candidate = root.resolve(relative).normalize()
                if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) {
                    return null
                }

                candidate.toRealPath().takeIf { it.startsWith(root) && Files.isRegularFile(it) }
            } catch (_: InvalidPathException) {
                null
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }

        internal fun isAllowedLocalImageContentType(contentType: String?): Boolean {
            val normalized = contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?: return false
            return normalized in ALLOWED_CONTENT_TYPES
        }

        internal fun isAllowedLocalImageSize(size: Long): Boolean {
            return size in 0..MAX_LOCAL_IMAGE_BYTES
        }
    }
}
