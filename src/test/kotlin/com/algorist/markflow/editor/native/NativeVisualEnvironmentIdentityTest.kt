package com.algorist.markflow.editor.native

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class NativeVisualEnvironmentIdentityTest : BasePlatformTestCase() {
    fun testOuterWindowGeometryDoesNotAffectStrictIdentity() {
        val baseline = representativeEntries(window = "1809x875")
        val drifted = representativeEntries(window = "1809x914")

        val baselineIdentity = strictVisualEnvironmentIdentity(baseline)
        val driftedIdentity = strictVisualEnvironmentIdentity(drifted)

        assertEquals(baselineIdentity, driftedIdentity)
        assertFalse(baselineIdentity.contains("window="))
    }

    fun testCaptureRelevantDriftStillChangesStrictIdentity() {
        val baseline = representativeEntries(window = "1809x875")
        val viewportDrift = baseline.map { entry ->
            if (entry.startsWith("viewport=")) "viewport=1199x760" else entry
        }
        val themeDrift = baseline.map { entry ->
            if (entry.startsWith("markflow_theme=")) "markflow_theme=DARK" else entry
        }

        val baselineIdentity = strictVisualEnvironmentIdentity(baseline)
        assertTrue(baselineIdentity != strictVisualEnvironmentIdentity(viewportDrift))
        assertTrue(baselineIdentity != strictVisualEnvironmentIdentity(themeDrift))
    }

    private fun representativeEntries(window: String): List<String> = listOf(
        "visual_contract=v2",
        "window=$window",
        "viewport=1200x760",
        "screen=1920x1080",
        "scale_x=1.000",
        "scale_y=1.000",
        "laf_theme_id=Islands Light",
        "editor_font_name=Inter",
        "editor_font_size=13",
        "markflow_theme=LIGHT",
        "mermaid_size=FIT_TO_VIEWPORT",
        "katex_density=COMFORTABLE",
    )
}
