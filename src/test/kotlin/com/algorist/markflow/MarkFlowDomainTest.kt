package com.algorist.markflow

import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jdom.Element
import com.algorist.markflow.editor.DocumentContentDiff
import com.algorist.markflow.editor.state.MarkFlowEditorState
import com.algorist.markflow.editor.state.SourceRevisionGate
import com.algorist.markflow.file.MarkFlowFileSupport

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

    fun testDocumentContentDiffComputesMiddleInsertion() {
        val edit = DocumentContentDiff.compute("hello world", "hello brave world")

        assertNotNull(edit)
        assertEquals(6, edit?.startOffset)
        assertEquals(6, edit?.endOffset)
        assertEquals("brave ", edit?.replacement)
    }

    fun testDocumentContentDiffComputesPrefixDeletion() {
        val edit = DocumentContentDiff.compute("prefix body", "body")

        assertNotNull(edit)
        assertEquals(0, edit?.startOffset)
        assertEquals(7, edit?.endOffset)
        assertEquals("", edit?.replacement)
    }

    fun testDocumentContentDiffReturnsNullForIdenticalContent() {
        assertNull(DocumentContentDiff.compute("same", "same"))
    }

    fun testSourceRevisionGateRejectsStaleRevisions() {
        val gate = SourceRevisionGate(initialRevision = 3)

        assertEquals(3L, gate.current())
        assertFalse(gate.acceptIncomingRevision(2))
        assertTrue(gate.acceptIncomingRevision(5))
        assertEquals(5L, gate.current())
        assertFalse(gate.acceptIncomingRevision(5))
    }

    fun testSourceRevisionGateAdvancesForExternalChanges() {
        val gate = SourceRevisionGate(initialRevision = 1)

        assertEquals(2L, gate.advanceForExternalChange())
        assertEquals(3L, gate.observeAtLeast(3))
        assertEquals(3L, gate.current())
        assertEquals(3L, gate.observeAtLeast(2))
    }

    fun testSourceRevisionGateNormalizesNonPositiveValues() {
        val gate = SourceRevisionGate(initialRevision = 0)

        assertEquals(1L, gate.current())
        assertFalse(gate.acceptIncomingRevision(0))
        assertFalse(gate.acceptIncomingRevision(-4))
        assertEquals(1L, gate.current())
    }

    fun testSourceRevisionGateDoesNotRegressOnObserveAtLeast() {
        val gate = SourceRevisionGate(initialRevision = 4)

        assertEquals(9L, gate.observeAtLeast(9))
        assertEquals(9L, gate.current())
        assertEquals(9L, gate.observeAtLeast(3))
        assertEquals(9L, gate.current())
        assertFalse(gate.acceptIncomingRevision(9))
        assertFalse(gate.acceptIncomingRevision(8))
    }

    fun testDocumentContentDiffComputesSuffixInsertion() {
        val edit = DocumentContentDiff.compute("hello", "hello world")

        assertNotNull(edit)
        assertEquals(5, edit?.startOffset)
        assertEquals(5, edit?.endOffset)
        assertEquals(" world", edit?.replacement)
    }

    fun testDocumentContentDiffComputesWholeReplacement() {
        val edit = DocumentContentDiff.compute("abc", "xyz")

        assertNotNull(edit)
        assertEquals(0, edit?.startOffset)
        assertEquals(3, edit?.endOffset)
        assertEquals("xyz", edit?.replacement)
    }
}
