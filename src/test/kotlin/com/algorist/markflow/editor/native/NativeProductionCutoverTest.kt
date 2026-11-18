package com.algorist.markflow.editor.native

import com.algorist.markflow.settings.MarkFlowSettingsService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class NativeProductionCutoverTest : BasePlatformTestCase() {
    fun testOwnedProductionLifecycleAttachesOnceAndPreservesAuthoritativeSource() {
        val source = "# Native production\n\nExact source stays authoritative.\n"
        myFixture.configureByText("cutover.md", source)
        val file = checkNotNull(myFixture.file.virtualFile)
        val authoritative = myFixture.editor.document
        val stampBefore = authoritative.modificationStamp

        assertEquals(0, NativeMarkFlowProductionLifecycle.liveOwnedControllerCountForDiagnostics)
        assertEquals(0, MarkFlowOwnedNativeFileEditor.liveEditorCountForDiagnostics)

        val owned = MarkFlowOwnedNativeFileEditor(project, file)
        try {
            assertSame(authoritative, owned.editor.document)
            val controller = checkNotNull(
                NativeMarkFlowProductionLifecycle.ownedController(owned.editor)
            )
            assertEquals(ProjectionPlanStatus.READY, controller.currentPlan?.status)
            assertEquals(1, NativeMarkFlowProductionLifecycle.liveOwnedControllerCountForDiagnostics)
            assertEquals(1, MarkFlowOwnedNativeFileEditor.liveEditorCountForDiagnostics)
            assertEquals(source, authoritative.text)
            assertEquals(stampBefore, authoritative.modificationStamp)

            assertFalse(NativeMarkFlowProductionLifecycle.attachOwned(owned.editor))
            assertSame(
                controller,
                NativeMarkFlowProductionLifecycle.ownedController(owned.editor),
            )

            val generationBefore = checkNotNull(controller.currentPlan?.identity?.configGeneration)
            MarkFlowSettingsService.bumpRuntimeSettingsRevision()
            NativeMarkFlowProductionLifecycle.refreshOwned()
            val generationAfter = checkNotNull(controller.currentPlan?.identity?.configGeneration)
            assertTrue(generationAfter > generationBefore)
            assertEquals(source, authoritative.text)
            assertEquals(stampBefore, authoritative.modificationStamp)
        } finally {
            owned.dispose()
        }

        assertEquals(0, NativeMarkFlowProductionLifecycle.liveOwnedControllerCountForDiagnostics)
        assertEquals(0, MarkFlowOwnedNativeFileEditor.liveEditorCountForDiagnostics)
        assertEquals(source, authoritative.text)
        assertEquals(stampBefore, authoritative.modificationStamp)
    }

    fun testOwnedProductionLifecycleRejectsNonMarkdownEditor() {
        myFixture.configureByText("plain.txt", "plain\n")
        val editor = myFixture.editor

        assertFalse(NativeMarkFlowProductionLifecycle.attachOwned(editor))
        assertNull(NativeMarkFlowProductionLifecycle.ownedController(editor))
        assertEquals(0, NativeMarkFlowProductionLifecycle.liveOwnedControllerCountForDiagnostics)
    }

    fun testPackagedRegistrationSelectsNativeLifecycleNotBrowserEditorAuthority() {
        val pluginXml = Files.readString(
            Path.of("src", "main", "resources", "META-INF", "plugin.xml").toAbsolutePath().normalize()
        )
        val jcefXml = Files.readString(
            Path.of("src", "main", "resources", "META-INF", "markflow-jcef.xml").toAbsolutePath().normalize()
        )

        assertFalse(pluginXml.contains("NativeMarkFlowEditorFactoryListener"))
        assertTrue(pluginXml.contains("NativeMarkFlowPresentationSettingsSink"))
        assertFalse(pluginXml.contains("com.algorist.markflow.editor.MarkFlowEditorProvider"))

        // #563 activates the owned provider and removes the generic production listener.
        assertTrue(pluginXml.contains("MarkFlowOwnedNativeFileEditorProvider"))
        assertTrue(pluginXml.contains("markflow-owned-native"))

        assertFalse(jcefXml.contains("MarkFlowEditorProvider"))
        assertFalse(jcefXml.contains("MarkFlowOwnedNativeFileEditorProvider"))
        assertFalse(jcefXml.contains("MarkFlowStartupActivity"))
        assertTrue(jcefXml.contains("JcefDerivedRendererRuntimeFactory"))
    }
}
