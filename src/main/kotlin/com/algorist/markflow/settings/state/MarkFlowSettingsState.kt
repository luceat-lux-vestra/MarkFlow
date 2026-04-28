package com.algorist.markflow.settings.state

enum class MermaidSizeMode {
    FIT_TO_VIEWPORT,
    ACTUAL_SIZE_SCROLL,
    SHRINK_TO_FIT
}

enum class ThemeSource {
    IDE_SYNC,
    LIGHT,
    DARK
}

enum class MermaidErrorDisplay {
    INLINE_ERROR_BOX,
    SILENT_LOG_ONLY
}

enum class KatexDisplayDensity {
    COMPACT,
    COMFORTABLE
}

enum class DiagramSecurityLevel {
    STRICT,
    LOOSE
}

data class MarkFlowSettingsState(
    var mermaidSizeMode: String = MermaidSizeMode.FIT_TO_VIEWPORT.name,
    var mermaidZoomPercent: Int = 100,
    var themeSource: String = ThemeSource.LIGHT.name,
    var mermaidErrorDisplay: String = MermaidErrorDisplay.INLINE_ERROR_BOX.name,
    var katexDisplayDensity: String = KatexDisplayDensity.COMFORTABLE.name,
    var diagramSecurityLevel: String = DiagramSecurityLevel.STRICT.name,
    var previewOnlyByDefault: Boolean = true,
    var idleEvictAfterMs: Int = 120_000
)

data class MarkFlowRuntimeSettings(
    val mermaidSizeMode: String,
    val mermaidZoomPercent: Int,
    val themeSource: String,
    val mermaidErrorDisplay: String,
    val katexDisplayDensity: String,
    val diagramSecurityLevel: String,
    val previewOnlyByDefault: Boolean,
    val mermaidSyntaxErrorMessage: String,
    val settingsRevision: Int
)
