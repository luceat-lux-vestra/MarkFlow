package com.algorist.markflow.sync

import com.algorist.markflow.document.AuthoritativeDocumentMutation
import com.algorist.markflow.document.AuthoritativeDocumentSnapshot
import com.algorist.markflow.document.DocumentMutationPolicy
import com.algorist.markflow.document.DocumentMutationPolicyDecision
import com.algorist.markflow.document.DocumentMutationPolicyRejection
import com.algorist.markflow.document.DocumentMutationRejection
import com.algorist.markflow.document.DocumentRevision
import com.algorist.markflow.document.DocumentSession
import com.algorist.markflow.document.InvalidMutationReason
import com.algorist.markflow.document.MutationOrigin
import com.algorist.markflow.document.SourceEdit
import com.algorist.markflow.document.SourceEditCollection
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import java.util.concurrent.Callable

class AttachmentSyncCoordinatorTest : BasePlatformTestCase() {

    fun testValidMutationDelegatesOnceAndReturnsAuthoritativeSnapshot() {
        val document = document("abc def")
        val session = session(document)
        val coordinator = coordinator(session, "attachment-a")

        val result = onEdtResult {
            coordinator.apply(request(coordinator.attachmentId, "request-1", session.revision, 4, 7, "xyz"))
        }

        val accepted = result as AttachmentMutationResult.Accepted
        assertEquals("abc xyz", document.text)
        assertEquals(DocumentRevision(1), accepted.snapshot.revision)
        assertEquals(DocumentRevision(1), session.revision)
        assertEquals(accepted.snapshot.revision, session.lastAuthoritativeMutation?.revision)
        assertEquals(MutationOrigin.WEB, session.lastAuthoritativeMutation?.origin)
    }

    fun testMultiEditRequestUsesOneRequestIdAndDelegatesOnce() {
        val document = document("0123456789")
        val session = session(document)
        val coordinator = coordinator(session, "attachment-a")
        val request = multiEditRequest(
            attachmentId = coordinator.attachmentId,
            requestValue = "transaction-1",
            revision = session.revision,
            edits = listOf(
                SourceEdit(1, 3, "AB"),
                SourceEdit(7, 9, "XY"),
            ),
        )
        var policyCalls = 0

        val first = onEdtResult {
            coordinator.apply(request) { _, proposal ->
                policyCalls += 1
                assertEquals(2, proposal.edits.edits.size)
                DocumentMutationPolicyDecision.Accept
            }
        }
        val duplicate = onEdtResult { coordinator.apply(request) }

        assertTrue(first is AttachmentMutationResult.Accepted)
        assertEquals("0AB3456XY9", document.text)
        assertEquals(DocumentRevision(2), session.revision)
        assertEquals(1, policyCalls)
        assertEquals(
            AttachmentMutationRejection.DuplicateRequest,
            (duplicate as AttachmentMutationResult.Rejected).reason,
        )
    }

    fun testStaleBaseRevisionRejectsWithoutChangingAuthority() {
        val document = document("abc")
        val session = session(document)
        val coordinator = coordinator(session, "attachment-a")
        writeDocument { document.replaceString(0, 3, "host") }
        val before = snapshot(session)

        val result = onEdtResult {
            coordinator.apply(request(coordinator.attachmentId, "request-1", DocumentRevision.INITIAL, 0, 4, "web"))
        }

        val rejected = result as AttachmentMutationResult.Rejected
        val reason = rejected.reason as AttachmentMutationRejection.StaleDocumentRevision
        assertEquals(before, reason.currentSnapshot)
        assertEquals("host", document.text)
        assertEquals(before.revision, session.revision)
    }

    fun testStaleMultiEditRequestRejectsWithoutChangingAuthority() {
        val document = document("0123456789")
        val session = session(document)
        val coordinator = coordinator(session, "attachment-a")
        writeDocument { document.replaceString(0, 1, "H") }
        val before = snapshot(session)
        val request = multiEditRequest(
            attachmentId = coordinator.attachmentId,
            requestValue = "transaction-1",
            revision = DocumentRevision.INITIAL,
            edits = listOf(SourceEdit(1, 3, "AB"), SourceEdit(7, 9, "XY")),
        )

        val result = onEdtResult { coordinator.apply(request) }

        assertEquals(
            before,
            ((result as AttachmentMutationResult.Rejected).reason as AttachmentMutationRejection.StaleDocumentRevision).currentSnapshot,
        )
        assertEquals("H123456789", document.text)
        assertEquals(before.revision, session.revision)
    }

