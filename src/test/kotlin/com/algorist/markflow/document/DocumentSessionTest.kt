package com.algorist.markflow.document

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandEvent
import com.intellij.openapi.command.CommandListener
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.util.Disposer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

class DocumentSessionTest : BasePlatformTestCase() {

    fun testSourceEditCollectionStringifiesWithoutReplacementPayload() {
        val replacement = "do-not-log-this-markdown"
        val collection = SourceEditCollection.of(
            listOf(
                SourceEdit(1, 2, replacement),
                SourceEdit(4, 4, "x"),
            ),
        )

        val rendered = collection.toString()
        assertTrue(replacement !in rendered)
        assertTrue("size=2" in rendered)
        assertTrue("range=1..2" in rendered)
        assertTrue("replacementLength=${replacement.length}" in rendered)
    }

    fun testInitialSnapshotUsesDocumentTextAndZeroRevision() {
        val document = document("hello")
        val session = session(document)

        assertEquals(DocumentRevision.INITIAL, session.revision)
        assertEquals(
            AuthoritativeDocumentSnapshot(DocumentRevision.INITIAL, "hello"),
            snapshot(session),
        )
        assertNull(session.lastAuthoritativeMutation)
    }

    fun testDirectIdeMutationAdvancesExactlyOnce() {
        val document = document("hello")
        val session = session(document)

        writeDocument { document.replaceString(0, 5, "world") }

        assertEquals("world", document.text)
        assertEquals(DocumentRevision(1), session.revision)
        assertEquals(MutationOrigin.IDE_HOST, session.lastAuthoritativeMutation?.origin)
        assertEquals(DocumentRevision(1), session.lastAuthoritativeMutation?.revision)
    }

    fun testAcceptedWebRangeMutationAdvancesExactlyOnceAndUsesExactRange() {
        val document = document("abc def")
        val session = session(document)

        val result = onEdtResult {
            session.applyWebProposal(
                DocumentMutationProposal(
                    baseDocumentRevision = session.revision,
                    edits = SourceEditCollection.of(
                        SourceEdit(startOffset = 4, endOffset = 7, replacement = "xyz"),
                    ),
                ),
            )
        }

        val accepted = result as DocumentMutationResult.Accepted
        assertEquals("abc xyz", document.text)
        assertEquals(DocumentRevision(1), accepted.snapshot.revision)
        assertEquals(DocumentRevision(1), session.revision)
        assertEquals(MutationOrigin.WEB, accepted.origin)
        assertEquals(MutationOrigin.WEB, session.lastAuthoritativeMutation?.origin)
    }

    fun testMultiEditProposalUsesPreTransactionCoordinatesAndOneCommandBoundary() {
        val document = document("0123456789")
        val session = session(document)
        val observed = mutableListOf<AuthoritativeDocumentMutation>()
        val commands = mutableListOf<CommandEvent>()
        val owner = listenerOwner()

        onEdt {
            session.addMutationListener(
                AuthoritativeDocumentMutationListener { observed += it },
                owner,
            )
            ApplicationManager.getApplication().messageBus.connect(owner).subscribe(
                CommandListener.TOPIC,
                object : CommandListener {
                    override fun commandStarted(event: CommandEvent) {
                        commands += event
                    }
                },
            )
        }

        val result = onEdtResult {
            session.applyWebProposal(
                proposal(
                    session.revision,
                    SourceEdit(1, 3, "AB"),
                    SourceEdit(7, 9, "XY"),
                ),
            )
        } as DocumentMutationResult.Accepted

        assertEquals("0AB3456XY9", document.text)
        assertEquals(DocumentRevision(2), result.snapshot.revision)
        assertEquals(DocumentRevision(2), session.revision)
        assertEquals(
            listOf(SourceEdit(7, 9, "XY"), SourceEdit(1, 3, "AB")),
            observed.map { it.edit },
        )
        assertEquals(
            listOf(
                AuthoritativeDocumentSnapshot(DocumentRevision(1), "0123456XY9"),
                AuthoritativeDocumentSnapshot(DocumentRevision(2), "0AB3456XY9"),
            ),
            observed.map { it.snapshot },
        )
        assertEquals(listOf(MutationOrigin.WEB, MutationOrigin.WEB), observed.map { it.origin })
        assertEquals(1, commands.size)
    }

