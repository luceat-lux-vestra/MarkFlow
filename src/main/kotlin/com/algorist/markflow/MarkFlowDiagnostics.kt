package com.algorist.markflow

internal object MarkFlowDiagnostics {
    private const val PROPERTY_NAME = "markflow.diagnostics"
    val enabled: Boolean = java.lang.Boolean.getBoolean(PROPERTY_NAME)
}
