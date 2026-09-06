package com.algorist.markflow.browser

import com.algorist.markflow.MarkFlowDiagnostics
import com.intellij.openapi.diagnostic.Logger
import com.sun.net.httpserver.HttpExchange
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Complete loopback HTTP trust boundary for the source-native realm.
 *
 * The shared server lifecycle remains owned by [MarkFlowWebviewResourceManager], but no
 * source-native entry, executable asset, or document-local image is served by the legacy generic
 * static-resource path. Browser-request filtering in `SourceNativeBrowserRequestPolicy` is a second,
 * independent enforcement layer over the same canonical route contract.
 */
internal class SourceNativeWebviewHttpBoundary(webviewRoot: Path) {
    private val root = webviewRoot.toAbsolutePath().normalize().toRealPath()
    private val localImages = ConcurrentHashMap<String, LocalDocumentResource>()
    private val secureRandom = SecureRandom()

    fun registerLocalImage(documentPath: String, port: Int): LocalDocumentRegistration? {
        val resource = createLocalDocumentResource(documentPath) ?: return null
        repeat(MAX_TOKEN_MINT_ATTEMPTS) {
            val token = mintCapabilityToken()
            if (localImages.putIfAbsent(token, resource) == null) {
                return LocalDocumentRegistration(
                    token = token,
                    baseUrl = "http://127.0.0.1:$port${SourceNativeWebviewRoutePolicy.LOCAL_IMAGE_PREFIX}$token/",
                )
            }
        }
        return null
    }

    fun unregisterLocalImage(token: String?) {
        if (!token.isNullOrBlank()) {
            localImages.remove(token)
        }
    }

    fun clearCapabilities() {
        localImages.clear()
    }

    fun serve(exchange: HttpExchange) {
        val uri = exchange.requestURI
        val decodedPath = uri?.path.orEmpty()
        val rawPath = uri?.rawPath.orEmpty()
        if (decodedPath.isEmpty() || rawPath.isEmpty()) {
            sendStatus(exchange, 404)
            return
        }

        when {
            SourceNativeWebviewRoutePolicy.isCanonicalEntry(rawPath) -> serveEntry(exchange)
            SourceNativeWebviewRoutePolicy.isCanonicalAsset(rawPath) -> serveAsset(exchange, rawPath)
            SourceNativeWebviewRoutePolicy.isCanonicalLocalImage(rawPath) -> serveLocalImage(exchange, rawPath)
            else -> sendStatus(exchange, 404)
        }
    }

    private fun serveEntry(exchange: HttpExchange) {
        val method = requireReadMethod(exchange) ?: return
        val target = resolveStaticFile(SourceNativeWebviewRoutePolicy.ENTRY_PATH) ?: run {
            sendStatus(exchange, 404)
            return
        }

        try {
            val template = Files.readString(target, StandardCharsets.UTF_8)
            val nonce = SourceNativeContentSecurityPolicy.mintNonce()
            val rendered = SourceNativeContentSecurityPolicy.renderDocument(template, nonce)
            if (rendered == null) {
                sendStatus(exchange, 500)
                return
            }
            val bytes = rendered.toByteArray(StandardCharsets.UTF_8)

            sourceNativeResponseHeaders(exchange)
            exchange.responseHeaders["Content-Type"] = "text/html; charset=utf-8"
            exchange.responseHeaders["Content-Security-Policy"] =
                SourceNativeContentSecurityPolicy.headerValue(nonce)
            sendBytes(exchange, method, bytes)
        } catch (_: IOException) {
            safeServerFailure(exchange)
        }
    }

    private fun serveAsset(exchange: HttpExchange, rawPath: String) {
        if (exchange.requestURI?.rawQuery != null) {
            sendStatus(exchange, 404)
            return
        }
        val method = requireReadMethod(exchange) ?: return
        val contentType = staticAssetContentType(rawPath) ?: run {
            sendStatus(exchange, 404)
            return
        }
        val target = resolveStaticFile(rawPath) ?: run {
            sendStatus(exchange, 404)
            return
        }

        try {
            sourceNativeResponseHeaders(exchange)
            exchange.responseHeaders["Content-Type"] = contentType
            sendFile(exchange, method, target)
        } catch (_: IOException) {
            safeServerFailure(exchange)
        }
    }

