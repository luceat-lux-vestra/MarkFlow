package com.algorist.markflow.sync

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.algorist.markflow.document.DocumentMutationRejection
import com.algorist.markflow.document.DocumentRevision
import com.algorist.markflow.document.SourceEdit
import com.algorist.markflow.document.SourceEditCollection
import java.io.StringReader

/** Explicit target wire messages. The discriminator is the first protocol decision. */
sealed interface AttachmentWireMessage {
    data class BootstrapSnapshot(
        val attachmentId: AttachmentId,
        val documentRevision: DocumentRevision,
        val source: String,
    ) : AttachmentWireMessage {
        override fun toString(): String =
            "BootstrapSnapshot(attachmentId=$attachmentId, documentRevision=$documentRevision, sourceLength=${source.length})"
    }

    data class RecoverySnapshot(
        val attachmentId: AttachmentId,
        val documentRevision: DocumentRevision,
        val source: String,
    ) : AttachmentWireMessage {
        override fun toString(): String =
            "RecoverySnapshot(attachmentId=$attachmentId, documentRevision=$documentRevision, sourceLength=${source.length})"
    }

    data class SnapshotRequest(
        val attachmentId: AttachmentId,
    ) : AttachmentWireMessage

    data class MutationRequest(
        val attachmentId: AttachmentId,
        val requestId: RequestId,
        val baseDocumentRevision: DocumentRevision,
        val edits: SourceEditCollection,
    ) : AttachmentWireMessage

    data class MutationAccepted(
        val attachmentId: AttachmentId,
        val requestId: RequestId,
        val finalDocumentRevision: DocumentRevision,
    ) : AttachmentWireMessage

    data class MutationAcceptedUnchanged(
        val attachmentId: AttachmentId,
        val requestId: RequestId,
        val finalDocumentRevision: DocumentRevision,
    ) : AttachmentWireMessage

    data class MutationRejected(
        val attachmentId: AttachmentId,
        val requestId: RequestId,
        val category: MutationRejectionCategory,
    ) : AttachmentWireMessage

    data class HostIncrementalUpdate(
        val attachmentId: AttachmentId,
        val documentRevision: DocumentRevision,
        val edit: SourceEdit,
    ) : AttachmentWireMessage
}

enum class MutationRejectionCategory {
    STALE_DOCUMENT_REVISION,
    DUPLICATE_REQUEST,
    WRONG_ATTACHMENT,
    DISPOSED,
    INVALID_MUTATION,
    INVALID_TRANSACTION,
    CONFLICT,
    UNSUPPORTED_FIDELITY,
    INTERNAL_FAILURE,
}

sealed interface AttachmentWireDecodeResult {
    data class Decoded(val message: AttachmentWireMessage) : AttachmentWireDecodeResult

    data object Rejected : AttachmentWireDecodeResult
}

/**
 * Strict codec for the bounded attachment transport. It intentionally has no compatibility or
 * fallback parser: missing, extra, malformed, numeric-revision, and unknown fields reject.
 */
object AttachmentWireCodec {
    private val gson = Gson()

    fun encode(message: AttachmentWireMessage): String = gson.toJson(message.toJson())

    fun encodeResult(
        attachmentId: AttachmentId,
        result: AttachmentMutationResult,
    ): String = encode(
        when (result) {
            is AttachmentMutationResult.Accepted -> AttachmentWireMessage.MutationAccepted(
                attachmentId = attachmentId,
                requestId = result.requestId,
                finalDocumentRevision = result.snapshot.revision,
            )
            is AttachmentMutationResult.AcceptedUnchanged -> AttachmentWireMessage.MutationAcceptedUnchanged(
                attachmentId = attachmentId,
                requestId = result.requestId,
                finalDocumentRevision = result.snapshot.revision,
            )
            is AttachmentMutationResult.Rejected -> AttachmentWireMessage.MutationRejected(
                attachmentId = attachmentId,
                requestId = result.requestId,
                category = result.reason.toWireCategory(),
            )
        },
    )

