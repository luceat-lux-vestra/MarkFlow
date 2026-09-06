package com.algorist.markflow.browser

import com.sun.net.httpserver.HttpExchange
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

/**
 * Source-native-only HTML response hardening.
 *
 * The built HTML contains only a Vite-controlled nonce placeholder. Every successful document
 * response replaces that placeholder with a fresh random nonce and binds the same nonce into the
 * CSP header. Static assets and legacy Crepe responses stay on the existing serving path; #134's
 * JCEF request handler remains an independent browser-network boundary.
 */
internal object SourceNativeCspResourceHandler {
    internal const val ENTRY_PATH = "/source-native.html"
    internal const val NONCE_PLACEHOLDER = "__MARKFLOW_CSP_NONCE__"

    private const val NONCE_BYTES = 32
    private val secureRandom = SecureRandom()
    private val nonceAttribute = Regex("""\bnonce="([^"]*)"""")
    private val metaTag = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)

    fun serve(exchange: HttpExchange, root: Path) {
        if (exchange.requestURI?.path != ENTRY_PATH) {
            sendStatus(exchange, 404)
            return
        }

        val method = exchange.requestMethod.uppercase(Locale.ROOT)
        if (method != "GET" && method != "HEAD") {
            exchange.responseHeaders["Allow"] = "GET, HEAD"
            sendStatus(exchange, 405)
            return
        }

        val target = root.resolve(ENTRY_PATH.removePrefix("/")).normalize()
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            sendStatus(exchange, 404)
            return
        }

        try {
            val template = Files.readString(target, StandardCharsets.UTF_8)
            val nonce = mintNonce()
            val rendered = renderTemplate(template, nonce)
            if (rendered == null) {
                sendStatus(exchange, 500)
                return
            }
            val bytes = rendered.toByteArray(StandardCharsets.UTF_8)

            exchange.responseHeaders["Content-Type"] = "text/html; charset=utf-8"
            exchange.responseHeaders["Cache-Control"] = "no-store"
            exchange.responseHeaders["X-Content-Type-Options"] = "nosniff"
            exchange.responseHeaders["Referrer-Policy"] = "no-referrer"
            exchange.responseHeaders["Cross-Origin-Resource-Policy"] = "same-origin"
            exchange.responseHeaders["Content-Security-Policy"] = contentSecurityPolicy(nonce)

            if (method == "HEAD") {
                exchange.responseHeaders["Content-Length"] = bytes.size.toString()
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
                return
            }

            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { output -> output.write(bytes) }
        } catch (_: IOException) {
            try {
                sendStatus(exchange, 500)
            } catch (_: IOException) {
                exchange.close()
            }
        }
    }

    /**
     * Fail closed unless every nonce-bearing build tag still carries exactly the configured Vite
     * placeholder and exactly one csp-nonce meta tag exists. The placeholder may not occur in any
     * other HTML text because replacement there could create an unintended authority channel.
     */
    internal fun renderTemplate(template: String, nonce: String): String? {
        if (!isValidNonce(nonce) || nonce == NONCE_PLACEHOLDER) return null

        val nonceMatches = nonceAttribute.findAll(template).toList()
        if (nonceMatches.size < 2 || nonceMatches.any { it.groupValues[1] != NONCE_PLACEHOLDER }) {
            return null
        }

        val cspMetaTags = metaTag.findAll(template)
            .map { it.value }
            .filter { it.contains("property=\"csp-nonce\"", ignoreCase = true) }
            .toList()
        if (cspMetaTags.size != 1) return null
        val metaNonce = nonceAttribute.find(cspMetaTags.single())?.groupValues?.get(1)
        if (metaNonce != NONCE_PLACEHOLDER) return null

        if (countOccurrences(template, NONCE_PLACEHOLDER) != nonceMatches.size) return null

        val rendered = template.replace(NONCE_PLACEHOLDER, nonce)
        return rendered.takeIf { !it.contains(NONCE_PLACEHOLDER) }
    }

    internal fun contentSecurityPolicy(nonce: String): String {
        require(isValidNonce(nonce) && nonce != NONCE_PLACEHOLDER)
        return listOf(
            "default-src 'none'",
            "script-src 'nonce-$nonce' 'strict-dynamic'",
            "style-src 'none'",
            "style-src-elem 'nonce-$nonce'",
            // CodeMirror performs bounded layout updates through style attributes. This is the only
            // inline-style compatibility exception; executable content remains nonce-only.
            "style-src-attr 'unsafe-inline'",
            "img-src 'self'",
            "font-src 'self'",
            "connect-src 'none'",
            "object-src 'none'",
            "frame-src 'none'",
            "child-src 'none'",
            "worker-src 'none'",
            "media-src 'none'",
            "manifest-src 'none'",
            "base-uri 'none'",
            "form-action 'none'",
            "frame-ancestors 'none'",
        ).joinToString("; ")
    }

    internal fun isValidNonce(nonce: String): Boolean =
        nonce.length == 43 && nonce.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    private fun mintNonce(): String {
        val bytes = ByteArray(NONCE_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun countOccurrences(value: String, needle: String): Int {
        var count = 0
        var offset = 0
        while (true) {
            val index = value.indexOf(needle, offset)
            if (index < 0) return count
            count += 1
            offset = index + needle.length
        }
    }

    private fun sendStatus(exchange: HttpExchange, status: Int) {
        exchange.use { it.sendResponseHeaders(status, -1) }
    }
}
