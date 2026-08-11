package com.algorist.markflow.browser

import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.jcef.JBCefApp

/**
 * Single entry point for "can this IDE run a JCEF browser at all?".
 *
 * Even with `com.intellij.modules.jcef` declared in `plugin.xml`, JCEF stays unavailable when the IDE
 * runs on an alternative JDK without JCEF or when it is switched off by registry, so every browser
 * creation path checks this before touching [JBCefApp] or `JBCefBrowser`.
 */
internal object MarkFlowJcefSupport {

    val isAvailable: Boolean by lazy {
        try {
            JBCefApp.isSupported()
        } catch (ex: Throwable) {
            LOG.warn("MARKFLOW_UI JCEF is unavailable in this IDE runtime: ${ex.message}")
            false
        }
    }

    private val LOG = Logger.getInstance(MarkFlowJcefSupport::class.java)
}
