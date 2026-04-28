package com.algorist.markflow.browser

internal data class MarkFlowLeaseReclaimCandidate(
    val leaseId: Int,
    val isActive: Boolean,
    val isShowing: Boolean,
    val hiddenSinceAtMs: Long,
    val lastUsedAtMs: Long
)

internal fun selectLeaseToReclaim(
    candidates: Iterable<MarkFlowLeaseReclaimCandidate>,
    nowMs: Long,
    hiddenGraceMs: Long
): MarkFlowLeaseReclaimCandidate? {
    return candidates
        .filter { candidate ->
            !candidate.isActive &&
                !candidate.isShowing &&
                candidate.hiddenSinceAtMs > 0L &&
                nowMs - candidate.hiddenSinceAtMs >= hiddenGraceMs
        }
        .sortedWith(
            compareBy<MarkFlowLeaseReclaimCandidate> { it.lastUsedAtMs }
                .thenBy { it.leaseId }
        )
        .firstOrNull()
}