    fun testInsertionReplacementAndDeletionUsePreTransactionUtf16Coordinates() {
        val document = document("abcdef")
        val session = session(document)

        val result = onEdtResult {
            session.applyWebProposal(
                proposal(
                    session.revision,
                    SourceEdit(1, 1, "X"),
                    SourceEdit(2, 4, "YZ"),
                    SourceEdit(4, 6, ""),
                ),
            )
        } as DocumentMutationResult.Accepted

        assertEquals("aXbYZ", result.snapshot.text)
        assertEquals(DocumentRevision(3), result.snapshot.revision)
        assertEquals(DocumentRevision(3), session.revision)
    }

    fun testSamePositionInsertionsPreserveDeclaredListOrder() {
        val document = document("ab")
        val session = session(document)
        val observed = mutableListOf<AuthoritativeDocumentMutation>()
        val owner = listenerOwner()

        onEdt {
            session.addMutationListener(
                AuthoritativeDocumentMutationListener { observed += it },
                owner,
            )
        }

        val result = onEdtResult {
            session.applyWebProposal(
                proposal(
                    session.revision,
                    SourceEdit(1, 1, "X"),
                    SourceEdit(1, 1, "Y"),
                ),
            )
        } as DocumentMutationResult.Accepted

        assertEquals("aXYb", result.snapshot.text)
        assertEquals(listOf(SourceEdit(1, 1, "Y"), SourceEdit(1, 1, "X")), observed.map { it.edit })
        assertEquals(DocumentRevision(2), result.snapshot.revision)
    }

    fun testUtf16OffsetsRemainDocumentCompatibleAcrossMultiEditProposal() {
        val document = document("a😀bc")
        val session = session(document)

        val result = onEdtResult {
            session.applyWebProposal(
                proposal(
                    session.revision,
                    SourceEdit(1, 3, "X"),
                    SourceEdit(4, 5, "Y"),
                ),
            )
        } as DocumentMutationResult.Accepted

        assertEquals("aXbY", result.snapshot.text)
        assertEquals(DocumentRevision(2), result.snapshot.revision)
    }

    fun testAuthoritativeObservationIsExactlyOnceOrderedAndSnapshotConsistent() {
        val document = document("abc")
        val session = session(document)
        val observed = mutableListOf<AuthoritativeDocumentMutation>()
        val owner = listenerOwner()

        onEdt {
            session.addMutationListener(
                AuthoritativeDocumentMutationListener { observed += it },
                owner,
            )
        }

        writeDocument { document.replaceString(0, 1, "x") }
        writeDocument { document.replaceString(1, 2, "y") }

        assertEquals(listOf(DocumentRevision(1), DocumentRevision(2)), observed.map { it.revision })
        assertEquals(listOf(MutationOrigin.IDE_HOST, MutationOrigin.IDE_HOST), observed.map { it.origin })
        assertEquals(SourceEdit(0, 1, "x"), observed[0].edit)
        assertEquals(SourceEdit(1, 2, "y"), observed[1].edit)
        assertEquals(AuthoritativeDocumentSnapshot(DocumentRevision(1), "xbc"), observed[0].snapshot)
        assertEquals(AuthoritativeDocumentSnapshot(DocumentRevision(2), "xyc"), observed[1].snapshot)

        onEdt { Disposer.dispose(owner) }
    }

    fun testAcceptedWebMutationEmitsOneAuthoritativeObservationBeforeResult() {
        val document = document("abc")
        val session = session(document)
        val observed = mutableListOf<AuthoritativeDocumentMutation>()
        val owner = listenerOwner()

        onEdt {
            session.addMutationListener(
                AuthoritativeDocumentMutationListener { observed += it },
                owner,
            )
        }
        val result = onEdtResult {
            session.applyWebProposal(
                proposal(session.revision, SourceEdit(1, 2, "X")),
            )
        } as DocumentMutationResult.Accepted

        assertEquals(1, observed.size)
        assertEquals(result.snapshot, observed.single().snapshot)
        assertEquals(result.snapshot.revision, observed.single().revision)
        assertEquals(MutationOrigin.WEB, observed.single().origin)
        assertEquals(SourceEdit(1, 2, "X"), observed.single().edit)

        onEdt { Disposer.dispose(owner) }
    }

