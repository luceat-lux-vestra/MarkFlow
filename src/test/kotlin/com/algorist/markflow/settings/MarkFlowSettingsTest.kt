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
        // Empty font family means the active IDE font, never a CSS stack.
        assertEquals("", state.fontFamily)
        assertEquals(16, state.baseFontSizePx)
    }

    fun testStateDefaultsToIdeSync() {
        val state = MarkFlowSettingsState()
        assertEquals("IDE_SYNC", state.themeSource)
    }
    fun testNormalizeBaseFontSizeClampsToRange() {
        val service = newService()
        val below = MarkFlowSettingsState(baseFontSizePx = 1)
        service.normalizeState(below)
        assertEquals(10, below.baseFontSizePx)

        val above = MarkFlowSettingsState(baseFontSizePx = 999)
        service.normalizeState(above)
        assertEquals(32, above.baseFontSizePx)

        val within = MarkFlowSettingsState(baseFontSizePx = 16)
        service.normalizeState(within)
        assertEquals(16, within.baseFontSizePx)

        val edgeLow = MarkFlowSettingsState(baseFontSizePx = 10)
        service.normalizeState(edgeLow)
        assertEquals(10, edgeLow.baseFontSizePx)

        val edgeHigh = MarkFlowSettingsState(baseFontSizePx = 32)
        service.normalizeState(edgeHigh)
        assertEquals(32, edgeHigh.baseFontSizePx)
    }

    fun testNormalizeFontFamilyTrimsAndKeepsEmptyAsDefault() {
        val service = newService()
        // The UI is a dropdown of single family names, so trimming is all that is needed.
        val trimmed = MarkFlowSettingsState(fontFamily = "  Inter  ")
        service.normalizeState(trimmed)
        assertEquals("Inter", trimmed.fontFamily)

        // Blank / whitespace-only stays empty, which the webview reads as the IDE font.
        val blank = MarkFlowSettingsState(fontFamily = "   ")
        service.normalizeState(blank)
        assertEquals("", blank.fontFamily)
    }
    fun testNormalizeKeepsTypographyDefaults() {
        // Simulates an old settings file that predates typography fields: no user override, so
        // normalization must leave the bundled defaults intact rather than corrupting them.
        val service = newService()
        val state = MarkFlowSettingsState()
        service.normalizeState(state)
        // Empty family = IDE default; size stays at the default.
        assertEquals("", state.fontFamily)
        assertEquals(16, state.baseFontSizePx)
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
    fun testTypographyPreservedWhenApplyingAnotherSetting() {
        val service = newService()
        // First apply: user sets a font + size. Configurable.apply() builds a COMPLETE state via
        // service.state.copy(...), so every field (including typography) is carried forward.
        service.updateFromUi(
            MarkFlowSettingsState(fontFamily = "Inter", baseFontSizePx = 20, themeSource = "IDE_SYNC")
        )
        // Second apply: user changes only the theme source, again with a complete state.
        service.updateFromUi(
            MarkFlowSettingsState(fontFamily = "Inter", baseFontSizePx = 20, themeSource = "DARK")
        )
        val state = service.state
        // Typography must survive the second apply; it is never reset to constructor defaults.
        assertEquals("Inter", state.fontFamily)
        assertEquals(20, state.baseFontSizePx)
        assertEquals("DARK", state.themeSource)
    }
}
