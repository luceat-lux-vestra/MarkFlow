package com.algorist.markflow.runtime

import com.algorist.markflow.browser.MarkFlowJcefSupport
import com.algorist.markflow.browser.MarkFlowWebviewResourceManager
import com.algorist.markflow.document.DocumentSessionLease
import com.algorist.markflow.document.DocumentSessionRegistry
import com.algorist.markflow.sync.AttachmentHostUpdateBinding
import com.algorist.markflow.sync.AttachmentId
import com.algorist.markflow.sync.AttachmentSyncCoordinator
import com.algorist.markflow.sync.AttachmentWireCodec
import com.algorist.markflow.sync.AttachmentWireMessage
import com.algorist.markflow.sync.AuthoritativeHostUpdate
import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The per-surface JCEF runtime owner required by #105/#81.
 *
 * Exactly one live instance owns, for its own lifetime only:
 * - one [SourceNativeRuntimeTransport] (one browser realm, one mutation/recovery query, one
 *   readiness query);
 * - one fresh [AttachmentId];
 * - one [AttachmentSyncCoordinator] consuming an already-owned/shared [DocumentSessionLease];
 * - one [AttachmentHostUpdateBinding];
 * - one [DocumentSessionRegistry] consumer lease;
 * - one webview-resource-manager reference when the default bundled source-native page is used.
 *
 * It does not own IntelliJ [Document] authority or [com.algorist.markflow.document.DocumentRevision]
 * advancement (owned by [com.algorist.markflow.document.DocumentSession]), does not pool browsers,
 * and does not derive [AttachmentId] from a browser/lease/session identity. Replacing a runtime
 * means disposing this instance and constructing a new one via [create]; the new instance always
 * has a fresh [AttachmentId] and a fresh transport.
 */
