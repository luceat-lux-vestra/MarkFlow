/// <reference types="vite/client" />

type IntelliJEditorState = {
    version: number;
    scrollTop: number;
    cursorOffset: number;
    selectionStart: number;
    selectionEnd: number;
};

type MarkFlowRuntimeSettings = {
    mermaidSizeMode?: "FIT_TO_VIEWPORT" | "ACTUAL_SIZE_SCROLL" | "SHRINK_TO_FIT";
    mermaidZoomPercent?: number;
    themeSource?: "IDE_SYNC" | "LIGHT" | "DARK";
    renderTriggerMode?: "LIVE" | "DEBOUNCED" | "MANUAL_REFRESH";
    renderDebounceMs?: number;
    mermaidErrorDisplay?: "INLINE_ERROR_BOX" | "SILENT_LOG_ONLY";
    katexDisplayDensity?: "COMPACT" | "COMFORTABLE";
    diagramSecurityLevel?: "STRICT" | "LOOSE";
    previewOnlyByDefault?: boolean;
    forceRerenderShortcutEnabled?: boolean;
    shortcutConflictDetected?: boolean;
    shortcutConflictMessage?: string;
    manualRenderToolbarLabel?: string;
    manualRenderInlineLabel?: string;
    manualRenderShortcutHint?: string;
    mermaidSyntaxErrorMessage?: string;
    settingsRevision?: number;
};

interface Window {
    intelliJ_initialMarkdown?: string;
    intelliJ_markFlowSettings?: MarkFlowRuntimeSettings;
    __markflowSessionId?: string;
    cefQuery?: (options: {
        request: string;
        onSuccess?: (response?: string) => void;
        onFailure?: (errorCode: number, errorMessage: string) => void;
    }) => void;
    markflowLog?: (message: string) => void;
    updateFromIntelliJ?: (newMarkdown: string) => void;
    applyEditorStateFromIntelliJ?: (state: IntelliJEditorState) => void;
    applyMarkFlowSettingsFromIntelliJ?: (settings: MarkFlowRuntimeSettings) => void;
    setMarkFlowEditorActive?: (active: boolean) => void;
}
