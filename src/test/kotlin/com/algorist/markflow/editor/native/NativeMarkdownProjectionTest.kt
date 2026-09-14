package com.algorist.markflow.editor.native

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class NativeMarkdownProjectionTest : BasePlatformTestCase() {
    fun testSnapshotCapturePreservesExactDocumentIdentity() {
        myFixture.configureByText("snapshot.md", "# Heading\n\nBody\n")
        val document = myFixture.editor.document

        val snapshot = ProjectionSnapshot.capture(document, configGeneration = 9L)

        assertEquals(document.text, snapshot.source)
        assertEquals(document.modificationStamp, snapshot.identity.modificationStamp)
        assertEquals(9L, snapshot.identity.configGeneration)
    }

    fun testProjectionRangeSeparatesParserContainmentFromPresentationBoundaryTouch() {
        val range = ProjectionRange(3, 7)

        assertTrue(range.contains(3))
        assertTrue(range.contains(6))
        assertFalse(range.contains(2))
        assertFalse(range.contains(7))
        assertFalse(range.contains(8))

        assertTrue(range.touches(3))
        assertTrue(range.touches(6))
        assertTrue(range.touches(7))
        assertFalse(range.touches(2))
        assertFalse(range.touches(8))
    }

    fun testRepresentativePlanUsesOnlyExactParserRanges() {
        val source = """# ATX Heading

Setext heading
==============

Paragraph with *emphasis*, **strong**, `code`, and [a link](https://example.com).

- unordered item
- second item

1. ordered item
2. second ordered item

> quoted paragraph

```kotlin
val fenced = 1
```

    val indented = 2

---

| Name | Value |
| --- | ---: |
| alpha | 1 |
| beta | 2 |
"""
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(
                ProjectionSourceIdentity(
                    modificationStamp = 7L,
                    source = source,
                    configGeneration = 3L,
                )
            )
        )

        assertEquals(ProjectionPlanStatus.READY, plan.status)
        assertEquals(source, plan.identity.source)
        assertTrue(plan.projections.isNotEmpty())
        val kinds = plan.projections.map { it.kind }.toSet()
        assertTrue(
            "missing representative kinds: ${NativeProjectionKind.entries.toSet() - kinds}",
            kinds.containsAll(NativeProjectionKind.entries.toSet()),
        )

        plan.projections.forEach { projection ->
            assertTrue(projection.sourceRange.isInside(source))
            projection.syntaxRanges.forEach { syntax ->
                assertTrue(syntax.isInside(source))
                assertTrue(syntax.startOffset >= projection.sourceRange.startOffset)
                assertTrue(syntax.endOffset <= projection.sourceRange.endOffset)
            }
            projection.contentRanges.forEach { content ->
                assertTrue(content.isInside(source))
                assertTrue(content.startOffset >= projection.sourceRange.startOffset)
                assertTrue(content.endOffset <= projection.sourceRange.endOffset)
            }
        }

        val atxHeading = plan.projections.first {
            it.kind == NativeProjectionKind.HEADING &&
                source.substring(it.sourceRange.startOffset, it.sourceRange.endOffset).startsWith("# ATX")
        }
        assertEquals("#", source.substring(atxHeading.syntaxRanges.single().startOffset, atxHeading.syntaxRanges.single().endOffset))

        val setextHeading = plan.projections.first {
            it.kind == NativeProjectionKind.HEADING &&
                source.substring(it.sourceRange.startOffset, it.sourceRange.endOffset).startsWith("Setext")
        }
        assertTrue(setextHeading.syntaxRanges.isNotEmpty())
        assertTrue(
            setextHeading.syntaxRanges.any { range ->
                source.substring(range.startOffset, range.endOffset).all { it == '=' }
            }
        )

        val link = plan.projections.single {
            it.kind == NativeProjectionKind.LINK &&
                source.substring(it.sourceRange.startOffset, it.sourceRange.endOffset).startsWith("[a link]")
        }
        assertEquals("a link", source.substring(link.contentRanges.single().startOffset, link.contentRanges.single().endOffset))
        assertEquals(
            listOf("[", "](https://example.com)"),
            link.syntaxRanges.map { range -> source.substring(range.startOffset, range.endOffset) },
        )

        val listMarkers = plan.projections
            .filter { it.kind == NativeProjectionKind.LIST_ITEM }
            .flatMap { it.syntaxRanges }
            .map { source.substring(it.startOffset, it.endOffset) }
        assertTrue(listMarkers.contains("-"))
        assertTrue(listMarkers.contains("1."))

        val quote = plan.projections.single { it.kind == NativeProjectionKind.BLOCK_QUOTE }
        assertTrue(quote.syntaxRanges.any { source.substring(it.startOffset, it.endOffset) == ">" })

        val thematic = plan.projections.single { it.kind == NativeProjectionKind.THEMATIC_BREAK }
        assertEquals(listOf(thematic.sourceRange), thematic.syntaxRanges)

        val table = plan.projections.single { it.kind == NativeProjectionKind.TABLE }
        assertEquals(
            "| Name | Value |\n| --- | ---: |\n| alpha | 1 |\n| beta | 2 |",
            source.substring(table.sourceRange.startOffset, table.sourceRange.endOffset).trimEnd(),
        )
        assertTrue(table.sourceRange.contains(table.sourceRange.startOffset))
        assertTrue(table.sourceRange.contains(table.sourceRange.endOffset - 1))
        assertFalse(table.sourceRange.contains(table.sourceRange.endOffset))

        val header = plan.projections.single { it.kind == NativeProjectionKind.TABLE_HEADER }
        assertEquals(2, header.contentRanges.size)
        assertEquals(
            listOf("Name", "Value"),
            header.contentRanges.map { range -> source.substring(range.startOffset, range.endOffset).trim() },
        )
        val rows = plan.projections.filter { it.kind == NativeProjectionKind.TABLE_ROW }
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.contentRanges.size == 2 })
        assertEquals(
            listOf(listOf("alpha", "1"), listOf("beta", "2")),
            rows.map { row ->
                row.contentRanges.map { range -> source.substring(range.startOffset, range.endOffset).trim() }
            },
        )
    }

    fun testInlineReferenceAndShortcutLinksExposeOnlyParserProvenVisibleLabels() {
        val source = """[inline label](https://example.com "title")

[reference label][target]

[shortcut]

[target]: https://example.com/target
[shortcut]: https://example.com/shortcut
"""
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(ProjectionSourceIdentity(1L, source, 0L))
        )
        assertEquals(ProjectionPlanStatus.READY, plan.status)

        val links = plan.projections.filter { it.kind == NativeProjectionKind.LINK }
        assertEquals(3, links.size)
        assertEquals(
            listOf("inline label", "reference label", "shortcut"),
            links.map { link ->
                val range = link.contentRanges.single()
                source.substring(range.startOffset, range.endOffset)
            },
        )
        links.forEach { link ->
            assertTrue(link.syntaxRanges.isNotEmpty())
            link.syntaxRanges.forEach { range ->
                assertTrue(range.isInside(source))
                assertTrue(range.startOffset >= link.sourceRange.startOffset)
                assertTrue(range.endOffset <= link.sourceRange.endOffset)
            }
        }
    }

    fun testImageInlineLinkSubtreeRemainsOwnedByHostResourceProjection() {
        val source = "![alt text](images/example.png)\n"
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(ProjectionSourceIdentity(1L, source, 0L))
        )

        assertEquals(ProjectionPlanStatus.READY, plan.status)
        assertTrue(plan.projections.none { it.kind == NativeProjectionKind.LINK })
        assertEquals(source, plan.identity.source)
    }

    fun testUnsupportedRawHtmlNeverBecomesAProjectedConstruct() {
        val source = "# safe\n\n<script>alert('x')</script>\n\n**unterminated\n"
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(
                ProjectionSourceIdentity(
                    modificationStamp = 1L,
                    source = source,
                    configGeneration = 0L,
                )
            )
        )

        plan.projections.forEach { projection ->
            assertTrue(projection.sourceRange.isInside(source))
            val projectedSource = source.substring(projection.sourceRange.startOffset, projection.sourceRange.endOffset)
            assertFalse(projectedSource.contains("<script>", ignoreCase = true))
        }
    }

    fun testSourceGenerationIdentityIncludesStampExactSourceAndConfig() {
        val original = ProjectionSourceIdentity(11L, "same", 2L)

        assertFalse(original == ProjectionSourceIdentity(12L, "same", 2L))
        assertFalse(original == ProjectionSourceIdentity(11L, "changed", 2L))
        assertFalse(original == ProjectionSourceIdentity(11L, "same", 3L))
        assertEquals(original, ProjectionSourceIdentity(11L, "same", 2L))
    }
}
