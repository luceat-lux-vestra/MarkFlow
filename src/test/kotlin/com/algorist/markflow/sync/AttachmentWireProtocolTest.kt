package com.algorist.markflow.sync

import com.algorist.markflow.document.AuthoritativeDocumentSnapshot
import com.algorist.markflow.document.DocumentMutationRejection
import com.algorist.markflow.document.DocumentRevision
import com.algorist.markflow.document.SourceEdit
import com.algorist.markflow.document.SourceEditCollection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentWireProtocolTest {

    @Test
    fun revisionWireRoundTripPreservesValuesBeyondJavaScriptSafeInteger() {
        val aboveSafeInteger = DocumentRevision.fromWire("9007199254740993")
        val longMax = DocumentRevision.fromWire("9223372036854775807")

        assertEquals("9007199254740993", aboveSafeInteger.toWire())
        assertEquals("9223372036854775807", longMax.toWire())
    }

    @Test
    fun malformedNonCanonicalAndOverflowRevisionsReject() {
        listOf("", " ", "+1", "-1", "01", "1.0", "1e3", "abc", "9223372036854775808")
            .forEach { raw ->
                var rejected = false
                try {
                    DocumentRevision.fromWire(raw)
                } catch (_: IllegalArgumentException) {
                    rejected = true
                }
                assertTrue("expected rejection for $raw", rejected)
            }
    }

    @Test
    fun identitiesRejectBlankControlAndOversizedValues() {
        listOf("", "  ", "attachment\n", "x".repeat(129)).forEach { value ->
            var rejectedAttachment = false
            try {
                AttachmentId.of(value)
            } catch (_: IllegalArgumentException) {
                rejectedAttachment = true
            }
            assertTrue(rejectedAttachment)

            var rejectedRequest = false
            try {
                RequestId.of(value)
            } catch (_: IllegalArgumentException) {
                rejectedRequest = true
            }
            assertTrue(rejectedRequest)

            var rejectedRecovery = false
            try {
                RecoveryId.of(value)
            } catch (_: IllegalArgumentException) {
                rejectedRecovery = true
            }
            assertTrue(rejectedRecovery)
        }
    }

    @Test
    fun typedMessagesRoundTripWithDecimalRevisionAndOrderedEdits() {
        val request = AttachmentWireMessage.MutationRequest(
            attachmentId = AttachmentId.of("attachment-a"),
            requestId = RequestId.of("request-1"),
            baseDocumentRevision = DocumentRevision.fromWire("9007199254740993"),
            edits = SourceEditCollection.of(
                listOf(SourceEdit(1, 1, "😀"), SourceEdit(5, 7, "replacement")),
            ),
        )

        val decoded = AttachmentWireCodec.decode(AttachmentWireCodec.encode(request)) as AttachmentWireDecodeResult.Decoded
        assertEquals(request, decoded.message)
    }

    @Test
    fun bootstrapAndRecoverySnapshotsAreTheOnlyMessagesThatCarrySource() {
        val bootstrap = AttachmentWireMessage.BootstrapSnapshot(
            AttachmentId.of("attachment-a"),
            DocumentRevision.INITIAL,
            "# bootstrap",
        )
        val recovery = AttachmentWireMessage.RecoverySnapshot(
            AttachmentId.of("attachment-a"),
            RecoveryId.of("recovery-1"),
            DocumentRevision(4),
            "# recovery",
        )
        val request = AttachmentWireMessage.SnapshotRequest(
            AttachmentId.of("attachment-a"),
            RecoveryId.of("recovery-1"),
        )

        assertEquals(bootstrap, (AttachmentWireCodec.decode(AttachmentWireCodec.encode(bootstrap)) as AttachmentWireDecodeResult.Decoded).message)
        assertEquals(recovery, (AttachmentWireCodec.decode(AttachmentWireCodec.encode(recovery)) as AttachmentWireDecodeResult.Decoded).message)
        assertEquals(request, (AttachmentWireCodec.decode(AttachmentWireCodec.encode(request)) as AttachmentWireDecodeResult.Decoded).message)
        assertTrue(AttachmentWireCodec.encode(bootstrap).contains("source"))
        assertTrue(AttachmentWireCodec.encode(recovery).contains("source"))
    }

    @Test
    fun normalAckAndIncrementalUpdateNeverContainWholeSource() {
        val ack = AttachmentWireCodec.encode(
            AttachmentWireMessage.MutationAccepted(
                AttachmentId.of("attachment-a"),
                RequestId.of("request-1"),
                DocumentRevision(1),
            ),
        )
        val update = AttachmentWireCodec.encode(
            AttachmentWireMessage.HostIncrementalUpdate(
                AttachmentId.of("attachment-a"),
                DocumentRevision(2),
                SourceEdit(1, 2, "x"),
            ),
        )

        assertFalse(ack.contains("source"))
        assertFalse(update.contains("source"))
        assertTrue(AttachmentWireCodec.decode(ack) is AttachmentWireDecodeResult.Decoded)
        assertTrue(AttachmentWireCodec.decode(update) is AttachmentWireDecodeResult.Decoded)
        assertTrue(
            AttachmentWireCodec.decode(
                ack.replace("}", ",\"source\":\"must-not-be-accepted\"}"),
            ) is AttachmentWireDecodeResult.Rejected,
        )
    }

    @Test
    fun malformedJsonUnknownTypeAndNumericRevisionReject() {
        val malformed = listOf(
            "",
            "not-json",
            "{}{}",
            "{\"type\":\"unknown\"}",
            "{\"type\":\"mutationAccepted\",\"attachmentId\":\"a\",\"requestId\":\"r\",\"finalDocumentRevision\":1}",
            "{\"type\":\"snapshotRequest\",\"attachmentId\":\"a\"}",
            "{\"type\":\"recoverySnapshot\",\"attachmentId\":\"a\",\"documentRevision\":\"0\",\"source\":\"x\"}",
        )
        malformed.forEach { raw ->
            assertTrue(AttachmentWireCodec.decode(raw) is AttachmentWireDecodeResult.Rejected)
        }
    }

    @Test
    fun hostBoundaryRejectsOversizedEditCollectionsIndependently() {
        val tooMany = SourceEditCollection.of(
            (0..AttachmentProtocolBounds.MAX_EDIT_COUNT).map { offset -> SourceEdit(offset * 2, offset * 2, "x") },
        )
        val tooMuchInserted = SourceEditCollection.of(
            SourceEdit(0, 0, "x".repeat(AttachmentProtocolBounds.MAX_INSERTED_UTF16_CODE_UNITS + 1)),
        )

        assertFalse(AttachmentProtocolBounds.validate(tooMany).isNullOrBlank())
        assertFalse(AttachmentProtocolBounds.validate(tooMuchInserted).isNullOrBlank())
        val oversizedEditsJson = (0..AttachmentProtocolBounds.MAX_EDIT_COUNT).joinToString(",") { offset ->
            """{"from":${offset * 2},"to":${offset * 2},"inserted":"x"}"""
        }
        assertTrue(
            AttachmentWireCodec.decode(
                """{"type":"mutationRequest","attachmentId":"a","requestId":"r","baseDocumentRevision":"0","edits":[$oversizedEditsJson]}"""
            ) is AttachmentWireDecodeResult.Rejected,
        )
        val oversizedInsertedMessage = AttachmentWireMessage.MutationRequest(
            AttachmentId.of("a"),
            RequestId.of("r"),
            DocumentRevision.INITIAL,
            tooMuchInserted,
        )
        assertTrue(
            AttachmentWireCodec.decode(AttachmentWireCodec.encode(oversizedInsertedMessage)) is AttachmentWireDecodeResult.Rejected,
        )
    }

    @Test
    fun sourceDiagnosticsRedactPayloads() {
        val secret = "private markdown payload"
        val edit = SourceEdit(1, 2, secret)
        val snapshot = AuthoritativeDocumentSnapshot(DocumentRevision.INITIAL, secret)

        assertFalse(edit.toString().contains(secret))
        assertFalse(SourceEditCollection.of(edit).toString().contains(secret))
        assertFalse(snapshot.toString().contains(secret))
    }

    @Test
    fun resultEncodingMapsTypedRejectionWithoutSource() {
        val attachmentId = AttachmentId.of("attachment-a")
        val requestId = RequestId.of("request-1")
        val accepted = AttachmentWireCodec.encodeResult(
            attachmentId,
            AttachmentMutationResult.Accepted(
                requestId,
                AuthoritativeDocumentSnapshot(DocumentRevision(3), "authoritative source"),
            ),
        )
        assertTrue(accepted.contains("mutationAccepted"))
        assertTrue(accepted.contains("finalDocumentRevision"))
        assertFalse(accepted.contains("authoritative source"))

        val rejected = AttachmentWireCodec.encodeResult(
            attachmentId,
            AttachmentMutationResult.Rejected(
                requestId,
                AttachmentMutationRejection.Conflict("conflict"),
            ),
        )
        assertTrue(rejected.contains("CONFLICT"))
        assertFalse(rejected.contains("conflict"))
        assertTrue(
            AttachmentWireCodec.decode(rejected) is AttachmentWireDecodeResult.Decoded,
        )
    }
}
