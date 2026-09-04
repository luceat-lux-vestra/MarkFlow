package com.algorist.markflow.sync

import com.algorist.markflow.document.AuthoritativeDocumentMutation
import com.algorist.markflow.document.DocumentMutationPolicy
import com.algorist.markflow.document.DocumentMutationRejection
import com.algorist.markflow.document.DocumentMutationResult
import com.algorist.markflow.document.DocumentMutationProposal
import com.algorist.markflow.document.DocumentSession
import com.algorist.markflow.document.MutationOrigin
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import java.util.LinkedHashSet

/**
 * Attachment-lifetime coordinator for one consumer of an existing [DocumentSession] authority.
 *
 * This object owns only its attachment identity, duplicate request state and disposed state. It
 * never owns the document, registry entry, persistence, or presentation/runtime resources. Both
 * [apply] and [dispose] are EDT-only and synchronous; this class does not hide thread dispatch.
 *
 * Request identity retention is exact for the complete live attachment lifetime. The ledger is
 * explicitly bounded; exhaustion terminalizes this attachment instead of evicting an old identity
 * and permitting a later RequestId collision to become a fresh mutation.
 */
class AttachmentSyncCoordinator(
    val attachmentId: AttachmentId,
    internal val documentSession: DocumentSession,
    private val requestIdentityCapacity: Int = AttachmentProtocolBounds.MAX_REQUEST_IDENTITIES_PER_ATTACHMENT,
) : Disposable {
    private val seenRequestIds = LinkedHashSet<RequestId>()
    private var lastCompletedRequestId: RequestId? = null
    private var disposed = false
    private val activeRequestIdContext = ThreadLocal<RequestId?>()

    init {
        require(requestIdentityCapacity > 0) { "request identity capacity must be positive" }
    }

    /** Total exact RequestIds retained for this attachment lifetime. */
    internal val rememberedRequestIdentityCount: Int
        get() = seenRequestIds.size

    /**
     * Legacy hot-slot instrumentation retained for the earlier duplicate test. Total bounded
     * identity retention is exposed by [rememberedRequestIdentityCount].
     */
    internal val retainedRequestIdentityCount: Int
        get() = if (lastCompletedRequestId == null) 0 else 1

    val isDisposed: Boolean
        get() = disposed

    /**
     * Validate attachment/lifetime/request state and delegate the accepted proposal to
     * [DocumentSession.applyWebProposal]. The document session remains the sole mutation and
     * revision authority.
     */
    fun apply(
        request: AttachmentMutationRequest,
        policy: DocumentMutationPolicy = DocumentMutationPolicy.ACCEPT,
    ): AttachmentMutationResult {
        assertEdt()

        if (disposed || documentSession.isDisposed) {
            return AttachmentMutationResult.Rejected(
                requestId = request.requestId,
                reason = AttachmentMutationRejection.DisposedAttachment,
            )
        }
        if (request.attachmentId != attachmentId) {
            return AttachmentMutationResult.Rejected(
                requestId = request.requestId,
                reason = AttachmentMutationRejection.WrongAttachment(request.attachmentId),
            )
        }

        val requestAlreadySeen = request.requestId in seenRequestIds
        if (request.requestId == lastCompletedRequestId
            || (requestAlreadySeen && request.baseDocumentRevision == documentSession.revision)) {
            return AttachmentMutationResult.Rejected(
                requestId = request.requestId,
                reason = AttachmentMutationRejection.DuplicateRequest,
            )
        }

        if (!requestAlreadySeen) {
            if (seenRequestIds.size >= requestIdentityCapacity) {
                terminalize()
                return AttachmentMutationResult.Rejected(
                    requestId = request.requestId,
                    reason = AttachmentMutationRejection.DisposedAttachment,
                )
            }
            seenRequestIds.add(request.requestId)
        }

        return try {
            val boundError = AttachmentProtocolBounds.validate(request.edits)
            if (boundError != null) {
                AttachmentMutationResult.Rejected(
                    requestId = request.requestId,
                    reason = AttachmentMutationRejection.InvalidTransaction(boundError),
                )
            } else {
                val documentResult = withActiveRequest(request.requestId) {
                    documentSession.applyWebProposal(
                        proposal = DocumentMutationProposal(
                            baseDocumentRevision = request.baseDocumentRevision,
                            edits = request.edits,
                        ),
                        policy = policy,
                    )
                }
                documentResult.toSyncResult(request.requestId)
            }
        } finally {
            lastCompletedRequestId = request.requestId
        }
    }

    /** Invalidates this attachment. The consumed [DocumentSession] is owned elsewhere. */
    override fun dispose() {
        assertEdt()
        if (disposed) return
        terminalize()
    }

    private fun terminalize() {
        disposed = true
        seenRequestIds.clear()
        lastCompletedRequestId = null
        activeRequestIdContext.remove()
    }

    private fun assertEdt() {
        ApplicationManager.getApplication().assertIsDispatchThread()
    }

    internal fun suppressOwnWebMutation(mutation: AuthoritativeDocumentMutation): Boolean =
        mutation.origin == MutationOrigin.WEB && activeRequestIdContext.get() != null

    private fun <T> withActiveRequest(requestId: RequestId, action: () -> T): T {
        val previous = activeRequestIdContext.get()
        activeRequestIdContext.set(requestId)
        return try {
            action()
        } finally {
            if (previous == null) activeRequestIdContext.remove() else activeRequestIdContext.set(previous)
        }
    }
}

