package com.algorist.markflow.editor.native

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.Reader
import java.io.StringReader

class NativeMarkdownEditTest : BasePlatformTestCase() {
    fun testMarkdownMimeWinsAndNormalizesOnlyPayload() {
        assertEquals(
            "# markdown\nbody\n",
            NativeMarkdownPastePolicy.choosePayload(
                markdownText = "\uFEFF# markdown\r\nbody\r",
                plainText = "plain fallback",
            ),
        )
    }

    fun testMarkdownLikePlainTextNormalizesButOrdinaryPlainTextDoesNot() {
        assertEquals(
            "# heading\nbody\n",
            NativeMarkdownPastePolicy.choosePayload(
                markdownText = null,
                plainText = "\uFEFF# heading\r\nbody\r",
            ),
        )
        assertEquals(
            "ordinary\r\nplain",
            NativeMarkdownPastePolicy.choosePayload(
                markdownText = null,
                plainText = "ordinary\r\nplain",
            ),
        )
    }

    fun testMarkdownClipboardIsUsedOnlyWhenPlainPayloadMatchesActivePaste() {
        val transferable = TestMarkdownTransferable(
            markdown = "**markdown**",
            plain = "plain\r\nsource",
        )
        assertEquals(
            "**markdown**",
            NativeMarkdownClipboard.readMarkdownForPlatformText(transferable, "plain\nsource"),
        )
        assertNull(
            NativeMarkdownClipboard.readMarkdownForPlatformText(transferable, "different paste source"),
        )
        assertTrue(NativeMarkdownClipboard.samePlatformPlainPayload("a\r\nb\r", "a\nb\n"))
        assertFalse(NativeMarkdownClipboard.samePlatformPlainPayload("clipboard", "producer"))
    }

    fun testPreprocessorUsesCapturedActiveTransferableInsteadOfMatchingGlobalClipboard() {
        myFixture.configureByText("bridge.md", "target")
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(0)
        val active = TestMarkdownTransferable(markdown = "**active**", plain = "same plain")
        val conflictingGlobal = TestMarkdownTransferable(markdown = "**global**", plain = "same plain")
        CopyPasteManager.getInstance().setContents(conflictingGlobal)

        NativePasteTransferableCapturePostProcessor().extractTransferableData(active)
        try {
            assertEquals(
                "**active**",
                NativeMarkdownPastePreProcessor().preprocessOnPaste(
                    project,
                    myFixture.file,
                    editor,
                    "same plain",
                    null,
                ),
            )
            assertNull(NativePasteTransferableBridge.take())
        } finally {
            NativePasteTransferableBridge.clear()
        }
    }

    fun testClipboardAuxiliaryPayloadsAreBoundedBeforeUse() {
        assertEquals("12345678", NativeMarkdownClipboard.readBounded(StringReader("12345678"), 8))
        assertNull(NativeMarkdownClipboard.readBounded(StringReader("123456789"), 8))
        assertEquals("abc", NativeMarkdownClipboard.readBounded(ZeroThenReader("abc"), 8))

        val oversizedMarkdown = TestMarkdownTransferable(
            markdown = "123456789",
            plain = "plain",
        )
        assertNull(
            NativeMarkdownClipboard.readMarkdownForPlatformText(
                oversizedMarkdown,
                platformText = "plain",
                maxChars = 8,
            ),
        )

        val oversizedPlain = TestMarkdownTransferable(
            markdown = "**safe**",
            plain = "123456789",
        )
        assertNull(
            NativeMarkdownClipboard.readMarkdownForPlatformText(
                oversizedPlain,
                platformText = "123456789",
                maxChars = 8,
            ),
        )
    }

    fun testParserProvenCodeBlocksDelegateToPlatformPaste() {
        val source = """# outside

```kotlin
val value = 1
```

    indented code

outside tail
"""
        val outsideOffset = source.indexOf("outside tail") + 2
        val fencedOffset = source.indexOf("val value") + 2
        val indentedOffset = source.indexOf("indented code") + 2

        assertTrue(
            NativeMarkdownPastePolicy.canNormalizeAt(
                source,
                listOf(NativePasteLocation(outsideOffset, outsideOffset, outsideOffset)),
            )
        )
        assertFalse(
            NativeMarkdownPastePolicy.canNormalizeAt(
                source,
                listOf(NativePasteLocation(fencedOffset, fencedOffset, fencedOffset)),
            )
        )
        assertFalse(
            NativeMarkdownPastePolicy.canNormalizeAt(
                source,
                listOf(NativePasteLocation(indentedOffset, indentedOffset, indentedOffset)),
            )
        )
    }

    fun testSelectionCrossingCodeBlockDelegatesToPlatformPaste() {
        val source = "before\n\n```text\ncode\n```\n\nafter\n"
        val start = source.indexOf("before")
        val end = source.indexOf("after") + 2
        assertFalse(
            NativeMarkdownPastePolicy.canNormalizeAt(
                source,
                listOf(NativePasteLocation(end, start, end)),
            )
        )
    }

    fun testPreprocessorDelegatesUnchangedWhenEditorHasMultipleCarets() {
        myFixture.configureByText("paste-multi.md", "first line\nsecond line\n")
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(1)
        requireNotNull(editor.caretModel.addCaret(editor.offsetToVisualPosition(12)))
        assertEquals(2, editor.caretModel.caretCount)

        val platformText = "\uFEFF# heading\r\nbody\r"
        assertEquals(
            platformText,
            NativeMarkdownPastePreProcessor().preprocessOnPaste(
                project,
                myFixture.file,
                editor,
                platformText,
                null,
            ),
        )
        assertEquals("first line\nsecond line\n", editor.document.text)
    }

