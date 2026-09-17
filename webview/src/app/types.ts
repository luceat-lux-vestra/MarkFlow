/** Stable host/renderer palette contract; keys mirror MarkFlowIdeThemeService.capture(). */
export type IdeColors = {
    background?: string;
    foreground?: string;
    selectionBackground?: string;
    selectionForeground?: string;
    border?: string;
};

/** Settings consumed by the retained Mermaid/KaTeX derived-renderer backend. */
export type MarkFlowRuntimeSettings = {
    mermaidSizeMode?: "FIT_TO_VIEWPORT" | "ACTUAL_SIZE_SCROLL" | "SHRINK_TO_FIT";
    mermaidZoomPercent?: number;
    themeSource?: "IDE_SYNC" | "LIGHT" | "DARK";
    mermaidErrorDisplay?: "INLINE_ERROR_BOX" | "SILENT_LOG_ONLY";
    katexDisplayDensity?: "COMPACT" | "COMFORTABLE";
    diagramSecurityLevel?: "STRICT" | "LOOSE";
    previewOnlyByDefault?: boolean;
    mermaidSyntaxErrorMessage?: string;
    fontFamily?: string;
    baseFontSizePx?: number;
    ideColorScheme?: IdeColors;
    ideFontFamily?: string | null;
    ideDark?: boolean;
    settingsRevision?: number;
};
