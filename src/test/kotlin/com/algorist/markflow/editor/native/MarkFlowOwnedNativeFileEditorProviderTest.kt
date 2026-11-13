package com.algorist.markflow.editor.native

import com.algorist.markflow.settings.MarkFlowSettingsService
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MarkFlowOwnedNativeFileEditorProviderTest : BasePlatformTestCase() {
    fun testProductionProviderAcceptsMarkdownWithoutDiagnosticGateAndUsesCoexistingPolicy() {
        myFixture.configureByText("proof.md", "# proof\n")
        val file = checkNotNull(myFixture.file.virtualFile)
        val provider = MarkFlowOwnedNativeFileEditorProvider()

        System.clearProperty(MarkFlowOwnedNativeFileEditorProvider.PROOF_ENABLED_PROPERTY)
        assertTrue(provider.accept(project, file))
        assertEquals(FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR, provider.policy)
        assertEquals("markflow-owned-native", provider.editorTypeId)

        try {
            System.setProperty(MarkFlowOwnedNativeFileEditorProvider.PROOF_ENABLED_PROPERTY, "false")
            assertTrue(provider.accept(project, file))
        } finally {
            System.clearProperty(MarkFlowOwnedNativeFileEditorProvider.PROOF_ENABLED_PROPERTY)
        }
    }

    fun testProductionProviderRejectsNonMarkdown() {
        myFixture.configureByText("plain.txt", "plain\n")
        val file = checkNotNull(myFixture.file.virtualFile)
        assertFalse(MarkFlowOwnedNativeFileEditorProvider().accept(project, file))
    }

    fun testOwnedEditorsShareAuthoritativeDocumentAndRegistryCleansUp() {
        myFixture.configureByText("owned.md", "# owned\n\nshared source\n")
        val file = checkNotNull(myFixture.file.virtualFile)
        val authoritative = myFixture.editor.document

        assertEquals(0, NativeMarkFlowProductionLifecycle.liveOwnedControllerCountForDiagnostics)
        assertEquals(0, MarkFlowOwnedNativeFileEditor.liveEditorCountForDiagnostics)

        val first = MarkFlowOwnedNativeFileEditor(project, file)
        val second = MarkFlowOwnedNativeFileEditor(project, file)
        try {
            assertSame(authoritative, first.editor.document)
            assertSame(authoritative, second.editor.document)
            assertNotSame(first.editor, second.editor)
            assertNotNull(NativeMarkFlowProductionLifecycle.ownedController(first.editor))
            assertNotNull(NativeMarkFlowProductionLifecycle.ownedController(second.editor))
            assertEquals(2, NativeMarkFlowProductionLifecycle.liveOwnedControllerCountForDiagnostics)
            assertEquals(2, MarkFlowOwnedNativeFileEditor.liveEditorCountForDiagnostics)

            val firstController = checkNotNull(NativeMarkFlowProductionLifecycle.ownedController(first.editor))
            val generationBefore = checkNotNull(firstController.currentPlan?.identity?.configGeneration)
            MarkFlowSettingsService.bumpRuntimeSettingsRevision()
            NativeMarkFlowProductionLifecycle.refreshOwned()
            val generationAfter = checkNotNull(firstController.currentPlan?.identity?.configGeneration)
            assertTrue(generationAfter > generationBefore)

            first.setState(
                MarkFlowOwnedNativeFileEditorState(
                    caretOffset = 2,
                    selectionStart = 1,
                    selectionEnd = 2,
                    verticalScrollOffset = 0,
                )
            )
            val state = first.getState(FileEditorStateLevel.FULL) as MarkFlowOwnedNativeFileEditorState
            assertEquals(2, state.caretOffset)
            assertEquals(1, state.selectionStart)
            assertEquals(2, state.selectionEnd)
            assertEquals(2, first.editor.caretModel.offset)
            assertEquals(1, first.editor.selectionModel.selectionStart)
            assertEquals(2, first.editor.selectionModel.selectionEnd)
        } finally {
            second.dispose()
            first.dispose()
        }

        assertEquals(0, NativeMarkFlowProductionLifecycle.liveOwnedControllerCountForDiagnostics)
        assertEquals(0, MarkFlowOwnedNativeFileEditor.liveEditorCountForDiagnostics)
    }
}
