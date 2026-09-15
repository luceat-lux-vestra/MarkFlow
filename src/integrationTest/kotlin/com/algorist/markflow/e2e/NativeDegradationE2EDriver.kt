package com.algorist.markflow.e2e

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.Editor
import com.intellij.driver.sdk.ui.components.common.JEditorUiComponent

/**
 * Narrow Driver adapter for #193 degraded-path acceptance. The production bridge remains inert;
 * only an explicitly launched Starter/Driver test can request typed source fallback.
 */
class NativeDegradationE2EDriver(private val driver: Driver) {
    fun degradeToSource(editor: JEditorUiComponent) {
        val bridge = driver.utility(NativeDegradationE2EBridgeRemote::class)
        driver.withContext(OnDispatcher.EDT) {
            check(bridge.degradeToSource(editor.editor)) {
                "native projection did not enter typed source fallback"
            }
        }
    }

    fun isDegradedToSource(editor: JEditorUiComponent): Boolean {
        val bridge = driver.utility(NativeDegradationE2EBridgeRemote::class)
        return driver.withContext(OnDispatcher.EDT) {
            bridge.planDegradedToSource(editor.editor)
        }
    }

    fun ownedHighlighters(editor: JEditorUiComponent): Int {
        val bridge = driver.utility(NativeDegradationE2EBridgeRemote::class)
        return driver.withContext(OnDispatcher.EDT) {
            bridge.ownedHighlighters(editor.editor)
        }
    }

    fun ownedFolds(editor: JEditorUiComponent): Int {
        val bridge = driver.utility(NativeDegradationE2EBridgeRemote::class)
        return driver.withContext(OnDispatcher.EDT) {
            bridge.ownedFolds(editor.editor)
        }
    }
}

@Remote(value = "com.algorist.markflow.editor.native.NativeProjectionE2EBridge", plugin = "com.algorist.markflow")
private interface NativeDegradationE2EBridgeRemote {
    fun degradeToSource(editor: Editor): Boolean
    fun planDegradedToSource(editor: Editor): Boolean
    fun ownedHighlighters(editor: Editor): Int
    fun ownedFolds(editor: Editor): Int
}
