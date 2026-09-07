package com.algorist.markflow.editor.native

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProcessCanceledException
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
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

    fun contains(offset: Int): Boolean = offset in startOffset..endOffset
}

internal enum class NativeProjectionKind {
    HEADING,
    EMPHASIS,
    STRONG,
    INLINE_CODE,
    CODE_FENCE,
}

internal data class NativeProjection(
    val kind: NativeProjectionKind,
    val sourceRange: ProjectionRange,
    val syntaxRanges: List<ProjectionRange> = emptyList(),
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
 * Snapshot-only Markdown projection planner for #145.
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
        projectionFor(node, source)?.let(output::add)
        node.children.forEach { child -> collect(child, source, output) }
    }

    private fun projectionFor(node: ASTNode, source: String): NativeProjection? {
        val kind = when (node.type) {
            in headingTypes -> NativeProjectionKind.HEADING
            MarkdownElementTypes.EMPH -> NativeProjectionKind.EMPHASIS
            MarkdownElementTypes.STRONG -> NativeProjectionKind.STRONG
            MarkdownElementTypes.CODE_SPAN -> NativeProjectionKind.INLINE_CODE
            MarkdownElementTypes.CODE_FENCE -> NativeProjectionKind.CODE_FENCE
            else -> return null
        }

        val sourceRange = ProjectionRange(node.startOffset, node.endOffset)
        check(sourceRange.isInside(source)) {
            "parser produced an out-of-bounds projection range"
        }

        val syntaxRanges = if (kind == NativeProjectionKind.HEADING) {
            node.children
                .asSequence()
                .filter { child -> child.type == MarkdownTokenTypes.ATX_HEADER }
                .map { child -> ProjectionRange(child.startOffset, child.endOffset) }
                .filter { range -> range.isInside(source) && range.startOffset >= sourceRange.startOffset && range.endOffset <= sourceRange.endOffset }
                .toList()
        } else {
            emptyList()
        }

        return NativeProjection(
            kind = kind,
            sourceRange = sourceRange,
            syntaxRanges = syntaxRanges,
            block = kind == NativeProjectionKind.HEADING || kind == NativeProjectionKind.CODE_FENCE,
        )
    }

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
}
