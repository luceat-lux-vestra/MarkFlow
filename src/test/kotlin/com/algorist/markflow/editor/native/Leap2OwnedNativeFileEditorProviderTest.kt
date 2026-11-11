package com.algorist.markflow.editor.native

import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class Leap2OwnedNativeFileEditorProviderTest : BasePlatformTestCase() {
    fun testProofProviderIsDormantOutsideDedicatedProbeAndUsesCoexistingPolicy() {
        myFixture.configureByText("proof.md", "# proof\n")
        val file = checkNotNull(myFixture.file.virtualFile)
        val provider = Leap2OwnedNativeFileEditorProvider()

        System.clearProperty(Leap2OwnedNativeFileEditorProvider.ENABLED_PROPERTY)
        assertFalse(provider.accept(project, file))
        assertEquals(FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR, provider.policy)

        try {
            System.setProperty(Leap2OwnedNativeFileEditorProvider.ENABLED_PROPERTY, "true")
            assertTrue(provider.accept(project, file))
            assertEquals("markflow-leap2-owned-native-proof", provider.editorTypeId)
        } finally {
            System.clearProperty(Leap2OwnedNativeFileEditorProvider.ENABLED_PROPERTY)
        }
    }
}
