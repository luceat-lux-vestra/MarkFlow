package com.algorist.markflow.editor.native

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

/**
 * Exact-source footnote semantics derived only from the pinned Markdown parser AST.
 *
 * IntelliJ's parser intentionally exposes footnotes through generic reference/paragraph nodes rather
 * than a dedicated footnote AST. This grammar accepts only those parser-proven shapes. It never
 * searches the document with a regex and never reconstructs Markdown syntax from normalized text.
 */
internal object NativeFootnoteSemanticParser {
    internal data class Reference(
        val sourceRange: ProjectionRange,
        val labelRange: ProjectionRange,
        val label: String,
        val number: Int,
    )

    internal data class Definition(
        val sourceRange: ProjectionRange,
        val labelRange: ProjectionRange,
        val bodyRange: ProjectionRange,
        val label: String,
        val number: Int,
    )

    internal data class Result(
        val references: List<Reference>,
        val definitions: List<Definition>,
    ) {
        companion object {
            val EMPTY = Result(emptyList(), emptyList())
        }
    }

    private data class Label(
        val sourceRange: ProjectionRange,
        val value: String,
    )

    private data class DefinitionCandidate(
        val sourceRange: ProjectionRange,
        val definingReferenceRange: ProjectionRange?,
        val label: Label,
        val bodyRange: ProjectionRange,
    )

    private data class ReferenceCandidate(
        val sourceRange: ProjectionRange,
        val label: Label,
    )

    fun parse(root: ASTNode, source: String): Result {
        if (source.isEmpty()) return Result.EMPTY

        val definitions = mutableListOf<DefinitionCandidate>()
        val references = mutableListOf<ReferenceCandidate>()

        fun visit(node: ASTNode) {
            definitionCandidate(node, source)?.let(definitions::add)
            referenceCandidate(node, source)?.let(references::add)
            node.children.forEach(::visit)
        }
        visit(root)

        if (definitions.isEmpty() || references.isEmpty()) return Result.EMPTY

        val definitionReferenceRanges = definitions
            .mapNotNull(DefinitionCandidate::definingReferenceRange)
            .toSet()
        val consumerReferences = references
            .filterNot { reference -> reference.sourceRange in definitionReferenceRanges }
            .sortedBy { reference -> reference.sourceRange.startOffset }

        val definitionsByLabel = definitions.groupBy { definition -> definition.label.value }
        val uniqueDefinitions = definitionsByLabel
            .filterValues { candidates -> candidates.size == 1 }
            .mapValues { (_, candidates) -> candidates.single() }

        // Number only labels that have one unambiguous definition and at least one consumer reference.
        // First reference source order is the stable numbering contract.
        val numbers = LinkedHashMap<String, Int>()
        consumerReferences.forEach { reference ->
            if (reference.label.value in uniqueDefinitions) {
                numbers.getOrPut(reference.label.value) { numbers.size + 1 }
            }
        }
        if (numbers.isEmpty()) return Result.EMPTY

        val acceptedReferences = consumerReferences.mapNotNull { reference ->
            val number = numbers[reference.label.value] ?: return@mapNotNull null
            Reference(
                sourceRange = reference.sourceRange,
                labelRange = reference.label.sourceRange,
                label = reference.label.value,
                number = number,
            )
        }
        val acceptedDefinitions = numbers.entries
            .mapNotNull { (label, number) ->
                uniqueDefinitions[label]?.let { definition ->
                    Definition(
                        sourceRange = definition.sourceRange,
                        labelRange = definition.label.sourceRange,
                        bodyRange = definition.bodyRange,
                        label = label,
                        number = number,
                    )
                }
            }
            .sortedBy { definition -> definition.sourceRange.startOffset }

        return Result(
            references = acceptedReferences,
            definitions = acceptedDefinitions,
        )
    }

    private fun referenceCandidate(node: ASTNode, source: String): ReferenceCandidate? {
        if (node.type != MarkdownElementTypes.SHORT_REFERENCE_LINK || node.endOffset <= node.startOffset) {
            return null
        }
        val sourceRange = projectionRangeOrNull(node.startOffset, node.endOffset, source) ?: return null
        val linkLabel = node.children.singleOrNull { child -> child.type == MarkdownElementTypes.LINK_LABEL }
            ?: return null
        val label = footnoteLabel(linkLabel, source, sourceRange) ?: return null
        return ReferenceCandidate(sourceRange, label)
    }

    private fun definitionCandidate(node: ASTNode, source: String): DefinitionCandidate? =
        when (node.type) {
            MarkdownElementTypes.PARAGRAPH -> paragraphDefinition(node, source)
            MarkdownElementTypes.LINK_DEFINITION -> linkDefinition(node, source)
            else -> null
        }

