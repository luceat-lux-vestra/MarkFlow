package com.algorist.markflow

internal object MarkFlowDiagnostics {
    private const val PROPERTY_NAME = "markflow.diagnostics"
    val enabled: Boolean = java.lang.Boolean.getBoolean(PROPERTY_NAME)

    fun shouldEmitCriticalBridgeMessage(message: String): Boolean {
        if (enabled) return true

        val normalized = message.trim()
        if (normalized.isEmpty()) return false

        return normalized.startsWith("MARKFLOW_UI bootError")
            || normalized.startsWith("MARKFLOW_UI window:error")
            || normalized.startsWith("MARKFLOW_UI window:unhandledrejection")
            || normalized.contains(" failed ", ignoreCase = true)
            || normalized.contains(" failed:", ignoreCase = true)
            || normalized.contains(" error ", ignoreCase = true)
            || normalized.contains(" missing", ignoreCase = true)
            || normalized.endsWith(":error", ignoreCase = true)
            || normalized.endsWith(" error", ignoreCase = true)
    }
}