/**
 * Attachment-owned observer that exposes canonical host events as exact incremental updates.
 * The originating attachment suppresses only its own synchronous WEB execution scope; other
 * attachments receive every real DocumentEvent in order.
 */
class AttachmentHostUpdateBinding(
    private val coordinator: AttachmentSyncCoordinator,
    private val onUpdate: (AuthoritativeHostUpdate) -> Unit,
) : Disposable {
    private var disposed = false

    init {
        ApplicationManager.getApplication().assertIsDispatchThread()
        coordinator.documentSession.addMutationListener({ mutation ->
            if (!disposed && !coordinator.isDisposed && !coordinator.suppressOwnWebMutation(mutation)) {
                onUpdate(AuthoritativeHostUpdate.from(coordinator.attachmentId, mutation))
            }
        }, this)
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
    }
}

private fun DocumentMutationResult.toSyncResult(requestId: RequestId): AttachmentMutationResult = when (this) {
    is DocumentMutationResult.Accepted -> {
        if (origin != MutationOrigin.WEB) {
            AttachmentMutationResult.Rejected(
                requestId,
                AttachmentMutationRejection.InternalFailure,
            )
        } else {
            AttachmentMutationResult.Accepted(requestId, snapshot)
        }
    }

    is DocumentMutationResult.AcceptedUnchanged -> {
        if (origin != MutationOrigin.WEB) {
            AttachmentMutationResult.Rejected(
                requestId,
                AttachmentMutationRejection.InternalFailure,
            )
        } else {
            AttachmentMutationResult.AcceptedUnchanged(requestId, snapshot)
        }
    }

    is DocumentMutationResult.Rejected -> AttachmentMutationResult.Rejected(
        requestId = requestId,
        reason = reason.toSyncRejection(),
    )
}

private fun DocumentMutationRejection.toSyncRejection(): AttachmentMutationRejection = when (this) {
    is DocumentMutationRejection.StaleRevision ->
        AttachmentMutationRejection.StaleDocumentRevision(currentSnapshot)
    is DocumentMutationRejection.InvalidMutation ->
        AttachmentMutationRejection.InvalidMutation(edit.startOffset, edit.endOffset, reason)
    is DocumentMutationRejection.InvalidTransaction ->
        AttachmentMutationRejection.InvalidTransaction(detail)
    is DocumentMutationRejection.Conflict -> AttachmentMutationRejection.Conflict(detail)
    is DocumentMutationRejection.UnsupportedFidelity ->
        AttachmentMutationRejection.UnsupportedFidelity(detail)
}
