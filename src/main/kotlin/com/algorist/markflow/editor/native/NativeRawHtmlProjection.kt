package com.algorist.markflow.editor.native

import com.intellij.openapi.progress.ProcessCanceledException
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.plugins.markdown.lang.parser.MarkdownParserManager
import java.util.Locale

internal enum class NativeRawHtmlProjectionKind(val block: Boolean) {
    INLINE(false),
    BLOCK(true),
}

/**
 * Exact source-backed raw-HTML fragment selected only from JetBrains Markdown parser ranges.
 *
 * The fragment never becomes a mutable representation of Markdown. [source] is a snapshot slice
 * retained only as bounded renderer input; [sourceRange] remains the authority for reveal/staleness.
 */
internal data class NativeRawHtmlProjection(
    val kind: NativeRawHtmlProjectionKind,
    val sourceRange: ProjectionRange,
    val source: String,
) {
    val block: Boolean
        get() = kind.block
}

/**
 * Conservative #149 raw-HTML planner.
 *
 * Block HTML is accepted only from parser-proven [MarkdownElementTypes.HTML_BLOCK] nodes. Inline
 * HTML is accepted only when parser-proven [MarkdownTokenTypes.HTML_TAG] tokens form an unambiguous
 * balanced fragment inside one paragraph. Regex is used only to classify an already parser-proven
 * tag token; it never discovers HTML ranges in Markdown source.
 *
 * Unmatched/crossed/unknown token shapes simply remain exact source.
 */
internal object NativeRawHtmlProjectionPlanner {
    fun plan(basePlan: NativeProjectionPlan): List<NativeRawHtmlProjection> {
        if (basePlan.status != ProjectionPlanStatus.READY) return emptyList()
        val source = basePlan.identity.source
        val parserSource: CharSequence = source
        return try {
            val parser = MarkdownParserManager.createMarkdownParser(
                MarkdownParserManager.FLAVOUR,
                assertionsEnabled = false,
            )
            val root = parser.parse(
                MarkdownElementTypes.MARKDOWN_FILE,
                parserSource,
                parseInlines = true,
            )
            buildList {
                collect(root, source, this)
            }
                .sortedWith(
                    compareBy<NativeRawHtmlProjection> { it.sourceRange.startOffset }
                        .thenBy { it.sourceRange.endOffset }
                        .thenBy { it.kind.ordinal }
                )
                .filterNonOverlapping()
        } catch (failure: Exception) {
            if (failure is ProcessCanceledException) throw failure
            emptyList()
        }
    }

    private fun collect(
        node: ASTNode,
        source: String,
        output: MutableList<NativeRawHtmlProjection>,
    ) {
        when (node.type) {
            MarkdownElementTypes.HTML_BLOCK -> {
                projection(node.startOffset, node.endOffset, NativeRawHtmlProjectionKind.BLOCK, source)
                    ?.let(output::add)
                return
            }
            MarkdownElementTypes.PARAGRAPH -> {
                output += inlineProjections(node, source)
                return
            }
        }
        node.children.forEach { child -> collect(child, source, output) }
    }

    private fun inlineProjections(paragraph: ASTNode, source: String): List<NativeRawHtmlProjection> {
        val tags = buildList {
            collectHtmlTags(paragraph, this)
        }.sortedBy { it.startOffset }
        if (tags.isEmpty()) return emptyList()

        val output = mutableListOf<NativeRawHtmlProjection>()
        val stack = mutableListOf<OpenTag>()
        tags.forEach { node ->
            val range = projectionRangeOrNull(node.startOffset, node.endOffset, source) ?: return@forEach
            val parsed = parseTag(source.substring(range.startOffset, range.endOffset)) ?: run {
                stack.clear()
                return@forEach
            }

            if (parsed.closing) {
                val open = stack.lastOrNull()
                if (open == null || open.name != parsed.name) {
                    stack.clear()
                    return@forEach
                }
                stack.removeAt(stack.lastIndex)
                if (stack.isEmpty()) {
                    projection(
                        open.startOffset,
                        range.endOffset,
                        NativeRawHtmlProjectionKind.INLINE,
                        source,
                    )?.let(output::add)
                }
                return@forEach
            }

            if (parsed.selfClosing || parsed.name in VOID_TAGS) {
                if (stack.isEmpty()) {
                    projection(
                        range.startOffset,
                        range.endOffset,
                        NativeRawHtmlProjectionKind.INLINE,
                        source,
                    )?.let(output::add)
                }
                return@forEach
            }

            stack += OpenTag(parsed.name, range.startOffset)
        }
        return output
    }

    private fun collectHtmlTags(node: ASTNode, output: MutableList<ASTNode>) {
        if (node.type == MarkdownTokenTypes.HTML_TAG) {
            output += node
            return
        }
        node.children.forEach { child -> collectHtmlTags(child, output) }
    }

    private fun parseTag(raw: String): ParsedTag? {
        val match = TAG_PATTERN.matchEntire(raw.trim()) ?: return null
        val name = match.groupValues[2].lowercase(Locale.ROOT)
        return ParsedTag(
            name = name,
            closing = match.groupValues[1].isNotEmpty(),
            selfClosing = match.groupValues[3].isNotEmpty(),
        )
    }

    private fun projection(
        startOffset: Int,
        endOffset: Int,
        kind: NativeRawHtmlProjectionKind,
        source: String,
    ): NativeRawHtmlProjection? {
        val range = projectionRangeOrNull(startOffset, endOffset, source) ?: return null
        return NativeRawHtmlProjection(
            kind = kind,
            sourceRange = range,
            source = source.substring(range.startOffset, range.endOffset),
        )
    }

    private fun projectionRangeOrNull(start: Int, end: Int, source: String): ProjectionRange? =
        if (start >= 0 && end > start && end <= source.length) ProjectionRange(start, end) else null

    private fun List<NativeRawHtmlProjection>.filterNonOverlapping(): List<NativeRawHtmlProjection> {
        if (isEmpty()) return this
        val result = mutableListOf<NativeRawHtmlProjection>()
        for (candidate in this) {
            val previous = result.lastOrNull()
            if (previous == null || previous.sourceRange.endOffset <= candidate.sourceRange.startOffset) {
                result += candidate
            }
        }
        return result
    }

    private data class OpenTag(
        val name: String,
        val startOffset: Int,
    )

    private data class ParsedTag(
        val name: String,
        val closing: Boolean,
        val selfClosing: Boolean,
    )

    private val TAG_PATTERN = """^<\s*(/?)\s*([A-Za-z][A-Za-z0-9:-]*)\b[^>]*?(/?)\s*>$""".toRegex()

    private val VOID_TAGS = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"
    )
}
