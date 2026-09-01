package com.algorist.markflow.settings

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MarkFlowIdeThemeServiceTest : BasePlatformTestCase() {

    fun testInitialSnapshotUsesOnlyTheWebviewPaletteContract() {
        val snapshot = MarkFlowIdeThemeService.getInstance().getSnapshot()
        val expectedKeys = setOf(
            "background",
            "foreground",
            "selectionBackground",
            "selectionForeground",
            "border"
        )

        assertTrue(snapshot.colors.keys.all { it in expectedKeys })

        val scheme = EditorColorsManager.getInstance().globalScheme
        assertEquals(
            String.format("#%06x", scheme.defaultBackground.rgb and 0x00FFFFFF),
            snapshot.colors["background"]
        )
        assertTrue(snapshot.fonts["codeFont"].orEmpty().isNotBlank())
    }

    fun testRefreshBumpsRuntimeSettingsRevision() {
        val settings = MarkFlowSettingsService.getInstance()
        val themeService = MarkFlowIdeThemeService.getInstance()
        val before = settings.runtimeSettings().settingsRevision

        themeService.refresh()

        assertTrue(settings.runtimeSettings().settingsRevision > before)
    }
}