    fun testWrongAttachmentRejectsBeforeDocumentMutation() {
        val document = document("abc")
        val session = session(document)
        val coordinator = coordinator(session, "attachment-a")
        val wrongAttachment = AttachmentId.of("attachment-b")

        val result = onEdtResult {
            coordinator.apply(request(wrongAttachment, "request-1", session.revision, 0, 3, "web"))
        }

        val rejected = result as AttachmentMutationResult.Rejected
        assertEquals(
            AttachmentMutationRejection.WrongAttachment(wrongAttachment),
            rejected.reason,
        )
        assertEquals("abc", document.text)
        assertEquals(DocumentRevision.INITIAL, session.revision)
    }

    fun testWrongAttachmentRejectsMultiEditRequestBeforeDocumentMutation() {
        val document = document("0123456789")
        val session = session(document)
        val coordinator = coordinator(session, "attachment-a")
        val wrongAttachment = AttachmentId.of("attachment-b")
        val request = multiEditRequest(
            attachmentId = wrongAttachment,
            requestValue = "transaction-1",
            revision = session.revision,
            edits = listOf(SourceEdit(1, 3, "AB"), SourceEdit(7, 9, "XY")),
        )

        val result = onEdtResult { coordinator.apply(request) }

        assertEquals(
            AttachmentMutationRejection.WrongAttachment(wrongAttachment),
            (result as AttachmentMutationResult.Rejected).reason,
        )
        assertEquals("0123456789", document.text)
        assertEquals(DocumentRevision.INITIAL, session.revision)
    }

    fun testBlankOrControlIdentitiesCannotBeConstructed() {
        assertInvalidIdentity { AttachmentId.of("   ") }
        assertInvalidIdentity { AttachmentId.of("attachment\n") }
        assertInvalidIdentity { RequestId.of("\t") }
        assertInvalidIdentity { RequestId.of("request\u0000") }
    }

    fun testDuplicateRequestIsRejectedAndCannotDoubleApply() {
        val document = document("abc")
        val session = session(document)
        val coordinator = coordinator(session, "attachment-a")
        val request = request(coordinator.attachmentId, "request-1", session.revision, 0, 1, "x")

        val first = onEdtResult { coordinator.apply(request) }
        val second = onEdtResult { coordinator.apply(request) }

        assertTrue(first is AttachmentMutationResult.Accepted)
        val rejected = second as AttachmentMutationResult.Rejected
        assertEquals(AttachmentMutationRejection.DuplicateRequest, rejected.reason)
        assertEquals("xbc", document.text)
        assertEquals(DocumentRevision(1), session.revision)
    }

    fun testRequestIdentityLedgerPreservesImmediateAndStaleReplaySafety() {
        val document = document("abc")
        val session = session(document)
        val coordinator = coordinator(session, "attachment-a")
        val first = request(coordinator.attachmentId, "request-0", session.revision, 0, 1, "0")
        var last = first

        repeat(2048) { index ->
            last = if (index == 0) {
                first
            } else {
                request(
                    attachmentId = coordinator.attachmentId,
                    requestValue = "request-$index",
                    revision = session.revision,
                    startOffset = 0,
                    endOffset = 1,
                    replacement = index.toString(),
                )
            }
            onEdt { coordinator.apply(last) }
        }

        assertEquals(2048, coordinator.rememberedRequestIdentityCount)
        assertEquals(
            AttachmentMutationRejection.DuplicateRequest,
            (onEdtResult { coordinator.apply(last) } as AttachmentMutationResult.Rejected).reason,
        )
        val oldReplay = onEdtResult { coordinator.apply(first) } as AttachmentMutationResult.Rejected
        assertTrue(oldReplay.reason is AttachmentMutationRejection.StaleDocumentRevision)
        assertEquals(2048, coordinator.rememberedRequestIdentityCount)
    }

