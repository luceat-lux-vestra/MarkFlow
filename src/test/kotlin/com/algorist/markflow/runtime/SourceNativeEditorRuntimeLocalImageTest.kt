package com.algorist.markflow.runtime

import com.algorist.markflow.document.DocumentSessionRegistry
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path

class SourceNativeEditorRuntimeLocalImageTest : BasePlatformTestCase() {
    private lateinit var tempRoot: Path

    override fun setUp() {
        super.setUp()
        tempRoot = Files.createTempDirectory("markflow-runtime-image-")
    }

    override fun tearDown() {
        try {
            tempRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testRuntimeTransfersCapabilityBeforeBootstrapAndOwnsItsDisposal() {
        val document = document("image-runtime.md", "![local](image.png)")
        val capability = capability()
        val fake = FakeSourceNativeRuntimeTransport()
        val runtime = onEdtResult {
            SourceNativeEditorRuntime.create(
                project = project,
                document = document,
                documentPath = "/logical/image-runtime.md",
                sourceNativeBaseUrl = { "http://source-native/index.html" },
                isJcefAvailable = { true },
                transportFactory = { fake },
                localImageCapabilityFactory = { path ->
                    assertEquals("/logical/image-runtime.md", path)
                    capability
                },
            )
        }!!
        Disposer.register(testRootDisposable, runtime)

        ready(fake)

        val capabilityScriptIndex = fake.executedScripts.indexOfFirst {
            it.contains("__markflowSourceNativeSetLocalImageCapability") && it.contains(capability.baseUrl)
        }
        val bootstrapScriptIndex = fake.executedScripts.indexOfFirst { it.contains("bootstrapSnapshot") }
        assertTrue(capabilityScriptIndex >= 0)
        assertTrue(bootstrapScriptIndex > capabilityScriptIndex)
        assertFalse(capability.isDisposed)
        assertEquals("![local](image.png)", document.text)

        onEdt {
            runtime.dispose()
            runtime.dispose()
        }
        assertTrue(capability.isDisposed)
        assertEquals(0, onEdtResult { registry().activeSessionCount })
        assertEquals("![local](image.png)", document.text)
    }

    fun testCapabilityCreationFailureDegradesImageOnlyAndStillBootstrapsSource() {
        val document = document("image-degraded.md", "source remains")
        val fake = FakeSourceNativeRuntimeTransport()
        val runtime = onEdtResult {
            SourceNativeEditorRuntime.create(
                project = project,
                document = document,
                documentPath = "/unavailable/image-degraded.md",
                sourceNativeBaseUrl = { "http://source-native/index.html" },
                isJcefAvailable = { true },
                transportFactory = { fake },
                localImageCapabilityFactory = { null },
            )
        }!!
        Disposer.register(testRootDisposable, runtime)

        ready(fake)

        assertTrue(runtime.isBootstrapped)
        assertEquals(1, fake.deliveredMessageCount("bootstrapSnapshot"))
        assertFalse(fake.executedScripts.any { it.contains("__markflowSourceNativeSetLocalImageCapability") })
        assertEquals("source remains", document.text)
        onEdt { runtime.dispose() }
    }

    fun testTransportFactoryFailureReleasesCapabilityBeforeOwnershipTransfer() {
        val document = document("image-factory-failure.md", "abc")
        val capability = capability()
        val sessionsBefore = onEdtResult { registry().activeSessionCount }

        val failure = captureFailure {
            onEdtResult {
                SourceNativeEditorRuntime.create(
                    project = project,
                    document = document,
                    documentPath = "/logical/image-factory-failure.md",
                    sourceNativeBaseUrl = { "http://source-native/index.html" },
                    isJcefAvailable = { true },
                    transportFactory = { throw IllegalStateException("transport failed") },
                    localImageCapabilityFactory = { capability },
                )
            }
        }

        assertTrue(failure is IllegalStateException)
        assertTrue(capability.isDisposed)
        assertEquals(sessionsBefore, onEdtResult { registry().activeSessionCount })
        assertEquals("abc", document.text)
    }

    fun testLoadFailureAfterOwnershipTransferReleasesCapability() {
        val document = document("image-load-failure.md", "abc")
        val capability = capability()
        val transport = ThrowingLoadTransport()
        val sessionsBefore = onEdtResult { registry().activeSessionCount }

        val failure = captureFailure {
            onEdtResult {
                SourceNativeEditorRuntime.create(
                    project = project,
                    document = document,
                    documentPath = "/logical/image-load-failure.md",
                    sourceNativeBaseUrl = { "http://source-native/index.html" },
                    isJcefAvailable = { true },
                    transportFactory = { transport },
                    localImageCapabilityFactory = { capability },
                )
            }
        }

        assertTrue(failure is IllegalStateException)
        assertTrue(capability.isDisposed)
        assertTrue(transport.disposed)
        assertEquals(sessionsBefore, onEdtResult { registry().activeSessionCount })
        assertEquals("abc", document.text)
    }

    fun testRealmReplacementInvalidatesCapabilityWithOwningRuntime() {
        val document = document("image-realm-replace.md", "abc")
        val capability = capability()
        val fake = FakeSourceNativeRuntimeTransport()
        val runtime = onEdtResult {
            SourceNativeEditorRuntime.create(
                project = project,
                document = document,
                documentPath = "/logical/image-realm-replace.md",
                sourceNativeBaseUrl = { "http://source-native/index.html" },
                isJcefAvailable = { true },
                transportFactory = { fake },
                localImageCapabilityFactory = { capability },
            )
        }!!
        Disposer.register(testRootDisposable, runtime)
        ready(fake)
        assertFalse(capability.isDisposed)

        fake.fireRealmReplacementLoadStart()
        onEdt { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() }

        assertTrue(runtime.isDisposed)
        assertTrue(capability.isDisposed)
        assertEquals(0, onEdtResult { registry().activeSessionCount })
        assertEquals("abc", document.text)
    }

    private fun capability(): SourceNativeLocalImageCapability {
        val documentDirectory = Files.createDirectories(tempRoot.resolve("docs-${System.nanoTime()}"))
        val document = documentDirectory.resolve("readme.md")
        Files.writeString(document, "test")
        Files.write(documentDirectory.resolve("image.png"), byteArrayOf(1, 2, 3))
        return SourceNativeLocalImageCapability.create(document.toString())!!
    }

    private fun ready(fake: FakeSourceNativeRuntimeTransport) {
        fake.fireLoadEnd()
        val response = fake.readinessHandler!!.invoke(readySignalJsonFromUrl(fake))
        assertEquals("{\"type\":\"runtimeReadyAck\"}", response)
        onEdt { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() }
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

    private fun document(fileName: String, text: String): Document =
        myFixture.configureByText(fileName, text).fileDocument

    private fun registry(): DocumentSessionRegistry = DocumentSessionRegistry.getInstance(project)

    private fun captureFailure(action: () -> Unit): Throwable {
        return try {
            action()
            throw AssertionError("expected failure")
        } catch (failure: Throwable) {
            if (failure is AssertionError && failure.message == "expected failure") throw failure
            failure
        }
    }

    private fun onEdt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            action()
        } else {
            application.invokeAndWait(Runnable(action), application.defaultModalityState)
        }
    }

    private fun <T> onEdtResult(action: () -> T): T {
        var result: T? = null
        onEdt { result = action() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private class ThrowingLoadTransport : SourceNativeRuntimeTransport {
        var disposed = false
            private set

        override fun loadUrl(url: String) {
            throw IllegalStateException("load failed")
        }

        override fun executeJavaScript(script: String) = Unit
        override fun buildBridgeGlueScript(): String = ""
        override fun setTransportMessageHandler(handler: (String) -> String?) = Unit
        override fun setReadinessMessageHandler(handler: (String) -> String?) = Unit
        override fun setLoadStartHandler(handler: () -> Unit) = Unit
        override fun setLoadEndHandler(handler: () -> Unit) = Unit

        override fun dispose() {
            disposed = true
        }
    }
}
