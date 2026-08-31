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

            editor.textField.text = "24"

            assertTrue("direct spinner input should enable Apply", configurable.isModified())
        } finally {
            configurable.disposeUIResources()
        }
    }
}
