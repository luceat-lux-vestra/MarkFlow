package com.algorist.markflow.runtime

import com.algorist.markflow.document.DocumentSessionRegistry
import com.algorist.markflow.sync.AttachmentId
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.net.URLDecoder

/** Reviewer-specified adversarial evidence for the #105 page-reload/new-JS-realm boundary. */
class SourceNativeEditorRuntimeRealmReplacementTest : BasePlatformTestCase() {

    fun testReplacementLoadStartImmediatelyFencesOldRealmBeforeCleanup() {
        val document = document("realm-replacement.md", "abc")
        val fake = FakeSourceNativeRuntimeTransport()
        val runtime = onEdtResult { createRuntime(document, fake) }
        ready(fake)

        val staleTransportHandler = fake.transportHandler!!
        val staleReadinessHandler = fake.readinessHandler!!
        val readySignal = readySignalJsonFromUrl(fake)
        val hostPushesBefore = fake.deliveredMessageCount("hostIncrementalUpdate")
        val scriptsBefore = fake.executedScripts.size

        // A second main-frame navigation is the earliest reliable signal that the one-realm
        // lifetime is ending. It must invalidate synchronously, before EDT cleanup runs.
        fake.fireRealmReplacementLoadStart()

        assertNull(staleReadinessHandler.invoke(readySignal))
        assertNull(
            staleTransportHandler.invoke(
                mutationRequestJson(runtime.attachmentId, "stale-after-reload", "0", 0, 1, "Z"),
            ),
        )
        assertEquals("abc", document.text)

        // The binding is still registered until the scheduled disposal executes, so mutate the
        // authoritative Document now: invalidation itself must suppress delivery into the new realm.
        onEdt {
            ApplicationManager.getApplication().runWriteAction {
                document.insertString(0, "H")
            }
        }
        assertEquals("Habc", document.text)
        assertEquals(hostPushesBefore, fake.deliveredMessageCount("hostIncrementalUpdate"))
        assertEquals(scriptsBefore, fake.executedScripts.size)

        flushEdtQueue()

        assertTrue(runtime.isDisposed)
        assertTrue(fake.disposed)
        assertEquals(0, onEdtResult { registry().activeSessionCount })
        assertEquals(0, SourceNativeEditorRuntime.liveInstanceCount)
    }

    fun testReplacementRequiresFreshRuntimeIdentityAndOldCallbacksStayInert() {
        val document = document("realm-fresh-identity.md", "abc")
        val oldFake = FakeSourceNativeRuntimeTransport()
        val oldRuntime = onEdtResult { createRuntime(document, oldFake) }
        ready(oldFake)
        val oldAttachmentId = oldRuntime.attachmentId
        val staleTransportHandler = oldFake.transportHandler!!
        val staleReadinessHandler = oldFake.readinessHandler!!
        val staleReadySignal = readySignalJsonFromUrl(oldFake)

        oldFake.fireRealmReplacementLoadStart()
        flushEdtQueue()

        val newFake = FakeSourceNativeRuntimeTransport()
        val newRuntime = onEdtResult { createRuntime(document, newFake) }
        ready(newFake)

        assertNotEquals(oldAttachmentId, newRuntime.attachmentId)
        assertEquals(1, newFake.deliveredMessageCount("bootstrapSnapshot"))
        assertNull(staleReadinessHandler.invoke(staleReadySignal))
        assertNull(
            staleTransportHandler.invoke(
                mutationRequestJson(oldAttachmentId, "stale-old-realm", "0", 0, 0, "X"),
            ),
        )
        assertEquals("abc", document.text)

        onEdt { newRuntime.dispose() }
        assertEquals(0, SourceNativeEditorRuntime.liveInstanceCount)
        assertEquals(0, onEdtResult { registry().activeSessionCount })
    }

    fun testRepeatedLoadEndAlsoFailsClosedIfReplacementLoadStartWasMissed() {
        val document = document("realm-defensive-load-end.md", "abc")
        val fake = FakeSourceNativeRuntimeTransport()
        val runtime = onEdtResult { createRuntime(document, fake) }
        ready(fake)
        val scriptCountBefore = fake.executedScripts.size

        // Defensive path: model a transport/JCEF callback ordering anomaly where another load-end
        // is observed without the runtime seeing the corresponding second load-start.
        fake.fireRealmReplacementLoadEnd()
        flushEdtQueue()

        assertTrue(runtime.isDisposed)
        assertTrue(fake.disposed)
        assertEquals(scriptCountBefore, fake.executedScripts.size)
        assertEquals(0, onEdtResult { registry().activeSessionCount })
        assertEquals(0, SourceNativeEditorRuntime.liveInstanceCount)
    }

    private fun createRuntime(
        document: Document,
        transport: FakeSourceNativeRuntimeTransport,
    ): SourceNativeEditorRuntime {
        val runtime = SourceNativeEditorRuntime.create(
            project = project,
            document = document,
            sourceNativeBaseUrl = { "http://source-native/index.html" },
            isJcefAvailable = { true },
            transportFactory = { transport },
        )!!
        Disposer.register(testRootDisposable, runtime)
        return runtime
    }

    private fun ready(fake: FakeSourceNativeRuntimeTransport) {
        fake.fireLoadEnd()
        val response = fake.readinessHandler!!.invoke(readySignalJsonFromUrl(fake))
        assertEquals("{\"type\":\"runtimeReadyAck\"}", response)
        flushEdtQueue()
    }

    private fun readySignalJsonFromUrl(fake: FakeSourceNativeRuntimeTransport): String {
        val url = fake.loadedUrls.single()
        return "{\"type\":\"runtimeReady\",\"attachmentId\":\"${urlParam(url, "attachmentId")}\"," +
            "\"runtimeToken\":\"${urlParam(url, "runtimeToken\")}\"}"
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

    private fun flushEdtQueue() {
        onEdt { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() }
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
