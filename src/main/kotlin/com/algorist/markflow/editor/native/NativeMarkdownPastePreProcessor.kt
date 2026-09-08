package com.algorist.markflow.editor.native

import com.algorist.markflow.file.MarkFlowFileSupport
import com.intellij.codeInsight.editorActions.CopyPastePreProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RawText
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.limits.FileSizeLimit.Companion.getDefaultContentLoadLimit
import com.intellij.psi.PsiFile
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.plugins.markdown.lang.parser.MarkdownParserManager
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.Reader

/**
 * Markdown-specific payload preprocessing for the selected native platform editor.
 *
 * The preprocessor never writes the Document. IntelliJ's normal paste action remains responsible
 * for selections, insertion, command/undo, dirty state and save semantics. IntelliJ 2026.2 bypasses
 * CopyPastePreProcessor for multicaret paste, so #146 deliberately delegates that path unchanged
 * rather than adding a second paste-action owner; full paste parity remains #152 work.
 */
class NativeMarkdownPastePreProcessor : CopyPastePreProcessor {
    override fun preprocessOnCopy(
        file: PsiFile,
        startOffsets: IntArray,
        endOffsets: IntArray,
        text: String,
    ): String? = null

    override fun preprocessOnPaste(
        project: Project,
        file: PsiFile,
        editor: Editor,
        text: String,
        rawText: RawText?,
    ): String {
        val virtualFile = file.virtualFile ?: return text
        if (!MarkFlowFileSupport.isMarkFlowTarget(virtualFile)) return text
        if (editor.caretModel.caretCount != 1) return text

        // The bridge captured the exact Transferable used by this PasteHandler invocation. Consume
        // it before parsing so cancellation cannot leave an active-paste payload behind.
        val activeTransferable = NativePasteTransferableBridge.take()
        val source = editor.document.immutableCharSequence.toString()
        val locations = editor.caretModel.allCarets.map { caret ->
            NativePasteLocation(
                caretOffset = caret.offset,
                selectionStart = caret.selectionStart,
                selectionEnd = caret.selectionEnd,
            )
        }
        if (!NativeMarkdownPastePolicy.canNormalizeAt(source, locations)) return text

        val markdownText = NativeMarkdownClipboard.readMarkdownForPlatformText(
            activeTransferable,
            text,
        )
        return NativeMarkdownPastePolicy.choosePayload(markdownText, text)
    }

    override fun requiresAllDocumentsToBeCommitted(editor: Editor, project: Project): Boolean = false
}

internal data class NativePasteLocation(
    val caretOffset: Int,
    val selectionStart: Int,
    val selectionEnd: Int,
)

internal object NativeMarkdownPastePolicy {
    fun choosePayload(markdownText: String?, plainText: String): String = when {
        !markdownText.isNullOrBlank() -> normalizeInsertedMarkdown(markdownText)
        looksLikeMarkdown(plainText) -> normalizeInsertedMarkdown(plainText)
        else -> plainText
    }

    fun normalizeInsertedMarkdown(text: String): String =
        text.replaceFirst(LEADING_BOM, "").replace(CRLF_OR_CR, "\n")

    fun looksLikeMarkdown(text: String): Boolean {
        val normalized = normalizeInsertedMarkdown(text)
        if (RAW_HTML.containsMatchIn(normalized)) return true
        if (MARKDOWN_LINE_PATTERNS.any { it.containsMatchIn(normalized) }) return true
        if (MARKDOWN_LINK.containsMatchIn(normalized) || MARKDOWN_IMAGE.containsMatchIn(normalized)) return true
        return hasMarkdownTableStructure(normalized.lines())
    }

    fun canNormalizeAt(source: String, locations: List<NativePasteLocation>): Boolean {
        if (locations.isEmpty()) return false
        val codeRanges = try {
            parserProvenCodeRanges(source)
        } catch (failure: RuntimeException) {
            if (failure is ProcessCanceledException) throw failure
            return false
        }
        return locations.none { location -> codeRanges.any { range -> range.intersects(location) } }
    }

    private fun parserProvenCodeRanges(source: String): List<SourceRange> {
        val parser = MarkdownParserManager.createMarkdownParser(
            MarkdownParserManager.FLAVOUR,
            assertionsEnabled = false,
        )
        val parserSource: CharSequence = source
        val root = parser.parse(
            MarkdownElementTypes.MARKDOWN_FILE,
            parserSource,
            parseInlines = true,
        )
        val ranges = mutableListOf<SourceRange>()

        fun collect(node: ASTNode) {
            check(node.startOffset >= 0 && node.endOffset >= node.startOffset && node.endOffset <= source.length)
            if (node.type == MarkdownElementTypes.CODE_FENCE || node.type == MarkdownElementTypes.CODE_BLOCK) {
                if (node.endOffset > node.startOffset) {
                    ranges += SourceRange(node.startOffset, node.endOffset)
                }
                return
            }
            node.children.forEach(::collect)
        }

        collect(root)
        return ranges
    }

