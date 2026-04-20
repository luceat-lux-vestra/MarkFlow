import { app } from "../state";
import { emitToIntelliJLog, safeReadMarkdown } from "../bridge";
import { reconfigureMermaid } from "../mermaid/config";
import { renderAllRegisteredMermaidPreviews } from "../mermaid";
import { applyRuntimeUiSettings, ensureManualPreviewToolbar, ensureShortcutConflictNotice } from "../settings";
import { beginExternalUpdateGuard, clearExternalUpdateGuardLater, replaceEditorMarkdown } from "../editor-state/sync";
import { recreateCrepeInstance } from "../mermaid/crepe";
import type { MarkFlowRuntimeSettings } from "../state";

// This file is the single source of truth for runtime settings.
// Other modules (mermaid/config.ts, mermaid/crepe.ts) import resolveRuntimeSettings from here.

export const DEFAULT_RUNTIME_SETTINGS: Required<MarkFlowRuntimeSettings> = {
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
    settingsRevision: 1
};

export const resolveRuntimeSettings = (raw: MarkFlowRuntimeSettings | undefined): Required<MarkFlowRuntimeSettings> => {
    const overrides: MarkFlowRuntimeSettings = raw ?? {};
    const merged: Required<MarkFlowRuntimeSettings> = { ...DEFAULT_RUNTIME_SETTINGS, ...overrides };
    return {
        ...merged,
        mermaidZoomPercent: Math.min(Math.max(merged.mermaidZoomPercent, 50), 200),
        renderDebounceMs: Math.min(Math.max(merged.renderDebounceMs, 300), 800)
    };
};

const resolveMermaidTheme = (): "default" | "dark" => {
    if (app.runtimeSettings.themeSource === "LIGHT") return "default";
    if (app.runtimeSettings.themeSource === "DARK") return "dark";
    return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "default";
};

export const logThemeDiagnostics = (raw: MarkFlowRuntimeSettings | undefined, appliedTheme: "default" | "dark") => {
    const payload = {
        source: raw?.themeSource ?? "<undefined>",
        resolvedSource: app.runtimeSettings.themeSource,
        appliedTheme,
        securityLevel: app.runtimeSettings.diagramSecurityLevel
    };
    emitToIntelliJLog(`MARKFLOW_UI theme:settings ${JSON.stringify(payload)}`);
};

export const rerenderPreviewsAfterSettingsChange = () => {
    console.info(
        `MARKFLOW_UI rerender:start ready=${app.isCrepeReady} hasCrepe=${app.activeCrepe !== null} revision=${app.lastAppliedSettingsRevision}`
    );
    emitToIntelliJLog(
        `MARKFLOW_UI rerender:start ready=${app.isCrepeReady} hasCrepe=${app.activeCrepe !== null} revision=${app.lastAppliedSettingsRevision}`
    );
    if (!app.activeCrepe || !app.isCrepeReady) return;

    const fallbackMarkdown = app.pendingMarkdownFromIntelliJ ?? window.intelliJ_initialMarkdown ?? "";
    const currentMarkdown = safeReadMarkdown(app.activeCrepe, fallbackMarkdown, "rerender");

    beginExternalUpdateGuard();
    try {
        replaceEditorMarkdown(app.activeCrepe, currentMarkdown, true);
    } finally {
        clearExternalUpdateGuardLater();
    }

    // Some preview nodes cache rendered HTML; run a second invalidation pass on next frame.
    requestAnimationFrame(() => {
        if (!app.activeCrepe || !app.isCrepeReady) return;
        beginExternalUpdateGuard();
        try {
            replaceEditorMarkdown(app.activeCrepe, currentMarkdown, true);
        } finally {
            clearExternalUpdateGuardLater();
            console.info(`MARKFLOW_UI rerender:done revision=${app.lastAppliedSettingsRevision}`);
            emitToIntelliJLog(`MARKFLOW_UI rerender:done revision=${app.lastAppliedSettingsRevision}`);
        }
    });
};

export const applyRuntimeSettingsFromHost = (raw: MarkFlowRuntimeSettings | undefined) => {
    emitToIntelliJLog(`MARKFLOW_UI settings:raw ${JSON.stringify(raw ?? {})}`);
    app.runtimeSettings = resolveRuntimeSettings(raw);
    const previewOnlyByDefaultChanged =
        app.hasAppliedRuntimeSettingsOnce && app.lastAppliedPreviewOnlyByDefault !== app.runtimeSettings.previewOnlyByDefault;
    const nextRevision = Number.isFinite(app.runtimeSettings.settingsRevision)
        ? Number(app.runtimeSettings.settingsRevision)
        : -1;
    const nextTheme = resolveMermaidTheme();

    // Ignore duplicated pushes for the same applied revision/theme to prevent rerender storms.
    if (!previewOnlyByDefaultChanged && nextRevision === app.lastAppliedSettingsRevision && nextTheme === app.lastAppliedMermaidTheme) {
        emitToIntelliJLog(
            `MARKFLOW_UI settings:skipDuplicate revision=${nextRevision} theme=${nextTheme}`
        );
        return;
    }

    console.info(
        `MARKFLOW_UI settings:apply revision=${nextRevision} theme=${nextTheme} source=${app.runtimeSettings.themeSource}`
    );
    emitToIntelliJLog(
        `MARKFLOW_UI settings:resolved revision=${nextRevision} source=${app.runtimeSettings.themeSource} security=${app.runtimeSettings.diagramSecurityLevel}`
    );
    logThemeDiagnostics(raw, nextTheme);
    reconfigureMermaid();
    app.lastAppliedMermaidTheme = nextTheme;
    app.lastAppliedSettingsRevision = nextRevision;
    const domApp = document.getElementById("app");
    if (domApp) {
        domApp.setAttribute("data-markflow-theme", app.runtimeSettings.themeSource);
        domApp.setAttribute("data-markflow-settings-revision", String(app.lastAppliedSettingsRevision));
    }
    applyRuntimeUiSettings();
    ensureManualPreviewToolbar();
    ensureShortcutConflictNotice();
    app.hasAppliedRuntimeSettingsOnce = true;
    app.lastAppliedPreviewOnlyByDefault = app.runtimeSettings.previewOnlyByDefault;

    renderAllRegisteredMermaidPreviews();

    if (previewOnlyByDefaultChanged) {
        emitToIntelliJLog("MARKFLOW_UI settings:previewOnlyByDefault changed -> recreate crepe");
        if (!app.isCrepeReady || !app.activeCrepe || app.isRecreatingCrepe) {
            app.pendingCrepeRecreate = true;
            emitToIntelliJLog("MARKFLOW_UI settings:previewOnlyByDefault recreate queued");
            return;
        }
        void recreateCrepeInstance("settings:previewOnlyByDefault");
        return;
    }

    if (!app.isCrepeReady || !app.activeCrepe) {
        app.pendingSettingsRerenderRevision = app.lastAppliedSettingsRevision;
        console.info(`MARKFLOW_UI rerender:queued revision=${app.lastAppliedSettingsRevision}`);
        emitToIntelliJLog(`MARKFLOW_UI rerender:queued revision=${app.lastAppliedSettingsRevision}`);
        return;
    }
    rerenderPreviewsAfterSettingsChange();
};
