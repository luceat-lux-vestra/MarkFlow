package com.algorist.markflow.runtime

import com.algorist.markflow.document.DocumentSessionRegistry
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class SourceNativeLocalImageCapabilityRuntimeTest : BasePlatformTestCase() {

    fun testCapabilityIsDeliveredBeforeBridgeAndNeverPlacedInPageUrl() {
        val document = document("local-image.md", "![image](image.png)")
        val transport = FakeSourceNativeRuntimeTransport()
        val capability = FakeLocalImageCapability(CAPABILITY_BASE_URL)
        val runtime = onEdtResult { createRuntime(document, transport, capability) }

        val loadedUrl = transport.loadedUrls.single()
        assertFalse(loadedUrl.contains("__markflow_source_image__"))
        assertFalse(loadedUrl.contains("__markflow_local__"))
        assertFalse(loadedUrl.contains(CAPABILITY_TOKEN))
        assertTrue(loadedUrl.contains("attachmentId="))
        assertTrue(loadedUrl.contains("runtimeToken="))

        transport.fireLoadEnd()
        assertTrue(transport.executedScripts.size >= 2)
        assertTrue(transport.executedScripts[0].contains("__markflowSourceNativeLocalImageBaseUrl"))
        assertTrue(transport.executedScripts[0].contains("__markflow_source_image__"))
        assertTrue(transport.executedScripts[0].contains(CAPABILITY_TOKEN))
        assertEquals("/* fake-bridge-glue */", transport.executedScripts[1])

        onEdt { runtime.dispose() }
        assertTrue(capability.disposed)
    }

    fun testRealmReplacementDisposesCapabilityBeforeReplacementCanReuseIt() {
        val document = document("replacement-image.md", "![image](image.png)")
        val transport = FakeSourceNativeRuntimeTransport()
        val capability = FakeLocalImageCapability(CAPABILITY_BASE_URL)
        val runtime = onEdtResult { createRuntime(document, transport, capability) }
        transport.fireLoadEnd()

        transport.fireRealmReplacementLoadStart()
        onEdt { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() }

        assertTrue(runtime.isDisposed)
        assertTrue(capability.disposed)
        assertTrue(transport.disposed)
    }

    fun testConstructorFailureRollsBackCapabilityAndDocumentLease() {
        val document = document("failed-image.md", "source")
        val capability = FakeLocalImageCapability(CAPABILITY_BASE_URL)
        val failingTransport = object : SourceNativeRuntimeTransport {
            var disposed = false

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

        var failed = false
        try {
            onEdtResult {
                SourceNativeEditorRuntime.create(
                    project = project,
                    document = document,
                    documentPath = "/virtual/failed-image.md",
                    sourceNativeBaseUrl = { "http://source-native/index.html" },
                    isJcefAvailable = { true },
                    transportFactory = { failingTransport },
                    localImageCapabilityFactory = { capability },
                )
            }
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertTrue(failed)
        assertTrue(capability.disposed)
        assertTrue(failingTransport.disposed)
        assertEquals(0, onEdtResult { DocumentSessionRegistry.getInstance(project).activeSessionCount })
    }

    private fun createRuntime(
        document: Document,
        transport: FakeSourceNativeRuntimeTransport,
        capability: FakeLocalImageCapability,
    ): SourceNativeEditorRuntime {
        val runtime = SourceNativeEditorRuntime.create(
            project = project,
            document = document,
            documentPath = "/virtual/local-image.md",
            sourceNativeBaseUrl = { "http://source-native/index.html" },
            isJcefAvailable = { true },
            transportFactory = { transport },
            localImageCapabilityFactory = { capability },
        )!!
        Disposer.register(testRootDisposable, runtime)
        return runtime
    }

    private fun document(fileName: String, text: String): Document =
        myFixture.configureByText(fileName, text).fileDocument

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

    private class FakeLocalImageCapability(
        override val baseUrl: String,
    ) : SourceNativeLocalImageCapability {
        var disposed = false
            private set

        override fun dispose() {
            disposed = true
        }
    }

    companion object {
        private const val CAPABILITY_TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        private const val CAPABILITY_BASE_URL =
            "http://127.0.0.1:41234/__markflow_source_image__/$CAPABILITY_TOKEN/"
    }
}
