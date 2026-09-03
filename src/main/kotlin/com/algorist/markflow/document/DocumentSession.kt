package com.algorist.markflow.document

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.diagnostic.Logger
import java.lang.ref.WeakReference
import java.util.LinkedHashSet

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
    private val onSnapshotCreated: (() -> Unit)? = null,
) : Disposable {
    @Volatile
    private var currentRevision: DocumentRevision = DocumentRevision.INITIAL

    @Volatile
    private var disposed = false

    @Volatile
    private var lastMutation: AuthoritativeDocumentMutation? = null

    private val mutationListeners = LinkedHashSet<MutationSubscription>()

    /** Only used synchronously while the current thread performs one document mutation. */
    private val mutationOriginContext = ThreadLocal<MutationOrigin?>()

    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            if (disposed || event.document !== document) return

            // A real source change has different old/new fragments. This also keeps a defensive
            // no-op event from manufacturing a revision when an implementation emits one.
            if (event.oldFragment.toString() == event.newFragment.toString()) return

            val nextRevision = currentRevision.next()
            val mutation = AuthoritativeDocumentMutation(
                revision = nextRevision,
                origin = mutationOriginContext.get() ?: originResolver.resolve(event),
                edit = SourceEdit(
                    startOffset = event.offset,
                    endOffset = event.offset + event.oldLength,
                    replacement = event.newFragment.toString(),
                ),
            )
            currentRevision = nextRevision
            lastMutation = mutation
            val listeners = synchronized(mutationListeners) { mutationListeners.toList() }
            listeners.forEach { subscription ->
                try {
                    subscription.notify(mutation)
                } catch (error: Exception) {
                    // The Document and revision transition is already authoritative. An
                    // observer is downstream of that transition and must not make an accepted
                    // mutation look rejected or prevent later observers from receiving it.
                    LOG.warn(
                        "Authoritative document mutation observer failed at revision ${mutation.revision}",
                        error,
                    )
                }
            }
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

    /** True after this session's document listener and observation subscriptions are disposed. */
    val isDisposed: Boolean
        get() = disposed

    /**
     * Register a synchronous observer owned by [parentDisposable].
     *
     * The callback runs on the same thread as the authoritative IntelliJ DocumentEvent, after
     * the session has advanced its revision. The normal integration contract is EDT-owned
     * document mutation and subscription lifecycle; mutation events intentionally do not retain
     * historical full-source snapshots.
     */
    fun addMutationListener(
        listener: AuthoritativeDocumentMutationListener,
        parentDisposable: Disposable,
    ) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        check(!disposed) { "DocumentSession is disposed" }

        val subscription = MutationSubscription(this, listener)
        synchronized(mutationListeners) {
            mutationListeners.add(subscription)
        }
        if (parentDisposable is DocumentSessionLease) {
            parentDisposable.registerOwnedSubscription(subscription)
        } else {
            Disposer.register(parentDisposable, subscription)
        }
    }

    /**
     * Read a fresh immutable snapshot from the live IntelliJ Document.
     *
     * This method requires the EDT or an IntelliJ read-access context and fails fast otherwise.
     * No source text is retained as a competing authority by this session. Callers that need a
     * full source must cross this explicit boundary or use a result that owns a boundary snapshot;
     * [AuthoritativeDocumentMutation] itself carries no historical source snapshot.
     */
    fun authoritativeSnapshot(): AuthoritativeDocumentSnapshot {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        onSnapshotCreated?.invoke()
        return AuthoritativeDocumentSnapshot(
            revision = currentRevision,
            text = document.text,
        )
    }

    /**
     * Apply one WEB transaction proposal using normal IntelliJ command/undo semantics.
     *
     * The revision, range, ordering, policy, and no-op checks all run inside one write command
     * against one authoritative pre-transaction snapshot. Only after every check passes are the
     * source-effective edits applied in reverse source/list order. The DocumentEvent produced by
     * each replaceString is the sole canonical revision-advance path; this method never
     * increments the revision itself.
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

            val invalidEdit = proposal.edits.edits.firstNotNullOfOrNull { edit ->
                edit.invalidReason(currentSnapshot.text.length)?.let { reason -> edit to reason }
            }
            if (invalidEdit != null) {
                return@Computable DocumentMutationResult.Rejected(
                    DocumentMutationRejection.InvalidMutation(invalidEdit.first, invalidEdit.second),
                )
            }

            val orderingError = proposal.edits.orderingError()
            if (orderingError != null) {
                return@Computable DocumentMutationResult.Rejected(
                    DocumentMutationRejection.InvalidMutation(
                        edit = orderingError.edit,
                        reason = orderingError.reason,
                    ),
                )
            }

            when (val decision = policy.validate(currentSnapshot, proposal)) {
                DocumentMutationPolicyDecision.Accept -> Unit
                is DocumentMutationPolicyDecision.Reject -> {
                    return@Computable DocumentMutationResult.Rejected(
                        decision.reason.toRejection(),
                    )
                }
            }

            val effectiveEdits = proposal.edits.edits.filter { edit ->
                currentSnapshot.text.substring(edit.startOffset, edit.endOffset) != edit.replacement
            }
            if (effectiveEdits.isEmpty()) {
                return@Computable DocumentMutationResult.AcceptedUnchanged(
                    snapshot = currentSnapshot,
                    origin = MutationOrigin.WEB,
                )
            }

            val expectedFinalRevision = effectiveEdits.fold(currentSnapshot.revision) { revision, _ ->
                revision.next()
            }
            withOriginContext(MutationOrigin.WEB) {
                effectiveEdits.asReversed().forEach { edit ->
                    val revisionBeforeEdit = currentRevision
                    document.replaceString(edit.startOffset, edit.endOffset, edit.replacement)

                    // DocumentImpl dispatches DocumentEvent synchronously. Keeping the
                    // per-event invariant here catches a broken listener/lifecycle path instead
                    // of silently creating another revision mechanism or returning a false
                    // acceptance.
                    check(currentRevision == revisionBeforeEdit.next()) {
                        "Authoritative Document mutation did not produce exactly one revision advance"
                    }
                }
            }

            check(currentRevision == expectedFinalRevision) {
                "Authoritative Document mutation did not produce the expected revision advances"
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

    private fun removeMutationListener(subscription: MutationSubscription) {
        synchronized(mutationListeners) {
            mutationListeners.remove(subscription)
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        synchronized(mutationListeners) {
            mutationListeners.forEach(MutationSubscription::invalidate)
            mutationListeners.clear()
        }
        mutationOriginContext.remove()
    }

    private class MutationSubscription(
        session: DocumentSession,
        private val listener: AuthoritativeDocumentMutationListener,
    ) : Disposable {
        private val sessionReference = WeakReference(session)
        @Volatile
        private var disposed = false

        fun notify(mutation: AuthoritativeDocumentMutation) {
            if (!disposed) listener.onMutation(mutation)
        }

        fun invalidate() {
            disposed = true
        }

        override fun dispose() {
            if (disposed) return
            disposed = true
            sessionReference.get()?.removeMutationListener(this)
        }
    }

    private companion object {
        private val LOG = Logger.getInstance(DocumentSession::class.java)
    }
}

private fun SourceEdit.invalidReason(documentLength: Int): InvalidMutationReason? = when {
    startOffset < 0 -> InvalidMutationReason.NegativeStartOffset
    endOffset < startOffset -> InvalidMutationReason.EndBeforeStart
    endOffset > documentLength -> InvalidMutationReason.EndBeyondDocument
    else -> null
}

private data class EditOrderingError(
    val edit: SourceEdit,
    val reason: InvalidMutationReason,
)

private fun SourceEditCollection.orderingError(): EditOrderingError? {
    edits.zipWithNext().forEach { (previous, current) ->
        if (previous.startOffset > current.startOffset) {
            return EditOrderingError(current, InvalidMutationReason.UnorderedEdits)
        }
        if (previous.endOffset > current.startOffset) {
            return EditOrderingError(current, InvalidMutationReason.OverlappingEdits)
        }
    }
    return null
}

private fun DocumentMutationPolicyRejection.toRejection(): DocumentMutationRejection = when (this) {
    is DocumentMutationPolicyRejection.Invalid -> DocumentMutationRejection.InvalidTransaction(detail)
    is DocumentMutationPolicyRejection.Conflict -> DocumentMutationRejection.Conflict(detail)
    is DocumentMutationPolicyRejection.UnsupportedFidelity ->
        DocumentMutationRejection.UnsupportedFidelity(detail)
}
