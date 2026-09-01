package com.algorist.markflow.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.algorist.markflow.settings.state.ThemeSource
import java.awt.GraphicsEnvironment
import javax.swing.JComboBox
import javax.swing.JSpinner

class MarkFlowSettingsConfigurableTest : BasePlatformTestCase() {

    fun testDirectFontSizeInputMarksConfigurableModified() {
        val configurable = MarkFlowSettingsConfigurable()
        try {
            configurable.createComponent()
            val spinnerField = MarkFlowSettingsConfigurable::class.java
                .getDeclaredField("baseFontSizeSpinner")
                .apply { isAccessible = true }
            val spinner = spinnerField.get(configurable) as JSpinner
            val editor = spinner.editor as JSpinner.DefaultEditor
            val current = (spinner.value as Number).toInt()
            val min = MarkFlowSettingsService.BASE_FONT_SIZE_MIN
            val max = MarkFlowSettingsService.BASE_FONT_SIZE_MAX
            val entered = if (current < max) current + 1 else current - 1

            assertTrue("platform font-size range must be non-empty", min < max)
            assertTrue("test input must stay in the platform range", entered in min..max)
            editor.textField.text = entered.toString()

            assertTrue("direct spinner input should enable Apply", configurable.isModified())

            editor.textField.text = (min - 1).toString()
            assertTrue("below-minimum input should be recognized as modified", configurable.isModified())
            assertEquals(min, (spinner.value as Number).toInt())

            editor.textField.text = (max + 1).toString()
            assertTrue("above-maximum input should be recognized as modified", configurable.isModified())
            assertEquals(max, (spinner.value as Number).toInt())
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testApplyPreservesTypographyWhenOnlyAnotherOptionChanges() {
        val service = MarkFlowSettingsService.getInstance()
        val original = service.state.copy()
        val configurable = MarkFlowSettingsConfigurable()
        try {
            val ideFontFamily = MarkFlowIdeThemeService.getInstance().getSnapshot().fonts["codeFont"].orEmpty()
            val customFontFamily = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .availableFontFamilyNames
                .firstOrNull { it.isNotBlank() && !it.equals(ideFontFamily, ignoreCase = true) }
            assertNotNull("test requires an installed font distinct from the IDE default", customFontFamily)

            val fontSize = (MarkFlowSettingsService.BASE_FONT_SIZE_MIN + 1)
                .coerceAtMost(MarkFlowSettingsService.BASE_FONT_SIZE_MAX)
            service.loadState(
                original.copy(
                    fontFamily = customFontFamily!!,
                    baseFontSizePx = fontSize,
                    themeSource = "IDE_SYNC"
                )
            )
            configurable.createComponent()

            val themeComboField = MarkFlowSettingsConfigurable::class.java
                .getDeclaredField("themeSourceCombo")
                .apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val themeCombo = themeComboField.get(configurable) as JComboBox<Any>
            themeCombo.selectedItem = ThemeSource.DARK

            configurable.apply()

            assertEquals(customFontFamily, service.state.fontFamily)
            assertEquals(fontSize, service.state.baseFontSizePx)
            assertEquals("DARK", service.state.themeSource)
        } finally {
            configurable.disposeUIResources()
            service.loadState(original)
        }
    }
}
