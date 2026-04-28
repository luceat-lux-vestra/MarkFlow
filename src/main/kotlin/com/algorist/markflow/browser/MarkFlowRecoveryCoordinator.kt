package com.algorist.markflow.browser

import com.algorist.markflow.MarkFlowEditor
import com.intellij.openapi.diagnostic.Logger

internal object MarkFlowRecoveryCoordinator {
    private val LOG = Logger.getInstance(MarkFlowRecoveryCoordinator::class.java)
    private val lock = Any()
    private val recoveryLeasesByFile = mutableMapOf<String, RecoveryLease>()

    fun claimRecoveryLease(editor: MarkFlowEditor, leaseId: Int, reason: String): RecoveryBridgeResponse {
        synchronized(lock) {
            val filePath = editor.getFile().path
            val current = recoveryLeasesByFile[filePath]
            val currentLeaderValid = current?.leader?.isDisposedEditor() == false

            if (!currentLeaderValid) {
                val nextEpoch = (current?.epoch ?: 0) + 1
                val updated = RecoveryLease(epoch = nextEpoch, leader = editor, leaseId = leaseId)
                recoveryLeasesByFile[filePath] = updated
                LOG.info("MARKFLOW_UI recovery:claim leader role=$leaseId epoch=$nextEpoch reason=$reason")
                return RecoveryBridgeResponse(role = "leader", epoch = nextEpoch, reason = reason)
            }

            val active = current
            if (active.leader === editor || active.leaseId == leaseId) {
                val nextEpoch = active.epoch
                val updated = RecoveryLease(epoch = nextEpoch, leader = editor, leaseId = leaseId)
                recoveryLeasesByFile[filePath] = updated
                LOG.info("MARKFLOW_UI recovery:claim leader role=$leaseId epoch=$nextEpoch reason=$reason (reused)")
                return RecoveryBridgeResponse(role = "leader", epoch = nextEpoch, reason = reason)
            }

            LOG.info("MARKFLOW_UI recovery:claim follower role=$leaseId epoch=${active.epoch} reason=$reason")
            return RecoveryBridgeResponse(role = "follower", epoch = active.epoch, reason = reason)
        }
    }

    fun completeRecoveryLease(
        editor: MarkFlowEditor,
        leaseId: Int,
        epoch: Int,
        success: Boolean
    ): RecoveryBridgeResponse {
        val reason = if (success) "complete" else "failed"
        synchronized(lock) {
            val filePath = editor.getFile().path
            val current = recoveryLeasesByFile[filePath]
            if (current?.leader === editor && current.leaseId == leaseId && current.epoch == epoch) {
                recoveryLeasesByFile.remove(filePath)
                LOG.info("MARKFLOW_UI recovery:complete leaseId=$leaseId epoch=$epoch success=$success")
                return RecoveryBridgeResponse(role = reason, epoch = epoch, reason = reason)
            }

            val logStatus = when {
                current == null -> "noActiveLease"
                current.leader !== editor -> "editorMismatch"
                current.leaseId != leaseId -> "leaseMismatch"
                current.epoch != epoch -> "epochMismatch"
                else -> "unknown"
            }
            LOG.info("MARKFLOW_UI recovery:complete ignored leaseId=$leaseId epoch=$epoch status=$logStatus")
        }
        return RecoveryBridgeResponse(role = "ignored", epoch = epoch, reason = reason)
    }

    fun clearRecoveryLease(editor: MarkFlowEditor, leaseId: Int) {
        synchronized(lock) {
            val filePath = editor.getFile().path
            val current = recoveryLeasesByFile[filePath]
            if (current?.leader === editor && current.leaseId == leaseId) {
                recoveryLeasesByFile.remove(filePath)
                LOG.info("MARKFLOW_UI recovery:detach cleaned filePath=$filePath leaseId=$leaseId")
            }
        }
    }

    internal data class RecoveryBridgeResponse(
        val role: String,
        val epoch: Int,
        val reason: String
    )

    private data class RecoveryLease(
        val epoch: Int,
        val leader: MarkFlowEditor,
        val leaseId: Int
    )
}
