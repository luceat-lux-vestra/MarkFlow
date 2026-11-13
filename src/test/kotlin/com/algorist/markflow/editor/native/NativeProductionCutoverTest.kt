package com.algorist.markflow.editor.native

import com.algorist.markflow.settings.MarkFlowSettingsService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class NativeProductionCutoverTest : BasePlatformTestCase() {
    fun testProductionLifecycleAttachesOnceAndPreservesAuthoritativeSource() {
        val source = "# Native production\n\nExact source stays authoritative.\n"
        myFixture.configureByText("cutover.md", source)
        val editor = myFixture.editor
        val stampBefore = editor.document.modificationStamp

        // Automatic listener attachment is intentionally disabled in unit-test mode so component
        // tests do not acquire a second owner. Exercise the exact production owner directly here.
        assertNull(NativeMarkFlowProductionLifecycle.controller(editor))
        assertTrue(NativeMarkFlowProductionLifecycle.attach(editor))
        val controller = checkNotNull(NativeMarkFlowProductionLifecycle.controller(editor))
        assertEquals(ProjectionPlanStatus.READY, controller.currentPlan?.status)
        assertEquals(source, editor.document.text)
        assertEquals(stampBefore, editor.document.modificationStamp)

        assertFalse(NativeMarkFlowProductionLifecycle.attach(editor))
        assertSame(controller, NativeMarkFlowProductionLifecycle.controller(editor))
        assertEquals(source, editor.document.text)
        assertEquals(stampBefore, editor.document.modificationStamp)

        val generationBefore = checkNotNull(controller.currentPlan?.identity?.configGeneration)
        MarkFlowSettingsService.bumpRuntimeSettingsRevision()
        NativeMarkFlowProductionLifecycle.refreshAll()
        val generationAfter = checkNotNull(controller.currentPlan?.identity?.configGeneration)
        assertTrue(generationAfter > generationBefore)
        assertEquals(source, editor.document.text)
        assertEquals(stampBefore, editor.document.modificationStamp)

        assertTrue(NativeMarkFlowProductionLifecycle.release(editor))
        assertNull(NativeMarkFlowProductionLifecycle.controller(editor))
        assertFalse(NativeMarkFlowProductionLifecycle.release(editor))
        assertEquals(source, editor.document.text)
        assertEquals(stampBefore, editor.document.modificationStamp)
    }

    fun testProductionLifecycleRejectsNonMarkdownEditor() {
        myFixture.configureByText("plain.txt", "plain\n")
        val editor = myFixture.editor
        assertFalse(NativeMarkFlowProductionLifecycle.attach(editor))
        assertNull(NativeMarkFlowProductionLifecycle.controller(editor))
    }

    fun testPackagedRegistrationSelectsNativeLifecycleNotBrowserEditorAuthority() {
        val pluginXml = Files.readString(
            Path.of("src", "main", "resources", "META-INF", "plugin.xml").toAbsolutePath().normalize()
        )
        val jcefXml = Files.readString(
            Path.of("src", "main", "resources", "META-INF", "markflow-jcef.xml").toAbsolutePath().normalize()
        )

        assertTrue(pluginXml.contains("NativeMarkFlowEditorFactoryListener"))
        assertTrue(pluginXml.contains("NativeMarkFlowPresentationSettingsSink"))
        assertFalse(pluginXml.contains("com.algorist.markflow.editor.MarkFlowEditorProvider"))

        // #556 temporarily packages a fail-closed provider so the real FileEditorManager can
        // exercise Candidate B. Its accept() remains false outside dedicated proof JVMs; this is
        // evidence plumbing, not a production cutover or resurrection of browser authority.
        assertTrue(pluginXml.contains("Leap2OwnedNativeFileEditorProvider"))
        assertTrue(pluginXml.contains("markflow-leap2-owned-native-proof"))

        assertFalse(jcefXml.contains("MarkFlowEditorProvider"))
        assertFalse(jcefXml.contains("Leap2OwnedNativeFileEditorProvider"))
        assertFalse(jcefXml.contains("MarkFlowStartupActivity"))
        assertTrue(jcefXml.contains("JcefDerivedRendererRuntimeFactory"))
    }
}
