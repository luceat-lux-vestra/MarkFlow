package com.algorist.markflow.editor.native

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class NativeMarkdownFidelityCorpusTest : BasePlatformTestCase() {
    fun testOrdinaryMarkdownContractFixturesRemainExactProjectionInputs() {
        ORDINARY_FIXTURES.forEachIndexed { index, fixture ->
            val source = readFixture(fixture.id)
            val plan = NativeMarkdownProjectionPlanner.plan(
                ProjectionSnapshot(
                    ProjectionSourceIdentity(
                        modificationStamp = index.toLong() + 1,
                        source = source,
                        configGeneration = 0L,
                    )
                )
            )

            assertEquals("${fixture.id}: parser degraded", ProjectionPlanStatus.READY, plan.status)
            assertEquals("${fixture.id}: exact source identity changed", source, plan.identity.source)
            assertTrue(
                "${fixture.id}: missing expected projection kinds ${fixture.requiredKinds - plan.projections.map { it.kind }.toSet()}",
                plan.projections.map { it.kind }.toSet().containsAll(fixture.requiredKinds),
            )
            plan.projections.forEach { projection ->
                assertTrue("${fixture.id}: source range escaped fixture", projection.sourceRange.isInside(source))
                assertTrue(projection.sourceRange.startOffset < projection.sourceRange.endOffset)
                projection.syntaxRanges.forEach { range ->
                    assertTrue("${fixture.id}: syntax range escaped fixture", range.isInside(source))
                    assertTrue(range.startOffset >= projection.sourceRange.startOffset)
                    assertTrue(range.endOffset <= projection.sourceRange.endOffset)
                }
                projection.contentRanges.forEach { range ->
                    assertTrue("${fixture.id}: content range escaped fixture", range.isInside(source))
                    assertTrue(range.startOffset >= projection.sourceRange.startOffset)
                    assertTrue(range.endOffset <= projection.sourceRange.endOffset)
                }
            }
        }
    }

    fun testLineEndingAndTrailingNewlineFixturesRemainByteExactInProjectionIdentity() {
        val lf = readFixture("line-endings-lf")
        val crlf = readFixture("line-endings-crlf")
        val noTrailing = readFixture("line-endings-no-trailing-newline")

        assertTrue(lf.contains('\n'))
        assertFalse(lf.contains("\r\n"))
        assertTrue(lf.endsWith("\n"))
        assertTrue(crlf.contains("\r\n"))
        assertTrue(crlf.endsWith("\r\n"))
        assertFalse(noTrailing.endsWith("\n"))
        assertFalse(noTrailing.endsWith("\r"))

        listOf(lf, crlf, noTrailing).forEachIndexed { index, source ->
            val plan = NativeMarkdownProjectionPlanner.plan(
                ProjectionSnapshot(ProjectionSourceIdentity(index.toLong(), source, 0L))
            )
            assertEquals(ProjectionPlanStatus.READY, plan.status)
            assertEquals(source, plan.identity.source)
        }
    }

    fun testTableLexicalFixtureUsesOnlyParserProvenCellRanges() {
        val source = readFixture("table-lexical-variants")
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(ProjectionSourceIdentity(1L, source, 0L))
        )

        assertEquals(ProjectionPlanStatus.READY, plan.status)
        val models = NativeTableProjectionPlanner.plan(plan)
        assertTrue("table fidelity fixture produced no native table models", models.isNotEmpty())
        models.forEach { model ->
            assertTrue(model.sourceRange.isInside(source))
            assertTrue(model.rows.isNotEmpty())
            model.rows.forEach { row ->
                assertTrue(row.sourceRange.isInside(source))
                assertTrue(row.cells.isNotEmpty())
            }
        }
        assertEquals(source, plan.identity.source)
    }

    fun testRepeatedSimilarBlocksKeepDistinctParserRangesWithoutSourceMatching() {
        val source = readFixture("repeated-similar-blocks")
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(ProjectionSourceIdentity(41L, source, 7L))
        )

        assertEquals(ProjectionPlanStatus.READY, plan.status)
        assertEquals(41L, plan.identity.modificationStamp)
        assertEquals(7L, plan.identity.configGeneration)
        assertEquals(source, plan.identity.source)

        val repeatedHeadings = plan.projections.filter { projection ->
            projection.kind == NativeProjectionKind.HEADING &&
                source.substring(projection.sourceRange.startOffset, projection.sourceRange.endOffset)
                    .startsWith("## Same heading")
        }
        assertEquals(3, repeatedHeadings.size)
        assertEquals(3, repeatedHeadings.map { it.sourceRange.startOffset }.toSet().size)
        repeatedHeadings.forEach { heading ->
            assertTrue(heading.sourceRange.isInside(source))
            assertTrue(heading.syntaxRanges.isNotEmpty())
        }
    }

    private fun readFixture(id: String): String = Files.readString(
        Path.of("fixtures/markdown-fidelity/cases/$id.md"),
        StandardCharsets.UTF_8,
    )

    private data class FixtureExpectation(
        val id: String,
        val requiredKinds: Set<NativeProjectionKind> = emptySet(),
    )

    private companion object {
        val ORDINARY_FIXTURES = listOf(
            FixtureExpectation(
                "mixed-list-markers",
                setOf(
                    NativeProjectionKind.UNORDERED_LIST,
                    NativeProjectionKind.ORDERED_LIST,
                    NativeProjectionKind.LIST_ITEM,
                ),
            ),
            FixtureExpectation(
                "headings-and-delimiters",
                setOf(
                    NativeProjectionKind.HEADING,
                    NativeProjectionKind.EMPHASIS,
                    NativeProjectionKind.STRONG,
                ),
            ),
            FixtureExpectation(
                "code-forms-and-fences",
                setOf(NativeProjectionKind.CODE_FENCE, NativeProjectionKind.CODE_BLOCK),
            ),
            FixtureExpectation("thematic-break-variants", setOf(NativeProjectionKind.THEMATIC_BREAK)),
            FixtureExpectation("links-and-references", setOf(NativeProjectionKind.LINK)),
            FixtureExpectation(
                "table-lexical-variants",
                setOf(
                    NativeProjectionKind.TABLE,
                    NativeProjectionKind.TABLE_HEADER,
                    NativeProjectionKind.TABLE_ROW,
                ),
            ),
            FixtureExpectation("whitespace-and-blank-lines", setOf(NativeProjectionKind.PARAGRAPH)),
            FixtureExpectation("line-endings-lf"),
            FixtureExpectation("line-endings-crlf"),
            FixtureExpectation("line-endings-no-trailing-newline"),
            FixtureExpectation("repeated-similar-blocks", setOf(NativeProjectionKind.PARAGRAPH)),
            FixtureExpectation("paste-boundaries"),
        )
    }
}
