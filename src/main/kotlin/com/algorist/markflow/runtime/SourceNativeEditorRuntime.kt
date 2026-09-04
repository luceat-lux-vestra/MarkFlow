package com.algorist.markflow.runtime

import com.algorist.markflow.browser.MarkFlowJcefSupport
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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComponent

/**
 * The per-surface JCEF runtime owner required by #105/#81.
 *
 * Exactly one live instance owns, for its own lifetime only:
 * - one [SourceNativeRuntimeTransport] (one browser realm, one mutation/recovery query, one
 *   readiness query);
 * - one fresh [AttachmentId];
 * - one [AttachmentSyncCoordinator] consuming an already-owned/shared [DocumentSessionLease];
 * - one [AttachmentHostUpdateBinding];
 * - one [DocumentSessionRegistry] consumer lease.
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
) : Disposable {
    private val gson = Gson()
    private val coordinator = AttachmentSyncCoordinator(attachmentId, lease.session)
    private val hostUpdateBinding: AttachmentHostUpdateBinding
    private val readySignalReceived = AtomicBoolean(false)

    @Volatile
    private var bootstrapSent = false

    @Volatile
    private var disposed = false

    /** The browser's Swing component for this runtime's current lifetime. */
    val component: JComponent
        get() = transport.component

    val isDisposed: Boolean
        get() = disposed

    /** True only after this exact runtime instance has sent its one [AttachmentWireMessage.BootstrapSnapshot]. */
    val isBootstrapped: Boolean
        get() = bootstrapSent

    init {
        hostUpdateBinding = AttachmentHostUpdateBinding(coordinator, ::deliverHostIncrementalUpdate)
        transport.setTransportMessageHandler(::handleTransportMessage)
        transport.setReadinessMessageHandler(::handleReadinessMessage)
        transport.setLoadEndHandler(::onLoadEnd)
        transport.loadUrl(sourceNativeUrl)
        liveInstances.incrementAndGet()
    }

    private fun onLoadEnd() {
        if (disposed) return
        transport.executeJavaScript(transport.buildBridgeGlueScript())
    }

    /**
     * Invoked by [transport] for every web -> host mutation/recovery message. May run off the
     * EDT; this method is the one explicit dispatch boundary that reaches onto the EDT for the
     * document/sync domain, and it re-checks disposal both before and after that dispatch so a
     * disposal/replacement race can never let stale work reach [coordinator] or [lease].
     */
    private fun handleTransportMessage(raw: String): String? {
        if (disposed) return null
        var result: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            if (disposed) return@invokeAndWait
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
        if (disposed) return null
        val signal = RuntimeReadinessCodec.decode(raw) ?: return null
        if (signal.attachmentId != attachmentId || signal.runtimeToken != runtimeToken) return null

        if (readySignalReceived.compareAndSet(false, true)) {
            ApplicationManager.getApplication().invokeLater { sendBootstrapSnapshotOnce() }
        }
        return READY_ACK_RESPONSE
    }

    private fun sendBootstrapSnapshotOnce() {
        if (disposed || bootstrapSent) return
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
        if (disposed || !bootstrapSent) return
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
        if (disposed) return
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
        disposed = true
        hostUpdateBinding.dispose()
        coordinator.dispose()
        transport.dispose()
        lease.dispose()
        liveInstances.decrementAndGet()
    }

    companion object {
        private const val READY_ACK_RESPONSE = "{\"type\":\"runtimeReadyAck\"}"

        private val liveInstances = AtomicInteger(0)

        /** Number of currently undisposed runtime owners, for repeated-lifecycle-cycle evidence. */
        internal val liveInstanceCount: Int
            get() = liveInstances.get()

        /**
         * Creates one fresh per-surface runtime owner, or `null` if JCEF is unavailable or the
         * source-native web bundle cannot be located. On `null`, no [DocumentSessionLease] is
         * acquired and no transport is created: a failed-to-initialize attempt leaves no partial
         * ownership behind.
         */
        fun create(
            project: Project,
            document: Document,
            sourceNativeBaseUrl: () -> String?,
            isJcefAvailable: () -> Boolean = { MarkFlowJcefSupport.isAvailable },
            transportFactory: () -> SourceNativeRuntimeTransport = { JcefSourceNativeRuntimeTransport() },
        ): SourceNativeEditorRuntime? {
            ApplicationManager.getApplication().assertIsDispatchThread()
            if (!isJcefAvailable()) return null
            val baseUrl = sourceNativeBaseUrl() ?: return null

            val lease = DocumentSessionRegistry.getInstance(project).acquire(document)
            val attachmentId = SourceNativeRuntimeIdentity.freshAttachmentId()
            val runtimeToken = SourceNativeRuntimeIdentity.freshRuntimeToken()
            val transport = transportFactory()

            return SourceNativeEditorRuntime(
                lease = lease,
                attachmentId = attachmentId,
                runtimeToken = runtimeToken,
                transport = transport,
                sourceNativeUrl = buildSourceNativeUrl(baseUrl, attachmentId, runtimeToken),
            )
        }

        private fun buildSourceNativeUrl(baseUrl: String, attachmentId: AttachmentId, runtimeToken: String): String {
            fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
            return "$baseUrl?attachmentId=${encode(attachmentId.value)}&runtimeToken=${encode(runtimeToken)}"
        }
    }
}