    fun testObserverFailureIsIsolatedAndAcceptedWebResultStillReturns() {
        val document = document("abc")
        val session = session(document)
        val observed = mutableListOf<AuthoritativeDocumentMutation>()
        val failingOwner = listenerOwner()
        val observingOwner = listenerOwner()

        onEdt {
            session.addMutationListener(
                AuthoritativeDocumentMutationListener {
                    throw IllegalStateException("observer failure")
                },
                failingOwner,
            )
            session.addMutationListener(
                AuthoritativeDocumentMutationListener { observed += it },
                observingOwner,
            )
        }

        val result = onEdtResult {
            session.applyWebProposal(
                proposal(session.revision, SourceEdit(1, 2, "X")),
            )
        } as DocumentMutationResult.Accepted

        assertEquals(DocumentRevision(1), session.revision)
        assertEquals(1, observed.size)
        assertEquals(result.snapshot, observed.single().snapshot)
        assertEquals(result.snapshot, snapshot(session))

        onEdt {
            Disposer.dispose(failingOwner)
            Disposer.dispose(observingOwner)
        }
    }

    fun testNoOpAndRejectedProposalsEmitNoAuthoritativeObservation() {
        val document = document("abc")
        val session = session(document)
        val observed = mutableListOf<AuthoritativeDocumentMutation>()
        val owner = listenerOwner()

        onEdt {
            session.addMutationListener(
                AuthoritativeDocumentMutationListener { observed += it },
                owner,
            )
        }

        onEdtResult {
            session.applyWebProposal(
                proposal(session.revision, SourceEdit(0, 3, "abc")),
            )
        }
        onEdtResult {
            session.applyWebProposal(
                proposal(session.revision, SourceEdit(-1, 1, "x")),
            )
        }
        onEdtResult {
            session.applyWebProposal(
                proposal(session.revision, SourceEdit(0, 3, "x")),
                policy = DocumentMutationPolicy { _, _ ->
                    DocumentMutationPolicyDecision.Reject(
                        DocumentMutationPolicyRejection.UnsupportedFidelity("unsupported"),
                    )
                },
            )
        }
        onEdtResult {
            session.applyWebProposal(
                proposal(session.revision, SourceEdit(0, 3, "x")),
                policy = DocumentMutationPolicy { _, _ ->
                    DocumentMutationPolicyDecision.Reject(
                        DocumentMutationPolicyRejection.Conflict("conflict"),
                    )
                },
            )
        }
        onEdtResult {
            session.applyWebProposal(
                proposal(session.revision, SourceEdit(0, 1, "x")),
            )
        }
        onEdtResult {
            session.applyWebProposal(
                proposal(DocumentRevision.INITIAL, SourceEdit(0, 1, "y")),
            )
        }

        assertEquals(1, observed.size)
        assertEquals(DocumentRevision(1), observed.single().revision)
        assertEquals("xbc", observed.single().snapshot.text)
        assertEquals(DocumentRevision(1), session.revision)

        onEdt { Disposer.dispose(owner) }
    }

    fun testLiveSnapshotRejectsBackgroundReadWithoutIntellijAccess() {
        val session = session(document("abc"))
        val failure = ApplicationManager.getApplication().executeOnPooledThread(Callable {
            runCatching { session.authoritativeSnapshot() }.exceptionOrNull()
        }).get()

        assertNotNull(failure)
    }

    fun testStaleProposalIsTypedAndLeavesDocumentAndRevisionUnchanged() {
        val document = document("abc")
        val session = session(document)
        writeDocument { document.replaceString(0, 3, "new") }
        val before = snapshot(session)

        val result = onEdtResult {
            session.applyWebProposal(
                proposal(
                    DocumentRevision.INITIAL,
                    SourceEdit(0, 1, "w"),
                    SourceEdit(2, 3, "b"),
                ),
            )
        }

        val rejected = result as DocumentMutationResult.Rejected
        val rejection = rejected.reason as DocumentMutationRejection.StaleRevision
        assertEquals(before, rejection.currentSnapshot)
        assertEquals("new", document.text)
        assertEquals(before.revision, session.revision)
    }

    fun testOneInvalidConstituentRejectsEntireTransactionBeforeAnyWrite() {
        val document = document("abcdef")
        val session = session(document)
        val observed = mutableListOf<AuthoritativeDocumentMutation>()
        val owner = listenerOwner()

        onEdt {
            session.addMutationListener(
                AuthoritativeDocumentMutationListener { observed += it },
                owner,
            )
        }

        val result = onEdtResult {
            session.applyWebProposal(
                proposal(
                    session.revision,
                    SourceEdit(0, 1, "A"),
                    SourceEdit(10, 11, "X"),
                ),
            )
        }

        val rejection = (result as DocumentMutationResult.Rejected).reason as DocumentMutationRejection.InvalidMutation
        assertEquals(InvalidMutationReason.EndBeyondDocument, rejection.reason)
        assertEquals("abcdef", document.text)
        assertEquals(DocumentRevision.INITIAL, session.revision)
        assertTrue(observed.isEmpty())
    }

