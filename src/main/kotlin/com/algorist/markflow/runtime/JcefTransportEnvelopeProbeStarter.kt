package com.algorist.markflow.runtime

import com.intellij.openapi.application.ApplicationStarter

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
        check(JcefTransportEnvelopeProbe.startIfRequested()) {
            "JCEF transport probe command requires -D${JcefTransportEnvelopeProbe.OUTPUT_PROPERTY}=<path>"
        }
    }

    companion object {
        const val COMMAND_NAME = "markflow-jcef-transport-probe"
    }
}
