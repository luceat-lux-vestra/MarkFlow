package com.algorist.markflow.document

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.util.IdentityHashMap
import java.util.LinkedHashSet

/**
 * Project-owned authority registry for live IntelliJ [Document] relationships.
 *
 * Acquisition and release are deliberately EDT-owned. FileEditor/surface lifecycle is expected
 * to enter this boundary from the EDT, which makes the identity lookup and last-consumer
 * transition one simple serialized operation instead of introducing a second concurrency model.
 * The project service itself is the only owner of the registry; no static or process-global
 * mutable state participates in document authority.
 */
@Service(Service.Level.PROJECT)
class DocumentSessionRegistry(
    private val project: Project,
) : Disposable {
    private data class SessionEntry(
        val session: DocumentSession,
        var consumerCount: Int,
    )

    private val sessions = IdentityHashMap<Document, SessionEntry>()
    private val stateLock = Any()
    private var disposed = false

    /**
     * Acquire the shared session for [document] in this project.
     *
     * The document object identity, not path, VFS stamp, source hash, browser lease, or web
     * session identity, is the registry key. The returned lease is one consumer's ownership
     * handle and must be disposed on the EDT exactly once.
     */
    fun acquire(document: Document): DocumentSessionLease {
        assertEdt()
        return synchronized(stateLock) {
            check(!disposed) { "DocumentSessionRegistry is disposed" }

            val entry = sessions[document]
            if (entry != null) {
                entry.consumerCount += 1
                return@synchronized DocumentSessionLease(this, document, entry.session)
            }

            val session = DocumentSession(document, project)
            sessions[document] = SessionEntry(session = session, consumerCount = 1)
            DocumentSessionLease(this, document, session)
        }
    }

    /** Number of live document relationships currently retained by this project service. */
    internal val activeSessionCount: Int
        get() = synchronized(stateLock) { sessions.size }

    internal fun contains(document: Document): Boolean = synchronized(stateLock) {
        sessions.containsKey(document)
    }

    internal fun release(lease: DocumentSessionLease) {
        assertEdt()
        val sessionToDispose = synchronized(stateLock) {
            if (!lease.markReleased()) return
            if (disposed) return

            val entry = sessions[lease.document]
            check(entry != null && entry.session === lease.session) {
                "DocumentSession lease does not belong to this registry"
            }

            entry.consumerCount -= 1
            check(entry.consumerCount >= 0) { "DocumentSession consumer count underflow" }
            if (entry.consumerCount != 0) return

            sessions.remove(lease.document)
            entry.session
        }
        // The listener was registered with the session as its parent. Disposer.dispose walks
        // that child relationship before invoking DocumentSession.dispose, removing the listener
        // deterministically with the final consumer.
        Disposer.dispose(sessionToDispose)
    }

    override fun dispose() {
        val remainingSessions = synchronized(stateLock) {
            if (disposed) return
            disposed = true

            val remaining = sessions.values.map { it.session }
            sessions.clear()
            remaining
        }
        remainingSessions.forEach(Disposer::dispose)
    }

    private fun assertEdt() {
        ApplicationManager.getApplication().assertIsDispatchThread()
    }

    companion object {
        fun getInstance(project: Project): DocumentSessionRegistry =
            project.getService(DocumentSessionRegistry::class.java)
    }
}

/** One project/document consumer's deterministic ownership handle. */
class DocumentSessionLease internal constructor(
    private val registry: DocumentSessionRegistry,
    internal val document: Document,
    val session: DocumentSession,
) : Disposable {
    private val ownedSubscriptions = LinkedHashSet<Disposable>()
    private var disposalStarted = false
    private var released = false

    /**
     * Release this consumer and all subscriptions registered with this lease.
     *
     * Direct disposal and [Disposer.dispose] intentionally share this implementation. A lease is
     * not used as a normal Disposer parent for mutation subscriptions, because direct
     * [dispose] must not depend on Disposer walking a child tree.
     */
    override fun dispose() {
        val subscriptions = synchronized(this) {
            if (disposalStarted) return
            disposalStarted = true
            val current = ownedSubscriptions.toList()
            ownedSubscriptions.clear()
            current
        }
        subscriptions.forEach(Disposer::dispose)
        registry.release(this)
    }

    internal fun registerOwnedSubscription(subscription: Disposable) {
        val disposeImmediately = synchronized(this) {
            if (disposalStarted || released) {
                true
            } else {
                ownedSubscriptions.add(subscription)
                false
            }
        }
        if (disposeImmediately) Disposer.dispose(subscription)
    }

    internal fun markReleased(): Boolean {
        return synchronized(this) {
            if (released) return@synchronized false
            released = true
            true
        }
    }
}
