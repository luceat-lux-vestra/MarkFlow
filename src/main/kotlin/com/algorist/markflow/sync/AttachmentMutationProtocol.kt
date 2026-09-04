package com.algorist.markflow.sync

import com.algorist.markflow.document.AuthoritativeDocumentMutation
import com.algorist.markflow.document.AuthoritativeDocumentSnapshot
import com.algorist.markflow.document.DocumentMutationRejection
import com.algorist.markflow.document.DocumentRevision
import com.algorist.markflow.document.InvalidMutationReason
import com.algorist.markflow.document.SourceEdit
import com.algorist.markflow.document.SourceEditCollection

/** Conservative target envelope enforced independently at the host protocol boundary. */
object AttachmentProtocolBounds {
    const val MAX_EDIT_COUNT = 1024
    const val MAX_INSERTED_UTF16_CODE_UNITS = 4 * 1024 * 1024
    const val MAX_IDENTITY_LENGTH = 128

    /**
     * Exact duplicate identity retention is bounded by the attachment lifetime itself.
     *
     * The entry count is derived from the existing 4 Mi UTF-16 protocol envelope and the maximum
     * identity length: even if every retained RequestId is 128 UTF-16 code units, retained ID text
     * cannot exceed 4 Mi code units and the number of retained objects is also finite. Reaching
     * the bound terminalizes the attachment instead of evicting history; the runtime owner may
     * replace it with a fresh [AttachmentId].
     */
    const val MAX_REQUEST_IDENTITIES_PER_ATTACHMENT =
        MAX_INSERTED_UTF16_CODE_UNITS / MAX_IDENTITY_LENGTH

    fun validate(edits: SourceEditCollection): String? {
        if (edits.edits.size > MAX_EDIT_COUNT) {
            return "edit count exceeds the target envelope"
        }
        if (edits.edits.sumOf { it.replacement.length.toLong() } > MAX_INSERTED_UTF16_CODE_UNITS) {
            return "inserted UTF-16 length exceeds the target envelope"
        }
        return null
    }
}

/**
 * Typed host-side request for one source-native transaction of explicit UTF-16 source edits.
 *
 * The attachment and request identities are correlation/lifetime dimensions. The
 * [baseDocumentRevision] is the only freshness input, and [edits] is the ordered pre-transaction
 * collection delegated once to the document authority.
 */
data class AttachmentMutationRequest(
    val attachmentId: AttachmentId,
    val requestId: RequestId,
    val baseDocumentRevision: DocumentRevision,
    val edits: SourceEditCollection,
)

/** Result of validating and applying one [AttachmentMutationRequest] transaction. */
sealed interface AttachmentMutationResult {
    val requestId: RequestId

    /** A source-changing mutation accepted by the document authority. */
    data class Accepted(
        override val requestId: RequestId,
        val snapshot: AuthoritativeDocumentSnapshot,
    ) : AttachmentMutationResult

    /** An accepted request that caused no source mutation and no revision advance. */
    data class AcceptedUnchanged(
        override val requestId: RequestId,
        val snapshot: AuthoritativeDocumentSnapshot,
    ) : AttachmentMutationResult

    /** A fail-closed outcome. The rejection category is intentionally typed and exhaustive. */
    data class Rejected(
        override val requestId: RequestId,
        val reason: AttachmentMutationRejection,
    ) : AttachmentMutationResult
}

/**
 * Rejection categories owned by the attachment protocol boundary.
 *
 * Document freshness, range validation, conflict and fidelity decisions are mapped directly from
 * [DocumentMutationRejection]. Attachment lifetime and duplicate state are owned only by the
 * attachment coordinator. No rejection logs or stores source/replacement content.
 */
sealed interface AttachmentMutationRejection {
    data class WrongAttachment(
        val requestedAttachmentId: AttachmentId,
    ) : AttachmentMutationRejection

    data object DuplicateRequest : AttachmentMutationRejection

    data object DisposedAttachment : AttachmentMutationRejection

    data class StaleDocumentRevision(
        val currentSnapshot: AuthoritativeDocumentSnapshot,
    ) : AttachmentMutationRejection

    data class InvalidMutation(
        val startOffset: Int,
        val endOffset: Int,
        val reason: InvalidMutationReason,
    ) : AttachmentMutationRejection

    /** A transaction-scoped rejection with no single offending edit. */
    data class InvalidTransaction(
        val detail: String,
    ) : AttachmentMutationRejection

    data class Conflict(
        val detail: String? = null,
    ) : AttachmentMutationRejection

    data class UnsupportedFidelity(
        val detail: String? = null,
    ) : AttachmentMutationRejection

    /** An impossible result invariant; no source payload is retained in the diagnostic category. */
    data object InternalFailure : AttachmentMutationRejection
}

/**
 * Minimal host-to-web authoritative update seam for later transport integration.
 *
 * [from] copies the exact immutable values carried by the document-domain event. It does not
 * read the live Document and does not reconstruct a whole-document edit.
 */
data class AuthoritativeHostUpdate(
    val attachmentId: AttachmentId,
    val revision: DocumentRevision,
    val edit: SourceEdit,
) {
    companion object {
        fun from(
            attachmentId: AttachmentId,
            mutation: AuthoritativeDocumentMutation,
        ): AuthoritativeHostUpdate =
            AuthoritativeHostUpdate(
                attachmentId = attachmentId,
                revision = mutation.revision,
                edit = mutation.edit,
            )
    }
}
