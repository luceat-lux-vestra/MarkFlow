package com.algorist.markflow.runtime

import com.algorist.markflow.document.DocumentSessionRegistry
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/** Failure-path ownership evidence for #105. No real JCEF browser is constructed. */
class SourceNativeEditorRuntimeFailureTest : BasePlatformTestCase() {

    fun testTransportFactoryFailureRollsBackDocumentSessionLease() {
        val document = document("factory-failure.md", "abc")
        val sessionsBefore = onEdtResult { registry().activeSessionCount }
        val liveBefore = SourceNativeEditorRuntime.liveInstanceCount

        val failure = captureFailure {
            onEdtResult {
                SourceNativeEditorRuntime.create(
                    project = project,
                    document = document,
                    sourceNativeBaseUrl = { "http://source-native/index.html" },
                    isJcefAvailable = { true },
                    transportFactory = { throw IllegalStateException("transport construction failed") },
                )
            }
        }

        assertTrue(failure is IllegalStateException)
        assertEquals("transport construction failed", failure.message)
        assertEquals(sessionsBefore, onEdtResult { registry().activeSessionCount })
        assertEquals(liveBefore, SourceNativeEditorRuntime.liveInstanceCount)
        assertEquals("abc", document.text)
    }

    fun testLoadFailureAfterHandlerRegistrationRollsBackEveryTransferredOwner() {
        val document = document("load-failure.md", "abc")
        val sessionsBefore = onEdtResult { registry().activeSessionCount }
        val liveBefore = SourceNativeEditorRuntime.liveInstanceCount
        val transport = ThrowingLoadTransport()

        val failure = captureFailure {
            onEdtResult {
                SourceNativeEditorRuntime.create(
                    project = project,
                    document = document,
                    sourceNativeBaseUrl = { "http://source-native/index.html" },
                    isJcefAvailable = { true },
                    transportFactory = { transport },
                )
            }
        }

        assertTrue(failure is IllegalStateException)
        assertEquals("load failed", failure.message)
        assertTrue(transport.transportHandlerRegistered)
        assertTrue(transport.readinessHandlerRegistered)
        assertTrue(transport.loadEndHandlerRegistered)
        assertEquals(1, transport.disposeCount)
        assertEquals(sessionsBefore, onEdtResult { registry().activeSessionCount })
        assertEquals(liveBefore, SourceNativeEditorRuntime.liveInstanceCount)
        assertEquals("abc", document.text)
    }

    fun testBundledResourceDefaultCanBeReacquiredAfterRuntimeDispose() {
        val document = document("bundled-resource.md", "abc")
        val firstTransport = FakeSourceNativeRuntimeTransport()
        val first = onEdtResult {
            SourceNativeEditorRuntime.create(
                project = project,
                document = document,
                isJcefAvailable = { true },
                transportFactory = { firstTransport },
            )
        }
        assertNotNull(first)
        assertTrue(firstTransport.loadedUrls.single().contains("/source-native.html?"))
        onEdt { first!!.dispose() }

        // The default runtime owns and releases its WebviewResourceManager reference. A fresh
        // runtime must be able to acquire the bundled resource again after that lifetime ends.
        val secondTransport = FakeSourceNativeRuntimeTransport()
        val second = onEdtResult {
            SourceNativeEditorRuntime.create(
                project = project,
                document = document,
                isJcefAvailable = { true },
                transportFactory = { secondTransport },
            )
        }
        assertNotNull(second)
        assertTrue(secondTransport.loadedUrls.single().contains("/source-native.html?"))
        onEdt { second!!.dispose() }

        assertEquals(0, onEdtResult { registry().activeSessionCount })
        assertEquals("abc", document.text)
    }

    private class ThrowingLoadTransport : SourceNativeRuntimeTransport {
        var transportHandlerRegistered = false
            private set
        var readinessHandlerRegistered = false
            private set
        var loadEndHandlerRegistered = false
            private set
        var disposeCount = 0
            private set
        private var disposed = false

        override fun loadUrl(url: String) {
            if (disposed) return
            throw IllegalStateException("load failed")
        }

        override fun executeJavaScript(script: String) = Unit

        override fun buildBridgeGlueScript(): String = ""

        override fun setTransportMessageHandler(handler: (String) -> String?) {
            if (!disposed) transportHandlerRegistered = true
        }

        override fun setReadinessMessageHandler(handler: (String) -> String?) {
            if (!disposed) readinessHandlerRegistered = true
        }

        override fun setLoadEndHandler(handler: () -> Unit) {
            if (!disposed) loadEndHandlerRegistered = true
        }

        override fun dispose() {
            if (disposed) return
            disposed = true
            disposeCount += 1
        }
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
        if (application.isDispatchThread) action() else application.invokeAndWait(Runnable(action), application.defaultModalityState)
    }

    private fun <T> onEdtResult(action: () -> T): T {
        var result: T? = null
        onEdt { result = action() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
