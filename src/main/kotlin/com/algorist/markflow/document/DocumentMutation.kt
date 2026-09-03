package com.algorist.markflow.document

/** Metadata describing the source-side owner of an authoritative document mutation. */
enum class MutationOrigin {
    IDE_HOST,
    WEB,
    UNDO_REDO,
    EXTERNAL_VFS,
}

/**
 * Immutable logical Markdown state observed from the authoritative IntelliJ [DocumentSession].
 *
 * The text is read from the live IntelliJ Document when the snapshot is created. It is not a
 * persistence-format or raw-file-byte cache.
 */
data class AuthoritativeDocumentSnapshot(
    val revision: DocumentRevision,
    val text: String,
)

/**
 * An explicit UTF-16 source-range replacement, using IntelliJ Document offset semantics.
 *
 * Bounds are intentionally validated by [DocumentSession] inside the mutation transaction so an
 * invalid proposal can produce a typed rejection instead of failing during proposal creation.
 */
data class SourceEdit(
    val startOffset: Int,
    val endOffset: Int,
    val replacement: String,
)

/** A web/domain proposal that is explicitly based on one document revision and source range. */
data class DocumentMutationProposal(
    val baseDocumentRevision: DocumentRevision,
    val edit: SourceEdit,
)

/** Metadata for the one canonical revision transition observed from a DocumentEvent. */
data class AuthoritativeDocumentMutation(
    val revision: DocumentRevision,
    val origin: MutationOrigin,
    val edit: SourceEdit,
    /**
     * The authoritative snapshot produced synchronously with the DocumentEvent.
     *
     * Consumers can reconcile from this immutable value without reading the live IntelliJ
     * Document from an arbitrary callback thread.
     */
    val snapshot: AuthoritativeDocumentSnapshot,
)

/** Receives one synchronous authoritative mutation transition from a DocumentSession. */
fun interface AuthoritativeDocumentMutationListener {
    fun onMutation(mutation: AuthoritativeDocumentMutation)
}

/** Result of applying a document-domain web proposal. */
sealed interface DocumentMutationResult {
    data class Accepted(
        val snapshot: AuthoritativeDocumentSnapshot,
        val origin: MutationOrigin,
    ) : DocumentMutationResult

    /** An accepted proposal whose replacement equals the current source and caused no event. */
    data class AcceptedUnchanged(
        val snapshot: AuthoritativeDocumentSnapshot,
        val origin: MutationOrigin,
    ) : DocumentMutationResult

    data class Rejected(
        val reason: DocumentMutationRejection,
    ) : DocumentMutationResult
}

/** Typed rejection reasons. Rejection never mutates the authoritative Document or revision. */
sealed interface DocumentMutationRejection {
    data class StaleRevision(
        val currentSnapshot: AuthoritativeDocumentSnapshot,
    ) : DocumentMutationRejection

    data class InvalidMutation(
        val edit: SourceEdit,
        val reason: InvalidMutationReason,
    ) : DocumentMutationRejection

    data class Conflict(
        val detail: String? = null,
    ) : DocumentMutationRejection

    data class UnsupportedFidelity(
        val detail: String? = null,
    ) : DocumentMutationRejection
}

sealed interface InvalidMutationReason {
    data object NegativeStartOffset : InvalidMutationReason
    data object EndBeforeStart : InvalidMutationReason
    data object EndBeyondDocument : InvalidMutationReason
    data class PolicyRejected(val detail: String) : InvalidMutationReason
}

/** A policy decision made after revision and range checks, but before the write command. */
sealed interface DocumentMutationPolicyDecision {
    data object Accept : DocumentMutationPolicyDecision

    data class Reject(
        val reason: DocumentMutationPolicyRejection,
    ) : DocumentMutationPolicyDecision
}

sealed interface DocumentMutationPolicyRejection {
    data class Invalid(val detail: String) : DocumentMutationPolicyRejection
    data class Conflict(val detail: String? = null) : DocumentMutationPolicyRejection
    data class UnsupportedFidelity(val detail: String? = null) : DocumentMutationPolicyRejection
}

/**
 * Apply-before-write validation seam for source fidelity and future conflict policy.
 *
 * A policy must be deterministic and side-effect free. It receives only an authoritative
 * snapshot and an explicit range proposal; it cannot mutate the Document. Stale revision and
 * range validation remain owned by [DocumentSession].
 */
fun interface DocumentMutationPolicy {
    fun validate(
        snapshot: AuthoritativeDocumentSnapshot,
        proposal: DocumentMutationProposal,
    ): DocumentMutationPolicyDecision

    companion object {
        val ACCEPT: DocumentMutationPolicy = DocumentMutationPolicy { _, _ ->
            DocumentMutationPolicyDecision.Accept
        }
    }
}
