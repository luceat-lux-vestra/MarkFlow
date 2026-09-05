package com.algorist.markflow.runtime

import com.algorist.markflow.sync.AttachmentId
import com.algorist.markflow.sync.AttachmentMutationRequest
import com.algorist.markflow.sync.AttachmentSyncCoordinator
import com.algorist.markflow.sync.AttachmentWireCodec
import com.algorist.markflow.sync.AttachmentWireDecodeResult
import com.algorist.markflow.sync.AttachmentWireMessage
import com.intellij.openapi.application.ApplicationManager

/**
 * Strict EDT-only glue between the raw merged #102 target wire codec and one runtime's
 * [AttachmentSyncCoordinator].
 *
 * Every branch either returns the exact strict encoded typed response or `null`. A `null` result
 * must never be reinterpreted as success by the caller: it fail-closes a malformed/unknown
 * payload, a message addressed to a different attachment, or a message travelling in the wrong
 * direction (only [AttachmentWireMessage.MutationRequest] and [AttachmentWireMessage.SnapshotRequest]
 * are valid web -> host messages on this transport).
 *
 * This object has no JCEF/browser dependency and no thread-dispatch responsibility of its own;
 * callers must already be on the EDT, exactly as [AttachmentSyncCoordinator.apply] requires.
 */
internal object SourceNativeAttachmentTransportHandler {
    fun handle(
        raw: String,
        attachmentId: AttachmentId,
        coordinator: AttachmentSyncCoordinator,
    ): String? {
        ApplicationManager.getApplication().assertIsDispatchThread()

        val decoded = AttachmentWireCodec.decode(raw)
        if (decoded !is AttachmentWireDecodeResult.Decoded) return null

        return when (val message = decoded.message) {
            is AttachmentWireMessage.MutationRequest -> {
                if (message.attachmentId != attachmentId) return null
                val result = coordinator.apply(
                    AttachmentMutationRequest(
                        attachmentId = message.attachmentId,
                        requestId = message.requestId,
                        baseDocumentRevision = message.baseDocumentRevision,
                        edits = message.edits,
                    ),
                )
                AttachmentWireCodec.encodeResult(attachmentId, result)
            }

            is AttachmentWireMessage.SnapshotRequest -> {
                if (message.attachmentId != attachmentId || coordinator.isDisposed) return null
                val snapshot = coordinator.documentSession.authoritativeSnapshot()
                AttachmentWireCodec.encode(
                    AttachmentWireMessage.RecoverySnapshot(
                        attachmentId = attachmentId,
                        recoveryId = message.recoveryId,
                        documentRevision = snapshot.revision,
                        source = snapshot.text,
                    ),
                )
            }

            // BootstrapSnapshot / RecoverySnapshot / MutationAccepted / MutationAcceptedUnchanged /
            // MutationRejected / HostIncrementalUpdate are host -> web message shapes. Receiving
            // one of them from the web direction is never valid and never a success.
            else -> null
        }
    }
}