    private fun serveLocalImage(exchange: HttpExchange, rawPath: String) {
        if (exchange.requestURI?.rawQuery != null) {
            sendStatus(exchange, 404)
            return
        }
        val method = requireReadMethod(exchange) ?: return
        val remainder = rawPath.removePrefix(SourceNativeWebviewRoutePolicy.LOCAL_IMAGE_PREFIX)
        val separator = remainder.indexOf('/')
        if (separator <= 0 || separator == remainder.lastIndex) {
            sendStatus(exchange, 404)
            return
        }

        val token = remainder.substring(0, separator)
        val rawRelativePath = remainder.substring(separator + 1)
        val resource = localImages[token] ?: run {
            sendStatus(exchange, 404)
            return
        }
        val relativePath = SourceNativeLocalImagePolicy.decodeRawRelativePath(rawRelativePath) ?: run {
            sendStatus(exchange, 404)
            return
        }
        val target = SourceNativeLocalImagePolicy.resolve(resource.documentDirectory, relativePath) ?: run {
            sendStatus(exchange, 404)
            return
        }
        val contentType = SourceNativeLocalImagePolicy.contentType(target) ?: run {
            sendStatus(exchange, 404)
            return
        }

        try {
            val size = Files.size(target)
            if (!SourceNativeLocalImagePolicy.isAllowedSize(size)) {
                sendStatus(exchange, 413)
                return
            }

            sourceNativeResponseHeaders(exchange)
            exchange.responseHeaders["Content-Type"] = contentType
            sendFile(exchange, method, target, size)
        } catch (_: IOException) {
            if (MarkFlowDiagnostics.enabled) {
                LOG.warn("MARKFLOW_UI source-native local image read failed")
            }
            safeServerFailure(exchange)
        }
    }

    private fun requireReadMethod(exchange: HttpExchange): String? {
        val method = exchange.requestMethod.uppercase(Locale.ROOT)
        if (method == "GET" || method == "HEAD") {
            return method
        }
        exchange.responseHeaders["Allow"] = "GET, HEAD"
        sendStatus(exchange, 405)
        return null
    }

    private fun resolveStaticFile(rawPath: String): Path? {
        return try {
            val relative = rawPath.removePrefix("/")
            if (relative.isBlank()) return null
            val candidate = root.resolve(relative).normalize()
            if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) return null
            candidate.toRealPath().takeIf { it.startsWith(root) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun staticAssetContentType(rawPath: String): String? =
        when (rawPath.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "js" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> null
        }

    private fun sourceNativeResponseHeaders(exchange: HttpExchange) {
        exchange.responseHeaders["Cache-Control"] = "no-store"
        exchange.responseHeaders["X-Content-Type-Options"] = "nosniff"
        exchange.responseHeaders["Referrer-Policy"] = "no-referrer"
        exchange.responseHeaders["Cross-Origin-Resource-Policy"] = "same-origin"
    }

    private fun sendBytes(exchange: HttpExchange, method: String, bytes: ByteArray) {
        if (method == "HEAD") {
            exchange.responseHeaders["Content-Length"] = bytes.size.toString()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
            return
        }
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun sendFile(exchange: HttpExchange, method: String, target: Path, knownSize: Long? = null) {
        val size = knownSize ?: Files.size(target)
        if (method == "HEAD") {
            exchange.responseHeaders["Content-Length"] = size.toString()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
            return
        }
        exchange.sendResponseHeaders(200, size)
        Files.newInputStream(target).use { input ->
            exchange.responseBody.use { output -> input.copyTo(output) }
        }
    }

    private fun createLocalDocumentResource(documentPath: String): LocalDocumentResource? {
        return try {
            val document = Path.of(documentPath).toAbsolutePath().normalize().toRealPath()
            if (!Files.isRegularFile(document)) return null
            val documentDirectory = document.parent?.toRealPath() ?: return null
            LocalDocumentResource(documentDirectory)
        } catch (ex: Exception) {
            if (MarkFlowDiagnostics.enabled) {
                LOG.debug("MARKFLOW_UI source-native local image root unavailable: ${ex.javaClass.simpleName}")
            }
            null
        }
    }

    private fun mintCapabilityToken(): String {
        val bytes = ByteArray(CAPABILITY_TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun safeServerFailure(exchange: HttpExchange) {
        try {
            sendStatus(exchange, 500)
        } catch (_: IOException) {
            exchange.close()
        }
    }

    private fun sendStatus(exchange: HttpExchange, status: Int) {
        exchange.use { it.sendResponseHeaders(status, -1) }
    }

    private data class LocalDocumentResource(val documentDirectory: Path)

    companion object {
        private val LOG = Logger.getInstance(SourceNativeWebviewHttpBoundary::class.java)
        private const val CAPABILITY_TOKEN_BYTES = 32
        private const val MAX_TOKEN_MINT_ATTEMPTS = 4
    }
}