    fun testInvalidUtf16RangeMapsToTypedRejectionWithoutMutation() {
        val document = document("abc")
        val session = session(document)
        val coordinator = coordinator(session, "attachment-a")
        val before = snapshot(session)

        val result = onEdtResult {
            coordinator.apply(request(coordinator.attachmentId, "request-1", before.revision, 0, 4, "x"))
        }

        val rejected = result as AttachmentMutationResult.Rejected
        assertEquals(
            AttachmentMutationRejection.InvalidMutation(
                startOffset = 0,
                endOffset = 4,
                reason = InvalidMutationReason.EndBeyondDocument,
            ),
            rejected.reason,
        )
        assertEquals(before, snapshot(session))
        assertEquals("abc", document.text)
    }

    fun testConflictAndUnsupportedFidelityRemainDistinctTypedRejections() {
        val conflictDocument = document("abc", "conflict.md")
        val conflictSession = session(conflictDocument)
        val conflictCoordinator = coordinator(conflictSession, "attachment-conflict")
        val conflict = onEdtResult {
            conflictCoordinator.apply(
                request(conflictCoordinator.attachmentId, "request-conflict", conflictSession.revision, 0, 1, "x"),
                policy = DocumentMutationPolicy { _, _ ->
                    DocumentMutationPolicyDecision.Reject(DocumentMutationPolicyRejection.Conflict("policy"))
                },
            )
        }

        val fidelityDocument = document("abc", "fidelity.md")
        val fidelitySession = session(fidelityDocument)
        val fidelityCoordinator = coordinator(fidelitySession, "attachment-fidelity")
        val fidelity = onEdtResult {
            fidelityCoordinator.apply(
                request(fidelityCoordinator.attachmentId, "request-fidelity", fidelitySession.revision, 0, 1, "x"),
                policy = DocumentMutationPolicy { _, _ ->
                    DocumentMutationPolicyDecision.Reject(
                        DocumentMutationPolicyRejection.UnsupportedFidelity("policy"),
                    )
                },
            )
        }

        assertEquals(
            AttachmentMutationRejection.Conflict("policy"),
            (conflict as AttachmentMutationResult.Rejected).reason,
        )
        assertEquals(
            AttachmentMutationRejection.UnsupportedFidelity("policy"),
            (fidelity as AttachmentMutationResult.Rejected).reason,
        )
        assertEquals("abc", conflictDocument.text)
        assertEquals("abc", fidelityDocument.text)
        assertEquals(DocumentRevision.INITIAL, conflictSession.revision)
        assertEquals(DocumentRevision.INITIAL, fidelitySession.revision)
    }

    fun testInvalidMultiEditPolicyRejectionRemainsTransactionScoped() {
        val document = document("0123456789")
        val session = session(document)
        val coordinator = coordinator(session, "attachment-a")
        val detail = "transaction policy rejected the complete edit set"
        val request = multiEditRequest(
            attachmentId = coordinator.attachmentId,
            requestValue = "transaction-invalid",
            revision = session.revision,
            edits = listOf(SourceEdit(1, 3, "AB"), SourceEdit(7, 9, "XY")),
        )

        val result = onEdtResult {
            coordinator.apply(request) { _, _ ->
                DocumentMutationPolicyDecision.Reject(
                    DocumentMutationPolicyRejection.Invalid(detail),
                )
            }
        }

        assertEquals(
            AttachmentMutationRejection.InvalidTransaction(detail),
            (result as AttachmentMutationResult.Rejected).reason,
        )
        assertEquals("0123456789", document.text)
        assertEquals(DocumentRevision.INITIAL, session.revision)
    }

