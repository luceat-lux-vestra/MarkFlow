package com.algorist.markflow.editor.native

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.CaretStateTransferableData
import com.intellij.openapi.editor.EditorCopyPasteHelper
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException

class NativeMarkdownDeferredPasteHandlerTest : BasePlatformTestCase() {
    fun testMulticaretWithoutSourceCaretMetadataDelegatesPlatformSegmentation() {
        val file = myFixture.tempDirFixture.createFile("multi.md", "first\nsecond\n")
        myFixture.configureFromExistingVirtualFile(file)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(0)
        requireNotNull(editor.caretModel.addCaret(editor.offsetToVisualPosition(6)))
        assertEquals(2, editor.caretModel.caretCount)

        val original = TestTransferable(markdown = "# one\r\n# two\r\n", plain = "plain fallback")
        val preparation = NativeDeferredMarkdownPaste.prepare(editor, original)

        assertEquals(NativeDeferredPasteDisposition.DELEGATE_SOURCE_MULTICARET_PAYLOAD, preparation.disposition)
        assertSame(original, preparation.transferable)
        assertEquals("plain fallback", preparation.transferable.getTransferData(DataFlavor.stringFlavor))
        assertEquals("first\nsecond\n", editor.document.text)
    }

    fun testSingleSourceCaretMetadataKeepsPlatformDuplicateSemantics() {
        val file = myFixture.tempDirFixture.createFile("single-source.md", "a\nb\n")
        myFixture.configureFromExistingVirtualFile(file)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(0)
        requireNotNull(editor.caretModel.addCaret(editor.offsetToVisualPosition(2)))

        val caretData = CaretStateTransferableData(intArrayOf(0), intArrayOf("plain".length))
        val preparation = NativeDeferredMarkdownPaste.prepare(
            editor,
            TestTransferable("**rich**", "plain", caretData),
        )

        assertEquals(NativeDeferredPasteDisposition.TRANSFORMED, preparation.disposition)
        assertEquals("**rich**", preparation.transferable.getTransferData(DataFlavor.stringFlavor))
        assertEquals(1, CaretStateTransferableData.getFrom(preparation.transferable)?.caretCount)
        WriteCommandAction.writeCommandAction(project)
            .withName("MarkFlow #152 Multicaret Paste Unit Proof")
            .run<RuntimeException> {
                EditorCopyPasteHelper.getInstance().pasteTransferable(editor, preparation.transferable)
            }
        assertEquals("**rich**a\n**rich**b\n", editor.document.text)
    }

    fun testMultipleSourceCaretMetadataDelegatesUnchangedBecauseOffsetsOwnOriginalString() {
        val file = myFixture.tempDirFixture.createFile("source-multi.md", "a\nb\n")
        myFixture.configureFromExistingVirtualFile(file)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(0)
        requireNotNull(editor.caretModel.addCaret(editor.offsetToVisualPosition(2)))

        val caretData = CaretStateTransferableData(intArrayOf(0, 2), intArrayOf(1, 3))
        val original = TestTransferable("**would-change-length**", "a\nb", caretData)
        val preparation = NativeDeferredMarkdownPaste.prepare(editor, original)

        assertEquals(NativeDeferredPasteDisposition.DELEGATE_SOURCE_MULTICARET_PAYLOAD, preparation.disposition)
        assertSame(original, preparation.transferable)
    }

    fun testAnyDestinationInsideParserProvenCodeDelegatesWholePaste() {
        val source = """outside

```text
code
```

tail
"""
        val file = myFixture.tempDirFixture.createFile("code.md", source)
        myFixture.configureFromExistingVirtualFile(file)
        val editor = myFixture.editor
        val outside = source.indexOf("outside")
        val inside = source.indexOf("code") + 1
        editor.caretModel.moveToOffset(outside)
        requireNotNull(editor.caretModel.addCaret(editor.offsetToVisualPosition(inside)))

        val caretData = CaretStateTransferableData(intArrayOf(0), intArrayOf("plain".length))
        val original = TestTransferable("**rich**", "plain", caretData)
        val preparation = NativeDeferredMarkdownPaste.prepare(editor, original)

        assertEquals(NativeDeferredPasteDisposition.DELEGATE_CODE_CONTEXT, preparation.disposition)
        assertSame(original, preparation.transferable)
        assertEquals(source, editor.document.text)
    }

    fun testColumnModeChecksEveryProspectivePlatformCloneDestination() {
        val source = """outside
```text
code
```
tail
"""
        val file = myFixture.tempDirFixture.createFile("column.md", source)
        myFixture.configureFromExistingVirtualFile(file)
        val editor = myFixture.editor
        (editor as EditorEx).setColumnMode(true)
        editor.caretModel.moveToLogicalPosition(com.intellij.openapi.editor.LogicalPosition(0, 1))

        val locations = NativeDeferredMarkdownPaste.deferredDestinationLocations(editor, "# one\n# two\n# three")
        assertEquals(3, locations.size)
        assertTrue(locations[1].caretOffset in source.indexOf("```text")..source.indexOf("code"))

        val original = TestTransferable("# one\n# two\n# three", "plain")
        val preparation = NativeDeferredMarkdownPaste.prepare(editor, original)
        assertEquals(NativeDeferredPasteDisposition.DELEGATE_CODE_CONTEXT, preparation.disposition)
        assertSame(original, preparation.transferable)
    }

    fun testNonMarkdownPlainPayloadDelegatesExactTransferable() {
        val file = myFixture.tempDirFixture.createFile("plain.md", "a\nb\n")
        myFixture.configureFromExistingVirtualFile(file)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(0)
        requireNotNull(editor.caretModel.addCaret(editor.offsetToVisualPosition(2)))

        val original = TestTransferable(markdown = null, plain = "ordinary plain")
        val preparation = NativeDeferredMarkdownPaste.prepare(editor, original)

        assertEquals(NativeDeferredPasteDisposition.DELEGATE_UNCHANGED, preparation.disposition)
        assertSame(original, preparation.transferable)
    }

    private class TestTransferable(
        private val markdown: String?,
        private val plain: String,
        private val caretState: CaretStateTransferableData? = null,
    ) : Transferable {
        private val markdownFlavor = DataFlavor("text/markdown;class=java.lang.String", "Markdown")
        private val flavors = buildList {
            if (markdown != null) add(markdownFlavor)
            add(DataFlavor.stringFlavor)
            if (caretState != null) add(CaretStateTransferableData.FLAVOR)
        }.toTypedArray()

        override fun getTransferDataFlavors(): Array<DataFlavor> = flavors.copyOf()

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavors.any { it == flavor }

        override fun getTransferData(flavor: DataFlavor): Any = when {
            flavor == markdownFlavor && markdown != null -> markdown
            flavor == DataFlavor.stringFlavor -> plain
            flavor == CaretStateTransferableData.FLAVOR && caretState != null -> caretState
            else -> throw UnsupportedFlavorException(flavor)
        }
    }
}
