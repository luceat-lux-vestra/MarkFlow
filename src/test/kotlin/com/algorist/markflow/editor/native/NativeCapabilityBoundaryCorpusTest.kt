package com.algorist.markflow.editor.native

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class NativeCapabilityBoundaryCorpusTest : BasePlatformTestCase() {
    fun testUnsupportedFootnoteFixtureRemainsSourceOnlyAndHostCapabilityFree() {
        val source = readDifferentialFixture("footnote-unsupported")
        myFixture.configureByText("footnote-unsupported.md", source)
        val editor = myFixture.editor
        val document = editor.document
        val markerStart = source.indexOf("[^note]")
        assertTrue("footnote marker fixture drifted", markerStart >= 0)
        val markerEnd = markerStart + "[^note]".length
        editor.caretModel.moveToOffset(source.indexOf("Footnote-like syntax").coerceAtLeast(0))
        val stampBefore = document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val plan = requireNotNull(controller.currentPlan)
            assertEquals(ProjectionPlanStatus.READY, plan.status)
            assertEquals(source, plan.identity.source)

            val footnoteLinks = plan.projections.filter { projection ->
                projection.kind == NativeProjectionKind.LINK &&
                    projection.sourceRange.intersects(markerStart, markerEnd)
            }
            assertTrue(
                "unsupported footnote-like syntax acquired ordinary LINK presentation: $footnoteLinks",
                footnoteLinks.isEmpty(),
            )

            val hostCapabilities = NativeHostResourceProjectionPlanner.plan(plan)
            assertTrue(
                "unsupported footnote-like syntax acquired a host resource/navigation capability",
                hostCapabilities.none { projection ->
                    projection.sourceRange.intersects(markerStart, markerEnd)
                },
            )

            val evidence = controller.evidenceSnapshot()
            assertEquals(
                "unsupported footnote-like fixture must not install inline semantic presentation",
                0,
                evidence.inlineOwnedHighlighters,
            )
            assertEquals(
                "unsupported footnote-like fixture must not conceal inline syntax",
                0,
                evidence.inlineOwnedFolds,
            )
            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)

            editor.caretModel.moveToOffset(markerStart + 2)

            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)
            assertTrue(
                "unsupported footnote-like marker must remain exact source on reveal",
                editor.foldingModel.allFoldRegions.none { fold ->
                    fold.isValid &&
                        !fold.isExpanded &&
                        fold.startOffset < markerEnd &&
                        fold.endOffset > markerStart
                },
            )
        } finally {
            controller.dispose()
        }
    }

    fun testTabsIndentationFixtureSeparatesSupportedListHierarchyFromTabCodeFallback() {
        val source = readDifferentialFixture("tabs-indentation")
        myFixture.configureByText("tabs-indentation.md", source)
        val editor = myFixture.editor
        val document = editor.document
        editor.caretModel.moveToOffset(source.indexOf("# Tabs and indentation").coerceAtLeast(0))
        val stampBefore = document.modificationStamp

        val controller = NativePresentationController(
            editor = editor,
            richPresentationEnabled = { true },
        )
        try {
            val plan = requireNotNull(controller.currentPlan)
            assertEquals(ProjectionPlanStatus.READY, plan.status)
            assertEquals(source, plan.identity.source)

            val list = NativeListProjectionPlanner.plan(plan).single()
            assertEquals(
                listOf("Parent", "Child indented with a tab", "Second child indented with a tab", "Sibling"),
                list.rows.map(NativeListRow::text),
            )
            assertEquals(listOf(0, 1, 1, 0), list.rows.map(NativeListRow::depth))
            assertEquals(listOf("-", "-", "-", "-"), list.rows.map(NativeListRow::marker))

            val tabCodeProjection = plan.projections
                .filter { it.kind == NativeProjectionKind.CODE_BLOCK }
                .single { projection ->
                    source.substring(projection.sourceRange.startOffset, projection.sourceRange.endOffset)
                        .contains("indented code line using a tab")
                }
            assertTrue(tabCodeProjection.sourceRange.isInside(source))
            assertTrue(
                "fixture must retain a literal tab for the code-like line",
                source.substring(tabCodeProjection.sourceRange.startOffset, tabCodeProjection.sourceRange.endOffset)
                    .contains("\tindented code line using a tab"),
            )
            assertTrue(
                "tab-indented code must remain exact source under the current four-space-only rich contract",
                NativeIndentedCodeProjectionPlanner.plan(plan).none { model ->
                    model.sourceRange.intersects(
                        tabCodeProjection.sourceRange.startOffset,
                        tabCodeProjection.sourceRange.endOffset,
                    )
                },
            )

            val evidence = controller.evidenceSnapshot()
            assertEquals(1, evidence.listModels)
            assertEquals(4, evidence.listRows)
            assertEquals(1, evidence.listMaxDepth)
            assertEquals(listOf(0, 1, 1, 0), evidence.listDepths)
            assertEquals(0, evidence.indentedCodeModels)
            assertEquals(0, evidence.indentedCodeInlays)
            assertEquals(0, evidence.indentedCodeFolds)
            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)

            val childOffset = source.indexOf("Child indented with a tab")
            assertTrue(childOffset >= 0)
            editor.caretModel.moveToOffset(childOffset + 1)

            val revealed = controller.evidenceSnapshot()
            assertEquals(
                "active tab-indented list must reveal exact source rather than normalize indentation",
                0,
                revealed.listInlays,
            )
            assertEquals(source, document.text)
            assertEquals(stampBefore, document.modificationStamp)
            assertTrue("tab spelling was normalized", document.text.contains("\t- Child indented with a tab"))
            assertTrue("tab spelling was normalized", document.text.contains("\tindented code line using a tab"))
        } finally {
            controller.dispose()
        }
    }

    private fun readDifferentialFixture(id: String): String = Files.readString(
        Path.of("fixtures/differential-acceptance/markdown/$id.md"),
        StandardCharsets.UTF_8,
    )
}
