package com.algorist.markflow.settings

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.ExtensionPointName

/**
 * Optional presentation sink for runtime-setting invalidation.
 *
 * The base plugin owns settings and native source correctness. Renderer/browser implementations
 * register below their optional dependency descriptor and may disappear without making settings or
 * source editing unavailable.
 */
interface MarkFlowRuntimeSettingsSink {
    fun runtimeSettingsChanged(forceReload: Boolean)

    companion object {
        val EP_NAME: ExtensionPointName<MarkFlowRuntimeSettingsSink> =
            ExtensionPointName.create("com.algorist.markflow.runtimeSettingsSink")
    }
}

object MarkFlowRuntimeSettingsNotifier {
    private val log = Logger.getInstance(MarkFlowRuntimeSettingsNotifier::class.java)

    fun notifyChanged(forceReload: Boolean = false) {
        MarkFlowRuntimeSettingsSink.EP_NAME.extensionList.forEach { sink ->
            runCatching { sink.runtimeSettingsChanged(forceReload) }
                .onFailure { failure ->
                    log.warn("MARKFLOW_SETTINGS optional renderer sink failed", failure)
                }
        }
    }
}