    fun testOverlappingAndUnorderedEditSetsRejectBeforeMutation() {
        val overlappingDocument = document("abcdef", "overlap.md")
        val overlappingSession = session(overlappingDocument)
        val overlapping = onEdtResult {
            overlappingSession.applyWebProposal(
                proposal(
                    overlappingSession.revision,
                    SourceEdit(1, 4, "X"),
                    SourceEdit(3, 5, "Y"),
                ),
            )
        }

        val unorderedDocument = document("abcdef", "unordered.md")
        val unorderedSession = session(unorderedDocument)
        val unordered = onEdtResult {
            unorderedSession.applyWebProposal(
                proposal(
                    unorderedSession.revision,
                    SourceEdit(4, 5, "Y"),
                    SourceEdit(1, 2, "X"),
                ),
            )
        }

        assertEquals(
            InvalidMutationReason.OverlappingEdits,
            ((overlapping as DocumentMutationResult.Rejected).reason as DocumentMutationRejection.InvalidMutation).reason,
        )
        assertEquals(
            InvalidMutationReason.UnorderedEdits,
            ((unordered as DocumentMutationResult.Rejected).reason as DocumentMutationRejection.InvalidMutation).reason,
        )
        assertEquals("abcdef", overlappingDocument.text)
        assertEquals("abcdef", unorderedDocument.text)
        assertEquals(DocumentRevision.INITIAL, overlappingSession.revision)
        assertEquals(DocumentRevision.INITIAL, unorderedSession.revision)
    }

    fun testTransactionScopedPolicyRejectsBeforeAnyWrite() {
        val document = document("abcdef")
        val session = session(document)
        val before = snapshot(session)
        var policyCalls = 0

        val result = onEdtResult {
            session.applyWebProposal(
                proposal(
                    before.revision,
                    SourceEdit(0, 1, "A"),
                    SourceEdit(4, 6, "YZ"),
                ),
                policy = DocumentMutationPolicy { policySnapshot, policyProposal ->
                    policyCalls += 1
                    assertEquals(before, policySnapshot)
                    assertEquals(2, policyProposal.edits.edits.size)
                    DocumentMutationPolicyDecision.Reject(
                        DocumentMutationPolicyRejection.Conflict("transaction rejected"),
                    )
                },
            )
        }

        assertTrue((result as DocumentMutationResult.Rejected).reason is DocumentMutationRejection.Conflict)
        assertEquals(1, policyCalls)
        assertEquals(before, snapshot(session))
        assertEquals("abcdef", document.text)
    }

    fun testInvalidTransactionPolicyRejectionHasNoOffendingEditProvenance() {
        val document = document("abcdef")
        val session = session(document)
        val before = snapshot(session)
        val detail = "the complete transaction is outside the supported fidelity envelope"

        val result = onEdtResult {
            session.applyWebProposal(
                proposal(
                    before.revision,
                    SourceEdit(0, 1, "A"),
                    SourceEdit(4, 6, "YZ"),
                ),
                policy = DocumentMutationPolicy { _, _ ->
                    DocumentMutationPolicyDecision.Reject(
                        DocumentMutationPolicyRejection.Invalid(detail),
                    )
                },
            )
        }

        assertEquals(
            DocumentMutationRejection.InvalidTransaction(detail),
            (result as DocumentMutationResult.Rejected).reason,
        )
        assertEquals(before, snapshot(session))
        assertEquals("abcdef", document.text)
    }

    fun testMixedEffectiveAndNoOpEditsOnlyAdvanceForEffectiveEvents() {
        val document = document("abcde")
        val session = session(document)
        val observed = mutableListOf<AuthoritativeDocumentMutation>()
        val owner = listenerOwner()

        onEdt {
            session.addMutationListener(
                AuthoritativeDocumentMutationListener { observed += it },
                owner,
            )
        }

        val result = onEdtResult {
            session.applyWebProposal(
                proposal(
                    session.revision,
                    SourceEdit(0, 1, "a"),
                    SourceEdit(2, 3, "X"),
                    SourceEdit(4, 5, "e"),
                ),
            )
        } as DocumentMutationResult.Accepted

        assertEquals("abXde", result.snapshot.text)
        assertEquals(DocumentRevision(1), result.snapshot.revision)
        assertEquals(listOf(SourceEdit(2, 3, "X")), observed.map { it.edit })
    }

