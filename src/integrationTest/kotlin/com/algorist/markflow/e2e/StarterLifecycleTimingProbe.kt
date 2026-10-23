package com.algorist.markflow.e2e

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Observational timing only. Does not change Starter launch/close behavior or test verdicts.
 * Times are from the integration-test worker's monotonic clock, not IDE process CPU usage.
 */
internal class StarterLifecycleTimingProbe(
    private val scenario: String,
    private val outputDirectory: Path,
) {
    private var startNs: Long? = null
    private var readyNs: Long? = null
    private var bodyDoneNs: Long? = null
    private var succeeded = false

    fun begin() {
        startNs = System.nanoTime()
    }

    fun ideReady() {
        readyNs = System.nanoTime()
    }

    fun scenarioComplete() {
        bodyDoneNs = System.nanoTime()
    }

    fun success() {
        succeeded = true
    }

    /**
     * Never turn a diagnostic write failure into a test failure, including in finally.
     * A missing phase is reported as NA rather than silently treated as zero time.
     */
    fun report() {
        runCatching {
            val endNs = System.nanoTime()
            val start = startNs ?: return
            fun elapsedMs(from: Long?, to: Long?): String =
                if (from != null && to != null && to >= from) ((to - from) / 1_000_000).toString() else "NA"

            val fields = listOf(
                "schema=markflow-starter-lifecycle-timing/v1",
                "scenario=$scenario",
                "source_head=${System.getenv("STARTER_SOURCE_SHA") ?: "UNKNOWN"}",
                "setup_to_ready_ms=${elapsedMs(start, readyNs)}",
                "scenario_body_ms=${elapsedMs(readyNs, bodyDoneNs)}",
                "close_after_body_ms=${elapsedMs(bodyDoneNs, endNs)}",
                "total_ms=${elapsedMs(start, endNs)}",
                "completed=$succeeded",
            )
            println("MARKFLOW_STARTER_LIFECYCLE " + fields.joinToString(" "))
            Files.createDirectories(outputDirectory)
            Files.writeString(
                outputDirectory.resolve("lifecycle-timing.txt"),
                fields.joinToString("\n", postfix = "\n"),
                StandardCharsets.UTF_8,
            )
        }.onFailure { failure ->
            System.err.println("MARKFLOW_STARTER_LIFECYCLE diagnostic_only_error=${failure.javaClass.simpleName}")
        }
    }
}
