package com.algorist.markflow.sync

import com.algorist.markflow.document.DocumentMutationPolicy
import com.algorist.markflow.document.DocumentMutationRejection
import com.algorist.markflow.document.DocumentMutationResult
import com.algorist.markflow.document.DocumentMutationProposal
import com.algorist.markflow.document.DocumentSession
import com.algorist.markflow.document.MutationOrigin
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager

/**
 * Attachment-lifetime coordinator for one consumer of an existing [DocumentSession] authority.
 *
 * This object owns only its attachment identity, duplicate request state and disposed state. It
 * never owns the document, registry entry, persistence, or presentation/runtime resources. Both
 * [apply] and [dispose] are EDT-only and synchronous; this class does not hide thread dispatch.
 */
class AttachmentSyncCoordinator(
    val attachmentId: AttachmentId,
    private val documentSession: DocumentSession,
) : Disposable {
    private val seenRequestIds = LinkedHashSet<RequestId>()
    private var disposed = false

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
        if (!seenRequestIds.add(request.requestId)) {
            return AttachmentMutationResult.Rejected(
                requestId = request.requestId,
                reason = AttachmentMutationRejection.DuplicateRequest,
            )
        }

        val documentResult = documentSession.applyWebProposal(
            proposal = DocumentMutationProposal(
                baseDocumentRevision = request.baseDocumentRevision,
                edit = request.edit,
            ),
            policy = policy,
        )
        return documentResult.toSyncResult(request.requestId)
    }

    /** Invalidates this attachment. The consumed [DocumentSession] is owned elsewhere. */
    override fun dispose() {
        assertEdt()
        if (disposed) return
        disposed = true
        seenRequestIds.clear()
    }

    private fun assertEdt() {
        ApplicationManager.getApplication().assertIsDispatchThread()
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
    is DocumentMutationRejection.Conflict -> AttachmentMutationRejection.Conflict(detail)
    is DocumentMutationRejection.UnsupportedFidelity ->
        AttachmentMutationRejection.UnsupportedFidelity(detail)
}
