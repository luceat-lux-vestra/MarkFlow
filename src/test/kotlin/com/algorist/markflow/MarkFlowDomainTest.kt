package com.algorist.markflow

import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jdom.Element

class MarkFlowDomainTest : BasePlatformTestCase() {

    fun testMarkFlowFileSupportRecognizesMarkdownExtensions() {
        assertTrue(MarkFlowFileSupport.isMarkFlowTarget(LightVirtualFile("note.md")))
        assertTrue(MarkFlowFileSupport.isMarkFlowTarget(LightVirtualFile("note.markdown")))
        assertTrue(MarkFlowFileSupport.isMarkFlowTarget(LightVirtualFile("note.mdown")))
        assertFalse(MarkFlowFileSupport.isMarkFlowTarget(LightVirtualFile("note.txt")))
    }

    fun testEditorStateRoundTripsThroughXml() {
        val original = MarkFlowEditorState(
            version = 2,
            scrollTop = 120,
            cursorOffset = 42,
            selectionStart = 10,
            selectionEnd = 18
        )

        val element = Element("state")
        original.writeTo(element)

        val restored = MarkFlowEditorState.readFrom(element)
        assertNotNull(restored)
        assertEquals(original, restored)
    }

    fun testEditorStateReadReturnsNullForEmptyElement() {
        assertNull(MarkFlowEditorState.readFrom(Element("state")))
    }
}
