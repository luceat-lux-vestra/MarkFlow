package com.algorist.markflow.runtime

import com.intellij.openapi.application.Application

/**
 * Narrow compatibility overload for the diagnostic probe.
 * IntelliJ 2026.2 exposes exit(force, exitConfirmed, restart); probe completion must never restart.
 */
internal fun Application.exit(force: Boolean, exitConfirmed: Boolean) {
    exit(force, exitConfirmed, false)
}
