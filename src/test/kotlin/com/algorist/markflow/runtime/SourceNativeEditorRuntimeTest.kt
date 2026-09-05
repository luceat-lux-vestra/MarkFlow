package com.algorist.markflow.runtime

import com.algorist.markflow.document.DocumentSession
import com.algorist.markflow.document.DocumentSessionRegistry
import com.algorist.markflow.sync.AttachmentId
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.net.URLDecoder

/**
 * Adversarial lifecycle/ownership/transport evidence for #105 using [FakeSourceNativeRuntimeTransport]
 * lifecycle fakes around the browser/query boundary, exactly as #105 permits. No real JCEF browser
 * is constructed by this suite; [JcefSourceNativeRuntimeTransport] has no automated coverage because
 * headless CI has no JCEF runtime (documented in the PR).
 */
class SourceNativeEditorRuntimeTest : BasePlatformTestCase() {

    fun testOneRuntimeOwnsExactlyOneTransportForItsLifetime() {
        val document = document("owner.md", "hello")
        val fake = FakeSourceNativeRuntimeTransport()

        val runtime = onEdtResult { createRuntime(document, fake) }
        assertNotNull(runtime)
        // Exactly one browser/realm was created and loaded for this runtime's lifetime.
        assertEquals(1, fake.loadedUrls.size)
        val url = fake.loadedUrls.single()
        assertTrue(url.startsWith("http://source-native/index.html?"))
        assertTrue(url.contains("attachmentId="))
        assertTrue(url.contains("runtimeToken="))
        assertFalse(fake.disposed)

        onEdt { runtime!!.dispose() }
        assertTrue(fake.disposed)
    }

    fun testSplitSurfacesShareDocumentSessionButHaveDistinctBrowsersAndAttachmentIds() {
        val document = document("split.md", "shared")
        val fakeA = FakeSourceNativeRuntimeTransport()
        val fakeB = FakeSourceNativeRuntimeTransport()

        val runtimeA = onEdtResult { createRuntime(document, fakeA) }!!
        val runtimeB = onEdtResult { createRuntime(document, fakeB) }!!

        assertNotEquals(runtimeA.attachmentId, runtimeB.attachmentId)
        assertNotSame(fakeA, fakeB)
        assertEquals(1, onEdtResult { registry().activeSessionCount })

        onEdt {
            runtimeA.dispose()
            assertEquals(1, registry().activeSessionCount)
            runtimeB.dispose()
            assertEquals(0, registry().activeSessionCount)
        }
    }

    fun testDisposingOneSplitSurfaceReleasesItsListenerFromTheStillLiveSharedSession() {
        val document = document("split-listener-leak.md", "shared")
        val fakeA = FakeSourceNativeRuntimeTransport()
        val fakeB = FakeSourceNativeRuntimeTransport()
        val runtimeA = onEdtResult { createRuntime(document, fakeA) }!!
        val runtimeB = onEdtResult { createRuntime(document, fakeB) }!!
        ready(fakeA)
        ready(fakeB)

        val listenerCountWithBoth = onEdtResult { sharedDocumentSession(document).mutationListenerCount }
        assertEquals(2, listenerCountWithBoth)

        onEdt { runtimeA.dispose() }

        // The still-live shared DocumentSession must not retain A's disposed AttachmentHostUpdateBinding
        // subscription: disposing one split surface must actually detach its listener, not merely
        // make its callback body a no-op while the registration itself leaks.
        val listenerCountAfterOneDisposed = onEdtResult { sharedDocumentSession(document).mutationListenerCount }
        assertEquals(1, listenerCountAfterOneDisposed)

        // B is still fully functional after A's disposal.
        fakeB.transportHandler!!.invoke(mutationRequestJson(runtimeB.attachmentId, "still-live", "0", 0, 0, "X"))
        assertEquals("Xshared", document.text)

        onEdt { runtimeB.dispose() }
        assertEquals(0, onEdtResult { sharedDocumentSession(document).mutationListenerCount })
    }

