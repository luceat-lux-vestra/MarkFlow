package com.algorist.markflow.editor.native

import com.algorist.markflow.trust.ExternalNavigationPolicy
import java.util.Locale

internal enum class NativeHostResourceKind {
    LOCAL_IMAGE,
    EXTERNAL_LINK,
}

/** Immutable host-resource intent derived from one exact #145 projection generation. */
internal data class NativeHostResourceProjection(
    val kind: NativeHostResourceKind,
    val sourceRange: ProjectionRange,
    val activationRange: ProjectionRange?,
    val target: String,
)

/**
 * Conservative #147 scanner layered on the parser-proven #145 plan.
 *
 * Code spans/fences and reference-definition lines are excluded. Supported host intents are
 * limited to document-relative image targets and explicit HTTP(S) links. Unsupported/ambiguous
 * constructs remain exact source and acquire no host capability.
 */
internal object NativeHostResourceProjectionPlanner {
    private val definitionPattern = Regex(
        pattern = """(?m)^[ \t]{0,3}\[([^\]\r\n]+)]\s*:\s*(?:<([^>\r\n]+)>|([^\s\r\n]+))(?:[ \t]+(?:\"[^\"\r\n]*\"|'[^'\r\n]*'|\([^\)\r\n]*\)))?[ \t]*$"""
    )
    private val labelWhitespace = Regex("\\s+")
    private val schemePrefix = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    fun plan(basePlan: NativeProjectionPlan): List<NativeHostResourceProjection> {
        if (basePlan.status != ProjectionPlanStatus.READY) return emptyList()
        val source = basePlan.identity.source
        val parserExcluded = basePlan.projections
            .asSequence()
            .filter { it.kind == NativeProjectionKind.CODE_FENCE || it.kind == NativeProjectionKind.INLINE_CODE }
            .map { it.sourceRange }
            .sortedBy { it.startOffset }
            .toList()
        val definitions = collectDefinitions(source, parserExcluded)
        val excluded = (parserExcluded + definitions.ranges).sortedBy { it.startOffset }
        val output = mutableListOf<NativeHostResourceProjection>()

        var index = 0
        while (index < source.length) {
            val excludedRange = excluded.firstOrNull { it.covers(index) }
            if (excludedRange != null) {
                index = excludedRange.endOffset
                continue
            }
            if (isEscaped(source, index)) {
                index += 1
                continue
            }

            val image = source[index] == '!' && index + 1 < source.length && source[index + 1] == '['
            val bracketStart = when {
                image -> index + 1
                source[index] == '[' -> index
                else -> {
                    index += 1
                    continue
                }
            }
            val sourceStart = index
            val labelEnd = findClosingBracket(source, bracketStart + 1, excluded) ?: run {
                index += 1
                continue
            }
            val label = source.substring(bracketStart + 1, labelEnd)
            val parsed = parseTarget(source, labelEnd + 1, label, definitions.targets, excluded)
            if (parsed == null) {
                index = labelEnd + 1
                continue
            }

            val target = parsed.target
            val kind = when {
                image && isPotentialDocumentRelativeImage(target) -> NativeHostResourceKind.LOCAL_IMAGE
                !image && label.isNotEmpty() && ExternalNavigationPolicy.validateHttpUrl(target) != null -> NativeHostResourceKind.EXTERNAL_LINK
                else -> null
            }
            if (kind != null) {
                output += NativeHostResourceProjection(
                    kind = kind,
                    sourceRange = ProjectionRange(sourceStart, parsed.syntaxEnd),
                    activationRange = if (kind == NativeHostResourceKind.EXTERNAL_LINK) {
                        ProjectionRange(bracketStart + 1, labelEnd)
                    } else {
                        null
                    },
                    target = target,
                )
            }
            index = parsed.syntaxEnd
        }

        return output.sortedWith(
            compareBy<NativeHostResourceProjection> { it.sourceRange.startOffset }
                .thenBy { it.sourceRange.endOffset }
                .thenBy { it.kind.ordinal }
        )
    }

    private fun collectDefinitions(
        source: String,
        parserExcluded: List<ProjectionRange>,
    ): Definitions {
        val targets = LinkedHashMap<String, String>()
        val ranges = mutableListOf<ProjectionRange>()
        definitionPattern.findAll(source).forEach { match ->
            if (parserExcluded.any { it.intersects(match.range.first, match.range.last + 1) }) return@forEach
            val label = normalizeLabel(match.groupValues[1])
            if (label.isEmpty()) return@forEach
            val target = match.groupValues[2].ifEmpty { match.groupValues[3] }
            if (target.isEmpty()) return@forEach
            targets.putIfAbsent(label, target)
            if (match.range.last >= match.range.first) {
                ranges += ProjectionRange(match.range.first, match.range.last + 1)
            }
        }
        return Definitions(targets, ranges)
    }

