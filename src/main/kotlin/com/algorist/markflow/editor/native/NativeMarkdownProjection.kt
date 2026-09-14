package com.algorist.markflow.editor.native

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProcessCanceledException
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.plugins.markdown.lang.parser.MarkdownParserManager

/** Immutable identity for one projection input generation. */
internal data class ProjectionSourceIdentity(
    val modificationStamp: Long,
    val source: String,
    val configGeneration: Long,
)

/** Immutable input captured atomically from the authoritative IntelliJ [Document]. */
internal data class ProjectionSnapshot(
    val identity: ProjectionSourceIdentity,
) {
    val source: String
        get() = identity.source

    companion object {
        fun capture(document: Document, configGeneration: Long): ProjectionSnapshot =
            ReadAction.computeBlocking<ProjectionSnapshot, RuntimeException> {
                ProjectionSnapshot(
                    ProjectionSourceIdentity(
                        modificationStamp = document.modificationStamp,
                        source = document.immutableCharSequence.toString(),
                        configGeneration = configGeneration,
                    )
                )
            }
    }
}

internal data class ProjectionRange(
    val startOffset: Int,
    val endOffset: Int,
) {
    init {
        require(startOffset >= 0) { "projection range start must be non-negative" }
        require(endOffset > startOffset) { "projection range must be non-empty" }
    }

    fun isInside(source: String): Boolean = endOffset <= source.length

    fun intersects(start: Int, end: Int): Boolean = start < endOffset && end > startOffset

    /** Parser/document ranges are half-open: [startOffset, endOffset). */
    fun contains(offset: Int): Boolean = offset in startOffset until endOffset

    /**
     * Presentation reveal is intentionally boundary-inclusive. A caret immediately before/after a
     * concealed delimiter must not sit against a hidden fold and be pushed by editor folding.
     * Semantic/parser containment remains [contains]'s half-open contract.
     */
    fun touches(offset: Int): Boolean = offset in startOffset..endOffset
}

internal enum class NativeProjectionKind {
    PARAGRAPH,
    HEADING,
    EMPHASIS,
    STRONG,
    LINK,
    UNORDERED_LIST,
    ORDERED_LIST,
    LIST_ITEM,
    BLOCK_QUOTE,
    INLINE_CODE,
    CODE_FENCE,
    CODE_BLOCK,
    THEMATIC_BREAK,
    TABLE,
    TABLE_HEADER,
    TABLE_ROW,
}

internal data class NativeProjection(
    val kind: NativeProjectionKind,
    val sourceRange: ProjectionRange,
    val syntaxRanges: List<ProjectionRange> = emptyList(),
    val contentRanges: List<ProjectionRange> = emptyList(),
    val block: Boolean,
)

internal enum class ProjectionPlanStatus {
    READY,
    DEGRADED_TO_SOURCE,
}

/**
 * Immutable derived plan. It never owns or mutates Markdown source.
 *
 * [failureClass] is intentionally limited to a type name so parser failures do not copy document
 * content into diagnostics by accident.
 */
internal data class NativeProjectionPlan(
    val identity: ProjectionSourceIdentity,
    val projections: List<NativeProjection>,
    val status: ProjectionPlanStatus,
    val failureClass: String? = null,
)

/**
 * Snapshot-only Markdown projection planner for the native editor.
 *
 * The bundled JetBrains Markdown parser is used only as a maintained range parser. The result is
 * immutable presentation intent; it is never a second editable Markdown representation.
 */
internal object NativeMarkdownProjectionPlanner {
    private val headingTypes = setOf(
        MarkdownElementTypes.ATX_1,
        MarkdownElementTypes.ATX_2,
        MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4,
        MarkdownElementTypes.ATX_5,
        MarkdownElementTypes.ATX_6,
        MarkdownElementTypes.SETEXT_1,
        MarkdownElementTypes.SETEXT_2,
    )

    private val linkTypes = setOf(
        MarkdownElementTypes.INLINE_LINK,
        MarkdownElementTypes.FULL_REFERENCE_LINK,
        MarkdownElementTypes.SHORT_REFERENCE_LINK,
        MarkdownElementTypes.AUTOLINK,
    )

