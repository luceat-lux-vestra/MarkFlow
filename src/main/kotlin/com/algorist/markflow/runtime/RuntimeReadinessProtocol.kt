package com.algorist.markflow.runtime

import com.algorist.markflow.sync.AttachmentId
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/**
 * One decoded, validated `runtimeReady` handshake signal from the current browser realm.
 *
 * This is a narrow lifecycle handshake distinct from the merged #102 [com.algorist.markflow.sync]
 * mutation/recovery wire protocol: it carries no [com.algorist.markflow.document.DocumentRevision],
 * no `RequestId`, and no `RecoveryId`. Its only purpose is to let the owning
 * [SourceNativeEditorRuntime] confirm that the exact current realm/attachment finished
 * constructing its web-side runtime before any `BootstrapSnapshot` is sent. It carries no
 * speculative protocol-version field.
 */
internal data class RuntimeReadySignal(
    val attachmentId: AttachmentId,
    val runtimeToken: String,
)

/**
 * Strict decoder for the readiness handshake wire shape. Missing/extra/blank/oversized/control-
 * character fields, non-object payloads, and any other shape reject to `null` rather than being
 * guessed into a signal.
 */
internal object RuntimeReadinessCodec {
    private const val MAX_TOKEN_LENGTH = 128
    private const val MESSAGE_TYPE = "runtimeReady"
    private val EXPECTED_KEYS = setOf("type", "attachmentId", "runtimeToken")

    fun decode(raw: String): RuntimeReadySignal? {
        return try {
            val reader = JsonReader(StringReader(raw))
            val root = JsonParser.parseReader(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT || !root.isJsonObject) return null

            val json = root.asJsonObject
            if (json.keySet() != EXPECTED_KEYS) return null

            val type = json.stringOrNull("type") ?: return null
            if (type != MESSAGE_TYPE) return null

            val attachmentIdRaw = json.stringOrNull("attachmentId") ?: return null
            val runtimeToken = json.stringOrNull("runtimeToken") ?: return null
            if (!isValidToken(runtimeToken)) return null

            val attachmentId = try {
                AttachmentId.of(attachmentIdRaw)
            } catch (_: IllegalArgumentException) {
                return null
            }

            RuntimeReadySignal(attachmentId, runtimeToken)
        } catch (_: Exception) {
            null
        }
    }

    private fun isValidToken(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_TOKEN_LENGTH && value.none(Character::isISOControl)

    private fun com.google.gson.JsonObject.stringOrNull(name: String): String? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return element.asString
    }
}
