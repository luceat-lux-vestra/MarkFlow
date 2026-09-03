package com.algorist.markflow.document

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DocumentSessionRegistryTest : BasePlatformTestCase() {

    fun testRegistryIsAProjectScopedService() {
        val first = DocumentSessionRegistry.getInstance(project)
        val second = project.getService(DocumentSessionRegistry::class.java)

        assertSame(first, second)
    }

    fun testSameProjectAndDocumentShareOneSessionAndObservationStream() {
        val document = document("shared.md", "a")
        val registry = registry()
        lateinit var first: DocumentSessionLease
        lateinit var second: DocumentSessionLease
        val firstObservations = mutableListOf<AuthoritativeDocumentMutation>()
        val secondObservations = mutableListOf<AuthoritativeDocumentMutation>()

        onEdt {
            first = registry.acquire(document)
            second = registry.acquire(document)
            assertSame(first.session, second.session)
            assertEquals(1, registry.activeSessionCount)

            first.session.addMutationListener(
                AuthoritativeDocumentMutationListener { firstObservations += it },
                first,
            )
            second.session.addMutationListener(
                AuthoritativeDocumentMutationListener { secondObservations += it },
                second,
            )
        }

        writeDocument { document.replaceString(0, 1, "b") }

        assertEquals(firstObservations, secondObservations)
        assertEquals(1, firstObservations.size)
        assertEquals(DocumentRevision(1), firstObservations.single().revision)
        assertEquals("b", secondObservations.single().snapshot.text)
        assertSame(first.session, second.session)

        onEdt {
            first.dispose()
            assertFalse(first.session.isDisposed)
        }
        writeDocument { document.replaceString(0, 1, "c") }
        assertEquals(1, firstObservations.size)
        assertEquals(2, secondObservations.size)
        assertEquals(DocumentRevision(2), secondObservations.last().revision)

        onEdt { Disposer.dispose(second) }
        assertTrue(second.session.isDisposed)

        writeDocument { document.replaceString(0, 1, "d") }
        assertEquals(2, secondObservations.size)
    }

    fun testDifferentDocumentsHaveDifferentSessions() {
        val firstDocument = document("first.md", "same")
        val secondDocument = document("second.md", "same")
        val registry = registry()
        lateinit var first: DocumentSessionLease
        lateinit var second: DocumentSessionLease

        onEdt {
            first = registry.acquire(firstDocument)
            second = registry.acquire(secondDocument)
            assertNotSame(first.session, second.session)
            assertEquals(2, registry.activeSessionCount)
            first.dispose()
            second.dispose()
        }

        assertTrue(first.session.isDisposed)
        assertTrue(second.session.isDisposed)
        assertEquals(0, registry.activeSessionCount)
    }

    fun testFirstReleaseKeepsSessionAliveAndFinalReleaseDisposesAndUnregisters() {
        val document = document("lifecycle.md", "a")
        val registry = registry()
        lateinit var first: DocumentSessionLease
        lateinit var second: DocumentSessionLease
        lateinit var session: DocumentSession

        onEdt {
            first = registry.acquire(document)
            second = registry.acquire(document)
            session = first.session

            first.dispose()
            assertFalse(session.isDisposed)
            assertTrue(registry.contains(document))
            assertEquals(1, registry.activeSessionCount)

            second.dispose()
            assertTrue(session.isDisposed)
            assertFalse(registry.contains(document))
            assertEquals(0, registry.activeSessionCount)

            // A repeated final release must not dispose or otherwise change the registry again.
            second.dispose()
        }
    }

    fun testReacquireAfterFinalReleaseCreatesFreshSeededLifetime() {
        val document = document("reopen.md", "a")
        val registry = registry()
        lateinit var oldLease: DocumentSessionLease
        lateinit var freshLease: DocumentSessionLease
        lateinit var oldSession: DocumentSession

        onEdt {
            oldLease = registry.acquire(document)
            oldSession = oldLease.session
            oldLease.dispose()

            freshLease = registry.acquire(document)
            assertNotSame(oldSession, freshLease.session)
            assertEquals(DocumentRevision.INITIAL, freshLease.session.revision)
        }

        writeDocument { document.replaceString(0, 1, "b") }

        assertEquals(DocumentRevision.INITIAL, oldSession.revision)
        assertEquals(DocumentRevision(1), freshLease.session.revision)
        assertEquals("b", onEdtResult { freshLease.session.authoritativeSnapshot() }.text)

        onEdt { freshLease.dispose() }
    }

    fun testProjectServiceDisposalCleansRemainingSessionsAndLeasesCanFinishDisposal() {
        val document = document("project-close.md", "a")
        val secondDocument = document("project-close-second.md", "b")
        val registry = registry()
        lateinit var first: DocumentSessionLease
        lateinit var second: DocumentSessionLease
        lateinit var firstSession: DocumentSession
        lateinit var secondSession: DocumentSession

        onEdt {
            first = registry.acquire(document)
            second = registry.acquire(secondDocument)
            firstSession = first.session
            secondSession = second.session
            assertEquals(2, registry.activeSessionCount)
        }

        // This is the same disposal callback invoked when the project-owned service is closed.
        Disposer.dispose(registry)

        assertTrue(firstSession.isDisposed)
        assertTrue(secondSession.isDisposed)
        assertEquals(0, registry.activeSessionCount)

        onEdt {
            Disposer.dispose(first)
            Disposer.dispose(second)
            var rejected = false
            try {
                registry.acquire(document)
            } catch (_: IllegalStateException) {
                rejected = true
            }
            assertTrue(rejected)
        }
    }

    private fun registry(): DocumentSessionRegistry =
        DocumentSessionRegistry(project).also {
            Disposer.register(testRootDisposable, it)
        }

    private fun document(fileName: String, text: String): Document =
        myFixture.configureByText(fileName, text).fileDocument

    private fun writeDocument(mutation: () -> Unit) {
        onEdt {
            WriteCommandAction.runWriteCommandAction(project, Runnable(mutation))
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
}