    fun testSeparateDocumentsAreIsolated() {
        val documentA = document("doc-a.md", "aaa")
        val documentB = document("doc-b.md", "bbb")
        val fakeA = FakeSourceNativeRuntimeTransport()
        val fakeB = FakeSourceNativeRuntimeTransport()

        val runtimeA = onEdtResult { createRuntime(documentA, fakeA) }!!
        val runtimeB = onEdtResult { createRuntime(documentB, fakeB) }!!
        ready(fakeA)

        assertNotEquals(runtimeA.attachmentId, runtimeB.attachmentId)
        assertTrue(fakeA.executedScripts.any { it.contains("\\\"source\\\":\\\"aaa\\\"") })

        // A message carrying attachment A's id sent to runtime B's own handler cannot cross documents.
        val crossResult = fakeB.transportHandler!!.invoke(mutationRequestJson(runtimeA.attachmentId, "r-cross", "0", 0, 0, "x"))
        assertNull(crossResult)
        assertEquals("aaa", documentA.text)
        assertEquals("bbb", documentB.text)

        onEdt { runtimeA.dispose(); runtimeB.dispose() }
    }

    fun testExactlyOneBootstrapAfterReadinessAndRepeatedReadinessIsInert() {
        val document = document("bootstrap.md", "content")
        val fake = FakeSourceNativeRuntimeTransport()
        val runtime = onEdtResult { createRuntime(document, fake) }!!

        fake.fireLoadEnd()
        assertTrue(fake.executedScripts.contains("/* fake-bridge-glue */"))

        val ackFirst = ready(fake)
        assertEquals("{\"type\":\"runtimeReadyAck\"}", ackFirst)
        assertTrue(runtime.isBootstrapped)
        assertEquals(1, fake.deliveredMessageCount("bootstrapSnapshot"))

        // A repeated readiness signal from the same still-current runtime must not resend bootstrap.
        val ackSecond = ready(fake)
        assertEquals("{\"type\":\"runtimeReadyAck\"}", ackSecond)
        assertEquals(1, fake.deliveredMessageCount("bootstrapSnapshot"))

        onEdt { runtime.dispose() }
    }

    fun testStaleReadinessFromDisposedRuntimeIsInert() {
        val document = document("stale-ready.md", "content")
        val fake = FakeSourceNativeRuntimeTransport()
        val runtime = onEdtResult { createRuntime(document, fake) }!!
        val realReadySignal = readySignalJsonFromUrl(fake)
        val staleReadinessHandler = fake.readinessHandler!!

        onEdt { runtime.dispose() }

        val response = staleReadinessHandler.invoke(realReadySignal)
        assertNull(response)
        assertFalse(runtime.isBootstrapped)
        assertEquals(0, fake.deliveredMessageCount("bootstrapSnapshot"))
    }

    fun testReadinessWithWrongAttachmentOrTokenIsRejected() {
        val document = document("wrong-token.md", "content")
        val fake = FakeSourceNativeRuntimeTransport()
        val runtime = onEdtResult { createRuntime(document, fake) }!!

        val wrongAttachment = fake.readinessHandler!!.invoke(
            "{\"type\":\"runtimeReady\",\"attachmentId\":\"someone-else\",\"runtimeToken\":\"irrelevant\"}",
        )
        assertNull(wrongAttachment)

        val url = fake.loadedUrls.single()
        val realAttachmentId = urlParam(url, "attachmentId")
        val wrongToken = fake.readinessHandler!!.invoke(
            "{\"type\":\"runtimeReady\",\"attachmentId\":\"$realAttachmentId\",\"runtimeToken\":\"wrong-token\"}",
        )
        assertNull(wrongToken)
        assertFalse(runtime.isBootstrapped)

        onEdt { runtime.dispose() }
    }

