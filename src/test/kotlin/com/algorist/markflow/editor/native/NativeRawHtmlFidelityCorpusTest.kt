package com.algorist.markflow.editor.native

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class NativeRawHtmlFidelityCorpusTest : BasePlatformTestCase() {
    fun testSharedSafeRawHtmlFixtureProducesOnlySanitizedSafeFragments() {
        val source = fixture("raw-html-safe.md")
        val plan = basePlan(source)
        val projections = NativeRawHtmlProjectionPlanner.plan(plan)

        assertEquals(2, projections.size)
        assertEquals(source, plan.identity.source)
        projections.forEach { projection ->
            assertEquals(
                projection.source,
                source.substring(projection.sourceRange.startOffset, projection.sourceRange.endOffset),
            )
            val sanitized = NativeRawHtmlSanitizer.sanitize(projection.source)
            assertTrue("safe fixture fragment unexpectedly blocked: ${projection.kind}", sanitized is NativeRawHtmlSanitizationResult.Safe)
        }
    }

    fun testSharedHostileRawHtmlFixtureFailsClosedForEveryProjectedFragment() {
        val source = fixture("raw-html-hostile.md")
        val plan = basePlan(source)
        val projections = NativeRawHtmlProjectionPlanner.plan(plan)

        assertTrue("hostile fixture produced no parser-owned raw HTML", projections.isNotEmpty())
        val projected = projections.joinToString("\n") { it.source }.lowercase()
        listOf("script", "img", "style=", "href=").forEach { marker ->
            assertTrue("hostile fixture projection missed $marker", projected.contains(marker))
        }
        projections.forEach { projection ->
            val sanitized = NativeRawHtmlSanitizer.sanitize(projection.source)
            assertTrue(
                "hostile fixture fragment was not fail-closed: ${projection.source}",
                sanitized is NativeRawHtmlSanitizationResult.Blocked,
            )
        }
        assertEquals(source, plan.identity.source)
    }

    private fun basePlan(source: String): NativeProjectionPlan =
        NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(
                ProjectionSourceIdentity(
                    modificationStamp = 17L,
                    source = source,
                    configGeneration = 4L,
                )
            )
        ).also { assertEquals(ProjectionPlanStatus.READY, it.status) }

    private fun fixture(name: String): String = Files.readString(
        Path.of("fixtures", "markdown-fidelity", "cases", name).toAbsolutePath().normalize()
    )
}
