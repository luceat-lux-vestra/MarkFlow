package com.algorist.markflow

import com.algorist.markflow.file.MarkFlowFileSupport
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.jcef.JBCefApp

class MarkFlowDomainTest : BasePlatformTestCase() {

    fun testPlatformUnitTestsCannotStartIncidentalJcefBrowser() {
        // Verify the effective IntelliJ runtime registry state, not just the
        // Gradle configuration text. Real JCEF acceptance runs in separate
        // runIde and Starter/Driver workflows with the browser enabled.
        assertEquals("false", System.getProperty("ide.browser.jcef.enabled"))
        assertFalse("Ordinary unit tests must not launch JCEF/Chromium", JBCefApp.isSupported())
    }

    fun testMarkFlowFileSupportRecognizesMarkdownExtensions() {
        assertTrue(MarkFlowFileSupport.isMarkFlowTarget(LightVirtualFile("note.md")))
        assertTrue(MarkFlowFileSupport.isMarkFlowTarget(LightVirtualFile("note.markdown")))
        assertTrue(MarkFlowFileSupport.isMarkFlowTarget(LightVirtualFile("note.mdown")))
        assertFalse(MarkFlowFileSupport.isMarkFlowTarget(LightVirtualFile("note.txt")))
    }
}
