package com.algorist.markflow.settings

import com.google.gson.Gson
import com.google.gson.JsonParser
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
        val min = MarkFlowSettingsService.BASE_FONT_SIZE_MIN
        val max = MarkFlowSettingsService.BASE_FONT_SIZE_MAX
        val below = MarkFlowSettingsState(baseFontSizePx = min - 1)
        service.normalizeState(below)
        assertEquals(min, below.baseFontSizePx)

        val above = MarkFlowSettingsState(baseFontSizePx = max + 1)
        service.normalizeState(above)
        assertEquals(max, above.baseFontSizePx)

        val within = MarkFlowSettingsState(baseFontSizePx = 16)
        service.normalizeState(within)
        assertEquals(16, within.baseFontSizePx)

        val edgeLow = MarkFlowSettingsState(baseFontSizePx = min)
        service.normalizeState(edgeLow)
        assertEquals(min, edgeLow.baseFontSizePx)

        val edgeHigh = MarkFlowSettingsState(baseFontSizePx = max)
        service.normalizeState(edgeHigh)
        assertEquals(max, edgeHigh.baseFontSizePx)
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

    fun testRuntimePayloadIncludesCapturedPaletteAndFont() {
        val service = newService()
        val snapshot = MarkFlowIdeThemeService.Snapshot(
            dark = true,
            colors = linkedMapOf(
                "background" to "#123456",
                "foreground" to "#abcdef",
                "selectionBackground" to "#654321",
                "selectionForeground" to "#fedcba",
                "border" to "#112233"
            ),
            fonts = mapOf("codeFont" to "JetBrains Mono")
        )

        val payload = service.runtimeSettingsFromSnapshot(snapshot, "IDE_SYNC", revision = 42)

        assertEquals(snapshot.colors, payload.ideColorScheme)
        val wirePayload = JsonParser.parseString(Gson().toJson(payload)).asJsonObject
            .getAsJsonObject("ideColorScheme")
        assertEquals("#123456", wirePayload.get("background").asString)
        assertEquals("JetBrains Mono", payload.ideFontFamily)
        assertTrue(payload.ideDark)
        assertEquals(42, payload.settingsRevision)
    }

    fun testRuntimePayloadFallsBackWhenPersistedFontIsUnavailable() {
        val service = newService()
        service.loadState(MarkFlowSettingsState(fontFamily = "Font That Is Not Installed"))
        val snapshot = MarkFlowIdeThemeService.Snapshot(
            dark = false,
            colors = emptyMap(),
            fonts = mapOf("codeFont" to "JetBrains Mono")
        )

        assertEquals("", service.runtimeSettingsFromSnapshot(snapshot).fontFamily)
        assertEquals("JetBrains Mono", service.runtimeSettingsFromSnapshot(snapshot).ideFontFamily)
    }

    fun testRuntimeSettingsReadsTheLiveSnapshotIntoThePayload() {
        val service = newService()
        val snapshot = MarkFlowIdeThemeService.getInstance().getSnapshot()

        val payload = service.runtimeSettings()

        assertEquals(snapshot.colors, payload.ideColorScheme)
        assertEquals(snapshot.fonts["codeFont"], payload.ideFontFamily)
        assertEquals(snapshot.dark, payload.ideDark)
    }

    fun testLoadStatePreservesTypographyAcrossPersistence() {
        val service = newService()
        service.loadState(MarkFlowSettingsState(fontFamily = "Inter", baseFontSizePx = 20))

        assertEquals("Inter", service.state.fontFamily)
        assertEquals(20, service.state.baseFontSizePx)
    }
}