    fun testValidMutationReachesCoordinatorExactlyOnceAndReturnsStrictAck() {
        val document = document("valid-mutation.md", "0123456789")
        val fake = FakeSourceNativeRuntimeTransport()
        val runtime = onEdtResult { createRuntime(document, fake) }!!
        ready(fake)

        val response = fake.transportHandler!!.invoke(mutationRequestJson(runtime.attachmentId, "req-1", "0", 0, 1, "Z"))

        assertNotNull(response)
        assertTrue(response!!.contains("\"type\":\"mutationAccepted\""))
        assertTrue(response.contains("\"finalDocumentRevision\":\"1\""))
        assertEquals("Z123456789", document.text)

        // A replayed identical request must be rejected, proving delegation happened exactly once.
        val replay = fake.transportHandler!!.invoke(mutationRequestJson(runtime.attachmentId, "req-1", "0", 0, 1, "Z"))
        assertTrue(replay!!.contains("\"type\":\"mutationRejected\""))
        assertEquals("Z123456789", document.text)

        onEdt { runtime.dispose() }
    }

    fun testMalformedBlankAndLegacyMessagesCannotMutateOrSucceed() {
        val document = document("malformed.md", "abc")
        val fake = FakeSourceNativeRuntimeTransport()
        val runtime = onEdtResult { createRuntime(document, fake) }!!
        ready(fake)

        val malformedInputs = listOf(
            "",
            "not json",
            "{}",
            "null",
            "{\"type\":\"unknown\"}",
            // Legacy whole-Markdown shape must not be reinterpreted as the target protocol.
            "{\"action\":\"update\",\"rawMarkdown\":\"pwned\",\"sourceRevision\":0}",
            // Extra-field variant of an otherwise-valid mutation request.
            "{\"type\":\"mutationRequest\",\"attachmentId\":\"${runtime.attachmentId}\",\"requestId\":\"x\"," +
                "\"baseDocumentRevision\":\"0\",\"edits\":[{\"from\":0,\"to\":0,\"inserted\":\"z\"}],\"extra\":true}",
            // Host-authored message shapes must never be accepted from the web direction.
            "{\"type\":\"bootstrapSnapshot\",\"attachmentId\":\"${runtime.attachmentId}\",\"documentRevision\":\"0\",\"source\":\"pwned\"}",
        )

        for (input in malformedInputs) {
            val result = fake.transportHandler!!.invoke(input)
            assertNull("expected fail-closed rejection for: $input", result)
        }
        assertEquals("abc", document.text)

        onEdt { runtime.dispose() }
    }

    fun testStaleAttachmentMutationCannotMutateReplacement() {
        val document = document("replacement.md", "abc")
        val fakeOld = FakeSourceNativeRuntimeTransport()
        val oldRuntime = onEdtResult { createRuntime(document, fakeOld) }!!
        ready(fakeOld)
        val staleHandler = fakeOld.transportHandler!!
        val staleAttachmentId = oldRuntime.attachmentId

        onEdt { oldRuntime.dispose() }

        val fakeNew = FakeSourceNativeRuntimeTransport()
        val newRuntime = onEdtResult { createRuntime(document, fakeNew) }!!
        ready(fakeNew)
        assertNotEquals(staleAttachmentId, newRuntime.attachmentId)

        // The old runtime's own captured handler is inert after its own disposal.
        val staleResult = staleHandler.invoke(mutationRequestJson(staleAttachmentId, "req-stale", "0", 0, 1, "Z"))
        assertNull(staleResult)

        // A message carrying the old attachment id sent to the *new* runtime's handler is also rejected.
        val wrongAttachmentResult = fakeNew.transportHandler!!.invoke(mutationRequestJson(staleAttachmentId, "req-wrong", "0", 0, 1, "Z"))
        assertNull(wrongAttachmentResult)
        assertEquals("abc", document.text)

        onEdt { newRuntime.dispose() }
    }

    fun testRecoveryCorrelationIsExact() {
        val document = document("recovery.md", "recover-me")
        val fake = FakeSourceNativeRuntimeTransport()
        val runtime = onEdtResult { createRuntime(document, fake) }!!
        ready(fake)

        val response = fake.transportHandler!!.invoke(
            "{\"type\":\"snapshotRequest\",\"attachmentId\":\"${runtime.attachmentId}\",\"recoveryId\":\"recovery-42\"}",
        )
        assertNotNull(response)
        assertTrue(response!!.contains("\"type\":\"recoverySnapshot\""))
        assertTrue(response.contains("\"recoveryId\":\"recovery-42\""))
        assertTrue(response.contains("\"source\":\"recover-me\""))

        onEdt { runtime.dispose() }
    }

