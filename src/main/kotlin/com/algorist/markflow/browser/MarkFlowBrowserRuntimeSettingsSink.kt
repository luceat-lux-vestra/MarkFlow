package com.algorist.markflow.browser

import com.algorist.markflow.settings.MarkFlowRuntimeSettingsSink

/** JCEF/browser migration adapter. Registration lives only in META-INF/markflow-jcef.xml. */
class MarkFlowBrowserRuntimeSettingsSink : MarkFlowRuntimeSettingsSink {
    override fun runtimeSettingsChanged(forceReload: Boolean) {
        MarkFlowSharedBrowserService.notifyRuntimeSettingsChanged(forceReload)
    }
}
