package com.algorist.markflow.runtime

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class SourceNativeExternalNavigationRuntimeTest : BasePlatformTestCase() {

    fun testCurrentIdentityValidHttpsRequestOpensExternallyOnceWithoutSourceMutation() {
        val document = document("external-navigation.md", "[example](https://example.com/path?q=1#frag)")
        val transport = FakeSourceNativeRuntimeTransport()
        val opened = mutableListOf<URI>()
        val runtime = createRuntime(document, transport) { opened += it }
        val sourceBefore = document.text

        val response = transport.externalNavigationHandler!!.invoke(
            navigationRequest(transport, "https://example.com/path?q=1#frag"),
        )
        flushEdtQueue()

        assertEquals("{\"type\":\"openExternalAccepted\"}", response)
        assertEquals(listOf(URI("https://example.com/path?q=1#frag")), opened)
        assertEquals(sourceBefore, document.text)
        onEdt { runtime.dispose() }
    }

    fun testHttpIsAllowedButDeniedSchemesAndRelativeValuesNeverReachOpener() {
        val document = document("external-navigation-policy.md", "source")
        val transport = FakeSourceNativeRuntimeTransport()
        val opened = mutableListOf<URI>()
        val runtime = createRuntime(document, transport) { opened += it }

        assertNotNull(
            transport.externalNavigationHandler!!.invoke(
                navigationRequest(transport, "http://example.com/path"),
            ),
        )
        for (url in listOf(
            "javascript:alert(1)",
            "file:///etc/passwd",
            "data:text/plain,x",
            "blob:https://example.com/id",
            "//example.com/path",
            "relative.md",
            "../parent",
            "#fragment",
            "mailto:test@example.com",
        )) {
            assertNull(url, transport.externalNavigationHandler!!.invoke(navigationRequest(transport, url)))
        }
        flushEdtQueue()

        assertEquals(listOf(URI("http://example.com/path")), opened)
        assertEquals("source", document.text)
        onEdt { runtime.dispose() }
    }

    fun testWrongAttachmentOrRuntimeIdentityFailsClosed() {
        val document = document("external-navigation-identity.md", "source")
        val transport = FakeSourceNativeRuntimeTransport()
        val opened = mutableListOf<URI>()
        val runtime = createRuntime(document, transport) { opened += it }
        val identity = runtimeIdentity(transport)

        assertNull(
            transport.externalNavigationHandler!!.invoke(
                requestJson("wrong-attachment", identity.runtimeToken, "https://example.com/a"),
            ),
        )
        assertNull(
            transport.externalNavigationHandler!!.invoke(
                requestJson(identity.attachmentId, "wrong-token", "https://example.com/b"),
            ),
        )
        flushEdtQueue()

        assertTrue(opened.isEmpty())
        assertEquals("source", document.text)
        onEdt { runtime.dispose() }
    }

    fun testMalformedAndOversizedBridgePayloadsFailClosed() {
        val document = document("external-navigation-bounds.md", "source")
        val transport = FakeSourceNativeRuntimeTransport()
        val opened = mutableListOf<URI>()
        val runtime = createRuntime(document, transport) { opened += it }

        for (raw in listOf(
            "not-json",
            "{}",
            "{\"type\":\"openExternal\"}",
            "x".repeat(SourceNativeExternalNavigationProtocol.MAX_MESSAGE_LENGTH + 1),
        )) {
            assertNull(transport.externalNavigationHandler!!.invoke(raw))
        }
        flushEdtQueue()

        assertTrue(opened.isEmpty())
        assertEquals("source", document.text)
        onEdt { runtime.dispose() }
    }

    fun testCapturedCallbackAfterDisposeIsInert() {
        val document = document("external-navigation-dispose.md", "source")
        val transport = FakeSourceNativeRuntimeTransport()
        val opened = mutableListOf<URI>()
        val runtime = createRuntime(document, transport) { opened += it }
        val staleHandler = transport.externalNavigationHandler!!
        val request = navigationRequest(transport, "https://example.com/stale")

        onEdt { runtime.dispose() }
        assertNull(staleHandler.invoke(request))
        flushEdtQueue()

        assertTrue(opened.isEmpty())
        assertEquals("source", document.text)
    }

    fun testRealmReplacementFencesCapturedCallbackBeforeDeferredDisposeCompletes() {
        val document = document("external-navigation-realm.md", "source")
        val transport = FakeSourceNativeRuntimeTransport()
        val opened = mutableListOf<URI>()
        val runtime = createRuntime(document, transport) { opened += it }
        val staleHandler = transport.externalNavigationHandler!!
        val request = navigationRequest(transport, "https://example.com/stale-realm")

        transport.fireLoadEnd()
        transport.fireRealmReplacementLoadStart()
        assertNull(staleHandler.invoke(request))
        flushEdtQueue()

        assertTrue(runtime.isDisposed)
        assertTrue(opened.isEmpty())
        assertEquals("source", document.text)
    }

    fun testExternalBrowserFailureIsPresentationOnlyAndDoesNotEscape() {
        val document = document("external-navigation-opener-failure.md", "source")
        val transport = FakeSourceNativeRuntimeTransport()
        val runtime = createRuntime(document, transport) { throw IllegalStateException("browser unavailable") }

        val response = transport.externalNavigationHandler!!.invoke(
            navigationRequest(transport, "https://example.com/failure"),
        )
        assertEquals("{\"type\":\"openExternalAccepted\"}", response)
        flushEdtQueue() // an opener exception would escape the EDT queue here if not contained
        assertEquals("source", document.text)
        onEdt { runtime.dispose() }
    }

    private fun createRuntime(
        document: Document,
        transport: FakeSourceNativeRuntimeTransport,
        externalNavigator: (URI) -> Unit,
    ): SourceNativeEditorRuntime {
        val runtime = onEdtResult {
            SourceNativeEditorRuntime.create(
                project = project,
                document = document,
                sourceNativeBaseUrl = { "http://source-native/index.html" },
                isJcefAvailable = { true },
                transportFactory = { transport },
                externalNavigator = externalNavigator,
            )!!
        }
        Disposer.register(testRootDisposable, runtime)
        return runtime
    }

    private fun navigationRequest(transport: FakeSourceNativeRuntimeTransport, url: String): String {
        val identity = runtimeIdentity(transport)
        return requestJson(identity.attachmentId, identity.runtimeToken, url)
    }

    private fun requestJson(attachmentId: String, runtimeToken: String, url: String): String =
        "{\"type\":\"openExternal\",\"attachmentId\":\"$attachmentId\",\"runtimeToken\":\"$runtimeToken\",\"url\":\"$url\"}"

    private fun runtimeIdentity(transport: FakeSourceNativeRuntimeTransport): RuntimeIdentity {
        val query = URI(transport.loadedUrls.single()).rawQuery
            .split('&')
            .associate { part ->
                val pieces = part.split('=', limit = 2)
                URLDecoder.decode(pieces[0], StandardCharsets.UTF_8) to
                    URLDecoder.decode(pieces.getOrElse(1) { "" }, StandardCharsets.UTF_8)
            }
        return RuntimeIdentity(
            attachmentId = query.getValue("attachmentId"),
            runtimeToken = query.getValue("runtimeToken"),
        )
    }

    private fun document(fileName: String, text: String): Document =
        myFixture.configureByText(fileName, text).fileDocument

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

    private data class RuntimeIdentity(
        val attachmentId: String,
        val runtimeToken: String,
    )
}