    private fun parseTarget(
        source: String,
        offset: Int,
        label: String,
        definitions: Map<String, String>,
        excluded: List<ProjectionRange>,
    ): ParsedTarget? {
        if (offset >= source.length || excluded.any { it.covers(offset) }) {
            val shortcut = definitions[normalizeLabel(label)] ?: return null
            return ParsedTarget(shortcut, offset)
        }
        return when (source[offset]) {
            '(' -> parseInlineTarget(source, offset, excluded)
            '[' -> parseReferenceTarget(source, offset, label, definitions, excluded)
            else -> definitions[normalizeLabel(label)]?.let { ParsedTarget(it, offset) }
        }
    }

    private fun parseInlineTarget(
        source: String,
        openParen: Int,
        excluded: List<ProjectionRange>,
    ): ParsedTarget? {
        var index = openParen + 1
        while (index < source.length && source[index] in INLINE_SPACE) index += 1
        if (index >= source.length || source[index] == '\n' || excluded.any { it.covers(index) }) return null

        val target: String
        if (source[index] == '<') {
            val start = ++index
            while (index < source.length && source[index] != '>' && source[index] != '\n') {
                if (excluded.any { it.covers(index) }) return null
                index += 1
            }
            if (index >= source.length || source[index] != '>') return null
            target = source.substring(start, index)
            index += 1
        } else {
            val start = index
            var nested = 0
            while (index < source.length) {
                if (excluded.any { it.covers(index) }) return null
                val ch = source[index]
                if (ch == '\n') return null
                if (ch == '\\' && index + 1 < source.length) {
                    index += 2
                    continue
                }
                if (ch == '(') nested += 1
                if (ch == ')') {
                    if (nested == 0) break
                    nested -= 1
                }
                if (nested == 0 && ch in INLINE_SPACE) break
                index += 1
            }
            if (index == start) return null
            target = source.substring(start, index)
        }

        while (index < source.length && source[index] in INLINE_SPACE) index += 1
        if (index >= source.length) return null
        if (source[index] != ')') {
            index = skipOptionalTitle(source, index) ?: return null
            while (index < source.length && source[index] in INLINE_SPACE) index += 1
            if (index >= source.length || source[index] != ')') return null
        }
        return ParsedTarget(target, index + 1)
    }

    private fun skipOptionalTitle(source: String, start: Int): Int? {
        if (start >= source.length) return null
        val opener = source[start]
        val closer = when (opener) {
            '"' -> '"'
            '\'' -> '\''
            '(' -> ')'
            else -> return null
        }
        var index = start + 1
        while (index < source.length && source[index] != '\n') {
            if (source[index] == '\\' && index + 1 < source.length) {
                index += 2
                continue
            }
            if (source[index] == closer) return index + 1
            index += 1
        }
        return null
    }

    private fun parseReferenceTarget(
        source: String,
        openBracket: Int,
        fallbackLabel: String,
        definitions: Map<String, String>,
        excluded: List<ProjectionRange>,
    ): ParsedTarget? {
        val close = findClosingBracket(source, openBracket + 1, excluded) ?: return null
        val explicit = source.substring(openBracket + 1, close)
        val label = explicit.ifEmpty { fallbackLabel }
        val target = definitions[normalizeLabel(label)] ?: return null
        return ParsedTarget(target, close + 1)
    }

    private fun findClosingBracket(
        source: String,
        start: Int,
        excluded: List<ProjectionRange>,
    ): Int? {
        var depth = 0
        var index = start
        while (index < source.length) {
            if (excluded.any { it.covers(index) }) return null
            val ch = source[index]
            if (ch == '\n' && depth == 0) return null
            if (ch == '\\' && index + 1 < source.length) {
                index += 2
                continue
            }
            when (ch) {
                '[' -> depth += 1
                ']' -> if (depth == 0) return index else depth -= 1
            }
            index += 1
        }
        return null
    }

    private fun normalizeLabel(raw: String): String =
        labelWhitespace.replace(raw.trim(), " ").lowercase(Locale.ROOT)

    private fun isPotentialDocumentRelativeImage(target: String): Boolean {
        if (target.isBlank() || target.length > MAX_TARGET_LENGTH) return false
        if (target.any(Character::isISOControl)) return false
        if (target.startsWith('/') || target.startsWith('\\') || target.startsWith("//") || target.startsWith("\\\\")) return false
        if (schemePrefix.containsMatchIn(target)) return false
        return true
    }

    private fun ProjectionRange.covers(offset: Int): Boolean = offset >= startOffset && offset < endOffset

    private fun isEscaped(source: String, offset: Int): Boolean {
        var slashes = 0
        var index = offset - 1
        while (index >= 0 && source[index] == '\\') {
            slashes += 1
            index -= 1
        }
        return slashes % 2 == 1
    }

    private data class Definitions(
        val targets: Map<String, String>,
        val ranges: List<ProjectionRange>,
    )

    private data class ParsedTarget(
        val target: String,
        val syntaxEnd: Int,
    )

    private const val MAX_TARGET_LENGTH = 4096
    private val INLINE_SPACE = setOf(' ', '\t')
}
