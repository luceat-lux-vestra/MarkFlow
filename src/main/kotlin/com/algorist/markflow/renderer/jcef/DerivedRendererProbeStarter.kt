package com.algorist.markflow.renderer.jcef

import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.net.ProxySettings

class DerivedRendererProbeStarter : ApplicationStarter {
    override val isHeadless: Boolean
        get() = false

    override fun main(args: List<String>) {
        check(args.firstOrNull() == COMMAND_NAME) {
            "Unexpected derived renderer probe command arguments: $args"
        }

        val proxyConfiguration = ProxySettings.getInstance().getProxyConfiguration()
        LOG.info("Derived renderer probe proxy settings initialized: ${proxyConfiguration.javaClass.simpleName}")

        check(DerivedRendererProbe.startIfRequested()) {
            "Derived renderer probe command requires -D${DerivedRendererProbe.OUTPUT_PROPERTY}=<path>"
        }
    }

    companion object {
        const val COMMAND_NAME = "markflow-derived-renderer-probe"
        private val LOG = Logger.getInstance(DerivedRendererProbeStarter::class.java)
    }
}
