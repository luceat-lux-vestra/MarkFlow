package com.algorist.markflow.runtime

import com.algorist.markflow.sync.AttachmentId
import java.util.UUID

/**
 * Mints fresh host-owned identity for one [SourceNativeEditorRuntime] attachment/runtime lifetime.
 *
 * The runtime token is intentionally not one of the four protocol identities
 * ([AttachmentId] / `RequestId` / `RecoveryId` / `DocumentRevision`, see [com.algorist.markflow.sync]).
 * It exists solely to correlate the narrow [RuntimeReadySignal] handshake to the exact runtime
 * instance that issued it; it is never serialized as part of the #102 mutation/recovery protocol
 * and never compared against or derived from those identities.
 */
internal object SourceNativeRuntimeIdentity {
    fun freshAttachmentId(): AttachmentId = AttachmentId.of("source-native-${UUID.randomUUID()}")

    fun freshRuntimeToken(): String = UUID.randomUUID().toString()
}
