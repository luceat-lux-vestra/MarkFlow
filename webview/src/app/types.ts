export type IntelliJEditorState = {
    version: number;
    scrollTop: number;
    cursorOffset: number;
    selectionStart: number;
    selectionEnd: number;
};

export type EditorUiState = {
    version: number;
    scrollTop: number;
    cursorOffset: number;
    selectionStart: number;
    selectionEnd: number;
};

export type MarkdownSourceSnapshot = {
    rawMarkdown: string;
    sourceRevision: number;
    leaseSessionId: string;
};

export type MarkdownUpdateAck = {
    ok: boolean;
    sourceRevision: number;
    reason?: string;
};

/** Stable host/webview palette contract; keys mirror MarkFlowIdeThemeService.capture(). */
export type IdeColors = {
    background?: string;
    foreground?: string;
    selectionBackground?: string;
    selectionForeground?: string;
    border?: string;
};

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

export type RecoveryRole = "leader" | "follower";

export type RecoveryBridgeResponse = {
    role?: RecoveryRole | string;
    epoch?: number;
    reason?: string;
};
