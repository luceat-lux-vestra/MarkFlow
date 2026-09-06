package com.algorist.markflow.browser

import java.security.SecureRandom
import java.util.Base64

/**
 * Source-native-only CSP template transformation.
 *
 * The Vite artifact contains a fixed nonce placeholder by design. A production response is valid
 * only after every placeholder occurrence has been proven to be a Vite nonce attribute and replaced
 * with one fresh cryptographically random value. This is defense in depth only: JCEF request
 * interception remains the independent browser request/network authority established by #134.
 */
internal object SourceNativeCspPolicy {
    internal const val BUILD_NONCE_PLACEHOLDER = "__MARKFLOW_SOURCE_NATIVE_CSP_NONCE__"

    private const val NONCE_BYTES = 32
    private const val MIN_EXPECTED_NONCE_MARKERS = 3
    private val secureRandom = SecureRandom()
    private val noncePattern = Regex("^[A-Za-z0-9_-]{43}$")
    private val placeholderPattern = Regex(Regex.escape(BUILD_NONCE_PLACEHOLDER))
    private val placeholderNonceAttribute =
        Regex("""nonce=([\"'])${Regex.escape(BUILD_NONCE_PLACEHOLDER)}\1""")
    private val cspNonceMetaTag =
        Regex("""<meta\b[^>]*\bproperty=([\"'])csp-nonce\1[^>]*>""", RegexOption.IGNORE_CASE)

    internal data class PreparedResponse(
        val html: String,
        val contentSecurityPolicy: String,
    )

    internal fun prepareResponse(builtHtml: String): PreparedResponse? {
        val nonce = mintNonce()
        val html = replaceBuildNoncePlaceholder(builtHtml, nonce) ?: return null
        return PreparedResponse(
            html = html,
            contentSecurityPolicy = contentSecurityPolicy(nonce),
        )
    }

    internal fun replaceBuildNoncePlaceholder(builtHtml: String, nonce: String): String? {
        if (!noncePattern.matches(nonce) || nonce == BUILD_NONCE_PLACEHOLDER) {
            return null
        }

        val allPlaceholderCount = placeholderPattern.findAll(builtHtml).count()
        val nonceAttributeCount = placeholderNonceAttribute.findAll(builtHtml).count()
        if (allPlaceholderCount < MIN_EXPECTED_NONCE_MARKERS || allPlaceholderCount != nonceAttributeCount) {
            return null
        }

        val metaTags = cspNonceMetaTag.findAll(builtHtml).toList()
        if (metaTags.size != 1 || !placeholderNonceAttribute.containsMatchIn(metaTags.single().value)) {
            return null
        }

        val transformed = builtHtml.replace(BUILD_NONCE_PLACEHOLDER, nonce)
        if (placeholderPattern.containsMatchIn(transformed)) {
            return null
        }
        return transformed
    }

    internal fun contentSecurityPolicy(nonce: String): String {
        require(noncePattern.matches(nonce) && nonce != BUILD_NONCE_PLACEHOLDER)
        return listOf(
            "default-src 'none'",
            "script-src 'nonce-$nonce' 'strict-dynamic'",
            "style-src 'none'",
            "style-src-elem 'nonce-$nonce'",
            // CodeMirror uses narrowly scoped inline style attributes for editor geometry. Element
            // styles remain nonce-bound, and #134 independently denies browser network widening.
            "style-src-attr 'unsafe-inline'",
            "img-src 'self'",
            "font-src 'self'",
            "connect-src 'none'",
            "object-src 'none'",
            "frame-src 'none'",
            "worker-src 'none'",
            "media-src 'none'",
            "manifest-src 'none'",
            "base-uri 'none'",
            "form-action 'none'",
            "frame-ancestors 'none'",
        ).joinToString("; ")
    }

    private fun mintNonce(): String {
        val bytes = ByteArray(NONCE_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
