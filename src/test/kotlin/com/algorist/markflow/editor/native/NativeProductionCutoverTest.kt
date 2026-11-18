package com.algorist.markflow.editor.native

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class NativeProductionCutoverTest : BasePlatformTestCase() {
    fun testPlatformEditorDoesNotAcquireProductionPresentationOwner() {
        myFixture.configureByText("cutover.md", "# Native production\n\nExact source stays authoritative.\n")
        val editor = myFixture.editor

        assertFalse(NativeMarkFlowProductionLifecycle.hasControllerForDiagnostics(editor))
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
