package com.algorist.markflow.runtime

import com.algorist.markflow.browser.LocalDocumentRegistration
import com.algorist.markflow.browser.MarkFlowWebviewResourceManager
import com.intellij.openapi.Disposable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One runtime-owned document-local image capability.
 *
 * The opaque token remains inside [baseUrl] and browser-realm memory; it is never a document path,
 * never a source authority and never part of the source-native page URL. This wrapper owns both
 * the target-only registration and the resource-manager reference that keeps its loopback endpoint alive.
 */
internal interface SourceNativeLocalImageCapability : Disposable {
    val baseUrl: String
}

internal fun createSourceNativeLocalImageCapability(documentPath: String): SourceNativeLocalImageCapability? {
    if (MarkFlowWebviewResourceManager.acquire() == null) return null

    var registration: LocalDocumentRegistration? = null
    return try {
        registration = MarkFlowWebviewResourceManager.registerSourceNativeLocalImage(documentPath)
        val current = registration
        if (current == null) {
            MarkFlowWebviewResourceManager.release()
            null
        } else {
            ManagedSourceNativeLocalImageCapability(current)
        }
    } catch (failure: Throwable) {
        fun cleanup(action: () -> Unit) {
            try {
                action()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
        }
        cleanup { MarkFlowWebviewResourceManager.unregisterSourceNativeLocalImage(registration?.token) }
        cleanup { MarkFlowWebviewResourceManager.release() }
        throw failure
    }
}

private class ManagedSourceNativeLocalImageCapability(
    private val registration: LocalDocumentRegistration,
) : SourceNativeLocalImageCapability {
    private val disposed = AtomicBoolean(false)

    override val baseUrl: String
        get() = registration.baseUrl

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return

        var firstFailure: Throwable? = null
        fun release(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                val existing = firstFailure
                if (existing == null) {
                    firstFailure = failure
                } else {
                    existing.addSuppressed(failure)
                }
            }
        }

        release { MarkFlowWebviewResourceManager.unregisterSourceNativeLocalImage(registration.token) }
        release { MarkFlowWebviewResourceManager.release() }
        firstFailure?.let { throw it }
    }
}
