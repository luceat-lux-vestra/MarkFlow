package com.algorist.markflow.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkFlowLeaseReclaimSelectorTest {

    @Test
    fun prefersOlderStableHiddenLease() {
        val selected = selectLeaseToReclaim(
            listOf(
                MarkFlowLeaseReclaimCandidate(
                    leaseId = 1,
                    isActive = false,
                    isShowing = false,
                    hiddenSinceAtMs = 1_000L,
                    lastUsedAtMs = 200L
                ),
                MarkFlowLeaseReclaimCandidate(
                    leaseId = 2,
                    isActive = false,
                    isShowing = false,
                    hiddenSinceAtMs = 900L,
                    lastUsedAtMs = 100L
                )
            ),
            nowMs = 1_500L,
            hiddenGraceMs = 250L
        )

        assertEquals(2, selected?.leaseId)
    }

    @Test
    fun ignoresRecentlyHiddenLeases() {
        val selected = selectLeaseToReclaim(
            listOf(
                MarkFlowLeaseReclaimCandidate(
                    leaseId = 1,
                    isActive = false,
                    isShowing = false,
                    hiddenSinceAtMs = 1_300L,
                    lastUsedAtMs = 10L
                )
            ),
            nowMs = 1_500L,
            hiddenGraceMs = 250L
        )

        assertNull(selected)
    }

    @Test
    fun ignoresActiveOrVisibleLeases() {
        val selected = selectLeaseToReclaim(
            listOf(
                MarkFlowLeaseReclaimCandidate(
                    leaseId = 1,
                    isActive = true,
                    isShowing = false,
                    hiddenSinceAtMs = 1_000L,
                    lastUsedAtMs = 10L
                ),
                MarkFlowLeaseReclaimCandidate(
                    leaseId = 2,
                    isActive = false,
                    isShowing = true,
                    hiddenSinceAtMs = 1_000L,
                    lastUsedAtMs = 5L
                )
            ),
            nowMs = 1_500L,
            hiddenGraceMs = 250L
        )

        assertNull(selected)
    }
}
