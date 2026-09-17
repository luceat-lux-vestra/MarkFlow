package com.algorist.markflow.settings

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProcessCanceledException

/**
 * Presentation invalidation sink for persisted/theme settings.
 *
 * The base plugin owns settings and source correctness. Native presentation consumers refresh from
 * typed settings through this extension point. Optional renderer creation is isolated separately
 * behind DerivedRendererRuntimeFactory and may disappear without affecting source editing.
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
            try {
                sink.presentationSettingsChanged()
            } catch (failure: ProcessCanceledException) {
                throw failure
            } catch (failure: Throwable) {
                log.warn("MARKFLOW_SETTINGS presentation sink failed", failure)
            }
        }
    }
}
