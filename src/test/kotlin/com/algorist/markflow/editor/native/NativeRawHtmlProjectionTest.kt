package com.algorist.markflow.editor.native

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class NativeRawHtmlProjectionTest : BasePlatformTestCase() {
    fun testSafeInlineAndBlockHtmlUseExactParserBackedSourceRanges() {
        val source = """# Raw HTML

Inline <span data-kind="safe">HTML content</span> remains source text.

<div class="callout">
<p>Block HTML content remains source text too.</p>
</div>
"""
        val projections = projections(source)

        assertEquals(2, projections.size)
        val inline = projections.single { it.kind == NativeRawHtmlProjectionKind.INLINE }
        val block = projections.single { it.kind == NativeRawHtmlProjectionKind.BLOCK }
        assertEquals("<span data-kind=\"safe\">HTML content</span>", inline.source)
        assertEquals(inline.source, source.substring(inline.sourceRange.startOffset, inline.sourceRange.endOffset))
        assertFalse(inline.block)
        assertEquals(
            "<div class=\"callout\">\n<p>Block HTML content remains source text too.</p>\n</div>",
            block.source.trimEnd(),
        )
        assertEquals(block.source, source.substring(block.sourceRange.startOffset, block.sourceRange.endOffset))
        assertTrue(block.block)
    }

    fun testNestedInlineHtmlProducesOneOutermostNonOverlappingFragment() {
        val source = "before <span><strong>safe</strong></span> after\n"
        val projections = projections(source)

        assertEquals(1, projections.size)
        assertEquals("<span><strong>safe</strong></span>", projections.single().source)
    }

    fun testUnmatchedAndCrossedInlineTagsRemainExactSourceFallback() {
        val unmatched = "before <span>unclosed after\n"
        val crossed = "before <span><strong>x</span></strong> after\n"

        assertTrue(projections(unmatched).isEmpty())
        assertTrue(projections(crossed).isEmpty())
    }

    fun testHostileHtmlIsStillParserSelectedForSeparateSanitizerDecision() {
        val source = """# Hostile

<script>window.markflowHostile = true;</script>

<img src="image.png" onerror="alert('active content')">
"""
        val projections = projections(source)

        assertTrue(projections.isNotEmpty())
        assertTrue(projections.any { it.source.contains("script", ignoreCase = true) })
        assertTrue(projections.any { it.source.contains("img", ignoreCase = true) })
    }

    fun testDegradedBasePlanProducesNoRawHtmlFragments() {
        val source = "<span>safe</span>"
        val degraded = NativeProjectionPlan(
            identity = ProjectionSourceIdentity(1L, source, 1L),
            projections = emptyList(),
            status = ProjectionPlanStatus.DEGRADED_TO_SOURCE,
            failureClass = "synthetic",
        )

        assertTrue(NativeRawHtmlProjectionPlanner.plan(degraded).isEmpty())
    }

    private fun projections(source: String): List<NativeRawHtmlProjection> {
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
        return NativeRawHtmlProjectionPlanner.plan(base)
    }
}