    fun plan(snapshot: ProjectionSnapshot): NativeProjectionPlan {
        val source = snapshot.source
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
            validateTree(root, source.length)

            val projections = buildList {
                collect(root, source, this)
            }.sortedWith(
                compareBy<NativeProjection> { it.sourceRange.startOffset }
                    .thenBy { it.sourceRange.endOffset }
                    .thenBy { it.kind.ordinal }
            )

            NativeProjectionPlan(
                identity = snapshot.identity,
                projections = projections,
                status = ProjectionPlanStatus.READY,
            )
        } catch (failure: Exception) {
            if (failure is ProcessCanceledException) throw failure
            NativeProjectionPlan(
                identity = snapshot.identity,
                projections = emptyList(),
                status = ProjectionPlanStatus.DEGRADED_TO_SOURCE,
                failureClass = failure.javaClass.name,
            )
        }
    }

    private fun collect(
        node: ASTNode,
        source: String,
        output: MutableList<NativeProjection>,
    ) {
        // #147 owns image/resource presentation. An IMAGE contains an INLINE_LINK subtree, but that
        // is image syntax rather than an ordinary clickable link. Do not let the ordinary #152
        // projection fold the image's source out from under the dedicated resource owner.
        if (node.type == MarkdownElementTypes.IMAGE) return

        projectionFor(node, source)?.let(output::add)
        node.children.forEach { child -> collect(child, source, output) }
    }

    private fun projectionFor(node: ASTNode, source: String): NativeProjection? {
        if (node.endOffset <= node.startOffset) return null

        val kind = when (node.type) {
            MarkdownElementTypes.PARAGRAPH -> NativeProjectionKind.PARAGRAPH
            in headingTypes -> NativeProjectionKind.HEADING
            MarkdownElementTypes.EMPH -> NativeProjectionKind.EMPHASIS
            MarkdownElementTypes.STRONG -> NativeProjectionKind.STRONG
            in linkTypes -> NativeProjectionKind.LINK
            MarkdownElementTypes.UNORDERED_LIST -> NativeProjectionKind.UNORDERED_LIST
            MarkdownElementTypes.ORDERED_LIST -> NativeProjectionKind.ORDERED_LIST
            MarkdownElementTypes.LIST_ITEM -> NativeProjectionKind.LIST_ITEM
            MarkdownElementTypes.BLOCK_QUOTE -> NativeProjectionKind.BLOCK_QUOTE
            MarkdownElementTypes.CODE_SPAN -> NativeProjectionKind.INLINE_CODE
            MarkdownElementTypes.CODE_FENCE -> NativeProjectionKind.CODE_FENCE
            MarkdownElementTypes.CODE_BLOCK -> NativeProjectionKind.CODE_BLOCK
            MarkdownTokenTypes.HORIZONTAL_RULE -> NativeProjectionKind.THEMATIC_BREAK
            GFMElementTypes.TABLE -> NativeProjectionKind.TABLE
            GFMElementTypes.HEADER -> NativeProjectionKind.TABLE_HEADER
            GFMElementTypes.ROW -> NativeProjectionKind.TABLE_ROW
            else -> return null
        }

        val sourceRange = ProjectionRange(node.startOffset, node.endOffset)
        check(sourceRange.isInside(source)) {
            "parser produced an out-of-bounds projection range"
        }

        return NativeProjection(
            kind = kind,
            sourceRange = sourceRange,
            syntaxRanges = syntaxRangesFor(kind, node, source, sourceRange),
            contentRanges = contentRangesFor(kind, node, source, sourceRange),
            block = kind in BLOCK_KINDS,
        )
    }

    private fun syntaxRangesFor(
        kind: NativeProjectionKind,
        node: ASTNode,
        source: String,
        sourceRange: ProjectionRange,
    ): List<ProjectionRange> {
        if (kind == NativeProjectionKind.LINK) {
            return linkSyntaxRanges(node, source, sourceRange)
        }
        if (kind == NativeProjectionKind.THEMATIC_BREAK) {
            return listOf(sourceRange)
        }

        val syntaxTypes = when (kind) {
            NativeProjectionKind.HEADING -> setOf(
                MarkdownTokenTypes.ATX_HEADER,
                MarkdownTokenTypes.SETEXT_1,
                MarkdownTokenTypes.SETEXT_2,
            )
            NativeProjectionKind.EMPHASIS,
            NativeProjectionKind.STRONG,
            -> setOf(MarkdownTokenTypes.EMPH)
            NativeProjectionKind.LIST_ITEM -> setOf(
                MarkdownTokenTypes.LIST_BULLET,
                MarkdownTokenTypes.LIST_NUMBER,
            )
            NativeProjectionKind.BLOCK_QUOTE -> setOf(MarkdownTokenTypes.BLOCK_QUOTE)
            NativeProjectionKind.INLINE_CODE -> setOf(
                MarkdownTokenTypes.BACKTICK,
                MarkdownTokenTypes.ESCAPED_BACKTICKS,
            )
            NativeProjectionKind.CODE_FENCE -> setOf(
                MarkdownTokenTypes.CODE_FENCE_START,
                MarkdownTokenTypes.CODE_FENCE_END,
            )
            else -> emptySet()
        }
        if (syntaxTypes.isEmpty()) return emptyList()
        val parserRanges = directChildRanges(node, syntaxTypes, source, sourceRange)
        return if (kind == NativeProjectionKind.LIST_ITEM || kind == NativeProjectionKind.BLOCK_QUOTE) {
            parserRanges.mapNotNull { range -> trimMarkerSeparator(range, source) }
        } else {
            parserRanges
        }
    }

    /**
     * LIST_BULLET/LIST_NUMBER/BLOCK_QUOTE tokens can include the separator whitespace following the
     * marker. Presentation owns only the actual marker: preserve the parser-proven separator bytes
     * exactly rather than folding them into a placeholder.
     */
    private fun trimMarkerSeparator(range: ProjectionRange, source: String): ProjectionRange? {
        var end = range.endOffset
        while (end > range.startOffset && source[end - 1].isWhitespace()) end -= 1
        return projectionRangeOrNull(range.startOffset, end, source)
    }

    /**
     * Structured content ranges are parser-owned semantic children, not guessed text splits.
     * Tables use CELL ranges. Links use the text/label content inside parser-proven brackets so the
     * presentation can conceal only the syntax around the visible label.
     */
    private fun contentRangesFor(
        kind: NativeProjectionKind,
        node: ASTNode,
        source: String,
        sourceRange: ProjectionRange,
    ): List<ProjectionRange> = when (kind) {
        NativeProjectionKind.TABLE_HEADER,
        NativeProjectionKind.TABLE_ROW,
        -> directChildRanges(node, setOf(GFMTokenTypes.CELL), source, sourceRange)
        NativeProjectionKind.LINK -> linkVisibleRange(node, source, sourceRange)?.let(::listOf).orEmpty()
        else -> emptyList()
    }

    private fun linkSyntaxRanges(
        node: ASTNode,
        source: String,
        sourceRange: ProjectionRange,
    ): List<ProjectionRange> {
        val visible = linkVisibleRange(node, source, sourceRange)
        if (visible != null) {
            return buildList {
                projectionRangeOrNull(sourceRange.startOffset, visible.startOffset, source)?.let(::add)
                projectionRangeOrNull(visible.endOffset, sourceRange.endOffset, source)?.let(::add)
            }
        }

        // Wrapped autolinks are a parser-proven LINK range whose exact source starts/ends in angle
        // brackets. Bare GFM autolinks intentionally keep no conceal ranges.
        if (
            node.type == MarkdownElementTypes.AUTOLINK &&
            sourceRange.endOffset - sourceRange.startOffset >= 2 &&
            source[sourceRange.startOffset] == '<' &&
            source[sourceRange.endOffset - 1] == '>'
        ) {
            return listOf(
                ProjectionRange(sourceRange.startOffset, sourceRange.startOffset + 1),
                ProjectionRange(sourceRange.endOffset - 1, sourceRange.endOffset),
            )
        }
        return emptyList()
    }

    private fun linkVisibleRange(
        node: ASTNode,
        source: String,
        sourceRange: ProjectionRange,
    ): ProjectionRange? {
        val textContainer = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
            ?: if (node.type == MarkdownElementTypes.SHORT_REFERENCE_LINK) {
                node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
            } else {
                null
            }
            ?: return null

        val children = textContainer.children
        val first = children.firstOrNull() ?: return null
        val last = children.lastOrNull() ?: return null
        if (first.type != MarkdownTokenTypes.LBRACKET || last.type != MarkdownTokenTypes.RBRACKET) return null
        val range = projectionRangeOrNull(first.endOffset, last.startOffset, source) ?: return null
        if (range.startOffset < sourceRange.startOffset || range.endOffset > sourceRange.endOffset) return null
        return range
    }

    private fun projectionRangeOrNull(start: Int, end: Int, source: String): ProjectionRange? {
        if (end <= start) return null
        return ProjectionRange(start, end).takeIf { it.isInside(source) }
    }

    private fun directChildRanges(
        node: ASTNode,
        acceptedTypes: Set<IElementType>,
        source: String,
        sourceRange: ProjectionRange,
    ): List<ProjectionRange> =
        node.children
            .asSequence()
            .filter { child -> child.type in acceptedTypes && child.endOffset > child.startOffset }
            .map { child -> ProjectionRange(child.startOffset, child.endOffset) }
            .filter { range ->
                range.isInside(source) &&
                    range.startOffset >= sourceRange.startOffset &&
                    range.endOffset <= sourceRange.endOffset
            }
            .distinct()
            .sortedBy(ProjectionRange::startOffset)
            .toList()

    private fun validateTree(root: ASTNode, sourceLength: Int) {
        fun validate(node: ASTNode, parent: ASTNode?) {
            check(node.startOffset >= 0)
            check(node.endOffset >= node.startOffset)
            check(node.endOffset <= sourceLength)
            if (parent != null) {
                check(node.startOffset >= parent.startOffset)
                check(node.endOffset <= parent.endOffset)
            }
            node.children.forEach { child -> validate(child, node) }
        }
        validate(root, null)
    }

    private val BLOCK_KINDS = setOf(
        NativeProjectionKind.PARAGRAPH,
        NativeProjectionKind.HEADING,
        NativeProjectionKind.UNORDERED_LIST,
        NativeProjectionKind.ORDERED_LIST,
        NativeProjectionKind.LIST_ITEM,
        NativeProjectionKind.BLOCK_QUOTE,
        NativeProjectionKind.CODE_FENCE,
        NativeProjectionKind.CODE_BLOCK,
        NativeProjectionKind.THEMATIC_BREAK,
        NativeProjectionKind.TABLE,
        NativeProjectionKind.TABLE_HEADER,
        NativeProjectionKind.TABLE_ROW,
    )
}
