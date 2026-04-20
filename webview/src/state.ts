import { Crepe } from "@milkdown/crepe";

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface EditorUiState {
    version: number;
    scrollTop: number;
    cursorOffset: number;
    selectionStart: number;
    selectionEnd: number;
}

export type RecoveryRole = "leader" | "follower";

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

export const EXTERNAL_UPDATE_GUARD_MS = 50;
export const BOOT_READY_TIMEOUT_MS = 5000;
export const MANUAL_MERMAID_SHORTCUT_KEY = "r";
export const MERMAID_RENDER_TIMEOUT_MS = 8000;
export const MERMAID_RENDER_RETRY_DELAY_MS = 250;
export const MERMAID_RENDER_MAX_RETRIES = 1;
export const MERMAID_LOADING_WATCHDOG_MS = 12000;

// ---------------------------------------------------------------------------
// Runtime settings interface (single import point for types)
// ---------------------------------------------------------------------------

export interface MarkFlowRuntimeSettings {
    mermaidSizeMode?: "FIT_TO_VIEWPORT" | "SHRINK_TO_FIT" | "ACTUAL_SIZE_SCROLL";
    mermaidZoomPercent?: number;
    themeSource?: "LIGHT" | "DARK" | "SYSTEM";
    renderTriggerMode?: "LIVE" | "DEBOUNCED" | "MANUAL_REFRESH";
    renderDebounceMs?: number;
    mermaidErrorDisplay?: "INLINE_ERROR_BOX" | "NONE";
    katexDisplayDensity?: "COMFORTABLE" | "COMPACT";
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
}

// ---------------------------------------------------------------------------
// State store — single source of truth for all mutable app state
// ---------------------------------------------------------------------------

export class AppStore {
    runtimeSettings: Required<MarkFlowRuntimeSettings> = {
        mermaidSizeMode: "FIT_TO_VIEWPORT",
        mermaidZoomPercent: 100,
        themeSource: "LIGHT",
        renderTriggerMode: "LIVE",
        renderDebounceMs: 500,
        mermaidErrorDisplay: "INLINE_ERROR_BOX",
        katexDisplayDensity: "COMFORTABLE",
        diagramSecurityLevel: "STRICT",
        previewOnlyByDefault: true,
        forceRerenderShortcutEnabled: true,
        shortcutConflictDetected: false,
        shortcutConflictMessage: "This shortcut may conflict with other IDE shortcuts. You can disable it in MarkFlow settings if needed.",
        manualRenderToolbarLabel: "Render Mermaid",
        manualRenderInlineLabel: "Render Mermaid Preview",
        manualRenderShortcutHint: "Shortcut: Cmd/Ctrl+Alt+Shift+R",
        mermaidSyntaxErrorMessage: "Mermaid Syntax Error",
        settingsRevision: 1,
    };

    isUpdatingFromIntelliJ = false;
    isCrepeReady = false;
    pendingMarkdownFromIntelliJ: string | null = null;
    pendingEditorStateFromIntelliJ: EditorUiState | null = null;
    removeMarkdownPasteHandler: (() => void) | null = null;
    isEditorActive = true;

    manualMermaidRenderers = new Map<string, () => void>();
    activeCrepe: Crepe | null = null;
    mermaidRenderQueues = new WeakMap<(html: string) => void, Promise<void>>();
    mermaidRenderRequestId = 0;
    mermaidPreviewEpoch = 0;
    lastAppliedMermaidTheme: "default" | "dark" = "default";
    lastAppliedSettingsRevision = -1;
    pendingSettingsRerenderRevision: number | null = null;
    pendingLayoutRecovery = false;
    pendingHostForceRerender = false;

    recoveryRequestInFlight = false;
    activeRecoveryEpoch: number | null = null;
    activeRecoveryRole: RecoveryRole | null = null;
    previewResumeRetryToken = 0;

    crepeSessionSequence = 0;
    activeCrepeSessionId = 0;
    isRecreatingCrepe = false;
    pendingCrepeRecreate = false;

    hasAppliedRuntimeSettingsOnce = false;
    lastAppliedPreviewOnlyByDefault = true;

    externalUpdateGuardToken = 0;

    mermaidDebounceTimers = new WeakMap<(html: string) => void, number>();
    allMermaidDebounceTimerIds = new Set<number>();
    mermaidLoadingWatchdogTimers = new WeakMap<(html: string) => void, number>();
    manualPreviewIdByRenderer = new WeakMap<(html: string) => void, string>();
    mermaidPreviewIdByRenderer = new WeakMap<(html: string) => void, string>();
    mermaidPreviewRenderers = new Map<string, () => void>();
}

// Singleton instance
export const app = new AppStore();
