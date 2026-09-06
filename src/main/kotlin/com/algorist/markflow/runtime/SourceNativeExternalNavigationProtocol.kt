package com.algorist.markflow.runtime

import com.algorist.markflow.sync.AttachmentId
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.net.URI

/** One bounded external-navigation request from the exact current source-native browser realm. */
internal data class SourceNativeExternalNavigationRequest(
    val attachmentId: AttachmentId,
    val runtimeToken: String,
    val url: String,
)

/**
 * Strict decoder and host-side URL policy for the #82 external-navigation capability.
 *
 * This protocol is intentionally independent from AttachmentWireCodec: navigation carries no
 * source revision, mutation, request/recovery identity, or document authority. Browser-side URL
 * checks are defense in depth only; this host policy is the final authorization boundary.
 */
internal object SourceNativeExternalNavigationProtocol {
    private const val MESSAGE_TYPE = "openExternal"
    internal const val MAX_URL_LENGTH = 4096
    internal const val MAX_MESSAGE_LENGTH = 8192
    private const val MAX_RUNTIME_TOKEN_LENGTH = 128
    private val EXPECTED_KEYS = setOf("type", "attachmentId", "runtimeToken", "url")

    fun decode(raw: String): SourceNativeExternalNavigationRequest? {
        if (raw.length > MAX_MESSAGE_LENGTH) return null

        return try {
            val reader = JsonReader(StringReader(raw))
            val root = JsonParser.parseReader(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT || !root.isJsonObject) return null

            val json = root.asJsonObject
            if (json.keySet() != EXPECTED_KEYS) return null
            if (json.stringOrNull("type") != MESSAGE_TYPE) return null

            val attachmentIdRaw = json.stringOrNull("attachmentId") ?: return null
            val runtimeToken = json.stringOrNull("runtimeToken") ?: return null
            val url = json.stringOrNull("url") ?: return null
            if (!isValidRuntimeToken(runtimeToken) || validateHttpUrl(url) == null) return null

            val attachmentId = try {
                AttachmentId.of(attachmentIdRaw)
            } catch (_: IllegalArgumentException) {
                return null
            }

            SourceNativeExternalNavigationRequest(attachmentId, runtimeToken, url)
        } catch (_: Exception) {
            null
        }
    }

    /** Return a host-authorized absolute HTTP(S) URI, or null without guessing/canonicalizing. */
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

    private fun isValidRuntimeToken(value: String): Boolean =
        value.isNotBlank()
            && value.length <= MAX_RUNTIME_TOKEN_LENGTH
            && value.none(Character::isISOControl)

    private fun com.google.gson.JsonObject.stringOrNull(name: String): String? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return element.asString
    }
}
