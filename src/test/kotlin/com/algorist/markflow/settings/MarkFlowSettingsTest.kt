package com.algorist.markflow.settings

import com.algorist.markflow.settings.state.MarkFlowSettingsState
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Unit tests for the pure settings normalization logic in [MarkFlowSettingsService].
 *
 * [MarkFlowSettingsService.normalizeState] is deliberately platform-free (it only touches
 * enum names and numeric ranges), so it can be exercised against a hand-built
 * [MarkFlowSettingsState] without mocking the IDE palette.
 */
class MarkFlowSettingsTest : BasePlatformTestCase() {

    private fun newService() = MarkFlowSettingsService()

    fun testStateAppliesTypographyDefaults() {
        val state = MarkFlowSettingsState()
        assertEquals(
            "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif",
            state.fontFamily
        )
        assertEquals(16, state.baseFontSizePx)
    }

    fun testStateDefaultsToIdeSync() {
        val state = MarkFlowSettingsState()
        assertEquals("IDE_SYNC", state.themeSource)
        assertTrue(state.ideThemeSync)
    }

    fun testNormalizeCoercesZoomIntoRange() {
        val service = newService()
        val below = MarkFlowSettingsState(mermaidZoomPercent = 1)
        service.normalizeState(below)
        assertEquals(50, below.mermaidZoomPercent)

        val above = MarkFlowSettingsState(mermaidZoomPercent = 500)
        service.normalizeState(above)
        assertEquals(200, above.mermaidZoomPercent)

        val within = MarkFlowSettingsState(mermaidZoomPercent = 120)
        service.normalizeState(within)
        assertEquals(120, within.mermaidZoomPercent)
    }

    fun testNormalizeFallsBackToKnownEnumNames() {
        val service = newService()
        val state = MarkFlowSettingsState(
            mermaidSizeMode = "NOT_A_SIZE",
            themeSource = "NOT_A_SOURCE",
            mermaidErrorDisplay = "GARBAGE",
            katexDisplayDensity = "TOO_DENSE",
            diagramSecurityLevel = "CLASSIFIED"
        )
        service.normalizeState(state)
        assertEquals("FIT_TO_VIEWPORT", state.mermaidSizeMode)
        assertEquals("LIGHT", state.themeSource)
        assertEquals("INLINE_ERROR_BOX", state.mermaidErrorDisplay)
        assertEquals("COMFORTABLE", state.katexDisplayDensity)
        assertEquals("STRICT", state.diagramSecurityLevel)
    }

    fun testNormalizeCoercesIdleEvictRange() {
        val service = newService()
        val below = MarkFlowSettingsState(idleEvictAfterMs = 1)
        service.normalizeState(below)
        assertEquals(10_000, below.idleEvictAfterMs)

        val above = MarkFlowSettingsState(idleEvictAfterMs = 100_000_000)
        service.normalizeState(above)
        assertEquals(3_600_000, above.idleEvictAfterMs)
    }
}
