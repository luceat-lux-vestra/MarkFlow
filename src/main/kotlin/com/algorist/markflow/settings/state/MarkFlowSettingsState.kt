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
    var ideThemeSync: Boolean = true,
    var fontFamily: String = DEFAULT_FONT_FAMILY,
    var baseFontSizePx: Int = DEFAULT_BASE_FONT_SIZE_PX,
    var mermaidErrorDisplay: String = MermaidErrorDisplay.INLINE_ERROR_BOX.name,
    var katexDisplayDensity: String = KatexDisplayDensity.COMFORTABLE.name,
    var diagramSecurityLevel: String = DiagramSecurityLevel.STRICT.name,
    var previewOnlyByDefault: Boolean = true,
    var idleEvictAfterMs: Int = 120_000
) {
    companion object {
        /** Default webview body font (Crepe's bundled default is used unless the user overrides this). */
        const val DEFAULT_FONT_FAMILY = "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif"

        /** Default webview base font size in px. IDE editor size is used on first load. */
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
    val ideColorScheme: Map<String, String> = emptyMap(),
    val ideFontFamily: String? = null,
    val ideBaseFontSizePx: Int? = null,
    val ideDark: Boolean = false,
    val settingsRevision: Int
)