    fun testNoOpIsAcceptedUnchangedWithoutAuthoritativeEvent() {
        val document = document("same")
        val session = session(document)
        val coordinator = coordinator(session, "attachment-a")

        val result = onEdtResult {
            coordinator.apply(request(coordinator.attachmentId, "request-1", session.revision, 0, 4, "same"))
        }

        val unchanged = result as AttachmentMutationResult.AcceptedUnchanged
        assertEquals(AuthoritativeDocumentSnapshot(DocumentRevision.INITIAL, "same"), unchanged.snapshot)
        assertEquals(DocumentRevision.INITIAL, session.revision)
        assertNull(session.lastAuthoritativeMutation)
    }

    fun testDisposedAttachmentRejectsDeterministicallyWithoutMutation() {
        val document = document("abc")
        val session = session(document)
        val coordinator = coordinator(session, "attachment-a")

        onEdt { coordinator.dispose() }
        val result = onEdtResult {
            coordinator.apply(request(coordinator.attachmentId, "request-1", session.revision, 0, 1, "x"))
        }

        val rejected = result as AttachmentMutationResult.Rejected
        assertEquals(AttachmentMutationRejection.DisposedAttachment, rejected.reason)
        assertEquals("abc", document.text)
        assertEquals(DocumentRevision.INITIAL, session.revision)
        assertTrue(coordinator.isDisposed)
    }

    fun testDisposedAttachmentRejectsMultiEditRequestWithoutMutation() {
        val document = document("0123456789")
        val session = session(document)
        val coordinator = coordinator(session, "attachment-a")
        val request = multiEditRequest(
            attachmentId = coordinator.attachmentId,
            requestValue = "transaction-1",
            revision = session.revision,
            edits = listOf(SourceEdit(1, 3, "AB"), SourceEdit(7, 9, "XY")),
        )

        onEdt { coordinator.dispose() }
        val result = onEdtResult { coordinator.apply(request) }

        assertEquals(
            AttachmentMutationRejection.DisposedAttachment,
            (result as AttachmentMutationResult.Rejected).reason,
        )
        assertEquals("0123456789", document.text)
        assertEquals(DocumentRevision.INITIAL, session.revision)
    }

    fun testIndependentAttachmentsDoNotShareDuplicateState() {
        val document = document("abc")
        val session = session(document)
        val first = coordinator(session, "attachment-a")
        val second = coordinator(session, "attachment-b")

        val firstResult = onEdtResult {
            first.apply(request(first.attachmentId, "same-request", session.revision, 0, 1, "x"))
        }
        val secondResult = onEdtResult {
            second.apply(request(second.attachmentId, "same-request", session.revision, 0, 1, "b"))
        }

        assertTrue(firstResult is AttachmentMutationResult.Accepted)
        assertTrue(secondResult is AttachmentMutationResult.Accepted)
        assertEquals("bbc", document.text)
        assertEquals(DocumentRevision(2), session.revision)
    }

    fun testHostUpdateUsesExactEventProvenance() {
        val document = document("abc")
        val session = session(document)
        writeDocument { document.replaceString(1, 2, "X") }
        val mutation: AuthoritativeDocumentMutation = session.lastAuthoritativeMutation ?: error("missing mutation")

        val update = AuthoritativeHostUpdate.from(AttachmentId.of("attachment-a"), mutation)

        assertEquals(AttachmentId.of("attachment-a"), update.attachmentId)
        assertEquals(mutation.revision, update.revision)
        assertEquals(mutation.edit, update.edit)
        assertSame(mutation.edit, update.edit)
        writeDocument { document.replaceString(0, 1, "z") }
        assertEquals("zXc", snapshot(session).text)
    }

