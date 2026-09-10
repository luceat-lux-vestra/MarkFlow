package com.algorist.markflow.trust

import java.net.URI

/** Host-owned allowlist for explicit external navigation. */
internal object ExternalNavigationPolicy {
    internal const val MAX_URL_LENGTH = 4096

    /** Return an authorized absolute HTTP(S) URI, or null without guessing/canonicalizing. */
    fun validateHttpUrl(raw: String): URI? {
        if (raw.isEmpty() || raw.length > MAX_URL_LENGTH) return null
        if (raw.any { Character.isISOControl(it) || Character.isWhitespace(it) }) return null

        return try {
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (!uri.isAbsolute || uri.isOpaque || uri.host.isNullOrBlank()) return null
            if (uri.rawUserInfo != null) return null
            uri
        } catch (_: Exception) {
            null
        }
    }
}
