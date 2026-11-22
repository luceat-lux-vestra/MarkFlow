package com.algorist.markflow.editor.native

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.math.max

class NativePresentationLayoutMetricsTest : BasePlatformTestCase() {
    fun testMetricsComeFromActiveEditorTypographyTabAndViewport() {
        myFixture.configureByText("layout-metrics.md", "plain\n")
        val editor = myFixture.editor
        val component = editor.contentComponent
        val fontMetrics = component.getFontMetrics(component.font)
        val expectedSpace = max(1, fontMetrics.charWidth(' '))
        val expectedTab = expectedSpace * editor.settings.getTabSize(editor.project).coerceAtLeast(1)

        val metrics = nativePresentationLayoutMetrics(editor)

        assertEquals(fontMetrics.height, metrics.fontMetrics.height)
        assertEquals(editor.lineHeight, metrics.lineHeight)
        assertEquals(expectedSpace, metrics.spaceWidth)
        assertEquals(expectedTab, metrics.tabWidth)
        assertEquals(max(editor.lineHeight, fontMetrics.height), metrics.rowHeight)
        assertEquals(max(expectedSpace, metrics.rowHeight / 2), metrics.rhythm)
        assertEquals(expectedTab + metrics.rhythm, metrics.nestedIndent)
        assertTrue(metrics.viewportWidth > 0)
        assertTrue(metrics.surfaceWidth() > 0)
    }

    fun testTableMinimumWidthIsContentSemanticRatherThanDevicePixelConstant() {
        myFixture.configureByText("layout-table.md", "plain\n")
        val editor = myFixture.editor
        val metrics = nativePresentationLayoutMetrics(editor)
        val ellipsis = metrics.fontMetrics.stringWidth("…")

        assertEquals(
            max(1, ellipsis) + metrics.horizontalPadding * 2,
            metrics.tableCellMinimumWidth(ellipsis),
        )
    }
}
