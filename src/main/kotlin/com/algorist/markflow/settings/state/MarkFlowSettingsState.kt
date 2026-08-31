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
    var themeSource: String = ThemeSource.IDE_SYNC.name,
    var fontFamily: String = DEFAULT_FONT_FAMILY,
    var baseFontSizePx: Int = DEFAULT_BASE_FONT_SIZE_PX,
    var mermaidErrorDisplay: String = MermaidErrorDisplay.INLINE_ERROR_BOX.name,
    var katexDisplayDensity: String = KatexDisplayDensity.COMFORTABLE.name,
    var diagramSecurityLevel: String = DiagramSecurityLevel.STRICT.name,
    var previewOnlyByDefault: Boolean = true,
    var idleEvictAfterMs: Int = 120_000
) {
    companion object {
        /**
         * Default MarkFlow body font. An empty string means "MarkFlow Default": the webview keeps
         * Crepe's bundled typography and is not asked to override the font family. The persisted
         * value is a single installed font family name (e.g. "Inter"), never a CSS font stack.
         */
        const val DEFAULT_FONT_FAMILY = ""

        /** Default MarkFlow base font size in px. */
        const val DEFAULT_BASE_FONT_SIZE_PX = 16
    }
}

data class MarkFlowRuntimeSettings(
    val mermaidSizeMode: String,
    val mermaidZoomPercent: Int,
    val themeSource: String,
    val mermaidErrorDisplay: String,
    val katexDisplayDensity: String,
    val diagramSecurityLevel: String,
    val previewOnlyByDefault: Boolean,
    val mermaidSyntaxErrorMessage: String,
    val fontFamily: String,
    val baseFontSizePx: Int,
    val ideColorScheme: Map<String, String> = emptyMap(),
    val ideFontFamily: String? = null,
    val ideDark: Boolean = false,
    val settingsRevision: Int
)
