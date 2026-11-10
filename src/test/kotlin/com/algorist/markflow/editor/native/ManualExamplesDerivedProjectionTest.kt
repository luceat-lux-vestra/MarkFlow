package com.algorist.markflow.editor.native

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class ManualExamplesDerivedProjectionTest : BasePlatformTestCase() {
    fun testMarkdownCoreExampleRemainsAReadySourceStableProjectionInput() {
        val source = example("markdown-core.md")
        val plan = plan(source)

        assertEquals(ProjectionPlanStatus.READY, plan.status)
        assertEquals(source, plan.identity.source)
        val kinds = plan.projections.map { it.kind }.toSet()
        setOf(
            NativeProjectionKind.PARAGRAPH,
            NativeProjectionKind.HEADING,
            NativeProjectionKind.EMPHASIS,
            NativeProjectionKind.STRONG,
            NativeProjectionKind.STRIKETHROUGH,
            NativeProjectionKind.LINK,
            NativeProjectionKind.UNORDERED_LIST,
            NativeProjectionKind.ORDERED_LIST,
            NativeProjectionKind.LIST_ITEM,
            NativeProjectionKind.BLOCK_QUOTE,
            NativeProjectionKind.INLINE_CODE,
            NativeProjectionKind.CODE_FENCE,
            NativeProjectionKind.CODE_BLOCK,
            NativeProjectionKind.THEMATIC_BREAK,
            NativeProjectionKind.TABLE,
        ).forEach { kind ->
            assertTrue("markdown manual example lost production projection kind: $kind", kind in kinds)
        }
        assertEquals(
            setOf(1, 2, 3, 4, 5, 6),
            plan.projections.filter { it.kind == NativeProjectionKind.HEADING }
                .mapNotNull { it.headingLevel }
                .toSet(),
        )
        listOf(
            "# ATX H1",
            "Setext H1",
            "~~strikethrough~~",
            "[JetBrains docs][jb-docs]",
            "~~~~text",
            "escaped \\| pipe",
            "- [x] Completed task",
            "Footnote-like syntax",
        ).forEach { marker ->
            assertTrue("markdown manual example lost representative marker: $marker", source.contains(marker))
        }
    }

    fun testKatexManualExampleExposesInlineDisplayAndPostFailureRecoveryInputs() {
        val source = example("katex-math.md")
        val derived = NativeDerivedProjectionPlanner.plan(plan(source))

        assertTrue(derived.any { it.kind == NativeDerivedProjectionKind.KATEX_INLINE })
        assertTrue(derived.any { it.kind == NativeDerivedProjectionKind.KATEX_DISPLAY })
        assertTrue(
            "valid formula after intentional failures must remain discoverable",
            derived.any {
                it.kind == NativeDerivedProjectionKind.KATEX_INLINE &&
                    it.source.contains("\\sqrt{16} = 4")
            },
        )
        assertTrue(source.contains("Malformed inline math: \$\\frac{1}{\$."))
        assertTrue(source.contains("\\begin{bmatrix}"))
        assertTrue(source.contains("\\begin{cases}"))
    }

    fun testMermaidFailureIsolationExampleProducesThreeIndependentFencedFragments() {
        val source = example("derived-mermaid-failure-isolation.md")
        val mermaid = NativeDerivedProjectionPlanner.plan(plan(source))
            .filter { it.kind == NativeDerivedProjectionKind.MERMAID }

        assertEquals(3, mermaid.size)
        assertTrue(mermaid[0].source.contains("flowchart LR"))
        assertTrue(mermaid[1].source.contains("this is not a valid Mermaid diagram"))
        assertTrue(mermaid[2].source.contains("sequenceDiagram"))
        mermaid.forEach { projection ->
            assertEquals(
                projection.source,
                source.substring(projection.contentRange.startOffset, projection.contentRange.endOffset),
            )
        }
    }

    private fun plan(source: String): NativeProjectionPlan =
        NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(
                ProjectionSourceIdentity(
                    modificationStamp = 31L,
                    source = source,
                    configGeneration = 7L,
                )
            )
        )

    private fun example(name: String): String = Files.readString(
        Path.of("examples", name).toAbsolutePath().normalize()
    )
}