    fun testAllNoOpTransactionReturnsAcceptedUnchangedWithoutRevision() {
        val document = document("abcde")
        val session = session(document)

        val result = onEdtResult {
            session.applyWebProposal(
                proposal(
                    session.revision,
                    SourceEdit(0, 1, "a"),
                    SourceEdit(2, 4, "cd"),
                    SourceEdit(5, 5, ""),
                ),
            )
        }

        assertTrue(result is DocumentMutationResult.AcceptedUnchanged)
        assertEquals(AuthoritativeDocumentSnapshot(DocumentRevision.INITIAL, "abcde"), (result as DocumentMutationResult.AcceptedUnchanged).snapshot)
        assertEquals(DocumentRevision.INITIAL, session.revision)
        assertNull(session.lastAuthoritativeMutation)
    }

    fun testInvalidRangeIsTypedAndLeavesDocumentAndRevisionUnchanged() {
        val document = document("abc")
        val session = session(document)
        val before = snapshot(session)

        val result = onEdtResult {
            session.applyWebProposal(
                DocumentMutationProposal(
                    baseDocumentRevision = before.revision,
                    edits = SourceEditCollection.of(
                        SourceEdit(startOffset = -1, endOffset = 1, replacement = "x"),
                    ),
                ),
            )
        }

        val rejected = result as DocumentMutationResult.Rejected
        val rejection = rejected.reason as DocumentMutationRejection.InvalidMutation
        assertEquals(InvalidMutationReason.NegativeStartOffset, rejection.reason)
        assertEquals(before, snapshot(session))
        assertEquals("abc", document.text)
    }

    fun testFidelityRejectionIsTypedAndLeavesDocumentAndRevisionUnchanged() {
        val document = document("abc")
        val session = session(document)
        val before = snapshot(session)

        val result = onEdtResult {
            session.applyWebProposal(
                proposal(before.revision, SourceEdit(0, 3, "xyz")),
                policy = DocumentMutationPolicy { _, _ ->
                    DocumentMutationPolicyDecision.Reject(
                        DocumentMutationPolicyRejection.UnsupportedFidelity("normalization is not supported"),
                    )
                },
            )
        }

        val rejected = result as DocumentMutationResult.Rejected
        assertTrue(rejected.reason is DocumentMutationRejection.UnsupportedFidelity)
        assertEquals(before, snapshot(session))
        assertEquals("abc", document.text)
    }

    fun testConflictRejectionSeamIsTypedAndLeavesDocumentAndRevisionUnchanged() {
        val document = document("abc")
        val session = session(document)
        val before = snapshot(session)

        val result = onEdtResult {
            session.applyWebProposal(
                proposal(before.revision, SourceEdit(1, 2, "x")),
                policy = DocumentMutationPolicy { _, _ ->
                    DocumentMutationPolicyDecision.Reject(
                        DocumentMutationPolicyRejection.Conflict("another owner has a pending transition"),
                    )
                },
            )
        }

        val rejected = result as DocumentMutationResult.Rejected
        assertTrue(rejected.reason is DocumentMutationRejection.Conflict)
        assertEquals(before, snapshot(session))
        assertEquals("abc", document.text)
    }

    fun testConsecutiveAcceptedProposalsHaveDeterministicOrdering() {
        val document = document("a")
        val session = session(document)

        val first = onEdtResult {
            session.applyWebProposal(
                proposal(session.revision, SourceEdit(0, 1, "b")),
            )
        }
        val second = onEdtResult {
            session.applyWebProposal(
                proposal(session.revision, SourceEdit(0, 1, "c")),
            )
        }

        val firstAccepted = first as DocumentMutationResult.Accepted
        val secondAccepted = second as DocumentMutationResult.Accepted
        assertEquals(DocumentRevision(1), firstAccepted.snapshot.revision)
        assertEquals(DocumentRevision(2), secondAccepted.snapshot.revision)
        assertEquals("c", secondAccepted.snapshot.text)
        assertEquals(DocumentRevision(2), session.revision)
    }

