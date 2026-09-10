package com.algorist.markflow.runtime

import com.algorist.markflow.sync.AttachmentId
import com.algorist.markflow.trust.ExternalNavigationPolicy
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
 * Strict decoder for the temporary source-native browser navigation bridge.
 *
 * URL authorization delegates to the host-owned #147 policy so the migration bridge and native
 * editor cannot drift on scheme/credential/host rules. The protocol still carries no source
 * mutation or document authority and remains temporary until #154.
 */
internal object SourceNativeExternalNavigationProtocol {
    private const val MESSAGE_TYPE = "openExternal"
    internal const val MAX_URL_LENGTH = ExternalNavigationPolicy.MAX_URL_LENGTH
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

    /** Compatibility delegate for temporary browser-runtime callers/tests. */
    fun validateHttpUrl(raw: String): URI? = ExternalNavigationPolicy.validateHttpUrl(raw)

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
