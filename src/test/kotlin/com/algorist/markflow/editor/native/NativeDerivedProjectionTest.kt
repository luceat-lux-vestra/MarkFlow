package com.algorist.markflow.editor.native

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class NativeDerivedProjectionTest : BasePlatformTestCase() {
    fun testMermaidFenceUsesExactBodyAndFullSourceRange() {
        val source = """before

```mermaid
graph TD
  A --> B
```

after
"""
        val derived = derived(source)

        val mermaid = derived.single { it.kind == NativeDerivedProjectionKind.MERMAID }
        assertEquals("graph TD\n  A --> B\n", mermaid.source)
        assertEquals("```mermaid\ngraph TD\n  A --> B\n```", source.substring(mermaid.sourceRange.startOffset, mermaid.sourceRange.endOffset))
        assertEquals(mermaid.source, source.substring(mermaid.contentRange.startOffset, mermaid.contentRange.endOffset))
        assertTrue(mermaid.block)
    }

    fun testInlineAndDisplayMathRemainDistinctRendererFragments() {
        val source = "Inline \\(not math\\) and \$x^2 + y^2\$.\n\n\$\$\\frac{a}{b}\$\$\n"
        val derived = derived(source)

        val inline = derived.single { it.kind == NativeDerivedProjectionKind.KATEX_INLINE }
        val display = derived.single { it.kind == NativeDerivedProjectionKind.KATEX_DISPLAY }
        assertEquals("x^2 + y^2", inline.source)
        assertFalse(inline.block)
        assertEquals("\\frac{a}{b}", display.source)
        assertTrue(display.block)
        assertEquals("\$x^2 + y^2\$", source.substring(inline.sourceRange.startOffset, inline.sourceRange.endOffset))
        assertEquals("\$\$\\frac{a}{b}\$\$", source.substring(display.sourceRange.startOffset, display.sourceRange.endOffset))
    }

    fun testEscapedDollarInlineCodeAndFencedCodeAreNotMath() {
        val source = "Escaped \\$not-math and `\$code\$`.\n\n```text\n\$fenced\$\n```\n\nReal \$ok\$.\n"
        val derived = derived(source)

        val math = derived.filter { it.kind == NativeDerivedProjectionKind.KATEX_INLINE }
        assertEquals(1, math.size)
        assertEquals("ok", math.single().source)
        assertFalse(derived.any { it.kind == NativeDerivedProjectionKind.MERMAID })
    }

    fun testUnclosedOrBlankConstructsDegradeToExactSource() {
        val source = "Broken \$math\n\n\$\$   \$\$\n\n```mermaid\n```\n"
        val derived = derived(source)

        assertTrue(derived.isEmpty())
    }

    fun testDegradedBasePlanNeverProducesDerivedFragments() {
        val source = "\$x\$"
        val identity = ProjectionSourceIdentity(1L, source, 2L)
        val degraded = NativeProjectionPlan(
            identity = identity,
            projections = emptyList(),
            status = ProjectionPlanStatus.DEGRADED_TO_SOURCE,
            failureClass = "synthetic",
        )

        assertTrue(NativeDerivedProjectionPlanner.plan(degraded).isEmpty())
    }

    private fun derived(source: String): List<NativeDerivedProjection> {
        val base = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(
                ProjectionSourceIdentity(
                    modificationStamp = 7L,
                    source = source,
                    configGeneration = 3L,
                )
            )
        )
        assertEquals(ProjectionPlanStatus.READY, base.status)
        return NativeDerivedProjectionPlanner.plan(base)
    }
}
