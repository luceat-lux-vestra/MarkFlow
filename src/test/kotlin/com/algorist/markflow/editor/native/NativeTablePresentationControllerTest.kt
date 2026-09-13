package com.algorist.markflow.editor.native

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class NativeTablePresentationControllerTest : BasePlatformTestCase() {
    fun testParserProvenRowsBecomeStructuredTableModelWithoutSourceReconstruction() {
        val source = """Before

| Name | Value |
| --- | ---: |
| alpha | 1 |
| beta | `2|3` |

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
        assertEquals(3, model.rows.size)
        assertTrue(model.rows.first().header)
        assertEquals(listOf("Name", "Value"), model.rows.first().cells)
        assertEquals(listOf("alpha", "1"), model.rows[1].cells)
        assertFalse(model.rows[2].header)
        assertTrue(model.rows[2].cells.last().contains("`2|3`"))
        assertEquals("| Name | Value |", source.substring(model.rows.first().sourceRange.startOffset, model.rows.first().sourceRange.endOffset))
    }

    fun testInactiveTableUsesNativeFoldAndInlayAndCaretRevealsExactSource() {
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
            assertEquals(1, inactive.ownedFolds)
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
            assertEquals(1, restored.ownedFolds)
            assertEquals(sourceBefore, document.text)
            assertEquals(stampBefore, document.modificationStamp)
        } finally {
            controller.dispose()
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