    fun decode(raw: String): AttachmentWireDecodeResult {
        return try {
            val reader = JsonReader(StringReader(raw))
            val root = JsonParser.parseReader(reader)
            require(reader.peek() == JsonToken.END_DOCUMENT)
            require(root.isJsonObject)
            val json = root.asJsonObject
            val type = json.string("type")
            AttachmentWireDecodeResult.Decoded(
                when (type) {
                    "bootstrapSnapshot" -> json.snapshot { id, revision, source ->
                        AttachmentWireMessage.BootstrapSnapshot(id, revision, source)
                    }
                    "recoverySnapshot" -> json.snapshot { id, revision, source ->
                        AttachmentWireMessage.RecoverySnapshot(id, revision, source)
                    }
                    "snapshotRequest" -> {
                        json.requireKeys("type", "attachmentId")
                        AttachmentWireMessage.SnapshotRequest(AttachmentId.of(json.string("attachmentId")))
                    }
                    "mutationRequest" -> json.mutationRequest()
                    "mutationAccepted" -> json.accepted { id, request, revision ->
                        AttachmentWireMessage.MutationAccepted(id, request, revision)
                    }
                    "mutationAcceptedUnchanged" -> json.accepted { id, request, revision ->
                        AttachmentWireMessage.MutationAcceptedUnchanged(id, request, revision)
                    }
                    "mutationRejected" -> {
                        json.requireKeys("type", "attachmentId", "requestId", "category")
                        AttachmentWireMessage.MutationRejected(
                            attachmentId = AttachmentId.of(json.string("attachmentId")),
                            requestId = RequestId.of(json.string("requestId")),
                            category = MutationRejectionCategory.valueOf(json.string("category")),
                        )
                    }
                    "hostIncrementalUpdate" -> {
                        json.requireKeys("type", "attachmentId", "documentRevision", "edit")
                        AttachmentWireMessage.HostIncrementalUpdate(
                            attachmentId = AttachmentId.of(json.string("attachmentId")),
                            documentRevision = DocumentRevision.fromWire(json.string("documentRevision")),
                            edit = json.edit("edit"),
                        )
                    }
                    else -> error("unknown attachment message type")
                },
            )
        } catch (_: Exception) {
            AttachmentWireDecodeResult.Rejected
        }
    }

    private fun AttachmentWireMessage.toJson(): JsonObject = when (this) {
        is AttachmentWireMessage.BootstrapSnapshot -> snapshotJson(
            type = "bootstrapSnapshot",
            attachmentId = attachmentId,
            revision = documentRevision,
            source = source,
        )
        is AttachmentWireMessage.RecoverySnapshot -> snapshotJson(
            type = "recoverySnapshot",
            attachmentId = attachmentId,
            revision = documentRevision,
            source = source,
        )
        is AttachmentWireMessage.SnapshotRequest -> JsonObject().apply {
            addProperty("type", "snapshotRequest")
            addProperty("attachmentId", attachmentId.value)
        }
        is AttachmentWireMessage.MutationRequest -> JsonObject().apply {
            addProperty("type", "mutationRequest")
            addProperty("attachmentId", attachmentId.value)
            addProperty("requestId", requestId.value)
            addProperty("baseDocumentRevision", baseDocumentRevision.toWire())
            add("edits", editsJson(edits))
        }
        is AttachmentWireMessage.MutationAccepted -> acceptedJson(
            "mutationAccepted", attachmentId, requestId, finalDocumentRevision,
        )
        is AttachmentWireMessage.MutationAcceptedUnchanged -> acceptedJson(
            "mutationAcceptedUnchanged", attachmentId, requestId, finalDocumentRevision,
        )
        is AttachmentWireMessage.MutationRejected -> JsonObject().apply {
            addProperty("type", "mutationRejected")
            addProperty("attachmentId", attachmentId.value)
            addProperty("requestId", requestId.value)
            addProperty("category", category.name)
        }
        is AttachmentWireMessage.HostIncrementalUpdate -> JsonObject().apply {
            addProperty("type", "hostIncrementalUpdate")
            addProperty("attachmentId", attachmentId.value)
            addProperty("documentRevision", documentRevision.toWire())
            add("edit", editJson(edit))
        }
    }

    private fun snapshotJson(
        type: String,
        attachmentId: AttachmentId,
        revision: DocumentRevision,
        source: String,
    ): JsonObject = JsonObject().apply {
        addProperty("type", type)
        addProperty("attachmentId", attachmentId.value)
        addProperty("documentRevision", revision.toWire())
        addProperty("source", source)
    }

    private fun acceptedJson(
        type: String,
        attachmentId: AttachmentId,
        requestId: RequestId,
        revision: DocumentRevision,
    ): JsonObject = JsonObject().apply {
        addProperty("type", type)
        addProperty("attachmentId", attachmentId.value)
        addProperty("requestId", requestId.value)
        addProperty("finalDocumentRevision", revision.toWire())
    }

    private fun editsJson(edits: SourceEditCollection): JsonArray = JsonArray().apply {
        edits.edits.forEach { add(editJson(it)) }
    }

    private fun editJson(edit: SourceEdit): JsonObject = JsonObject().apply {
        addProperty("from", edit.startOffset)
        addProperty("to", edit.endOffset)
        addProperty("inserted", edit.replacement)
    }

