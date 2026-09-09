package com.algorist.markflow.editor.native

import com.algorist.markflow.renderer.DerivedRendererKind

internal enum class NativeDerivedProjectionKind(
    val rendererKind: DerivedRendererKind,
    val block: Boolean,
) {
    MERMAID(DerivedRendererKind.MERMAID, true),
    KATEX_INLINE(DerivedRendererKind.KATEX, false),
    KATEX_DISPLAY(DerivedRendererKind.KATEX, true),
}

/**
 * One renderer fragment derived from an immutable #145 projection generation.
 *
 * [sourceRange] is the complete Markdown syntax to reveal/hide. [contentRange] is the exact
 * renderer input inside that syntax. Neither range grants source mutation authority.
 */
internal data class NativeDerivedProjection(
    val kind: NativeDerivedProjectionKind,
    val sourceRange: ProjectionRange,
    val contentRange: ProjectionRange,
    val source: String,
) {
    init {
        require(sourceRange.startOffset <= contentRange.startOffset)
        require(sourceRange.endOffset >= contentRange.endOffset)
    }

    val block: Boolean
        get() = kind.block
}

/**
 * Conservative native renderer-fragment planner for #148.
 *
 * Mermaid recognition reuses parser-proven fenced-code ranges from #145. Math scanning excludes
 * parser-proven fenced-code and inline-code ranges and leaves every ambiguous/unclosed construct as
 * exact source. It never reconstructs Markdown and never creates a mutable secondary model.
 */
internal object NativeDerivedProjectionPlanner {
    fun plan(basePlan: NativeProjectionPlan): List<NativeDerivedProjection> {
        if (basePlan.status != ProjectionPlanStatus.READY) return emptyList()
        val source = basePlan.identity.source
        val excluded = basePlan.projections
            .asSequence()
            .filter { it.kind == NativeProjectionKind.CODE_FENCE || it.kind == NativeProjectionKind.INLINE_CODE }
            .map { it.sourceRange }
            .sortedBy { it.startOffset }
            .toList()

        val mermaid = basePlan.projections
            .asSequence()
            .filter { it.kind == NativeProjectionKind.CODE_FENCE }
            .mapNotNull { projection -> mermaidProjection(source, projection.sourceRange) }
            .toList()
        val math = mathProjections(source, excluded)

        return (mermaid + math)
            .sortedWith(
                compareBy<NativeDerivedProjection> { it.sourceRange.startOffset }
                    .thenBy { it.sourceRange.endOffset }
                    .thenBy { it.kind.ordinal }
            )
            .filterNonOverlapping()
    }

    private fun mermaidProjection(source: String, range: ProjectionRange): NativeDerivedProjection? {
        if (!range.isInside(source)) return null
        val block = source.substring(range.startOffset, range.endOffset)
        val firstBreak = block.indexOf('\n')
        if (firstBreak < 0) return null

        val opening = block.substring(0, firstBreak).trim()
        val marker = opening.takeWhile { it == '`' || it == '~' }
        if (marker.length < 3 || marker.any { it != marker[0] }) return null
        val info = opening.substring(marker.length).trim()
        if (!info.equals("mermaid", ignoreCase = true)) return null

        val closingStart = findClosingFenceStart(block, marker) ?: return null
        val contentStart = firstBreak + 1
        if (closingStart <= contentStart) return null
        val body = block.substring(contentStart, closingStart)
        if (body.isBlank()) return null

        val contentRange = ProjectionRange(
            range.startOffset + contentStart,
            range.startOffset + closingStart,
        )
        return NativeDerivedProjection(
            kind = NativeDerivedProjectionKind.MERMAID,
            sourceRange = range,
            contentRange = contentRange,
            source = source.substring(contentRange.startOffset, contentRange.endOffset),
        )
    }

    private fun findClosingFenceStart(block: String, marker: String): Int? {
        var lineStart = block.lastIndexOf('\n', startIndex = block.length - 1)
        while (lineStart >= 0) {
            val candidateStart = lineStart + 1
            val candidate = block.substring(candidateStart).trim()
            if (isClosingFence(candidate, marker)) return candidateStart
            lineStart = if (lineStart == 0) -1 else block.lastIndexOf('\n', startIndex = lineStart - 1)
        }
        return null
    }

    private fun isClosingFence(candidate: String, marker: String): Boolean {
        if (candidate.isEmpty()) return false
        val fence = candidate.takeWhile { it == marker[0] }
        return fence.length >= marker.length && candidate.substring(fence.length).isBlank()
    }

    private fun mathProjections(
        source: String,
        excluded: List<ProjectionRange>,
    ): List<NativeDerivedProjection> {
        val output = mutableListOf<NativeDerivedProjection>()
        var index = 0
        while (index < source.length) {
            val excludedRange = excluded.firstOrNull { range -> range.covers(index) }
            if (excludedRange != null) {
                index = excludedRange.endOffset
                continue
            }
            if (source[index] != '$' || isEscaped(source, index)) {
                index += 1
                continue
            }

            val display = index + 1 < source.length && source[index + 1] == '$'
            val delimiterLength = if (display) 2 else 1
            val contentStart = index + delimiterLength
            val closing = findMathClosing(source, contentStart, display, excluded)
            if (closing == null) {
                index += delimiterLength
                continue
            }
            val contentEnd = closing
            if (contentEnd <= contentStart || source.substring(contentStart, contentEnd).isBlank()) {
                index = closing + delimiterLength
                continue
            }

            val sourceEnd = closing + delimiterLength
            val sourceRange = ProjectionRange(index, sourceEnd)
            val contentRange = ProjectionRange(contentStart, contentEnd)
            output += NativeDerivedProjection(
                kind = if (display) NativeDerivedProjectionKind.KATEX_DISPLAY else NativeDerivedProjectionKind.KATEX_INLINE,
                sourceRange = sourceRange,
                contentRange = contentRange,
                source = source.substring(contentStart, contentEnd),
            )
            index = sourceEnd
        }
        return output
    }

    private fun findMathClosing(
        source: String,
        start: Int,
        display: Boolean,
        excluded: List<ProjectionRange>,
    ): Int? {
        var index = start
        while (index < source.length) {
            if (excluded.any { range -> range.covers(index) }) return null
            if (!display && source[index] == '\n') return null
            if (source[index] == '$' && !isEscaped(source, index)) {
                if (display) {
                    if (index + 1 < source.length && source[index + 1] == '$') return index
                } else if (index + 1 >= source.length || source[index + 1] != '$') {
                    return index
                }
            }
            index += 1
        }
        return null
    }

    private fun ProjectionRange.covers(offset: Int): Boolean =
        offset >= startOffset && offset < endOffset

    private fun isEscaped(source: String, offset: Int): Boolean {
        var slashes = 0
        var index = offset - 1
        while (index >= 0 && source[index] == '\\') {
            slashes += 1
            index -= 1
        }
        return slashes % 2 == 1
    }

    private fun List<NativeDerivedProjection>.filterNonOverlapping(): List<NativeDerivedProjection> {
        if (isEmpty()) return this
        val result = mutableListOf<NativeDerivedProjection>()
        for (candidate in this) {
            val previous = result.lastOrNull()
            if (previous == null || previous.sourceRange.endOffset <= candidate.sourceRange.startOffset) {
                result += candidate
            }
        }
        return result
    }
}
