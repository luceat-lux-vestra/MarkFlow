package com.algorist.markflow.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
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
}
