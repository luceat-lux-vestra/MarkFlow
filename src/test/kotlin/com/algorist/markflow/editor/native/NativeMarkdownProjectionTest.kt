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

Paragraph with *emphasis*, **strong**, ~~struck~~, `code`, and [a link](https://example.com).\
Hard-break continuation.

[representative]: https://example.com/reference "Representative"

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
            projection.referenceDefinitionRanges?.let { ranges ->
                listOfNotNull(ranges.label, ranges.destination, ranges.title).forEach { semantic ->
                    assertTrue(semantic.isInside(source))
                    assertTrue(semantic.startOffset >= projection.sourceRange.startOffset)
                    assertTrue(semantic.endOffset <= projection.sourceRange.endOffset)
                }
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

        val strikethrough = plan.projections.single { it.kind == NativeProjectionKind.STRIKETHROUGH }
        assertEquals(
            "~~struck~~",
            source.substring(strikethrough.sourceRange.startOffset, strikethrough.sourceRange.endOffset),
        )
        assertEquals(
            listOf("~~", "~~"),
            strikethrough.syntaxRanges.map { range -> source.substring(range.startOffset, range.endOffset) },
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

    fun testMultilineBlockQuoteExposesEveryParserProvenMarkerRange() {
        val source = "> first line\n> second line\n\nTail\n"
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(ProjectionSourceIdentity(17L, source, 0L))
        )

        assertEquals(ProjectionPlanStatus.READY, plan.status)
        val quote = plan.projections.single { it.kind == NativeProjectionKind.BLOCK_QUOTE }
        assertEquals(
            listOf(">", ">"),
            quote.syntaxRanges.map { range ->
                source.substring(range.startOffset, range.endOffset)
            },
        )
        assertEquals(
            listOf(0, source.indexOf("> second line")),
            quote.syntaxRanges.map(ProjectionRange::startOffset),
        )
        assertTrue(quote.syntaxRanges.all { range -> range.isInside(source) })
        assertEquals(source, plan.identity.source)
    }

    fun testNestedBlockQuoteExposesParserOwnedMarkerDepthAndContainedQuote() {
        val source = "> outer\n> > nested\n> outer again\n\nTail\n"
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(ProjectionSourceIdentity(19L, source, 0L))
        )

        assertEquals(ProjectionPlanStatus.READY, plan.status)
        val quotes = plan.projections.filter { it.kind == NativeProjectionKind.BLOCK_QUOTE }
        assertTrue("nested quote projection missing", quotes.size >= 2)

        val outer = quotes.maxBy { quote -> quote.sourceRange.endOffset - quote.sourceRange.startOffset }
        val contained = quotes.filter { quote ->
            quote !== outer &&
                quote.sourceRange.startOffset >= outer.sourceRange.startOffset &&
                quote.sourceRange.endOffset <= outer.sourceRange.endOffset
        }
        assertTrue("nested BLOCK_QUOTE must remain parser-contained by outer quote", contained.isNotEmpty())

        val markerDepthsByLine = outer.syntaxRanges
            .groupingBy { range ->
                source.substring(0, range.startOffset).count { it == '\n' }
            }
            .eachCount()
        assertEquals(mapOf(0 to 1, 1 to 2, 2 to 1), markerDepthsByLine)
        assertTrue(
            outer.syntaxRanges.all { range ->
                range.isInside(source) && source.substring(range.startOffset, range.endOffset) == ">"
            }
        )
        assertEquals(source, plan.identity.source)
    }

    fun testQuoteContainedOrdinaryListKeepsQuoteAndListParserOwnershipSeparate() {
        val source = "> outer\n> > - nested item\n> > - another item\n> outer again\n\nTail\n"
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(ProjectionSourceIdentity(23L, source, 0L))
        )

        assertEquals(ProjectionPlanStatus.READY, plan.status)
        val quotes = plan.projections.filter { it.kind == NativeProjectionKind.BLOCK_QUOTE }
        val outer = quotes.maxBy { quote -> quote.sourceRange.endOffset - quote.sourceRange.startOffset }
        val nested = quotes
            .filter { quote ->
                quote !== outer &&
                    quote.sourceRange.startOffset >= outer.sourceRange.startOffset &&
                    quote.sourceRange.endOffset <= outer.sourceRange.endOffset
            }
            .maxBy { quote -> quote.sourceRange.endOffset - quote.sourceRange.startOffset }

        val lists = plan.projections.filter { it.kind == NativeProjectionKind.UNORDERED_LIST }
        val quotedList = lists.single { list ->
            list.sourceRange.startOffset >= nested.sourceRange.startOffset &&
                list.sourceRange.endOffset <= nested.sourceRange.endOffset
        }
        val items = plan.projections
            .filter { projection ->
                projection.kind == NativeProjectionKind.LIST_ITEM &&
                    projection.sourceRange.startOffset >= quotedList.sourceRange.startOffset &&
                    projection.sourceRange.endOffset <= quotedList.sourceRange.endOffset
            }
            .sortedBy { it.sourceRange.startOffset }

        assertEquals(2, items.size)
        assertEquals(
            listOf("-", "-"),
            items.map { item ->
                val marker = item.syntaxRanges.single()
                source.substring(marker.startOffset, marker.endOffset)
            },
        )
        assertEquals(
            listOf("nested item", "another item"),
            NativeListProjectionPlanner.plan(plan).single().rows.map(NativeListRow::text),
        )
        assertEquals(source, plan.identity.source)
    }

    fun testManualBlockQuoteFixtureExposesMarkerOnlyRowsWithoutSynthesizedContent() {
        val source =
            "> First-level quote.\n" +
                ">\n" +
                "> > Nested quote.\n" +
                "> >\n" +
                "> > - Nested quoted list item\n" +
                "> > - Another quoted item\n" +
                ">\n" +
                "> Back to first level.\n"

        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(ProjectionSourceIdentity(29L, source, 0L))
        )

        assertEquals(ProjectionPlanStatus.READY, plan.status)
        val quotes = plan.projections.filter { it.kind == NativeProjectionKind.BLOCK_QUOTE }
        val outer = quotes.maxBy { quote -> quote.sourceRange.endOffset - quote.sourceRange.startOffset }
        val markerCountsByLine = outer.syntaxRanges
            .groupingBy { range -> source.substring(0, range.startOffset).count { it == '\n' } }
            .eachCount()
        assertEquals(
            mapOf(0 to 1, 1 to 1, 2 to 2, 3 to 2, 4 to 2, 5 to 2, 6 to 1, 7 to 1),
            markerCountsByLine,
        )

        val markerOnlyLines = setOf(1, 3, 6)
        markerOnlyLines.forEach { lineIndex ->
            val lineStart = source.lineSequence().take(lineIndex).sumOf { it.length + 1 }
            val lineEnd = source.indexOf('\n', lineStart).let { if (it < 0) source.length else it }
            val markers = outer.syntaxRanges.filter { it.startOffset >= lineStart && it.endOffset <= lineEnd }
            assertTrue(markers.isNotEmpty())
            assertTrue(markers.all { range -> source.substring(range.startOffset, range.endOffset) == ">" })
            val afterLastMarker = markers.maxOf(ProjectionRange::endOffset)
            assertTrue(source.substring(afterLastMarker, lineEnd).all { it == ' ' || it == '\t' })
        }
        assertEquals(source, plan.identity.source)
    }

    fun testHeadingLevelsAndVisibleRangesRemainParserBounded() {
        val source = """# H1
## H2 ##
### H3
#### H4
##### H5
###### H6

Setext one
==========

Setext two
----------
"""
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(ProjectionSourceIdentity(1L, source, 0L))
        )

        assertEquals(ProjectionPlanStatus.READY, plan.status)
        val headings = plan.projections.filter { it.kind == NativeProjectionKind.HEADING }
        assertEquals(8, headings.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 1, 2), headings.map { it.headingLevel })
        assertEquals(
            listOf("H1", "H2", "H3", "H4", "H5", "H6", "Setext one", "Setext two"),
            headings.map { heading ->
                val visible = heading.contentRanges.single()
                assertTrue(visible.startOffset >= heading.sourceRange.startOffset)
                assertTrue(visible.endOffset <= heading.sourceRange.endOffset)
                source.substring(visible.startOffset, visible.endOffset)
            },
        )

        val h2 = headings[1]
        assertEquals(
            listOf("##", "##"),
            h2.syntaxRanges.map { range -> source.substring(range.startOffset, range.endOffset) },
        )
        assertEquals(source, plan.identity.source)
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

    fun testReferenceDefinitionsExposeOnlyParserOwnedSemanticRanges() {
        val source = """First [consumer][shared], second [consumer two][shared], [collapsed][], and [shortcut].

[shared]: https://example.com/shared "Shared title"
[collapsed]: /collapsed
[shortcut]: /shortcut
[unused]: /unused
[multi]:
      /multi
           'Multi title'
"""
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(ProjectionSourceIdentity(7L, source, 0L))
        )

        assertEquals(ProjectionPlanStatus.READY, plan.status)
        val definitions = plan.projections.filter {
            it.kind == NativeProjectionKind.REFERENCE_DEFINITION
        }
        assertEquals(5, definitions.size)

        fun raw(range: ProjectionRange): String =
            source.substring(range.startOffset, range.endOffset)

        val byLabel = definitions.associateBy { definition ->
            val ranges = checkNotNull(definition.referenceDefinitionRanges)
            raw(ranges.label)
        }
        assertEquals(
            setOf("[shared]", "[collapsed]", "[shortcut]", "[unused]", "[multi]"),
            byLabel.keys,
        )

        val shared = checkNotNull(byLabel["[shared]"]?.referenceDefinitionRanges)
        assertEquals("https://example.com/shared", raw(shared.destination))
        assertEquals("\"Shared title\"", raw(checkNotNull(shared.title)))

        val unused = checkNotNull(byLabel["[unused]"]?.referenceDefinitionRanges)
        assertEquals("/unused", raw(unused.destination))
        assertNull(unused.title)

        val multilineProjection = checkNotNull(byLabel["[multi]"])
        val multiline = checkNotNull(multilineProjection.referenceDefinitionRanges)
        assertEquals("/multi", raw(multiline.destination))
        assertEquals("'Multi title'", raw(checkNotNull(multiline.title)))
        assertTrue(
            source.substring(
                multilineProjection.sourceRange.startOffset,
                multilineProjection.sourceRange.endOffset,
            ).contains("\n")
        )

        val sharedDefinition = checkNotNull(byLabel["[shared]"])
        assertEquals(
            1,
            definitions.count {
                it.sourceRange == sharedDefinition.sourceRange
            },
        )
        assertEquals(
            2,
            plan.projections.count {
                it.kind == NativeProjectionKind.LINK &&
                    source.substring(it.sourceRange.startOffset, it.sourceRange.endOffset)
                        .endsWith("[shared]")
            },
        )

        definitions.forEach { definition ->
            assertTrue(definition.block)
            val ranges = checkNotNull(definition.referenceDefinitionRanges)
            listOfNotNull(ranges.label, ranges.destination, ranges.title).forEach { range ->
                assertTrue(range.isInside(source))
                assertTrue(range.startOffset >= definition.sourceRange.startOffset)
                assertTrue(range.endOffset <= definition.sourceRange.endOffset)
            }
        }
        assertEquals(source, plan.identity.source)
    }

    fun testMalformedReferenceDefinitionFailsClosedToExactSource() {
        val source = """[broken] https://example.com/no-colon

Ordinary paragraph remains editable.
"""
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(ProjectionSourceIdentity(8L, source, 0L))
        )

        assertEquals(ProjectionPlanStatus.READY, plan.status)
        assertTrue(
            plan.projections.none {
                it.kind == NativeProjectionKind.REFERENCE_DEFINITION
            },
        )
        assertEquals(source, plan.identity.source)
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

    fun testSingleTildeRemainsExactSourceWithoutStrikethroughProjection() {
        val source = "Before ~single~ after\n"
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(ProjectionSourceIdentity(1L, source, 0L))
        )

        assertEquals(ProjectionPlanStatus.READY, plan.status)
        assertTrue(plan.projections.none { it.kind == NativeProjectionKind.STRIKETHROUGH })
        assertEquals(source, plan.identity.source)
    }

    fun testUnterminatedStrikethroughRemainsExactSourceWithoutProjection() {
        val source = "Before ~~unterminated marker\n"
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(ProjectionSourceIdentity(2L, source, 0L))
        )

        assertEquals(ProjectionPlanStatus.READY, plan.status)
        assertTrue(plan.projections.none { it.kind == NativeProjectionKind.STRIKETHROUGH })
        assertEquals(source, plan.identity.source)
    }

    fun testSourceGenerationIdentityIncludesStampExactSourceAndConfig() {
        val original = ProjectionSourceIdentity(11L, "same", 2L)

        assertFalse(original == ProjectionSourceIdentity(12L, "same", 2L))
        assertFalse(original == ProjectionSourceIdentity(11L, "changed", 2L))
        assertFalse(original == ProjectionSourceIdentity(11L, "same", 3L))
        assertEquals(original, ProjectionSourceIdentity(11L, "same", 2L))
    }

    fun testWrappedHttpAutolinksRemainSupportedWhileUnsupportedEmailAndBareGfmLiteralsFailClosed() {
        val source = """<https://example.com/path>
<http://example.com/plain>
<user@example.com>

https://example.com/bare
www.example.com
user@example.com
"""
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(ProjectionSourceIdentity(3L, source, 0L))
        )

        assertEquals(ProjectionPlanStatus.READY, plan.status)
        val links = plan.projections.filter { it.kind == NativeProjectionKind.LINK }
        assertEquals(
            listOf("<https://example.com/path>", "<http://example.com/plain>"),
            links.map { link -> source.substring(link.sourceRange.startOffset, link.sourceRange.endOffset) },
        )
        assertEquals(
            listOf("https://example.com/path", "http://example.com/plain"),
            links.map { link ->
                val visible = link.contentRanges.single()
                source.substring(visible.startOffset, visible.endOffset)
            },
        )
        links.forEach { link ->
            assertEquals(1, link.contentRanges.size)
            assertEquals(2, link.syntaxRanges.size)
            assertEquals("<", source.substring(link.syntaxRanges.first().startOffset, link.syntaxRanges.first().endOffset))
            assertEquals(">", source.substring(link.syntaxRanges.last().startOffset, link.syntaxRanges.last().endOffset))
        }

        val wrappedEmailStart = source.indexOf("<user@example.com>")
        assertTrue(wrappedEmailStart >= 0)
        assertTrue(
            "unsupported wrapped email autolink acquired LINK presentation",
            links.none {
                link -> link.sourceRange.intersects(
                    wrappedEmailStart,
                    wrappedEmailStart + "<user@example.com>".length,
                )
            },
        )

        val bareRegionStart = source.indexOf("\n\n") + 2
        listOf("https://example.com/bare", "www.example.com", "user@example.com").forEach { literal ->
            val start = source.indexOf(literal, bareRegionStart)
            assertTrue(start >= 0)
            assertTrue(
                "unsupported bare GFM autolink literal acquired LINK presentation: $literal",
                links.none { link -> link.sourceRange.intersects(start, start + literal.length) },
            )
        }
        assertEquals(source, plan.identity.source)
    }

    fun testLinkWithoutNonEmptyVisibleContentFailsClosed() {
        val source = "Before [](https://example.com/empty) after\n"
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(ProjectionSourceIdentity(5L, source, 0L))
        )

        assertEquals(ProjectionPlanStatus.READY, plan.status)
        val emptyLinkStart = source.indexOf("[](")
        assertTrue(emptyLinkStart >= 0)
        assertTrue(
            "link without non-empty visible content acquired maintained LINK presentation",
            plan.projections.none { projection ->
                projection.kind == NativeProjectionKind.LINK &&
                    projection.sourceRange.intersects(
                        emptyLinkStart,
                        emptyLinkStart + "[](https://example.com/empty)".length,
                    )
            },
        )
        assertEquals(source, plan.identity.source)
    }

    fun testFootnoteLikeReferenceFailsClosedWithoutRegressingOrdinaryReferenceLinks() {
        val source = """A [reference link][guide] and [shortcut].

Footnote-like syntax remains unsupported.[^note]

[guide]: https://example.com/guide
[shortcut]: https://example.com/shortcut
[^note]: https://example.com/footnote
"""
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(ProjectionSourceIdentity(4L, source, 0L))
        )

        assertEquals(ProjectionPlanStatus.READY, plan.status)
        val links = plan.projections.filter { it.kind == NativeProjectionKind.LINK }
        assertEquals(
            listOf("reference link", "shortcut"),
            links.map { link ->
                val visible = link.contentRanges.single()
                source.substring(visible.startOffset, visible.endOffset)
            },
        )
        val footnoteStart = source.indexOf("[^note]")
        assertTrue(footnoteStart >= 0)
        assertTrue(
            "unsupported footnote-like reference acquired LINK presentation",
            links.none { link -> link.sourceRange.intersects(footnoteStart, footnoteStart + "[^note]".length) },
        )
        assertEquals(source, plan.identity.source)
    }

    fun testHardLineBreakProjectionDistinguishesBackslashAndTwoSpaceMarkersFromSoftBreaks() {
        val source =
            "Soft line starts here\n" +
                "and continues softly.\n\n" +
                "Backslash hard break\\\n" +
                "remains a distinct line.\n\n" +
                "Two-space hard break  \n" +
                "also remains distinct.\n\n" +
                "Escaped backslash\\\\\n" +
                "is only a soft break.\n"
        myFixture.configureByText("hard-line-break-projection.md", source)
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot.capture(myFixture.editor.document, 0L)
        )

        assertEquals(ProjectionPlanStatus.READY, plan.status)
        val hardBreaks = plan.projections.filter { it.kind == NativeProjectionKind.HARD_LINE_BREAK }
        assertEquals(2, hardBreaks.size)
        assertEquals(
            listOf("\\", "  "),
            hardBreaks.map { projection ->
                source.substring(projection.sourceRange.startOffset, projection.sourceRange.endOffset)
            },
        )
        hardBreaks.forEach { projection ->
            assertEquals(listOf(projection.sourceRange), projection.syntaxRanges)
            assertFalse(projection.block)
            assertEquals('\n', source[projection.sourceRange.endOffset])
        }
        assertEquals(source, plan.identity.source)
    }

    fun testLineAndThematicBreakContractSurvivesHeadingListAndParagraphAdjacency() {
        val source =
            "# Heading neighbor\n\n" +
                "Heading-adjacent hard break\\\n" +
                "heading continuation\n\n" +
                "- List hard break\\\n" +
                "  list continuation\n\n" +
                "Paragraph hard break with spaces  \n" +
                "paragraph continuation\n\n" +
                "Soft line starts here\n" +
                "soft continuation\n\n" +
                "---\n\n" +
                "***\n\n" +
                "___\n"

        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(
                ProjectionSourceIdentity(
                    modificationStamp = 17L,
                    source = source,
                    configGeneration = 0L,
                )
            )
        )

        assertEquals(ProjectionPlanStatus.READY, plan.status)

        val hardBreaks = plan.projections.filter { it.kind == NativeProjectionKind.HARD_LINE_BREAK }
        assertEquals(3, hardBreaks.size)
        assertEquals(
            listOf("\\", "\\", "  "),
            hardBreaks.map { source.substring(it.sourceRange.startOffset, it.sourceRange.endOffset) },
        )
        hardBreaks.forEach { projection ->
            assertEquals(
                "hard-break projection must leave the physical newline outside its source range",
                '\n',
                source[projection.sourceRange.endOffset],
            )
        }

        val softLineEnd = source.indexOf('\n', source.indexOf("Soft line starts here"))
        assertTrue(softLineEnd > 0)
        assertTrue(
            "soft line break must not acquire a hard-break projection",
            hardBreaks.none { it.sourceRange.endOffset == softLineEnd },
        )

        val thematicBreaks = plan.projections.filter { it.kind == NativeProjectionKind.THEMATIC_BREAK }
        assertEquals(3, thematicBreaks.size)
        assertEquals(
            listOf("---", "***", "___"),
            thematicBreaks.map { source.substring(it.sourceRange.startOffset, it.sourceRange.endOffset) },
        )
        thematicBreaks.forEach { projection ->
            assertEquals(listOf(projection.sourceRange), projection.syntaxRanges)
            assertTrue(projection.block)
        }
    }

}
