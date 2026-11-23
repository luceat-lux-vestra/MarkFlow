package com.algorist.markflow.renderer.jcef

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JcefStartupRequestQueueTest {
    @Test
    fun capacityIsFailClosedWithoutEvictingEarlierRequests() {
        val queue = JcefStartupRequestQueue<String>(2)

        assertEquals(JcefStartupRequestQueue.OfferResult.ACCEPTED, queue.offer("a", "first"))
        assertEquals(JcefStartupRequestQueue.OfferResult.ACCEPTED, queue.offer("b", "second"))
        assertEquals(JcefStartupRequestQueue.OfferResult.FULL, queue.offer("c", "third"))
        assertEquals(2, queue.size)
        assertEquals(listOf("first", "second"), queue.drain())
        assertTrue(queue.isEmpty)
    }

    @Test
    fun startupDeadlinePhaseMovesFromEntryLoadToReadyHandshakeWithoutExtendingQueueOwnership() {
        val queue = JcefStartupRequestQueue<String>(2)

        assertNull(queue.deadlinePhaseOrNull)
        queue.offer("a", "first")
        assertEquals(JcefStartupRequestQueue.Phase.ENTRY_LOADING, queue.deadlinePhaseOrNull)

        assertTrue(queue.markEntryLoaded())
        assertEquals(JcefStartupRequestQueue.Phase.READY_HANDSHAKE, queue.deadlinePhaseOrNull)
        assertFalse("Repeated load-end must not restart the handshake phase", queue.markEntryLoaded())

        queue.offer("b", "second")
        assertEquals(JcefStartupRequestQueue.Phase.READY_HANDSHAKE, queue.deadlinePhaseOrNull)
        assertEquals(listOf("first", "second"), queue.drain())
        assertNull(queue.deadlinePhaseOrNull)
    }

    @Test
    fun startupFailureIsTerminalForTheRuntimeInstance() {
        val queue = JcefStartupRequestQueue<String>(2)
        queue.offer("a", "first")
        queue.markEntryLoaded()
        queue.markTerminalFailure("ready handshake deadline expired")

        assertEquals("ready handshake deadline expired", queue.terminalFailureMessage)
        assertTrue(queue.terminallyFailed)
        assertEquals(JcefStartupRequestQueue.Phase.TERMINAL_FAILURE, queue.phase)
        queue.markTerminalFailure("later load failure")
        assertEquals("ready handshake deadline expired", queue.terminalFailureMessage)
        assertFalse("Late ready must not resurrect a failed runtime", queue.markReady())
        assertEquals(
            JcefStartupRequestQueue.OfferResult.TERMINAL_FAILURE,
            queue.offer("b", "second"),
        )
    }

    @Test
    fun healthyStartupMayTransitionToReady() {
        val queue = JcefStartupRequestQueue<String>(2)
        queue.offer("a", "first")
        assertTrue(queue.markEntryLoaded())

        assertFalse(queue.terminallyFailed)
        assertTrue(queue.markReady())
        assertEquals(JcefStartupRequestQueue.Phase.READY, queue.phase)
        assertEquals(listOf("first"), queue.drain())
    }

    @Test
    fun removeAndDrainReleasePendingStartupWork() {
        val queue = JcefStartupRequestQueue<String>(3)
        queue.offer("a", "first")
        queue.offer("b", "second")
        queue.offer("c", "third")

        assertEquals("second", queue.remove("b"))
        assertEquals(listOf("first", "third"), queue.drain())
        assertTrue(queue.isEmpty)
        assertNull(queue.deadlinePhaseOrNull)
    }
}
