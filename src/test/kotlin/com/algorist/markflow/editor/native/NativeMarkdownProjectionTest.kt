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

        val table = plan.projections.single { it.kind == NativeProjectionKind.TABLE }
        assertEquals(
            "| Name | Value |\n| --- | ---: |\n| alpha | 1 |\n| beta | 2 |",
            source.substring(table.sourceRange.startOffset, table.sourceRange.endOffset).trimEnd(),
        )
        assertTrue(plan.projections.any { it.kind == NativeProjectionKind.TABLE_HEADER })
        assertTrue(plan.projections.count { it.kind == NativeProjectionKind.TABLE_ROW } >= 2)
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
