package com.algorist.markflow.runtime

import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.net.ProxySettings

/**
 * Dedicated IntelliJ command entry point for the #109 real-JCEF evidence runner.
 *
 * Unlike a ProjectActivity this does not depend on a project being opened by the test IDE. The
 * command id is owned by the appStarter extension registration; after application initialization
 * IntelliJ invokes [main] on the EDT and the probe owns completion and application shutdown.
 */
class JcefTransportEnvelopeProbeStarter : ApplicationStarter {
    override val isHeadless: Boolean
        get() = false

    override fun main(args: List<String>) {
        check(args.firstOrNull() == COMMAND_NAME) {
            "Unexpected JCEF transport probe command arguments: $args"
        }

        // The diagnostic command runs earlier than a normal editor surface. Resolve the public
        // proxy-settings service before the first JBCefApp access so legacy migration behind the
        // platform proxy facade cannot be first-created from inside JBCefApp's class initializer.
        val proxyConfiguration = ProxySettings.getInstance().proxyConfiguration
        LOG.info("JCEF transport probe proxy settings initialized: ${proxyConfiguration.javaClass.simpleName}")

        check(JcefTransportEnvelopeProbe.startIfRequested()) {
            "JCEF transport probe command requires -D${JcefTransportEnvelopeProbe.OUTPUT_PROPERTY}=<path>"
        }
    }

    companion object {
        const val COMMAND_NAME = "markflow-jcef-transport-probe"
        private val LOG = Logger.getInstance(JcefTransportEnvelopeProbeStarter::class.java)
    }
}