    /**
     * The current IntelliJ parser represents the minimum footnote definition
     * `[^label]: body` as a paragraph whose first semantic child is a short reference/link label,
     * followed immediately by a parser-owned COLON token. Accept only that exact AST shape.
     */
    private fun paragraphDefinition(node: ASTNode, source: String): DefinitionCandidate? {
        val children = node.children
        val firstIndex = children.indexOfFirst { child -> !child.isLayoutToken() }
        if (firstIndex < 0) return null
        val first = children[firstIndex]

        val labelNode = when (first.type) {
            MarkdownElementTypes.SHORT_REFERENCE_LINK ->
                first.children.singleOrNull { child -> child.type == MarkdownElementTypes.LINK_LABEL }
            MarkdownElementTypes.LINK_LABEL -> first
            else -> null
        } ?: return null

        val sourceRange = projectionRangeOrNull(node.startOffset, node.endOffset, source) ?: return null
        val label = footnoteLabel(labelNode, source, sourceRange) ?: return null

        val colonIndex = children
            .withIndex()
            .drop(firstIndex + 1)
            .firstOrNull { (_, child) -> !child.isLayoutToken() }
            ?.index
            ?: return null
        val colon = children[colonIndex]
        if (colon.type != MarkdownTokenTypes.COLON || colon.startOffset != first.endOffset) return null

        val bodyStart = children
            .drop(colonIndex + 1)
            .firstOrNull { child -> !child.isLayoutToken() }
            ?.startOffset
            ?: return null
        val bodyRange = projectionRangeOrNull(bodyStart, node.endOffset, source) ?: return null
        if (source.substring(bodyRange.startOffset, bodyRange.endOffset).isBlank()) return null

        return DefinitionCandidate(
            sourceRange = sourceRange,
            definingReferenceRange = if (first.type == MarkdownElementTypes.SHORT_REFERENCE_LINK) {
                projectionRangeOrNull(first.startOffset, first.endOffset, source)
            } else {
                null
            },
            label = label,
            bodyRange = bodyRange,
        )
    }

    /**
     * A URI-shaped footnote body may be parsed as a normal LINK_DEFINITION. Treat it as a footnote
     * only when the parser-owned definition label itself has footnote syntax.
     */
    private fun linkDefinition(node: ASTNode, source: String): DefinitionCandidate? {
        val sourceRange = projectionRangeOrNull(node.startOffset, node.endOffset, source) ?: return null
        val children = node.children
        val labelNode = children.singleOrNull { child -> child.type == MarkdownElementTypes.LINK_LABEL }
            ?: return null
        val label = footnoteLabel(labelNode, source, sourceRange) ?: return null

        val colonIndex = children.indexOfFirst { child -> child.type == MarkdownTokenTypes.COLON }
        if (colonIndex < 0) return null
        val bodyStart = children
            .drop(colonIndex + 1)
            .firstOrNull { child -> !child.isLayoutToken() }
            ?.startOffset
            ?: return null
        val bodyRange = projectionRangeOrNull(bodyStart, node.endOffset, source) ?: return null
        if (source.substring(bodyRange.startOffset, bodyRange.endOffset).isBlank()) return null

        return DefinitionCandidate(
            sourceRange = sourceRange,
            definingReferenceRange = null,
            label = label,
            bodyRange = bodyRange,
        )
    }

    /**
     * Keep the accepted label grammar aligned with IntelliJ's current footnote contract without
     * calling its internal helper: [^ + one-or-more non-space/tab characters + ].
     */
    private fun footnoteLabel(
        node: ASTNode,
        source: String,
        ownerRange: ProjectionRange,
    ): Label? {
        if (node.type != MarkdownElementTypes.LINK_LABEL || node.endOffset <= node.startOffset) return null
        val children = node.children
        val first = children.firstOrNull() ?: return null
        val last = children.lastOrNull() ?: return null
        if (first.type != MarkdownTokenTypes.LBRACKET || last.type != MarkdownTokenTypes.RBRACKET) return null

        val contentRange = projectionRangeOrNull(first.endOffset, last.startOffset, source) ?: return null
        if (
            contentRange.startOffset < ownerRange.startOffset ||
            contentRange.endOffset > ownerRange.endOffset
        ) return null

        val content = source.substring(contentRange.startOffset, contentRange.endOffset)
        if (content.length < 2 || content.first() != '^') return null
        val value = content.substring(1)
        if (value.isEmpty() || value.any { char -> char == ' ' || char == '\t' || char == '\n' || char == '\r' }) {
            return null
        }

        val labelRange = projectionRangeOrNull(node.startOffset, node.endOffset, source) ?: return null
        return Label(labelRange, value)
    }

    private fun ASTNode.isLayoutToken(): Boolean =
        type == MarkdownTokenTypes.WHITE_SPACE || type == MarkdownTokenTypes.EOL

    private fun projectionRangeOrNull(start: Int, end: Int, source: String): ProjectionRange? =
        if (end > start) ProjectionRange(start, end).takeIf { range -> range.isInside(source) } else null
}