    fun testStrongEditChangesOnlyExplicitSelection() {
        myFixture.configureByText("strong.md", "prefix alpha suffix")
        val editor = myFixture.editor
        val start = editor.document.text.indexOf("alpha")
        val end = start + "alpha".length
        editor.caretModel.primaryCaret.setSelection(start, end)

        assertEquals(
            NativeMarkdownRichEditResult.APPLIED,
            NativeMarkdownRichEdit.apply(project, editor, NativeMarkdownRichEditKind.STRONG),
        )
        assertEquals("prefix **alpha** suffix", editor.document.text)
        assertEquals("alpha", editor.selectionModel.selectedText)
    }

    fun testMulticaretEmphasisUsesOffsetSafeOrdering() {
        myFixture.configureByText("multi.md", "alpha middle beta")
        val editor = myFixture.editor
        val primary = editor.caretModel.primaryCaret
        primary.setSelection(0, 5)
        val secondary = requireNotNull(editor.caretModel.addCaret(editor.offsetToVisualPosition(17)))
        secondary.setSelection(13, 17)

        assertEquals(
            NativeMarkdownRichEditResult.APPLIED,
            NativeMarkdownRichEdit.apply(project, editor, NativeMarkdownRichEditKind.EMPHASIS),
        )
        assertEquals("*alpha* middle *beta*", editor.document.text)
        assertEquals(2, editor.caretModel.caretCount)
        assertEquals(setOf("alpha", "beta"), editor.caretModel.allCarets.mapNotNull { it.selectedText }.toSet())
    }

    fun testGuardedMulticaretSelectionFailsBeforeAnySourceMutation() {
        myFixture.configureByText("guarded.md", "alpha middle beta")
        val editor = myFixture.editor
        val document = editor.document
        val primary = editor.caretModel.primaryCaret
        primary.setSelection(0, 5)
        val secondary = requireNotNull(editor.caretModel.addCaret(editor.offsetToVisualPosition(17)))
        secondary.setSelection(13, 17)
        val sourceBefore = document.text
        val guarded = document.createGuardedBlock(0, 5)

        try {
            assertEquals(
                NativeMarkdownRichEditResult.GUARDED_SELECTION,
                NativeMarkdownRichEdit.apply(project, editor, NativeMarkdownRichEditKind.EMPHASIS),
            )
            assertEquals(sourceBefore, document.text)
            assertEquals(setOf("alpha", "beta"), editor.caretModel.allCarets.mapNotNull { it.selectedText }.toSet())
        } finally {
            document.removeGuardedBlock(guarded)
        }
    }

    fun testReadOnlyRichEditFailsWithoutSourceMutation() {
        myFixture.configureByText("readonly.md", "alpha")
        val editor = myFixture.editor
        editor.caretModel.primaryCaret.setSelection(0, 5)
        editor.document.setReadOnly(true)
        try {
            assertEquals(
                NativeMarkdownRichEditResult.READ_ONLY,
                NativeMarkdownRichEdit.apply(project, editor, NativeMarkdownRichEditKind.STRONG),
            )
            assertEquals("alpha", editor.document.text)
        } finally {
            editor.document.setReadOnly(false)
        }
    }

    fun testOverlapPolicyRejectsAmbiguousRangesButAllowsAdjacentRanges() {
        assertTrue(
            NativeMarkdownRichEdit.hasAmbiguousOverlap(
                listOf(NativeEditRange(2, 8), NativeEditRange(6, 10))
            )
        )
        assertFalse(
            NativeMarkdownRichEdit.hasAmbiguousOverlap(
                listOf(NativeEditRange(2, 8), NativeEditRange(8, 10))
            )
        )
    }

    fun testRichEditRequiresEveryCaretSelectionAndRejectsUnsafeInlineCode() {
        myFixture.configureByText("guard.md", "alpha beta`gamma")
        val editor = myFixture.editor
        editor.caretModel.primaryCaret.moveToOffset(2)
        assertEquals(
            NativeMarkdownRichEditResult.NO_SELECTION,
            NativeMarkdownRichEdit.apply(project, editor, NativeMarkdownRichEditKind.STRONG),
        )
        assertEquals("alpha beta`gamma", editor.document.text)

        val start = editor.document.text.indexOf("beta")
        val end = editor.document.text.length
        editor.caretModel.primaryCaret.setSelection(start, end)
        assertEquals(
            NativeMarkdownRichEditResult.UNSUPPORTED_SELECTION,
            NativeMarkdownRichEdit.apply(project, editor, NativeMarkdownRichEditKind.INLINE_CODE),
        )
        assertEquals("alpha beta`gamma", editor.document.text)
    }

    private class ZeroThenReader(text: String) : Reader() {
        private val delegate = StringReader(text)
        private var returnedZero = false

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (!returnedZero) {
                returnedZero = true
                return 0
            }
            return delegate.read(cbuf, off, len)
        }

        override fun read(): Int = delegate.read()

        override fun close() {
            delegate.close()
        }
    }

    private class TestMarkdownTransferable(
        private val markdown: String,
        private val plain: String,
    ) : Transferable {
        private val markdownFlavor = DataFlavor("text/markdown;class=java.lang.String", "Markdown")

        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(markdownFlavor, DataFlavor.stringFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
            flavor == markdownFlavor || flavor == DataFlavor.stringFlavor

        override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
            markdownFlavor -> markdown
            DataFlavor.stringFlavor -> plain
            else -> throw UnsupportedFlavorException(flavor)
        }
    }
}
