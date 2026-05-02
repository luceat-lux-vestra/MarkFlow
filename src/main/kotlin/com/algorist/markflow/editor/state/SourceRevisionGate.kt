package com.algorist.markflow.editor.state

import java.util.concurrent.atomic.AtomicLong

internal class SourceRevisionGate(initialRevision: Long = 1L) {
    private val revision = AtomicLong(initialRevision.coerceAtLeast(1L))

    fun current(): Long = revision.get()

    fun acceptIncomingRevision(incomingRevision: Long): Boolean {
        val normalized = incomingRevision.coerceAtLeast(1L)
        while (true) {
            val current = revision.get()
            if (normalized <= current) {
                return false
            }
            if (revision.compareAndSet(current, normalized)) {
                return true
            }
        }
    }

    fun advanceForExternalChange(): Long {
        return revision.incrementAndGet()
    }

    fun observeAtLeast(revisionValue: Long): Long {
        val normalized = revisionValue.coerceAtLeast(1L)
        while (true) {
            val current = revision.get()
            if (normalized <= current) {
                return current
            }
            if (revision.compareAndSet(current, normalized)) {
                return normalized
            }
        }
    }
}
