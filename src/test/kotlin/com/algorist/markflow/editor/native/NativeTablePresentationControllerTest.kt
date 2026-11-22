package com.algorist.markflow.editor.native

import com.intellij.openapi.editor.FoldRegion
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class NativeTablePresentationControllerTest : BasePlatformTestCase() {
    fun testParserProvenRowsBecomeStructuredTableModelWithoutSourceReconstruction() {
        val source = """Before

| Name | Value |
| --- | ---: |
| alpha | 1 |
| beta | `2\|3` |

After
"""
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(
                ProjectionSourceIdentity(
                    modificationStamp = 1L,
                    source = source,
                    configGeneration = 0L,
                )
            )
        )

        val model = NativeTableProjectionPlanner.plan(plan).single()

        assertEquals(source.indexOf("| Name"), model.sourceRange.startOffset)
        assertEquals(model.sourceRange.startOffset + 1, model.firstContentOffset)
        assertEquals(3, model.rows.size)
        assertTrue(model.rows.first().header)
        assertEquals(listOf("Name", "Value"), model.rows.first().cells.map(NativeTableCellModel::sourceText))
        assertEquals(listOf("alpha", "1"), model.rows[1].cells.map(NativeTableCellModel::sourceText))
        assertFalse(model.rows[2].header)
        assertEquals(listOf("beta", "`2\\|3`"), model.rows[2].cells.map(NativeTableCellModel::sourceText))
        assertEquals("| Name | Value |", source.substring(model.rows.first().sourceRange.startOffset, model.rows.first().sourceRange.endOffset))
    }

    fun testParserOwnedSeparatorProducesLeftCenterRightColumnAlignment() {
        val source = """| left | center | right |
| :--- | :----: | ---: |
| plain | centered | 100 |
"""
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(ProjectionSourceIdentity(1L, source, 0L))
        )
        val tableProjection = plan.projections.single { it.kind == NativeProjectionKind.TABLE }
        assertEquals(1, tableProjection.syntaxRanges.size)
        assertEquals(
            ":--- | :----: | ---:",
            source.substring(
                tableProjection.syntaxRanges.single().startOffset,
                tableProjection.syntaxRanges.single().endOffset,
            ).trim('|', ' ', '\n', '\r'),
        )

        val model = NativeTableProjectionPlanner.plan(plan).single()
        assertEquals(
            listOf(
                NativeTableAlignment.LEFT,
                NativeTableAlignment.CENTER,
                NativeTableAlignment.RIGHT,
            ),
            model.alignments,
        )
        assertEquals(source, plan.identity.source)
    }

    fun testTableRendererComputesAlignmentSpecificTextOriginsWithinCellPadding() {
        myFixture.configureByText("alignment.md", "plain\n")
        val renderer = NativeTableInlayRenderer(
            myFixture.editor,
            NativeTableModel(
                sourceRange = ProjectionRange(0, 1),
                rows = emptyList(),
                alignments = emptyList(),
            ),
        )

        val padding = nativePresentationLayoutMetrics(myFixture.editor).horizontalPadding
        val contentWidth = 120 - padding * 2
        assertEquals(
            100 + padding,
            renderer.alignedTextStartX(100, 120, 40, NativeTableAlignment.LEFT),
        )
        assertEquals(
            100 + padding + (contentWidth - 40) / 2,
            renderer.alignedTextStartX(100, 120, 40, NativeTableAlignment.CENTER),
        )
        assertEquals(
            100 + 120 - padding - 40,
            renderer.alignedTextStartX(100, 120, 40, NativeTableAlignment.RIGHT),
        )
        assertEquals(
            "clipped text must consume the full editor-derived padded width rather than drift outside the cell",
            100 + padding,
            renderer.alignedTextStartX(100, 120, 200, NativeTableAlignment.CENTER),
        )
    }

    fun testParserOwnedEmphasisStrongInlineCodeAndSimpleLinkBecomeSemanticCellRuns() {
        val source = """| kind | emphasis | strong | link | code |
| --- | --- | --- | --- | --- |
| plain | *emphasis* | **strong** | [link](https://example.com) | `code` |
"""
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(ProjectionSourceIdentity(1L, source, 0L))
        )
        val model = NativeTableProjectionPlanner.plan(plan).single()
        val body = model.rows[1]

        assertEquals(
            listOf("plain", "*emphasis*", "**strong**", "[link](https://example.com)", "`code`"),
            body.cells.map(NativeTableCellModel::sourceText),
        )
        assertEquals(
            listOf("plain", "emphasis", "strong", "link", "code"),
            body.cells.map(NativeTableCellModel::displayText),
        )
        assertEquals(
            listOf(NativeTableInlineStyle.EMPHASIS),
            body.cells[1].displayRuns.map(NativeTableTextRun::style).distinct(),
        )
        assertEquals(
            listOf(NativeTableInlineStyle.STRONG),
            body.cells[2].displayRuns.map(NativeTableTextRun::style).distinct(),
        )
        assertEquals(
            listOf(NativeTableInlineStyle.LINK),
            body.cells[3].displayRuns.map(NativeTableTextRun::style).distinct(),
        )
        assertEquals(
            listOf(NativeTableInlineStyle.INLINE_CODE),
            body.cells[4].displayRuns.map(NativeTableTextRun::style).distinct(),
        )
        assertEquals(source, plan.identity.source)
    }

    fun testReleaseFixtureSimpleTableLinkUsesVisibleParserLabelAndKeepsExactSource() {
        val source = """| left | center | right |
| --- | --- | --- |
| [link](https://example.com/table) | value | final |
"""
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(ProjectionSourceIdentity(1L, source, 0L))
        )
        val model = NativeTableProjectionPlanner.plan(plan).single()
        val cell = model.rows[1].cells[0]

        assertEquals("[link](https://example.com/table)", cell.sourceText)
        assertEquals("link", cell.displayText)
        assertEquals(
            listOf(NativeTableInlineStyle.LINK),
            cell.displayRuns.map(NativeTableTextRun::style).distinct(),
        )
        assertEquals(source, plan.identity.source)
    }

    fun testCompositeLinkLabelFailsClosedToExactCellSource() {
        val source = """| kind | value |
| --- | --- |
| link | [**strong label**](https://example.com/table) |
"""
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(ProjectionSourceIdentity(1L, source, 0L))
        )
        val model = NativeTableProjectionPlanner.plan(plan).single()
        val cell = model.rows[1].cells[1]

        assertEquals("[**strong label**](https://example.com/table)", cell.sourceText)
        assertEquals(cell.sourceText, cell.displayText)
        assertEquals(
            listOf(NativeTableInlineStyle.PLAIN),
            cell.displayRuns.map(NativeTableTextRun::style).distinct(),
        )
        assertEquals(source, plan.identity.source)
    }

    fun testEscapedPipeIsDisplayNormalizedWithoutChangingExactCellSourceOrInlineStyle() {
        val source = """| plain | emphasis | link | code |
| --- | --- | --- | --- |
| escaped \| pipe | *em \| phasis* | [left\|right](https://example.com/table) | `code \| literal` |
"""
        val plan = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(ProjectionSourceIdentity(1L, source, 0L))
        )
        val model = NativeTableProjectionPlanner.plan(plan).single()
        val cells = model.rows[1].cells

        assertEquals(
            listOf(
                "escaped \\| pipe",
                "*em \\| phasis*",
                "[left\\|right](https://example.com/table)",
                "`code \\| literal`",
            ),
            cells.map(NativeTableCellModel::sourceText),
        )
        assertEquals(
            listOf("escaped | pipe", "em | phasis", "left|right", "code | literal"),
            cells.map(NativeTableCellModel::displayText),
        )
        assertEquals(
            listOf(NativeTableInlineStyle.PLAIN),
            cells[0].displayRuns.map(NativeTableTextRun::style).distinct(),
        )
        assertEquals(
            listOf(NativeTableInlineStyle.EMPHASIS),
            cells[1].displayRuns.map(NativeTableTextRun::style).distinct(),
        )
        assertEquals(
            listOf(NativeTableInlineStyle.LINK),
            cells[2].displayRuns.map(NativeTableTextRun::style).distinct(),
        )
        assertEquals(
            listOf(NativeTableInlineStyle.INLINE_CODE),
            cells[3].displayRuns.map(NativeTableTextRun::style).distinct(),
        )
        assertEquals(source, plan.identity.source)
    }

    fun testInactiveTableUsesNativeFoldsAndInlayAndCaretRevealsExactSource() {
        val source = """Before

| Name | Value |
| --- | ---: |
| alpha | 1 |
| beta | 2 |

After
"""
        myFixture.configureByText("table.md", source)
        val editor = myFixture.editor
        val document = editor.document
        val sourceBefore = document.text
        val stampBefore = document.modificationStamp
        val bodyOffset = source.indexOf("After")
        val firstCellOffset = source.indexOf("Name")
        editor.caretModel.moveToOffset(bodyOffset)

        val controller = NativePresentationController(editor)
        try {
            val inactive = controller.tableEvidenceSnapshot()
            assertEquals(1, inactive.tableModels)
            assertEquals(1, inactive.ownedInlays)
            assertEquals(2, inactive.ownedFolds)
            assertEquals(sourceBefore, document.text)
            assertEquals(stampBefore, document.modificationStamp)

            editor.caretModel.moveToOffset(firstCellOffset)
            val active = controller.tableEvidenceSnapshot()
            assertEquals(1, active.tableModels)
            assertEquals(0, active.ownedInlays)
            assertEquals(0, active.ownedFolds)
            assertEquals(sourceBefore, document.text)
            assertEquals(stampBefore, document.modificationStamp)

            editor.caretModel.moveToOffset(bodyOffset)
            val restored = controller.tableEvidenceSnapshot()
            assertEquals(1, restored.ownedInlays)
            assertEquals(2, restored.ownedFolds)
            assertEquals(sourceBefore, document.text)
            assertEquals(stampBefore, document.modificationStamp)
        } finally {
            controller.dispose()
        }
    }

    fun testTablePresentationCoexistsWithForeignWholeTableFoldWithoutChangingItsOwnership() {
        val tableSource = """| Name | Value |
| --- | --- |
| alpha | 😀"""
        val source = """Before

$tableSource

After
"""
        myFixture.configureByText("platform-fold-table.md", source)
        val editor = myFixture.editor
        val document = editor.document
        val sourceBefore = document.text
        val stampBefore = document.modificationStamp
        val tableStart = source.indexOf(tableSource)
        val tableEnd = tableStart + tableSource.length
        val firstCellOffset = source.indexOf("Name", tableStart)
        val bodyOffset = source.indexOf("After")
        editor.caretModel.moveToOffset(bodyOffset)

        lateinit var foreignFold: FoldRegion
        editor.foldingModel.runBatchFoldingOperation {
            foreignFold = checkNotNull(
                editor.foldingModel.addFoldRegion(tableStart, tableEnd, "platform table")
            )
            foreignFold.isExpanded = true
        }
        assertTrue(foreignFold.isValid)
        assertTrue(foreignFold.isExpanded)

        val controller = NativePresentationController(editor)
        try {
            val inactive = controller.tableEvidenceSnapshot()
            assertEquals(1, inactive.tableModels)
            assertEquals(1, inactive.ownedInlays)
            assertEquals(2, inactive.ownedFolds)
            assertTrue(foreignFold.isValid)
            assertTrue(foreignFold.isExpanded)
            assertEquals(sourceBefore, document.text)
            assertEquals(stampBefore, document.modificationStamp)

            editor.caretModel.moveToOffset(firstCellOffset)
            val revealed = controller.tableEvidenceSnapshot()
            assertEquals(0, revealed.ownedInlays)
            assertEquals(0, revealed.ownedFolds)
            assertTrue(foreignFold.isValid)
            assertTrue(foreignFold.isExpanded)
            assertEquals(sourceBefore, document.text)
            assertEquals(stampBefore, document.modificationStamp)

            editor.caretModel.moveToOffset(bodyOffset)
            val restored = controller.tableEvidenceSnapshot()
            assertEquals(1, restored.ownedInlays)
            assertEquals(2, restored.ownedFolds)
            assertTrue(foreignFold.isValid)
            assertTrue(foreignFold.isExpanded)
            assertEquals(sourceBefore, document.text)
            assertEquals(stampBefore, document.modificationStamp)
        } finally {
            controller.dispose()
        }

        assertTrue(foreignFold.isValid)
        assertTrue(foreignFold.isExpanded)
        editor.foldingModel.runBatchFoldingOperation {
            if (foreignFold.isValid) editor.foldingModel.removeFoldRegion(foreignFold)
        }
    }

    fun testScreenReaderDispositionKeepsAuthoritativeTableSourceVisible() {
        val source = """| A | B |
| --- | --- |
| 1 | 2 |

After
"""
        myFixture.configureByText("accessible-table.md", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("After"))
        val table = NativeTablePresentationController(editor, richPresentationEnabled = { false })
        try {
            val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(editor.document, 0L))
            table.applyPlan(plan)
            val evidence = table.evidenceSnapshot()
            assertEquals(1, evidence.tableModels)
            assertEquals(0, evidence.ownedInlays)
            assertEquals(0, evidence.ownedFolds)
            assertEquals(1L, evidence.accessibilityFallbacks)
            assertEquals(source, editor.document.text)
        } finally {
            table.dispose()
        }
    }
}
