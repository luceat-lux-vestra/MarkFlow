package com.algorist.markflow.sync

import com.algorist.markflow.document.DocumentRevision
import com.algorist.markflow.document.DocumentSession
import com.algorist.markflow.document.SourceEdit
import com.algorist.markflow.document.SourceEditCollection
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class AttachmentRequestIdentityBoundTest : BasePlatformTestCase() {

    fun testEvictedStyleRequestIdReuseAtCurrentRevisionCannotMutate() {
        val document = document("abc")
        val session = session(document)
        val coordinator = coordinator(session, capacity = 3)

        val first = onEdtResult {
            coordinator.apply(request(coordinator.attachmentId, "request-1", session.revision, 0, 1, "x"))
        }
        assertTrue(first is AttachmentMutationResult.Accepted)

        val second = onEdtResult {
            coordinator.apply(request(coordinator.attachmentId, "request-2", session.revision, 0, 1, "y"))
        }
        assertTrue(second is AttachmentMutationResult.Accepted)
        assertEquals("ybc", document.text)
        assertEquals(DocumentRevision(2), session.revision)
        assertEquals(2, coordinator.rememberedRequestIdentityCount)

        val reused = onEdtResult {
            coordinator.apply(request(coordinator.attachmentId, "request-1", session.revision, 0, 1, "z"))
        } as AttachmentMutationResult.Rejected

        assertEquals(AttachmentMutationRejection.DuplicateRequest, reused.reason)
        assertEquals("ybc", document.text)
        assertEquals(DocumentRevision(2), session.revision)
        assertEquals(2, coordinator.rememberedRequestIdentityCount)
    }

    fun testRequestIdentityCapacityTerminalizesInsteadOfEvictingHistory() {
        val document = document("abc")
        val session = session(document)
        val coordinator = coordinator(session, capacity = 2)

        assertTrue(onEdtResult {
            coordinator.apply(request(coordinator.attachmentId, "request-1", session.revision, 0, 1, "x"))
        } is AttachmentMutationResult.Accepted)
        assertTrue(onEdtResult {
            coordinator.apply(request(coordinator.attachmentId, "request-2", session.revision, 0, 1, "y"))
        } is AttachmentMutationResult.Accepted)
        assertEquals(2, coordinator.rememberedRequestIdentityCount)

        val exhausted = onEdtResult {
            coordinator.apply(request(coordinator.attachmentId, "request-3", session.revision, 0, 1, "z"))
        } as AttachmentMutationResult.Rejected

        assertEquals(AttachmentMutationRejection.DisposedAttachment, exhausted.reason)
        assertTrue(coordinator.isDisposed)
        assertEquals(0, coordinator.rememberedRequestIdentityCount)
        assertEquals("ybc", document.text)
        assertEquals(DocumentRevision(2), session.revision)

        val lateOld = onEdtResult {
            coordinator.apply(request(coordinator.attachmentId, "request-1", session.revision, 0, 1, "q"))
        } as AttachmentMutationResult.Rejected
        assertEquals(AttachmentMutationRejection.DisposedAttachment, lateOld.reason)
        assertEquals("ybc", document.text)
    }

    private fun document(text: String): Document =
        myFixture.configureByText("attachment-request-bound.md", text).fileDocument

    private fun session(document: Document): DocumentSession = DocumentSession(document, project).also {
        com.intellij.openapi.util.Disposer.register(testRootDisposable, it)
    }

    private fun coordinator(session: DocumentSession, capacity: Int): AttachmentSyncCoordinator =
        AttachmentSyncCoordinator(
            attachmentId = AttachmentId.of("attachment-a"),
            documentSession = session,
            requestIdentityCapacity = capacity,
        ).also {
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

    private fun <T> onEdtResult(action: () -> T): T {
        var result: T? = null
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            result = action()
        } else {
            application.invokeAndWait(
                Runnable { result = action() },
                application.defaultModalityState,
            )
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
