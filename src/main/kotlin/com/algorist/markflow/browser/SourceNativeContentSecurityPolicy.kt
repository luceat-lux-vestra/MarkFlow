package com.algorist.markflow.browser

import java.security.SecureRandom
import java.util.Base64

/** Pure source-native CSP and nonce-template policy; it performs no HTTP I/O. */
internal object SourceNativeContentSecurityPolicy {
    const val NONCE_PLACEHOLDER = "__MARKFLOW_CSP_NONCE__"

    private const val NONCE_BYTES = 32
    private val secureRandom = SecureRandom()
    private val nonceAttribute = Regex("""\bnonce="([^"]*)"""")
    private val metaTag = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val validNonce = Regex("^[A-Za-z0-9_-]{43}$")

    fun mintNonce(): String {
        val bytes = ByteArray(NONCE_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun isValidNonce(nonce: String): Boolean = validNonce.matches(nonce) && nonce != NONCE_PLACEHOLDER

    /**
     * Fail closed unless every nonce-bearing build tag still carries exactly the configured Vite
     * placeholder and exactly one `meta[property="csp-nonce"]` tag exists. The placeholder may not
     * occur outside nonce attributes, avoiding accidental replacement into unrelated HTML text.
     */
    fun renderDocument(template: String, nonce: String): String? {
        if (!isValidNonce(nonce)) return null

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

    fun headerValue(nonce: String): String {
        require(isValidNonce(nonce))
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
}
