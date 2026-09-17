package com.algorist.markflow

import com.algorist.markflow.file.MarkFlowFileSupport
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MarkFlowDomainTest : BasePlatformTestCase() {

    fun testMarkFlowFileSupportRecognizesMarkdownExtensions() {
        assertTrue(MarkFlowFileSupport.isMarkFlowTarget(LightVirtualFile("note.md")))
        assertTrue(MarkFlowFileSupport.isMarkFlowTarget(LightVirtualFile("note.markdown")))
        assertTrue(MarkFlowFileSupport.isMarkFlowTarget(LightVirtualFile("note.mdown")))
        assertFalse(MarkFlowFileSupport.isMarkFlowTarget(LightVirtualFile("note.txt")))
    }
}