    fun testUndoRedoAndExternalOriginsShareOneRevisionStream() {
        val document = document("a")
        val session = session(document)

        onEdt {
            session.withMutationOrigin(MutationOrigin.UNDO_REDO) {
                WriteCommandAction.runWriteCommandAction(project) {
                    document.replaceString(0, 1, "b")
                }
            }
        }
        assertEquals(DocumentRevision(1), session.revision)
        assertEquals(MutationOrigin.UNDO_REDO, session.lastAuthoritativeMutation?.origin)

        onEdt {
            session.withMutationOrigin(MutationOrigin.EXTERNAL_VFS) {
                WriteCommandAction.runWriteCommandAction(project) {
                    document.replaceString(0, 1, "c")
                }
            }
        }
        assertEquals(DocumentRevision(2), session.revision)
        assertEquals(MutationOrigin.EXTERNAL_VFS, session.lastAuthoritativeMutation?.origin)
        assertEquals("c", document.text)
    }

    fun testNoOpProposalIsExplicitAndDoesNotAdvanceRevision() {
        val document = document("same")
        val session = session(document)

        val result = onEdtResult {
            session.applyWebProposal(
                proposal(session.revision, SourceEdit(0, 4, "same")),
            )
        }

        val unchanged = result as DocumentMutationResult.AcceptedUnchanged
        assertEquals(DocumentRevision.INITIAL, unchanged.snapshot.revision)
        assertEquals(DocumentRevision.INITIAL, session.revision)
        assertEquals("same", document.text)
        assertNull(session.lastAuthoritativeMutation)
    }

    fun testRepeatedSimilarBlocksRequireExplicitTargetAndNeverGuess() {
        val source = Files.readString(
            Path.of("fixtures/markdown-fidelity/cases/repeated-similar-blocks.md"),
            StandardCharsets.UTF_8,
        )
        val document = document(source, "document-session-middle.md")
        val session = session(document)
        val target = "second edit target"
        val targetOffset = source.indexOf(target)
        assertTrue("fixture must contain the explicit middle target", targetOffset >= 0)

        val result = onEdtResult {
            session.applyWebProposal(
                DocumentMutationProposal(
                    session.revision,
                    SourceEditCollection.of(
                        SourceEdit(targetOffset, targetOffset + target.length, "middle edit target"),
                    ),
                ),
            )
        }

        result as DocumentMutationResult.Accepted
        assertTrue(document.text.contains("before the first edit target"))
        assertTrue(document.text.contains("before the middle edit target"))
        assertTrue(document.text.contains("before the third edit target"))
        assertEquals(DocumentRevision(1), session.revision)

        val rejectionDocument = document(source, "document-session-ambiguous.md")
        val rejectionSession = session(rejectionDocument)
        val rejection = onEdtResult {
            rejectionSession.applyWebProposal(
                DocumentMutationProposal(
                    rejectionSession.revision,
                    SourceEditCollection.of(
                        SourceEdit(0, source.length, source.replace("first", "guessed")),
                    ),
                ),
                policy = DocumentMutationPolicy { _, _ ->
                    DocumentMutationPolicyDecision.Reject(
                        DocumentMutationPolicyRejection.UnsupportedFidelity(
                            "ambiguous repeated-block reconstruction must not guess a target",
                        ),
                    )
                },
            )
        }

        val rejected = rejection as DocumentMutationResult.Rejected
        assertTrue(rejected.reason is DocumentMutationRejection.UnsupportedFidelity)
        assertEquals(source, rejectionDocument.text)
        assertEquals(DocumentRevision.INITIAL, rejectionSession.revision)
    }

    private fun document(text: String, fileName: String = "document-session.md"): Document =
        myFixture.configureByText(fileName, text).fileDocument

    private fun proposal(
        revision: DocumentRevision,
        vararg edits: SourceEdit,
    ): DocumentMutationProposal = DocumentMutationProposal(
        baseDocumentRevision = revision,
        edits = SourceEditCollection.of(edits.asList()),
    )

    private fun session(document: Document): DocumentSession {
        return DocumentSession(document, project).also {
            com.intellij.openapi.util.Disposer.register(testRootDisposable, it)
        }
    }

    private fun listenerOwner(): Disposable = object : Disposable {
        override fun dispose() = Unit
    }.also { Disposer.register(testRootDisposable, it) }

    private fun snapshot(session: DocumentSession): AuthoritativeDocumentSnapshot =
        onEdtResult(session::authoritativeSnapshot)

    private fun writeDocument(mutation: () -> Unit) {
        onEdt {
            WriteCommandAction.runWriteCommandAction(project, Runnable(mutation))
        }
    }

    private fun onEdt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            action()
        } else {
            application.invokeAndWait(Runnable(action), application.defaultModalityState)
        }
    }

    private fun <T> onEdtResult(action: () -> T): T {
        var result: T? = null
        onEdt { result = action() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