    fun testCanonicalHostEventProducesExactIncrementalUpdateAndSuppressesOwnOrigin() {
        val document = document("incremental.md", "0123456789")
        val fakeA = FakeSourceNativeRuntimeTransport()
        val fakeB = FakeSourceNativeRuntimeTransport()
        val runtimeA = onEdtResult { createRuntime(document, fakeA) }!!
        val runtimeB = onEdtResult { createRuntime(document, fakeB) }!!
        ready(fakeA)
        ready(fakeB)

        fakeA.transportHandler!!.invoke(mutationRequestJson(runtimeA.attachmentId, "own-req", "0", 0, 1, "Z"))

        // The originating attachment receives no hostIncrementalUpdate for its own synchronous WEB event.
        assertEquals(0, fakeA.deliveredMessageCount("hostIncrementalUpdate"))
        // The other attachment observing the same DocumentSession receives exactly one.
        assertEquals(1, fakeB.deliveredMessageCount("hostIncrementalUpdate"))
        val delivered = fakeB.executedScripts.last { it.contains("hostIncrementalUpdate") }
        assertTrue(delivered.contains("\\\"documentRevision\\\":\\\"1\\\""))

        onEdt { runtimeA.dispose(); runtimeB.dispose() }
    }

    fun testCallbacksAfterDisposalAreInert() {
        val document = document("dispose-inert.md", "abc")
        val fake = FakeSourceNativeRuntimeTransport()
        val runtime = onEdtResult { createRuntime(document, fake) }!!
        ready(fake)
        val transportHandler = fake.transportHandler!!
        val readinessHandler = fake.readinessHandler!!
        val loadEndHandler = fake.loadEndHandler!!
        val readySignal = readySignalJsonFromUrl(fake)
        val scriptCountBeforeDispose = fake.executedScripts.size

        onEdt { runtime.dispose() }

        assertNull(transportHandler.invoke(mutationRequestJson(runtime.attachmentId, "post-dispose", "1", 0, 1, "Q")))
        assertNull(readinessHandler.invoke(readySignal))
        loadEndHandler.invoke() // must not throw and must not re-inject glue after dispose
        assertEquals(scriptCountBeforeDispose, fake.executedScripts.size)
        assertEquals("abc", document.text)
    }

    fun testDisposalReleasesEveryOwnedResourceExactlyOnce() {
        val document = document("dispose-once.md", "abc")
        val fake = FakeSourceNativeRuntimeTransport()
        val runtime = onEdtResult { createRuntime(document, fake) }!!
        assertFalse(fake.disposed)
        assertEquals(1, onEdtResult { registry().activeSessionCount })

        onEdt {
            runtime.dispose()
            runtime.dispose() // idempotent: must not double-release
            runtime.dispose()
        }

        assertTrue(fake.disposed)
        assertEquals(0, onEdtResult { registry().activeSessionCount })
        assertTrue(runtime.isDisposed)
    }

    fun testRepeatedLifecycleCyclesShowNoRetainedResourceGrowth() {
        val document = document("cycles.md", "abc")
        repeat(25) {
            val fake = FakeSourceNativeRuntimeTransport()
            val runtime = onEdtResult { createRuntime(document, fake) }!!
            ready(fake)
            onEdt { runtime.dispose() }
        }
        assertEquals(0, SourceNativeEditorRuntime.liveInstanceCount)
        assertEquals(0, onEdtResult { registry().activeSessionCount })
        assertEquals("abc", document.text)
    }

    fun testNoEditLifecycleDoesNotMutateMarkdown() {
        val document = document("no-edit.md", "untouched content")
        val fake = FakeSourceNativeRuntimeTransport()
        val runtime = onEdtResult { createRuntime(document, fake) }!!
        fake.fireLoadEnd()
        ready(fake)
        onEdt { runtime.dispose() }

        assertEquals("untouched content", document.text)
    }