    private fun hasMarkdownTableStructure(lines: List<String>): Boolean =
        lines.count(TABLE_ROW::matches) >= 2 && lines.any(TABLE_DELIMITER::matches)

    private data class SourceRange(val startOffset: Int, val endOffset: Int) {
        fun intersects(location: NativePasteLocation): Boolean {
            val selectionStart = minOf(location.selectionStart, location.selectionEnd)
            val selectionEnd = maxOf(location.selectionStart, location.selectionEnd)
            if (selectionEnd > selectionStart) {
                return selectionStart < endOffset && selectionEnd > startOffset
            }
            return location.caretOffset in startOffset until endOffset
        }
    }

    private val LEADING_BOM = Regex("^\\uFEFF")
    private val CRLF_OR_CR = Regex("\\r\\n?")
    private val RAW_HTML = Regex("</?[A-Za-z][A-Za-z0-9-]*(?:\\s[^<>]*)?>")
    private val MARKDOWN_LINK = Regex("\\[[^]]+]\\([^)]+\\)")
    private val MARKDOWN_IMAGE = Regex("!\\[[^]]*]\\([^)]+\\)")
    private val MARKDOWN_LINE_PATTERNS = listOf(
        Regex("^#{1,6}\\s+\\S", RegexOption.MULTILINE),
        Regex("^\\s*```", RegexOption.MULTILINE),
        Regex("^\\s*\\$\\$", RegexOption.MULTILINE),
        Regex("^\\s*>\\s+\\S", RegexOption.MULTILINE),
        Regex("^\\s*[-*+]\\s+\\S", RegexOption.MULTILINE),
        Regex("^\\s*\\d+\\.\\s+\\S", RegexOption.MULTILINE),
        Regex("^\\s*[-*_]{3,}\\s*$", RegexOption.MULTILINE),
    )
    private val TABLE_ROW = Regex("^\\s*\\|.*\\|\\s*$")
    private val TABLE_DELIMITER = Regex("^\\s*\\|?\\s*[:\\-]{3,}(?:\\s*\\|\\s*[:\\-]{3,})+\\s*\\|?\\s*$")
}

internal object NativeMarkdownClipboard {
    private const val MARKDOWN_MIME = "text/markdown"
    private const val READ_BUFFER_SIZE = 8 * 1024
    private val CRLF_OR_CR = Regex("\\r\\n?")

    /**
     * The supplied Transferable must be the exact active paste payload captured by
     * NativePasteTransferableCapturePostProcessor, never a later/global clipboard lookup. Its
     * plain-text flavor must still correlate with the platform text seen at this point in the
     * preprocessor chain; if an earlier preprocessor changed that text, Markdown MIME does not win.
     *
     * Both flavors are streamed with the same character-count limit used by IntelliJ's
     * BasePasteHandler. This prevents the auxiliary Markdown lookup from creating an unbounded
     * second payload after the platform has already size-checked its authoritative plain text.
     */
    fun readMarkdownForPlatformText(
        transferable: Transferable?,
        platformText: String,
        maxChars: Int = getDefaultContentLoadLimit(),
    ): String? {
        if (transferable == null) return null
        val transferablePlain = readFlavorBounded(transferable, DataFlavor.stringFlavor, maxChars) ?: return null
        if (!samePlatformPlainPayload(transferablePlain, platformText)) return null
        return readMarkdown(transferable, maxChars)
    }

    internal fun samePlatformPlainPayload(transferablePlain: String, platformText: String): Boolean =
        transferablePlain.replace(CRLF_OR_CR, "\n") == platformText.replace(CRLF_OR_CR, "\n")

    internal fun readBounded(reader: Reader, maxChars: Int): String? {
        require(maxChars >= 0)
        val builder = StringBuilder(minOf(maxChars, READ_BUFFER_SIZE))
        val buffer = CharArray(READ_BUFFER_SIZE)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) return builder.toString()
            if (count == 0) {
                val next = reader.read()
                if (next < 0) return builder.toString()
                if (builder.length >= maxChars) return null
                builder.append(next.toChar())
                continue
            }
            if (count > maxChars - builder.length) return null
            builder.appendRange(buffer, 0, count)
        }
    }

    private fun readMarkdown(transferable: Transferable, maxChars: Int): String? {
        val flavor = try {
            transferable.transferDataFlavors.firstOrNull { candidate ->
                candidate.isMimeTypeEqual(MARKDOWN_MIME)
            }
        } catch (failure: ProcessCanceledException) {
            throw failure
        } catch (_: Exception) {
            return null
        } ?: return null
        return readFlavorBounded(transferable, flavor, maxChars)
    }

    private fun readFlavorBounded(transferable: Transferable, flavor: DataFlavor, maxChars: Int): String? =
        try {
            flavor.getReaderForText(transferable).use { reader -> readBounded(reader, maxChars) }
        } catch (failure: ProcessCanceledException) {
            throw failure
        } catch (_: Exception) {
            null
        }
}
