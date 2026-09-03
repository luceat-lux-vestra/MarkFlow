package com.algorist.markflow.document

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable

/**
 * Document-domain authority for one IntelliJ [Document] relationship.
 *
 * The IntelliJ Document is the only live logical Markdown source. This class owns the document's
 * logical revision stream and mutation-origin metadata, but it does not own a FileEditor,
 * browser/JCEF object, web runtime, protocol identity, persistence cache, or presentation state.
 *
 * [applyWebProposal] is synchronous and must be called on the EDT. It performs stale checking,
 * range/policy validation, and the normal IntelliJ write-command mutation in one transaction.
 * Direct host/document changes must use IntelliJ's normal write semantics; their synchronous
 * DocumentEvent is observed by this session and advances the same revision stream.
 */
class DocumentSession(
    private val document: Document,
    private val commandProject: Project? = null,
    private val originResolver: DocumentMutationOriginResolver = DocumentMutationOriginResolver.DEFAULT,
) : Disposable {
    @Volatile
    private var currentRevision: DocumentRevision = DocumentRevision.INITIAL

    @Volatile
    private var disposed = false

    @Volatile
    private var lastMutation: AuthoritativeDocumentMutation? = null

    /** Only used synchronously while the current thread performs one document mutation. */
    private val mutationOriginContext = ThreadLocal<MutationOrigin?>()

    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            if (disposed || event.document !== document) return

            // A real source change has different old/new fragments. This also keeps a defensive
            // no-op event from manufacturing a revision when an implementation emits one.
            if (event.oldFragment.toString() == event.newFragment.toString()) return

            val nextRevision = currentRevision.next()
            currentRevision = nextRevision
            lastMutation = AuthoritativeDocumentMutation(
                revision = nextRevision,
                origin = mutationOriginContext.get() ?: originResolver.resolve(event),
                edit = SourceEdit(
                    startOffset = event.offset,
                    endOffset = event.offset + event.oldLength,
                    replacement = event.newFragment.toString(),
                ),
            )
        }
    }

    init {
        // IntelliJ owns removal through this session's Disposable lifetime. Do not remove this
        // listener again from dispose(): the disposable tree removes the child listener before
        // invoking the session's dispose callback.
        document.addDocumentListener(documentListener, this)
    }

    /** Current logical ordering, seeded at zero before any accepted source mutation. */
    val revision: DocumentRevision
        get() = currentRevision

    /** Most recently observed mutation metadata, or null before the first source mutation. */
    val lastAuthoritativeMutation: AuthoritativeDocumentMutation?
        get() = lastMutation

    /**
     * Read a fresh immutable snapshot from the live IntelliJ Document.
     *
     * Callers should use this on the EDT or under the IntelliJ read access appropriate to their
     * integration. No source text is retained as a competing authority by this session.
     */
    fun authoritativeSnapshot(): AuthoritativeDocumentSnapshot = AuthoritativeDocumentSnapshot(
        revision = currentRevision,
        text = document.text,
    )

    /**
     * Apply one WEB proposal using normal IntelliJ command/undo semantics.
     *
     * The revision check is inside the write command, immediately before range/policy validation
     * and the Document write. The DocumentEvent produced by replaceString is the sole canonical
     * revision-advance path; this method never increments the revision itself.
     */
    fun applyWebProposal(
        proposal: DocumentMutationProposal,
        policy: DocumentMutationPolicy = DocumentMutationPolicy.ACCEPT,
    ): DocumentMutationResult {
        ApplicationManager.getApplication().assertIsDispatchThread()
        check(!disposed) { "DocumentSession is disposed" }

        return WriteCommandAction.runWriteCommandAction(commandProject, Computable {
            val currentSnapshot = authoritativeSnapshot()
            if (proposal.baseDocumentRevision != currentSnapshot.revision) {
                return@Computable DocumentMutationResult.Rejected(
                    DocumentMutationRejection.StaleRevision(currentSnapshot),
                )
            }

            val invalidReason = proposal.edit.invalidReason(currentSnapshot.text.length)
            if (invalidReason != null) {
                return@Computable DocumentMutationResult.Rejected(
                    DocumentMutationRejection.InvalidMutation(proposal.edit, invalidReason),
                )
            }

            when (val decision = policy.validate(currentSnapshot, proposal)) {
                DocumentMutationPolicyDecision.Accept -> Unit
                is DocumentMutationPolicyDecision.Reject -> {
                    return@Computable DocumentMutationResult.Rejected(
                        decision.reason.toRejection(proposal.edit),
                    )
                }
            }

            val existingText = currentSnapshot.text.substring(
                proposal.edit.startOffset,
                proposal.edit.endOffset,
            )
            if (existingText == proposal.edit.replacement) {
                return@Computable DocumentMutationResult.AcceptedUnchanged(
                    snapshot = currentSnapshot,
                    origin = MutationOrigin.WEB,
                )
            }

            val expectedRevision = currentSnapshot.revision.next()
            withOriginContext(MutationOrigin.WEB) {
                document.replaceString(
                    proposal.edit.startOffset,
                    proposal.edit.endOffset,
                    proposal.edit.replacement,
                )
            }

            // DocumentImpl dispatches DocumentEvent synchronously. Keeping this invariant here
            // catches a broken listener/lifecycle path instead of silently creating a second
            // revision mechanism or returning a false acceptance.
            check(currentRevision == expectedRevision) {
                "Authoritative Document mutation did not produce exactly one revision advance"
            }
            DocumentMutationResult.Accepted(
                snapshot = authoritativeSnapshot(),
                origin = MutationOrigin.WEB,
            )
        })
    }

    /**
     * Classify one synchronous non-WEB Document mutation without changing revision semantics.
     *
     * The context is metadata only: it must wrap the normal IntelliJ write/undo/VFS operation on
     * the EDT, and it is restored immediately after [mutation] returns. WEB callers must use
     * [applyWebProposal] so the base-revision check cannot be bypassed.
     */
    fun <T> withMutationOrigin(origin: MutationOrigin, mutation: () -> T): T {
        require(origin != MutationOrigin.WEB) {
            "WEB mutations must enter through applyWebProposal"
        }
        ApplicationManager.getApplication().assertIsDispatchThread()
        check(!disposed) { "DocumentSession is disposed" }
        return withOriginContext(origin, mutation)
    }

    private fun <T> withOriginContext(origin: MutationOrigin, mutation: () -> T): T {
        val previous = mutationOriginContext.get()
        mutationOriginContext.set(origin)
        return try {
            mutation()
        } finally {
            if (previous == null) {
                mutationOriginContext.remove()
            } else {
                mutationOriginContext.set(previous)
            }
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        mutationOriginContext.remove()
    }
}

private fun SourceEdit.invalidReason(documentLength: Int): InvalidMutationReason? = when {
    startOffset < 0 -> InvalidMutationReason.NegativeStartOffset
    endOffset < startOffset -> InvalidMutationReason.EndBeforeStart
    endOffset > documentLength -> InvalidMutationReason.EndBeyondDocument
    else -> null
}

private fun DocumentMutationPolicyRejection.toRejection(edit: SourceEdit): DocumentMutationRejection = when (this) {
    is DocumentMutationPolicyRejection.Invalid -> DocumentMutationRejection.InvalidMutation(
        edit = edit,
        reason = InvalidMutationReason.PolicyRejected(detail),
    )
    is DocumentMutationPolicyRejection.Conflict -> DocumentMutationRejection.Conflict(detail)
    is DocumentMutationPolicyRejection.UnsupportedFidelity ->
        DocumentMutationRejection.UnsupportedFidelity(detail)
}