internal class SourceNativeEditorRuntime private constructor(
    private val lease: DocumentSessionLease,
    val attachmentId: AttachmentId,
    private val runtimeToken: String,
    private val transport: SourceNativeRuntimeTransport,
    sourceNativeUrl: String,
    private val releaseWebviewResource: (() -> Unit)?,
) : Disposable {
    private val gson = Gson()
    private lateinit var coordinator: AttachmentSyncCoordinator
    private lateinit var hostUpdateBinding: AttachmentHostUpdateBinding
    private val readySignalReceived = AtomicBoolean(false)
    private val mainFrameLoadReceived = AtomicBoolean(false)
    private var countedLiveInstance = false

    @Volatile
    private var bootstrapSent = false

    @Volatile
    private var invalidated = false

    @Volatile
    private var disposed = false

    val isDisposed: Boolean
        get() = disposed

    /** True only after this exact runtime instance has sent its one [AttachmentWireMessage.BootstrapSnapshot]. */
    val isBootstrapped: Boolean
        get() = bootstrapSent

    init {
        try {
            coordinator = AttachmentSyncCoordinator(attachmentId, lease.session)
            hostUpdateBinding = AttachmentHostUpdateBinding(coordinator, ::deliverHostIncrementalUpdate)
            transport.setTransportMessageHandler(::handleTransportMessage)
            transport.setReadinessMessageHandler(::handleReadinessMessage)
            transport.setLoadEndHandler(::onLoadEnd)
            transport.loadUrl(sourceNativeUrl)
            liveInstances.incrementAndGet()
            countedLiveInstance = true
        } catch (failure: Throwable) {
            invalidated = true
            disposed = true
            releaseOwnedResources()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun onLoadEnd() {
        if (!isCurrentRuntime()) return
        if (!mainFrameLoadReceived.compareAndSet(false, true)) {
            invalidateForRealmReplacement()
            return
        }
        transport.executeJavaScript(transport.buildBridgeGlueScript())
    }

    /**
     * A second main-frame load means the browser has crossed the one-realm-per-runtime boundary.
     * Reusing this runtime's [AttachmentId], readiness state or bootstrap state in that new JS
     * realm would violate #105's identity/lifetime contract. Invalidate synchronously so every
     * already-captured callback fails closed, then release owned IntelliJ/JCEF resources on the
     * EDT. A replacement must be constructed through [create], which issues fresh identities.
     */
    private fun invalidateForRealmReplacement() {
        if (invalidated || disposed) return
        invalidated = true
        ApplicationManager.getApplication().invokeLater {
            if (!disposed) dispose()
        }
    }

    private fun isCurrentRuntime(): Boolean = !disposed && !invalidated

    /**
     * Invoked by [transport] for every web -> host mutation/recovery message. May run off the
     * EDT; this method is the one explicit dispatch boundary that reaches onto the EDT for the
     * document/sync domain, and it re-checks lifecycle validity both before and after that dispatch
     * so a disposal/replacement race can never let stale work reach [coordinator] or [lease].
     */
    private fun handleTransportMessage(raw: String): String? {
        if (!isCurrentRuntime()) return null
        var result: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            if (!isCurrentRuntime()) return@invokeAndWait
            result = SourceNativeAttachmentTransportHandler.handle(raw, attachmentId, coordinator)
        }
        return result
    }

    /**
     * Invoked by [transport] for the narrow readiness handshake. A signal not addressed to this
     * exact attachment/runtime token is inert. Repeated valid signals are inert after the first:
     * only the first current-runtime readiness may trigger the one [AttachmentWireMessage.BootstrapSnapshot].
     */
    private fun handleReadinessMessage(raw: String): String? {
        if (!isCurrentRuntime()) return null
        val signal = RuntimeReadinessCodec.decode(raw) ?: return null
        if (signal.attachmentId != attachmentId || signal.runtimeToken != runtimeToken) return null

        if (readySignalReceived.compareAndSet(false, true)) {
            ApplicationManager.getApplication().invokeLater { sendBootstrapSnapshotOnce() }
        }
        return READY_ACK_RESPONSE
    }

    private fun sendBootstrapSnapshotOnce() {
        if (!isCurrentRuntime() || bootstrapSent) return
        ApplicationManager.getApplication().assertIsDispatchThread()
        bootstrapSent = true
        val snapshot = lease.session.authoritativeSnapshot()
        deliverToWeb(
            AttachmentWireCodec.encode(
                AttachmentWireMessage.BootstrapSnapshot(
                    attachmentId = attachmentId,
                    documentRevision = snapshot.revision,
                    source = snapshot.text,
                ),
            ),
        )
    }

    private fun deliverHostIncrementalUpdate(update: AuthoritativeHostUpdate) {
        // Steady-state pushes are gated on this exact runtime having already completed its one
        // bootstrap boundary; the eventual bootstrap snapshot always carries the current
        // revision, so an update observed before bootstrap needs no separate delivery.
        if (!isCurrentRuntime() || !bootstrapSent) return
        deliverToWeb(
            AttachmentWireCodec.encode(
                AttachmentWireMessage.HostIncrementalUpdate(
                    attachmentId = update.attachmentId,
                    documentRevision = update.revision,
                    edit = update.edit,
                ),
            ),
        )
    }

    private fun deliverToWeb(raw: String) {
        if (!isCurrentRuntime()) return
        val payloadLiteral = gson.toJson(raw)
        transport.executeJavaScript(
            """
            (function(payload) {
                if (typeof window.__markflowSourceNativeReceive === 'function') {
                    window.__markflowSourceNativeReceive(payload);
                }
            })($payloadLiteral);
            """.trimIndent(),
        )
    }

    override fun dispose() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (disposed) return
        invalidated = true
        disposed = true
        releaseOwnedResources()?.let { throw it }
    }

    /**
     * Releases every resource for which this runtime has accepted ownership, attempting all
     * releases even when one cleanup action fails. This keeps disposal and constructor rollback
     * failure-atomic with respect to the remaining resources rather than stopping at the first
     * exception and leaking the rest.
     */
    private fun releaseOwnedResources(): Throwable? {
        var firstFailure: Throwable? = null

        fun release(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                if (firstFailure == null) {
                    firstFailure = failure
                } else {
                    firstFailure!!.addSuppressed(failure)
                }
            }
        }

        if (::hostUpdateBinding.isInitialized) {
            // AttachmentHostUpdateBinding registers its DocumentSession mutation-listener
            // subscription as a Disposer child of itself. Disposer.dispose must therefore own
            // this transition; direct .dispose() would leave that child subscription registered.
            release { Disposer.dispose(hostUpdateBinding) }
        }
        if (::coordinator.isInitialized) {
            release { coordinator.dispose() }
        }
        release { transport.dispose() }
        release { lease.dispose() }
        releaseWebviewResource?.let { release(it) }

        if (countedLiveInstance) {
            countedLiveInstance = false
            liveInstances.decrementAndGet()
        }
        return firstFailure
    }

    companion object {
        private const val READY_ACK_RESPONSE = "{\"type\":\"runtimeReadyAck\"}"

        private val liveInstances = AtomicInteger(0)

        /** Number of currently undisposed runtime owners, for repeated-lifecycle-cycle evidence. */
        internal val liveInstanceCount: Int
            get() = liveInstances.get()

        /**
         * Creates one fresh per-surface runtime owner, or `null` if JCEF is unavailable or the
         * source-native web bundle cannot be located.
         *
         * The default bundled-webview path acquires one explicit [MarkFlowWebviewResourceManager]
         * owner reference and transfers that reference to the returned runtime. Custom URL
         * suppliers (used by deterministic tests) are external/non-owned resources and therefore
         * have no corresponding manager release.
         *
         * Any exception before ownership transfer rolls back every resource already acquired. If
         * runtime initialization fails after transfer (for example `loadUrl` throws), the runtime
         * constructor performs the same all-resources rollback before rethrowing.
         */
        fun create(
            project: Project,
            document: Document,
            sourceNativeBaseUrl: (() -> String?)? = null,
            isJcefAvailable: () -> Boolean = { MarkFlowJcefSupport.isAvailable },
            transportFactory: () -> SourceNativeRuntimeTransport = { JcefSourceNativeRuntimeTransport() },
        ): SourceNativeEditorRuntime? {
            ApplicationManager.getApplication().assertIsDispatchThread()
            if (!isJcefAvailable()) return null

            var releaseWebviewResource: (() -> Unit)? = null
            val baseUrl = if (sourceNativeBaseUrl == null) {
                if (MarkFlowWebviewResourceManager.acquire() == null) return null
                releaseWebviewResource = { MarkFlowWebviewResourceManager.release() }
                MarkFlowWebviewResourceManager.loadSourceNativeIndexUrl() ?: run {
                    releaseWebviewResource.invoke()
                    return null
                }
            } else {
                sourceNativeBaseUrl.invoke() ?: return null
            }

            var lease: DocumentSessionLease? = null
            var transport: SourceNativeRuntimeTransport? = null
            var ownershipTransferred = false

            try {
                lease = DocumentSessionRegistry.getInstance(project).acquire(document)
                val attachmentId = SourceNativeRuntimeIdentity.freshAttachmentId()
                val runtimeToken = SourceNativeRuntimeIdentity.freshRuntimeToken()
                transport = transportFactory()
                val sourceNativeUrl = buildSourceNativeUrl(baseUrl, attachmentId, runtimeToken)

                // From this point the constructor owns rollback if initialization itself fails.
                ownershipTransferred = true
                return SourceNativeEditorRuntime(
                    lease = lease,
                    attachmentId = attachmentId,
                    runtimeToken = runtimeToken,
                    transport = transport,
                    sourceNativeUrl = sourceNativeUrl,
                    releaseWebviewResource = releaseWebviewResource,
                )
            } catch (failure: Throwable) {
                if (!ownershipTransferred) {
                    cleanupBeforeOwnershipTransfer(transport, lease, releaseWebviewResource)
                        ?.let(failure::addSuppressed)
                }
                throw failure
            }
        }

        private fun cleanupBeforeOwnershipTransfer(
            transport: SourceNativeRuntimeTransport?,
            lease: DocumentSessionLease?,
            releaseWebviewResource: (() -> Unit)?,
        ): Throwable? {
            var firstFailure: Throwable? = null

            fun release(action: () -> Unit) {
                try {
                    action()
                } catch (failure: Throwable) {
                    if (firstFailure == null) {
                        firstFailure = failure
                    } else {
                        firstFailure!!.addSuppressed(failure)
                    }
                }
            }

            transport?.let { release(it::dispose) }
            lease?.let { release(it::dispose) }
            releaseWebviewResource?.let { release(it) }
            return firstFailure
        }

        private fun buildSourceNativeUrl(baseUrl: String, attachmentId: AttachmentId, runtimeToken: String): String {
            fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
            return "$baseUrl?attachmentId=${encode(attachmentId.value)}&runtimeToken=${encode(runtimeToken)}"
        }
    }
}