    fun testOriginatingAttachmentSuppressesOwnEventsButOtherAttachmentGetsCanonicalSequence() {
        val document = document("0123456789", "two-attachments.md")
        val session = session(document)
        val first = coordinator(session, "attachment-a")
        val second = coordinator(session, "attachment-b")
        val firstUpdates = mutableListOf<AuthoritativeHostUpdate>()
        val secondUpdates = mutableListOf<AuthoritativeHostUpdate>()

        onEdt {
            AttachmentHostUpdateBinding(first) { firstUpdates += it }.also {
                com.intellij.openapi.util.Disposer.register(testRootDisposable, it)
            }
            AttachmentHostUpdateBinding(second) { secondUpdates += it }.also {
                com.intellij.openapi.util.Disposer.register(testRootDisposable, it)
            }
        }

        onEdtResult {
            first.apply(
                multiEditRequest(
                    attachmentId = first.attachmentId,
                    requestValue = "transaction-1",
                    revision = session.revision,
                    edits = listOf(SourceEdit(1, 3, "AB"), SourceEdit(7, 9, "XY")),
                ),
            )
        }

        assertTrue(firstUpdates.isEmpty())
        assertEquals(listOf(DocumentRevision(1), DocumentRevision(2)), secondUpdates.map { it.revision })
        assertEquals(
            listOf(SourceEdit(7, 9, "XY"), SourceEdit(1, 3, "AB")),
            secondUpdates.map { it.edit },
        )
        assertTrue(secondUpdates.all { it.attachmentId == second.attachmentId })
    }

    fun testDisposedDocumentSessionIsTypedAsDisposedAttachment() {
        val document = document("abc")
        val session = session(document)
        val coordinator = coordinator(session, "attachment-a")
        onEdt { session.dispose() }

        val result = onEdtResult {
            coordinator.apply(request(coordinator.attachmentId, "request-1", session.revision, 0, 1, "x"))
        }

        assertEquals(
            AttachmentMutationRejection.DisposedAttachment,
            (result as AttachmentMutationResult.Rejected).reason,
        )
        assertEquals("abc", document.text)
        assertEquals(DocumentRevision.INITIAL, session.revision)
    }

    fun testMutationHandlingDoesNotHideBackgroundThreadDispatch() {
        val session = session(document("abc"))
        val coordinator = coordinator(session, "attachment-a")
        val failure = ApplicationManager.getApplication().executeOnPooledThread(Callable {
            runCatching {
                coordinator.apply(request(coordinator.attachmentId, "request-1", session.revision, 0, 1, "x"))
            }.exceptionOrNull()
        }).get()

        assertNotNull(failure)
    }

    private fun document(text: String, fileName: String = "attachment-sync.md"): Document =
        myFixture.configureByText(fileName, text).fileDocument

    private fun session(document: Document): DocumentSession = DocumentSession(document, project).also {
        com.intellij.openapi.util.Disposer.register(testRootDisposable, it)
    }

    private fun coordinator(session: DocumentSession, attachmentValue: String): AttachmentSyncCoordinator =
        AttachmentSyncCoordinator(AttachmentId.of(attachmentValue), session).also {
            com.intellij.openapi.util.Disposer.register(testRootDisposable, it)
        }

    private fun request(
        attachmentId: AttachmentId,
        requestValue: String,
        revision: DocumentRevision,
        startOffset: Int,
        endOffset: Int,
        replacement: String,
    ): AttachmentMutationRequest = AttachmentMutationRequest(
        attachmentId = attachmentId,
        requestId = RequestId.of(requestValue),
        baseDocumentRevision = revision,
        edits = SourceEditCollection.of(SourceEdit(startOffset, endOffset, replacement)),
    )

    private fun multiEditRequest(
        attachmentId: AttachmentId,
        requestValue: String,
        revision: DocumentRevision,
        edits: List<SourceEdit>,
    ): AttachmentMutationRequest = AttachmentMutationRequest(
        attachmentId = attachmentId,
        requestId = RequestId.of(requestValue),
        baseDocumentRevision = revision,
        edits = SourceEditCollection.of(edits),
    )

    private fun assertInvalidIdentity(create: () -> Any) {
        assertThrows(IllegalArgumentException::class.java) { create() }
    }

    private fun snapshot(session: DocumentSession): AuthoritativeDocumentSnapshot =
        onEdtResult(session::authoritativeSnapshot)

    private fun writeDocument(mutation: () -> Unit) {
        onEdt { WriteCommandAction.runWriteCommandAction(project, Runnable(mutation)) }
    }

    private fun onEdt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) action()
        else application.invokeAndWait(Runnable(action), application.defaultModalityState)
    }

    private fun <T> onEdtResult(action: () -> T): T {
        var result: T? = null
        onEdt { result = action() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