    fun testJcefUnavailableCannotProduceARuntimeOrLease() {
        val document = document("jcef-unavailable.md", "abc")
        val sessionCountBefore = onEdtResult { registry().activeSessionCount }

        val runtime = onEdtResult {
            SourceNativeEditorRuntime.create(
                project = project,
                document = document,
                sourceNativeBaseUrl = { "http://source-native/index.html" },
                isJcefAvailable = { false },
                transportFactory = { FakeSourceNativeRuntimeTransport() },
            )
        }

        assertNull(runtime)
        assertEquals(sessionCountBefore, onEdtResult { registry().activeSessionCount })
    }

    fun testMissingSourceNativeBundleCannotProduceARuntimeOrLease() {
        val document = document("missing-bundle.md", "abc")
        val sessionCountBefore = onEdtResult { registry().activeSessionCount }

        val runtime = onEdtResult {
            SourceNativeEditorRuntime.create(
                project = project,
                document = document,
                sourceNativeBaseUrl = { null },
                isJcefAvailable = { true },
                transportFactory = { FakeSourceNativeRuntimeTransport() },
            )
        }

        assertNull(runtime)
        assertEquals(sessionCountBefore, onEdtResult { registry().activeSessionCount })
    }

    // --- helpers -------------------------------------------------------------------------------

    /**
     * Registers every created runtime with [testRootDisposable] as a safety net: if a test
     * assertion fails before its own explicit `dispose()` call, tearDown still disposes the
     * runtime instead of leaking a live instance (and its [DocumentSessionRegistry] lease) into
     * later tests. `dispose()` is idempotent, so an explicit prior dispose is unaffected.
     */
    private fun createRuntime(
        document: Document,
        transport: FakeSourceNativeRuntimeTransport,
    ): SourceNativeEditorRuntime? {
        val runtime = SourceNativeEditorRuntime.create(
            project = project,
            document = document,
            sourceNativeBaseUrl = { "http://source-native/index.html" },
            isJcefAvailable = { true },
            transportFactory = { transport },
        )
        runtime?.let { com.intellij.openapi.util.Disposer.register(testRootDisposable, it) }
        return runtime
    }

    /** Fires load-end (idempotent), sends the real readiness signal, and flushes the scheduled bootstrap. */
    private fun ready(fake: FakeSourceNativeRuntimeTransport): String? {
        fake.fireLoadEnd()
        val response = fake.readinessHandler!!.invoke(readySignalJsonFromUrl(fake))
        onEdt { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() }
        return response
    }

    private fun readySignalJsonFromUrl(fake: FakeSourceNativeRuntimeTransport): String {
        val url = fake.loadedUrls.single()
        return "{\"type\":\"runtimeReady\",\"attachmentId\":\"${urlParam(url, "attachmentId")}\"," +
            "\"runtimeToken\":\"${urlParam(url, "runtimeToken")}\"}"
    }

    private fun urlParam(url: String, name: String): String {
        val query = url.substringAfter('?')
        val raw = query.split('&').first { it.startsWith("$name=") }.substringAfter('=')
        return URLDecoder.decode(raw, Charsets.UTF_8)
    }

    private fun mutationRequestJson(
        attachmentId: AttachmentId,
        requestId: String,
        baseRevision: String,
        from: Int,
        to: Int,
        inserted: String,
    ): String =
        "{\"type\":\"mutationRequest\",\"attachmentId\":\"$attachmentId\",\"requestId\":\"$requestId\"," +
            "\"baseDocumentRevision\":\"$baseRevision\",\"edits\":[{\"from\":$from,\"to\":$to,\"inserted\":\"$inserted\"}]}"

    private fun document(fileName: String, text: String): Document =
        myFixture.configureByText(fileName, text).fileDocument

    private fun registry(): DocumentSessionRegistry = DocumentSessionRegistry.getInstance(project)

    /** Reads the shared [DocumentSession] for [document] via a throwaway lease that is released immediately. */
    private fun sharedDocumentSession(document: Document): DocumentSession {
        val lease = registry().acquire(document)
        try {
            return lease.session
        } finally {
            lease.dispose()
        }
    }

    private fun onEdt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) action() else application.invokeAndWait(Runnable(action), application.defaultModalityState)
    }

    private fun <T> onEdtResult(action: () -> T): T {
        var result: T? = null
        onEdt { result = action() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
