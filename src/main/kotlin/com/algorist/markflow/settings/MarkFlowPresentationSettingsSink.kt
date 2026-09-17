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
interface MarkFlowPresentationSettingsSink {
    fun presentationSettingsChanged()

    companion object {
        val EP_NAME: ExtensionPointName<MarkFlowPresentationSettingsSink> =
            ExtensionPointName.create("com.algorist.markflow.presentationSettingsSink")
    }
}

object MarkFlowPresentationSettingsNotifier {
    private val log = Logger.getInstance(MarkFlowPresentationSettingsNotifier::class.java)

    fun notifyChanged() {
        MarkFlowPresentationSettingsSink.EP_NAME.extensionList.forEach { sink ->
            runCatching { sink.presentationSettingsChanged() }
                .onFailure { failure ->
                    log.warn("MARKFLOW_SETTINGS presentation sink failed", failure)
                }
        }
    }
}