    private fun JsonObject.snapshot(
        create: (AttachmentId, DocumentRevision, String) -> AttachmentWireMessage,
    ): AttachmentWireMessage {
        requireKeys("type", "attachmentId", "documentRevision", "source")
        return create(
            AttachmentId.of(string("attachmentId")),
            DocumentRevision.fromWire(string("documentRevision")),
            string("source"),
        )
    }

    private fun JsonObject.mutationRequest(): AttachmentWireMessage.MutationRequest {
        requireKeys("type", "attachmentId", "requestId", "baseDocumentRevision", "edits")
        val editsElement = get("edits")
        require(editsElement.isJsonArray)
        val array = editsElement.asJsonArray
        require(array.size() in 1..AttachmentProtocolBounds.MAX_EDIT_COUNT)
        val edits = array.map { element ->
            require(element.isJsonObject)
            element.asJsonObject.editValue()
        }
        val collection = SourceEditCollection.of(edits)
        require(AttachmentProtocolBounds.validate(collection) == null)
        return AttachmentWireMessage.MutationRequest(
            attachmentId = AttachmentId.of(string("attachmentId")),
            requestId = RequestId.of(string("requestId")),
            baseDocumentRevision = DocumentRevision.fromWire(string("baseDocumentRevision")),
            edits = collection,
        )
    }

    private fun JsonObject.accepted(
        create: (AttachmentId, RequestId, DocumentRevision) -> AttachmentWireMessage,
    ): AttachmentWireMessage {
        requireKeys("type", "attachmentId", "requestId", "finalDocumentRevision")
        return create(
            AttachmentId.of(string("attachmentId")),
            RequestId.of(string("requestId")),
            DocumentRevision.fromWire(string("finalDocumentRevision")),
        )
    }

    private fun JsonObject.edit(name: String): SourceEdit {
        val element = get(name)
        require(element != null && element.isJsonObject)
        return element.asJsonObject.editValue()
    }

    private fun JsonObject.editValue(): SourceEdit {
        requireKeys("from", "to", "inserted")
        return SourceEdit(
            startOffset = int("from"),
            endOffset = int("to"),
            replacement = string("inserted"),
        )
    }

    private fun JsonObject.string(name: String): String {
        val element = get(name)
        require(element != null && element.isJsonPrimitive && element.asJsonPrimitive.isString)
        return element.asString
    }

    private fun JsonObject.int(name: String): Int {
        val element = get(name)
        require(element != null && element.isJsonPrimitive && element.asJsonPrimitive.isNumber)
        val value = element.asString
        require(value.matches(Regex("-?(0|[1-9][0-9]*)")))
        return value.toLong().also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    }

    private fun JsonObject.requireKeys(vararg expected: String) {
        require(keySet() == expected.toSet())
    }
}

private fun DocumentMutationRejection.toWireCategory(): MutationRejectionCategory = when (this) {
    is DocumentMutationRejection.StaleRevision -> MutationRejectionCategory.STALE_DOCUMENT_REVISION
    is DocumentMutationRejection.InvalidMutation -> MutationRejectionCategory.INVALID_MUTATION
    is DocumentMutationRejection.InvalidTransaction -> MutationRejectionCategory.INVALID_TRANSACTION
    is DocumentMutationRejection.Conflict -> MutationRejectionCategory.CONFLICT
    is DocumentMutationRejection.UnsupportedFidelity -> MutationRejectionCategory.UNSUPPORTED_FIDELITY
}

private fun AttachmentMutationRejection.toWireCategory(): MutationRejectionCategory = when (this) {
    is AttachmentMutationRejection.WrongAttachment -> MutationRejectionCategory.WRONG_ATTACHMENT
    AttachmentMutationRejection.DuplicateRequest -> MutationRejectionCategory.DUPLICATE_REQUEST
    AttachmentMutationRejection.DisposedAttachment -> MutationRejectionCategory.DISPOSED
    is AttachmentMutationRejection.StaleDocumentRevision -> MutationRejectionCategory.STALE_DOCUMENT_REVISION
    is AttachmentMutationRejection.InvalidMutation -> MutationRejectionCategory.INVALID_MUTATION
    is AttachmentMutationRejection.InvalidTransaction -> MutationRejectionCategory.INVALID_TRANSACTION
    is AttachmentMutationRejection.Conflict -> MutationRejectionCategory.CONFLICT
    is AttachmentMutationRejection.UnsupportedFidelity -> MutationRejectionCategory.UNSUPPORTED_FIDELITY
    AttachmentMutationRejection.InternalFailure -> MutationRejectionCategory.INTERNAL_FAILURE
}
