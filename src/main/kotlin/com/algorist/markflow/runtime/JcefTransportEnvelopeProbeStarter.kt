package com.algorist.markflow.runtime

import com.intellij.openapi.application.ApplicationStarter

/**
 * Dedicated IntelliJ command entry point for the #109 real-JCEF evidence runner.
 *
 * Unlike a ProjectActivity this does not depend on a project being opened by the test IDE. The
 * IntelliJ platform invokes ApplicationStarter.main after application initialization, on the EDT;
 * the probe then owns its own completion and application shutdown.
 */
class JcefTransportEnvelopeProbeStarter : ApplicationStarter {
    override fun getCommandName(): String = COMMAND_NAME

    override fun isHeadless(): Boolean = false

    override fun main(args: List<String>) {
        check(JcefTransportEnvelopeProbe.startIfRequested()) {
            "JCEF transport probe command requires -D${JcefTransportEnvelopeProbe.OUTPUT_PROPERTY}=<path>"
        }
    }

    companion object {
        const val COMMAND_NAME = "markflow-jcef-transport-probe"
    }
}
