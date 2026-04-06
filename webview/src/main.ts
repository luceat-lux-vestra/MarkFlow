import {Crepe} from "@milkdown/crepe";
import {editorViewCtx, parserCtx} from "@milkdown/core";
import {Slice} from "@milkdown/prose/model";
import {TextSelection} from "@milkdown/prose/state";
import "@milkdown/crepe/theme/common/style.css";
import "@milkdown/crepe/theme/frame.css";
import "katex/dist/katex.min.css";
import mermaid from "mermaid";
import "./style.css";

// Shared editor state.
// Prevent feedback loops while applying external IntelliJ updates.
let isUpdatingFromIntelliJ = false;
let isCrepeReady = false;
let pendingMarkdownFromIntelliJ: string | null = null;
let pendingEditorStateFromIntelliJ: EditorUiState | null = null;
let removeMarkdownPasteHandler: (() => void) | null = null;
const EXTERNAL_UPDATE_GUARD_MS = 50;
const BOOT_READY_TIMEOUT_MS = 5000;
let isEditorActive = true;
// Keep debounce scheduling local per render request; a single global timer causes multi-block previews to cancel each other.
// let mermaidDebounceTimer: number | null = null;
const manualMermaidRenderers = new Map<string, () => void>();
let activeCrepe: Crepe | null = null;
const MANUAL_MERMAID_SHORTCUT_KEY = "r";
const MERMAID_RENDER_TIMEOUT_MS = 8000;
const MERMAID_RENDER_RETRY_DELAY_MS = 250;
const MERMAID_RENDER_MAX_RETRIES = 1;
const MERMAID_LOADING_WATCHDOG_MS = 12000;
let mermaidRenderQueues = new WeakMap<(html: string) => void, Promise<void>>();
let mermaidRenderRequestId = 0;
let mermaidPreviewEpoch = 0;
let lastAppliedMermaidTheme: "default" | "dark" = "default";
let lastAppliedSettingsRevision = -1;
let pendingSettingsRerenderRevision: number | null = null;
let pendingLayoutRecovery = false;
let pendingHostForceRerender = false;
let externalUpdateGuardToken = 0;
let isRecreatingCrepe = false;
let pendingCrepeRecreate = false;
let hasAppliedRuntimeSettingsOnce = false;
let lastAppliedPreviewOnlyByDefault = true;
let recoveryRequestInFlight = false;
let activeRecoveryEpoch: number | null = null;
let activeRecoveryRole: RecoveryRole | null = null;
let previewResumeRetryToken = 0;
let crepeSessionSequence = 0;
let activeCrepeSessionId = 0;
const mermaidDebounceTimers = new WeakMap<(html: string) => void, number>();
const allMermaidDebounceTimerIds = new Set<number>();
const mermaidLoadingWatchdogTimers = new WeakMap<(html: string) => void, number>();
const manualPreviewIdByRenderer = new WeakMap<(html: string) => void, string>();
const mermaidPreviewRenderers = new Map<string, () => void>();
let mermaidPreviewIdByRenderer = new WeakMap<(html: string) => void, string>();

const DEFAULT_RUNTIME_SETTINGS: Required<MarkFlowRuntimeSettings> = {
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

const resolveRuntimeSettings = (raw: MarkFlowRuntimeSettings | undefined): Required<MarkFlowRuntimeSettings> => {
    const overrides: MarkFlowRuntimeSettings = raw ?? {};
    const merged: Required<MarkFlowRuntimeSettings> = {...DEFAULT_RUNTIME_SETTINGS, ...overrides};
    return {
        ...merged,
        mermaidZoomPercent: Math.min(Math.max(merged.mermaidZoomPercent, 50), 200),
        renderDebounceMs: Math.min(Math.max(merged.renderDebounceMs, 300), 800)
    };
};

let runtimeSettings = resolveRuntimeSettings(window.intelliJ_markFlowSettings);

// Generate unique ids for Mermaid preview rendering.
const uid = () => Math.random().toString(36).substring(7);

const normalizePreviewSnippet = (value: string, maxLength = 160) => value.replace(/\s+/g, " ").trim().slice(0, maxLength);

const isMermaidLanguage = (language: string) => language.trim().toLowerCase() === "mermaid";

// Keep Mermaid preview rendering readable across SVG- and HTML-label based diagram families.
const resolveMermaidTheme = (): "default" | "dark" => {
    if (runtimeSettings.themeSource === "LIGHT") return "default";
    if (runtimeSettings.themeSource === "DARK") return "dark";
    return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "default";
};

const resolveDiagramSecurityLevel = (): "strict" | "loose" => {
    return runtimeSettings.diagramSecurityLevel === "LOOSE" ? "loose" : "strict";
};

lastAppliedMermaidTheme = resolveMermaidTheme();

const createMermaidPreviewConfig = () => {
    const theme = resolveMermaidTheme();
    const themeVariables = theme === "dark"
        ? {
            primaryColor: "#1f2937",
            primaryTextColor: "#f9fafb",
            lineColor: "#f9fafb",
            textColor: "#f9fafb",
            background: "#111827"
        }
        : {
            primaryColor: "#e5e7eb",
            primaryTextColor: "#111827",
            lineColor: "#111827",
            textColor: "#111827",
            background: "#ffffff"
        };

    // FIT_TO_VIEWPORT: useMaxWidth=true (always fit to viewport)
    // SHRINK_TO_FIT: useMaxWidth=false (actual size with CSS max-width constraint)
    // ACTUAL_SIZE_SCROLL: useMaxWidth=false (actual size with scroll)
    const useMaxWidth = runtimeSettings.mermaidSizeMode === "FIT_TO_VIEWPORT";

    return {
    startOnLoad: false,
    theme,
    themeVariables,
    securityLevel: resolveDiagramSecurityLevel(),
    useMaxWidth,
    htmlLabels: false,
    flowchart: {
        htmlLabels: false,
        useMaxWidth
    },
    class: {
        htmlLabels: false,
        useMaxWidth
    },
    state: {
        htmlLabels: false,
        useMaxWidth
    },
    stateDiagram: {
        useMaxWidth
    },
    mindmap: {
        useMaxWidth
    },
    sequence: {
        useMaxWidth
    },
    sequenceDiagram: {
        useMaxWidth
    },
    gantt: {
        useMaxWidth
    },
    pie: {
        useMaxWidth
    },
    journey: {
        useMaxWidth
    },
    requirement: {
        useMaxWidth
    },
    requirementDiagram: {
        useMaxWidth
    },
    sankey: {
        useMaxWidth
    },
    block: {
        useMaxWidth
    },
    c4: {
        useMaxWidth
    },
    git: {
        useMaxWidth
    },
    gitGraph: {
        useMaxWidth
    },
    er: {
        useMaxWidth
    },
    erDiagram: {
        useMaxWidth
    },
    quadrantChart: {
        useMaxWidth
    },
    xychart: {
        // Mermaid xychart-beta fails to render reliably with non-fit useMaxWidth=false.
        useMaxWidth: true
    },
    timeline: {
        useMaxWidth
    },
    architecture: {
        useMaxWidth
    },
    kanban: {
        useMaxWidth
    },
    packet: {
        useMaxWidth
    },
    venn: {
        useMaxWidth
    },
    xyChart: {
        // Keep camelCase variant aligned for Mermaid parser compatibility.
        useMaxWidth: true
    }
    };
};

const reconfigureMermaid = () => {
    const nextConfig = createMermaidPreviewConfig();
    emitToIntelliJLog(
        `MARKFLOW_UI mermaid:initialize theme=${nextConfig.theme} security=${nextConfig.securityLevel}`
    );
    mermaid.initialize(nextConfig);
};

// Diagnostics.
const emitToIntelliJLog = (message: string) => {
    const logger = window.markflowLog;
    if (typeof logger !== "function") return;
    try {
        logger(message);
    } catch {
        // Ignore diagnostics bridge failures so editor boot is unaffected.
    }
};

const logMermaidTrace = (detail: string) => {
    const line = `MARKFLOW_UI mermaid:${detail}`;
    console.info(line);
    emitToIntelliJLog(line);
};

const markFlowStage = (stage: string, detail = "") => {
    const message = detail ? `MARKFLOW_UI ${stage}: ${detail}` : `MARKFLOW_UI ${stage}`;
    console.info(message);
    emitToIntelliJLog(message);
    const app = document.getElementById("app");
    if (app) {
        app.setAttribute("data-markflow-stage", stage);
    }
};

const logThemeDiagnostics = (raw: MarkFlowRuntimeSettings | undefined, appliedTheme: "default" | "dark") => {
    const payload = {
        source: raw?.themeSource ?? "<undefined>",
        resolvedSource: runtimeSettings.themeSource,
        appliedTheme,
        securityLevel: runtimeSettings.diagramSecurityLevel
    };
    emitToIntelliJLog(`MARKFLOW_UI theme:settings ${JSON.stringify(payload)}`);
};

const applyRuntimeUiSettings = () => {
    const app = document.getElementById("app");
    if (!app) return;
    app.setAttribute("data-katex-density", runtimeSettings.katexDisplayDensity);
};

const renderAllManualMermaidPreviews = () => {
    const renderers = Array.from(manualMermaidRenderers.values());
    manualMermaidRenderers.clear();
    renderers.forEach((render) => render());
};

const registerMermaidPreviewRenderer = (applyPreview: (html: string) => void, renderNow: () => void) => {
    const existingId = mermaidPreviewIdByRenderer.get(applyPreview);
    const previewId = existingId ?? `mermaid-preview-${uid()}`;
    mermaidPreviewIdByRenderer.set(applyPreview, previewId);
    mermaidPreviewRenderers.set(previewId, () => {
        if (!activeCrepe || !isCrepeReady) {
            return;
        }
        renderNow();
    });
};

const renderAllRegisteredMermaidPreviews = () => {
    Array.from(mermaidPreviewRenderers.values()).forEach((render) => render());
};

const renderAllMermaidAndLatexPreviews = () => {
    emitToIntelliJLog("MARKFLOW_UI forceRerender:triggered");
    renderAllManualMermaidPreviews();
    renderAllRegisteredMermaidPreviews();
    if (activeCrepe && isCrepeReady) {
        requestAnimationFrame(() => {
            if (!activeCrepe || !isCrepeReady) return;
            window.dispatchEvent(new Event("resize"));
            emitToIntelliJLog("MARKFLOW_UI forceRerender:done");
        });
        return;
    }

    pendingHostForceRerender = true;
    emitToIntelliJLog("MARKFLOW_UI forceRerender:queued");
};

const triggerForceRerender = () => {
    renderAllMermaidAndLatexPreviews();
};

window.addEventListener("markflowForceRerender", () => {
    emitToIntelliJLog("MARKFLOW_UI action:forceRerender received");
    triggerForceRerender();
});

const clearAllMermaidDebounceTimers = () => {
    allMermaidDebounceTimerIds.forEach((timerId) => window.clearTimeout(timerId));
    allMermaidDebounceTimerIds.clear();
};

const clearMermaidLoadingWatchdog = (applyPreview: (html: string) => void) => {
    const timerId = mermaidLoadingWatchdogTimers.get(applyPreview);
    if (timerId !== undefined) {
        window.clearTimeout(timerId);
        mermaidLoadingWatchdogTimers.delete(applyPreview);
    }
};

const invalidateMermaidPreviewLifecycle = (reason: string) => {
    mermaidPreviewEpoch += 1;
    mermaidRenderRequestId += 1;
    manualMermaidRenderers.clear();
    mermaidPreviewRenderers.clear();
    mermaidPreviewIdByRenderer = new WeakMap();
    clearAllMermaidDebounceTimers();
    mermaidRenderQueues = new WeakMap();
    emitToIntelliJLog(`MARKFLOW_UI mermaid:lifecycleInvalidated reason=${reason} epoch=${mermaidPreviewEpoch}`);
};

const ensureManualPreviewToolbar = () => {
    const existing = document.getElementById("markflow-manual-refresh");
    if (runtimeSettings.renderTriggerMode !== "MANUAL_REFRESH") {
        manualMermaidRenderers.clear();
        existing?.remove();
        return;
    }

    if (existing) {
        const existingButton = existing.querySelector<HTMLButtonElement>(".markflow-manual-refresh-button");
        if (existingButton) {
            existingButton.textContent = runtimeSettings.manualRenderToolbarLabel;
            existingButton.title = runtimeSettings.manualRenderShortcutHint;
        }
        return;
    }

    const toolbar = document.createElement("div");
    toolbar.id = "markflow-manual-refresh";
    toolbar.className = "markflow-manual-refresh-toolbar";

    const button = document.createElement("button");
    button.type = "button";
    button.className = "markflow-manual-refresh-button";
    button.textContent = runtimeSettings.manualRenderToolbarLabel;
    button.title = runtimeSettings.manualRenderShortcutHint;
    button.addEventListener("click", () => {
        renderAllManualMermaidPreviews();
    });

    toolbar.append(button);
    document.body.append(toolbar);
};

const ensureShortcutConflictNotice = () => {
    const conflictNoticeId = "markflow-shortcut-conflict-notice";
    const existing = document.getElementById(conflictNoticeId);

    if (!runtimeSettings.forceRerenderShortcutEnabled || !runtimeSettings.shortcutConflictDetected) {
        existing?.remove();
        return;
    }

    if (existing) {
        return;
    }

    const notice = document.createElement("div");
    notice.id = conflictNoticeId;
    notice.className = "markflow-shortcut-conflict-notice";
    notice.innerHTML = `
        <div class="markflow-notice-content">
            <span class="markflow-notice-icon">⚠️</span>
            <span class="markflow-notice-text">${runtimeSettings.shortcutConflictMessage}</span>
        </div>
    `;

    const app = document.getElementById("app");
    if (app) {
        app.insertBefore(notice, app.firstChild);
    }
};

const rerenderPreviewsAfterSettingsChange = () => {
    console.info(
        `MARKFLOW_UI rerender:start ready=${isCrepeReady} hasCrepe=${activeCrepe !== null} revision=${lastAppliedSettingsRevision}`
    );
    emitToIntelliJLog(
        `MARKFLOW_UI rerender:start ready=${isCrepeReady} hasCrepe=${activeCrepe !== null} revision=${lastAppliedSettingsRevision}`
    );
    if (!activeCrepe || !isCrepeReady) return;

    const fallbackMarkdown = pendingMarkdownFromIntelliJ ?? window.intelliJ_initialMarkdown ?? "";
    const currentMarkdown = safeReadMarkdown(activeCrepe, fallbackMarkdown, "rerender");

    beginExternalUpdateGuard();
    try {
        replaceEditorMarkdown(activeCrepe, currentMarkdown, true);
    } finally {
        clearExternalUpdateGuardLater();
    }

    // Some preview nodes cache rendered HTML; run a second invalidation pass on next frame.
    requestAnimationFrame(() => {
        if (!activeCrepe || !isCrepeReady) return;
        beginExternalUpdateGuard();
        try {
            replaceEditorMarkdown(activeCrepe, currentMarkdown, true);
        } finally {
            clearExternalUpdateGuardLater();
            console.info(`MARKFLOW_UI rerender:done revision=${lastAppliedSettingsRevision}`);
            emitToIntelliJLog(`MARKFLOW_UI rerender:done revision=${lastAppliedSettingsRevision}`);
        }
    });
};

const applyRuntimeSettingsFromHost = (raw: MarkFlowRuntimeSettings | undefined) => {
    emitToIntelliJLog(`MARKFLOW_UI settings:raw ${JSON.stringify(raw ?? {})}`);
    runtimeSettings = resolveRuntimeSettings(raw);
    const previewOnlyByDefaultChanged =
        hasAppliedRuntimeSettingsOnce && lastAppliedPreviewOnlyByDefault !== runtimeSettings.previewOnlyByDefault;
    const nextRevision = Number.isFinite(runtimeSettings.settingsRevision)
        ? Number(runtimeSettings.settingsRevision)
        : -1;
    const nextTheme = resolveMermaidTheme();

    // Ignore duplicated pushes for the same applied revision/theme to prevent rerender storms.
    if (!previewOnlyByDefaultChanged && nextRevision === lastAppliedSettingsRevision && nextTheme === lastAppliedMermaidTheme) {
        emitToIntelliJLog(
            `MARKFLOW_UI settings:skipDuplicate revision=${nextRevision} theme=${nextTheme}`
        );
        return;
    }

    console.info(
        `MARKFLOW_UI settings:apply revision=${nextRevision} theme=${nextTheme} source=${runtimeSettings.themeSource}`
    );
    emitToIntelliJLog(
        `MARKFLOW_UI settings:resolved revision=${nextRevision} source=${runtimeSettings.themeSource} security=${runtimeSettings.diagramSecurityLevel}`
    );
    logThemeDiagnostics(raw, nextTheme);
    reconfigureMermaid();
    lastAppliedMermaidTheme = nextTheme;
    lastAppliedSettingsRevision = nextRevision;
    const app = document.getElementById("app");
    if (app) {
        app.setAttribute("data-markflow-theme", runtimeSettings.themeSource);
        app.setAttribute("data-markflow-settings-revision", String(lastAppliedSettingsRevision));
    }
    applyRuntimeUiSettings();
    ensureManualPreviewToolbar();
    ensureShortcutConflictNotice();
    hasAppliedRuntimeSettingsOnce = true;
    lastAppliedPreviewOnlyByDefault = runtimeSettings.previewOnlyByDefault;

    renderAllRegisteredMermaidPreviews();

    if (previewOnlyByDefaultChanged) {
        emitToIntelliJLog("MARKFLOW_UI settings:previewOnlyByDefault changed -> recreate crepe");
        if (!isCrepeReady || !activeCrepe || isRecreatingCrepe) {
            pendingCrepeRecreate = true;
            emitToIntelliJLog("MARKFLOW_UI settings:previewOnlyByDefault recreate queued");
            return;
        }
        void recreateCrepeInstance("settings:previewOnlyByDefault");
        return;
    }

    if (!isCrepeReady || !activeCrepe) {
        pendingSettingsRerenderRevision = lastAppliedSettingsRevision;
        console.info(`MARKFLOW_UI rerender:queued revision=${lastAppliedSettingsRevision}`);
        emitToIntelliJLog(`MARKFLOW_UI rerender:queued revision=${lastAppliedSettingsRevision}`);
        return;
    }
    rerenderPreviewsAfterSettingsChange();
};

const wrapMermaidSvg = (svg: string) => {
    const isXyChartSvg = /xychart/i.test(svg);
    const sizeClassByMode: Record<string, string> = {
        FIT_TO_VIEWPORT: "fit-to-viewport",
        SHRINK_TO_FIT: "shrink-to-fit",
        ACTUAL_SIZE_SCROLL: "actual-size-scroll"
    };
    const sizeClass = sizeClassByMode[runtimeSettings.mermaidSizeMode] ?? "fit-to-viewport";
    const chartTypeClass = isXyChartSvg ? " markflow-mermaid-chart-xychart" : "";
    const zoomScale = runtimeSettings.mermaidZoomPercent / 100;
    return `<div class="markflow-mermaid-preview markflow-mermaid-size-${sizeClass}${chartTypeClass}" style="transform: scale(${zoomScale}); transform-origin: top left;">${svg}</div>`;
};

const renderMermaidError = (applyPreview: (html: string) => void, error: unknown) => {
    clearMermaidLoadingWatchdog(applyPreview);
    console.error("MARKFLOW_UI mermaid:renderError", error);
    emitToIntelliJLog(`MARKFLOW_UI mermaid:renderError ${String(error)}`);
    if (runtimeSettings.mermaidErrorDisplay === "INLINE_ERROR_BOX") {
        applyPreview(`<div class="mermaid-error">${runtimeSettings.mermaidSyntaxErrorMessage}</div>`);
        return;
    }
    applyPreview("");
};

const requestPreviewResumeRefresh = (reason: string) => {
    const retryToken = ++previewResumeRetryToken;
    requestAnimationFrame(() => {
        if (retryToken !== previewResumeRetryToken) {
            return;
        }
        if (!isEditorActive || document.visibilityState === "hidden") {
            return;
        }
        recoverEditorLayout(reason);
    });
};

const scheduleMermaidRender = (renderNow: () => void, applyPreviewKey?: (html: string) => void) => {
    if (runtimeSettings.renderTriggerMode === "LIVE") {
        logMermaidTrace("trigger live");
        renderNow();
        return;
    }

    if (runtimeSettings.renderTriggerMode === "DEBOUNCED") {
        logMermaidTrace(`trigger debounced ${runtimeSettings.renderDebounceMs}ms`);
        if (applyPreviewKey) {
            const previousTimerId = mermaidDebounceTimers.get(applyPreviewKey);
            if (previousTimerId !== undefined) {
                window.clearTimeout(previousTimerId);
                allMermaidDebounceTimerIds.delete(previousTimerId);
            }
        }
        const timerId = window.setTimeout(() => {
            allMermaidDebounceTimerIds.delete(timerId);
            if (applyPreviewKey) {
                mermaidDebounceTimers.delete(applyPreviewKey);
            }
            renderNow();
        }, runtimeSettings.renderDebounceMs);
        allMermaidDebounceTimerIds.add(timerId);
        if (applyPreviewKey) {
            mermaidDebounceTimers.set(applyPreviewKey, timerId);
        }
        return;
    }

    renderNow();
};

const enqueueMermaidRender = (applyPreview: (html: string) => void, task: () => Promise<void>) => {
    const previousQueue = mermaidRenderQueues.get(applyPreview) ?? Promise.resolve();
    const nextQueue = previousQueue
        .catch(() => {
            // Keep this preview's queue progressing even after a failed render.
        })
        .then(task)
        .catch((error) => {
            const detail = error instanceof Error ? error.message : String(error);
            console.warn(`MARKFLOW_UI mermaid:queueFailure ${detail}`);
            emitToIntelliJLog(`MARKFLOW_UI mermaid:queueFailure ${detail}`);
        });

    mermaidRenderQueues.set(applyPreview, nextQueue);
};

const withTimeout = async <T>(promise: Promise<T>, timeoutMs: number): Promise<T> => {
    let timeoutId: number | null = null;
    const timeoutPromise = new Promise<never>((_, reject) => {
        timeoutId = window.setTimeout(() => {
            reject(new Error(`Mermaid render timed out after ${timeoutMs}ms`));
        }, timeoutMs);
    });

    try {
        return await Promise.race([promise, timeoutPromise]);
    } finally {
        if (timeoutId !== null) {
            window.clearTimeout(timeoutId);
        }
    }
};

const showBootError = (stage: string, detail: string) => {
    emitToIntelliJLog(`MARKFLOW_UI bootError ${stage}: ${detail}`);
    const app = document.getElementById("app");
    if (!app) return;
    app.innerHTML = `
      <div style="font-family: sans-serif; padding: 16px; color: #b91c1c; background: #fff1f2; border: 1px solid #fecdd3;">
        <div style="font-weight: 700; margin-bottom: 8px;">MarkFlow UI failed to boot</div>
        <div><b>stage:</b> ${stage}</div>
        <div><b>detail:</b> ${detail}</div>
      </div>
    `;
};

const clearRecoveryState = (reason: string) => {
    if (activeRecoveryEpoch === null && activeRecoveryRole === null && !recoveryRequestInFlight) {
        return;
    }

    emitToIntelliJLog(
        `MARKFLOW_UI recovery:clear reason=${reason} epoch=${activeRecoveryEpoch ?? -1} role=${activeRecoveryRole ?? "none"}`
    );
    activeRecoveryEpoch = null;
    activeRecoveryRole = null;
    recoveryRequestInFlight = false;
};

const notifyRecoveryOutcome = (status: "complete" | "failed", epoch: number, reason: string) => {
    if (!window.cefQuery) {
        clearRecoveryState(`notify:${status}:bridgeMissing`);
        return;
    }

    const request = JSON.stringify({
        action: `recovery:${status}`,
        sessionId: window.__markflowSessionId,
        epoch,
        reason
    });

    window.cefQuery({
        request,
        onSuccess: (response) => {
            emitToIntelliJLog(`MARKFLOW_UI recovery:${status}:ack ${response ?? "<empty>"}`);
            clearRecoveryState(`notify:${status}:ack`);
        },
        onFailure: (_errCode, errMsg) => {
            emitToIntelliJLog(`MARKFLOW_UI recovery:${status}:ackFailed ${errMsg}`);
            clearRecoveryState(`notify:${status}:failed`);
        }
    });
};

type RecoveryBridgeResponse = {
    role?: string;
    epoch?: number;
};

const requestRecoveryLease = (reason: string): Promise<void> => {
    if (!window.cefQuery) {
        clearRecoveryState("request:bridgeMissing");
        return Promise.resolve();
    }

    recoveryRequestInFlight = true;
    const request = JSON.stringify({
        action: "recovery:request",
        sessionId: window.__markflowSessionId,
        reason
    });

    return new Promise((resolve) => {
        window.cefQuery?.({
            request,
            onSuccess: (response) => {
                try {
                    const parsed = response ? (JSON.parse(response) as RecoveryBridgeResponse) : {};
                    activeRecoveryRole = parsed.role === "leader" || parsed.role === "follower" ? parsed.role : null;
                    activeRecoveryEpoch = typeof parsed.epoch === "number" ? parsed.epoch : null;
                    recoveryRequestInFlight = false;
                } catch (error) {
                    emitToIntelliJLog(`MARKFLOW_UI recovery:request:parseFailed ${String(error)}`);
                    clearRecoveryState("request:parseFailed");
                }
                resolve();
            },
            onFailure: (_errCode, errMsg) => {
                emitToIntelliJLog(`MARKFLOW_UI recovery:request:failed ${errMsg}`);
                clearRecoveryState("request:failed");
                resolve();
            }
        });
    });
};

const isEditorViewContextError = (error: unknown): boolean => {
    const message = error instanceof Error ? error.message : String(error);
    return message.includes("Context \"editorView\" not found");
};

const logEditorViewContextError = (reason: string, error: unknown) => {
    emitToIntelliJLog(`MARKFLOW_UI ${reason} editorView context missing: ${String(error)}`);
};


const safeReadMarkdown = (crepe: Crepe, fallback: string, reason: string): string => {
    try {
        return crepe.getMarkdown();
    } catch (error) {
        emitToIntelliJLog(`MARKFLOW_UI markdown:read failed reason=${reason} error=${String(error)}`);
        if (isEditorViewContextError(error)) {
            logEditorViewContextError(`markdownRead:${reason}`, error);
        }
        return fallback;
    }
};

window.addEventListener("error", (event) => {
    const detail = event.message || String(event.error ?? "unknown error");
    markFlowStage("window:error", detail);
    if (isEditorViewContextError(event.error ?? detail)) {
        logEditorViewContextError("window:error", event.error ?? detail);
        return;
    }
    showBootError("window:error", detail);
});

window.addEventListener("unhandledrejection", (event) => {
    const reason = event.reason instanceof Error ? event.reason.message : String(event.reason);
    markFlowStage("window:unhandledrejection", reason);
    if (isEditorViewContextError(event.reason ?? reason)) {
        logEditorViewContextError("window:unhandledrejection", event.reason ?? reason);
        return;
    }
    showBootError("window:unhandledrejection", reason);
});

type EditorUiState = {
    version: number;
    scrollTop: number;
    cursorOffset: number;
    selectionStart: number;
    selectionEnd: number;
};

type RecoveryRole = "leader" | "follower";

// Editor state helpers.
const clamp = (value: number, min: number, max: number) => Math.min(Math.max(value, min), max);

const getScrollElement = () => {
    const app = document.getElementById("app");
    return app ?? document.scrollingElement ?? document.documentElement;
};


const recoverEditorLayout = (reason: string) => {
    if (!activeCrepe || !isCrepeReady) {
        pendingLayoutRecovery = true;
        emitToIntelliJLog(`MARKFLOW_UI layout:queued reason=${reason}`);
        return;
    }

    emitToIntelliJLog(`MARKFLOW_UI layout:start reason=${reason}`);
    window.dispatchEvent(new Event("resize"));

    requestAnimationFrame(() => {
        if (!activeCrepe || !isCrepeReady) return;
        window.dispatchEvent(new Event("resize"));
        emitToIntelliJLog(`MARKFLOW_UI layout:done reason=${reason}`);
    });
};

function clearExternalUpdateGuardLater() {
    const token = externalUpdateGuardToken;
    setTimeout(() => {
        if (token !== externalUpdateGuardToken) {
            return;
        }
        isUpdatingFromIntelliJ = false;
    }, EXTERNAL_UPDATE_GUARD_MS);
}

function beginExternalUpdateGuard() {
    externalUpdateGuardToken += 1;
    isUpdatingFromIntelliJ = true;
}

function captureEditorUiState(crepe: Crepe): EditorUiState {
    let cursorOffset = -1;
    let selectionStart = -1;
    let selectionEnd = -1;

    try {
        crepe.editor.action((ctx) => {
            const view = ctx.get(editorViewCtx);
            const selection = view.state.selection;
            cursorOffset = selection.head;
            selectionStart = Math.min(selection.from, selection.to);
            selectionEnd = Math.max(selection.from, selection.to);
        });
    } catch (error) {
        emitToIntelliJLog(`MARKFLOW_UI state:capture skipped ${String(error)}`);
    }

    return {
        version: 1,
        scrollTop: getScrollElement().scrollTop,
        cursorOffset,
        selectionStart,
        selectionEnd
    };
}

function applyEditorUiState(crepe: Crepe, state: Partial<EditorUiState>) {
    const scrollTop = Math.max(0, state.scrollTop ?? 0);

    try {
        crepe.editor.action((ctx) => {
            const view = ctx.get(editorViewCtx);
            const docSize = view.state.doc.content.size;

            const fallbackCursor = state.cursorOffset ?? -1;
            const rawStart = state.selectionStart ?? fallbackCursor;
            const rawEnd = state.selectionEnd ?? fallbackCursor;

            if (rawStart == null || rawEnd == null || rawStart < 0 || rawEnd < 0) {
                return;
            }

            const start = clamp(rawStart, 0, docSize);
            const end = clamp(rawEnd, 0, docSize);
            const tr = view.state.tr.setSelection(TextSelection.create(view.state.doc, start, end));
            view.dispatch(tr);
            view.focus();
        });
    } catch (error) {
        emitToIntelliJLog(`MARKFLOW_UI state:apply skipped ${String(error)}`);
        if (isEditorViewContextError(error)) {
            logEditorViewContextError("state:apply", error);
        }
    }

    requestAnimationFrame(() => {
        getScrollElement().scrollTop = scrollTop;
    });
}

function replaceEditorMarkdown(crepe: Crepe, newMarkdown: string, skipHistory = false) {
    try {
        crepe.editor.action((ctx) => {
            const view = ctx.get(editorViewCtx);
            const parser = ctx.get(parserCtx);

            const doc = parser(newMarkdown);
            if (!doc) return;

            const state = view.state;
            const tr = state.tr.replaceWith(0, state.doc.content.size, doc);
            if (skipHistory) {
                tr.setMeta("addToHistory", false);
            }
            view.dispatch(tr);
        });
    } catch (error) {
        emitToIntelliJLog(`MARKFLOW_UI markdown:replace skipped ${String(error)}`);
        if (isEditorViewContextError(error)) {
            logEditorViewContextError("markdown:replace", error);
        }
    }
}

// Markdown clipboard handling.
function normalizeClipboardMarkdown(text: string) {
    return text.replace(/^\uFEFF/, "").replace(/\r\n?/g, "\n");
}

function hasMarkdownTableStructure(lines: string[]) {
    const tableLikeLines = lines.filter((line) => /^\s*\|.*\|\s*$/.test(line));
    if (tableLikeLines.length < 2) return false;

    return lines.some((line) => /^\s*\|?\s*[:\-]{3,}(?:\s*\|\s*[:\-]{3,})+\s*\|?\s*$/.test(line));
}

function looksLikeMarkdownClipboard(text: string) {
    const normalized = normalizeClipboardMarkdown(text);
    const lines = normalized.split("\n");

    if (/^#{1,6}\s+\S/m.test(normalized)) return true;
    if (/^\s*```/m.test(normalized)) return true;
    if (/^\s*\$\$/m.test(normalized)) return true;
    if (/^\s*>\s+\S/m.test(normalized)) return true;
    if (/^\s*[-*+]\s+\S/m.test(normalized)) return true;
    if (/^\s*\d+\.\s+\S/m.test(normalized)) return true;
    if (/^\s*[-*_]{3,}\s*$/m.test(normalized)) return true;
    if (/!\[[^\]]*]\([^)]+\)/m.test(normalized)) return true;
    if (/\[[^\]]+]\([^)]+\)/m.test(normalized)) return true;
    return hasMarkdownTableStructure(lines);
}

function getMarkdownClipboardText(event: ClipboardEvent) {
    const clipboardData = event.clipboardData;
    if (!clipboardData) return null;

    const markdownText = clipboardData.getData("text/markdown");
    if (markdownText.trim()) return normalizeClipboardMarkdown(markdownText);

    const plainText = clipboardData.getData("text/plain");
    if (!plainText.trim()) return null;

    const normalizedPlainText = normalizeClipboardMarkdown(plainText);
    return looksLikeMarkdownClipboard(normalizedPlainText) ? normalizedPlainText : null;
}

function replaceSelectionWithMarkdown(crepe: Crepe, markdownText: string) {
    try {
        crepe.editor.action((ctx) => {
            const view = ctx.get(editorViewCtx);
            const parser = ctx.get(parserCtx);

            try {
                const doc = parser(markdownText);
                if (!doc) {
                    view.dispatch(view.state.tr.insertText(markdownText).scrollIntoView());
                    return;
                }

                view.dispatch(view.state.tr.replaceSelection(new Slice(doc.content, 0, 0)).scrollIntoView());
            } catch (error) {
                console.warn("MARKFLOW_UI markdown paste fallback to plain text", error);
                view.dispatch(view.state.tr.insertText(markdownText).scrollIntoView());
            }
        });
    } catch (error) {
        emitToIntelliJLog(`MARKFLOW_UI paste:replace skipped ${String(error)}`);
        if (isEditorViewContextError(error)) {
            logEditorViewContextError("paste:replaceSelection", error);
        }
    }
}

function installMarkdownPasteHandler(crepe: Crepe) {
    removeMarkdownPasteHandler?.();
    removeMarkdownPasteHandler = null;

    try {
        crepe.editor.action((ctx) => {
            const view = ctx.get(editorViewCtx);

            const handler = (event: ClipboardEvent) => {
                const markdownText = getMarkdownClipboardText(event);
                if (!markdownText) return;

                const selection = view.state.selection;
                if (selection.$from.parent.type.spec.code || selection.$to.parent.type.spec.code) {
                    return;
                }

                event.preventDefault();
                event.stopImmediatePropagation();
                replaceSelectionWithMarkdown(crepe, markdownText);
            };

            view.dom.addEventListener("paste", handler, true);
            removeMarkdownPasteHandler = () => view.dom.removeEventListener("paste", handler, true);
        });
    } catch (error) {
        emitToIntelliJLog(`MARKFLOW_UI paste:install skipped ${String(error)}`);
        if (isEditorViewContextError(error)) {
            logEditorViewContextError("paste:install", error);
        }
    }
}

function flushPendingIntelliJState(crepe: Crepe) {
    if (!isCrepeReady) return;

    const pendingMarkdown = pendingMarkdownFromIntelliJ;
    pendingMarkdownFromIntelliJ = null;
    if (pendingMarkdown !== null) {
        markFlowStage("bridge:updateFromIntelliJ:flush", pendingMarkdown.slice(0, 32));
        beginExternalUpdateGuard();
        try {
            replaceEditorMarkdown(crepe, pendingMarkdown);
        } finally {
            clearExternalUpdateGuardLater();
        }
    }

    const pendingState = pendingEditorStateFromIntelliJ;
    pendingEditorStateFromIntelliJ = null;
    if (pendingState !== null) {
        markFlowStage("bridge:applyEditorState:flush", `${pendingState.scrollTop},${pendingState.cursorOffset}`);
        applyEditorUiState(crepe, pendingState);
    }
}

function logCrepeCreateFailure(error: unknown) {
    console.error("MARKFLOW_UI crepe:create failed", error);
    showBootError("crepe:create", String(error));
}

// JCEF bridge payload serialization.
function sanitizeUiState(uiState: EditorUiState): EditorUiState {
    return {
        version: Number.isFinite(uiState.version) ? uiState.version : 1,
        scrollTop: Number.isFinite(uiState.scrollTop) ? uiState.scrollTop : 0,
        cursorOffset: Number.isFinite(uiState.cursorOffset) ? uiState.cursorOffset : -1,
        selectionStart: Number.isFinite(uiState.selectionStart) ? uiState.selectionStart : -1,
        selectionEnd: Number.isFinite(uiState.selectionEnd) ? uiState.selectionEnd : -1
    };
}

// Send JSON payloads to Kotlin via the JCEF bridge.
function sendToIntelliJ(markdownText: string, uiState: EditorUiState) {
    if (!window.cefQuery) {
        return;
    }

    const safeState = sanitizeUiState(uiState);
    const sessionId = window.__markflowSessionId;
    const request = JSON.stringify({
        action: "update",
        sessionId,
        content: markdownText,
        version: safeState.version,
        scrollTop: safeState.scrollTop,
        cursorOffset: safeState.cursorOffset,
        selectionStart: safeState.selectionStart,
        selectionEnd: safeState.selectionEnd
    });

    if (!request || request === "undefined") {
        return;
    }

    window.cefQuery({
        request,
        onSuccess: () => {
            console.info("MARKFLOW_UI cefQuery:onSuccess");
        },
        onFailure: (_errCode, errMsg) => {
            console.error("MARKFLOW_UI cefQuery:onFailure", errMsg);
        }
    });
}

function createCrepeInstance(initialText: string, crepeSessionId: number): Crepe {
    return new Crepe({
        root: document.getElementById("app"),
        defaultValue: initialText,
        featureConfigs: {
            [Crepe.Feature.CodeMirror]: {
                previewOnlyByDefault: runtimeSettings.previewOnlyByDefault,
                renderPreview: (language, content, applyPreview) => {
                    if (isMermaidLanguage(language) && content.trim()) {
                        const renderEpoch = mermaidPreviewEpoch;
                        const requestId = ++mermaidRenderRequestId;
                        const isRenderContextActive = () => {
                            return renderEpoch === mermaidPreviewEpoch && crepeSessionId === activeCrepeSessionId;
                        };

                        if (!isRenderContextActive()) {
                            return;
                        }

                        const settlePreview = (html: string) => {
                            clearMermaidLoadingWatchdog(applyPreview);
                            if (isRenderContextActive()) {
                                applyPreview(html);
                            }
                        };
                        markFlowStage("mermaid:renderPreview", normalizePreviewSnippet(content, 32));
                        logMermaidTrace(
                            `renderPreview id=${requestId} theme=${lastAppliedMermaidTheme} len=${content.length}`
                        );
                        const renderNow = (attempt = 0) => {
                            const scheduledRevision = lastAppliedSettingsRevision;
                            const scheduledTheme = lastAppliedMermaidTheme;
                            const svgId = `mermaid-svg-${uid()}`;

                            clearMermaidLoadingWatchdog(applyPreview);
                            const watchdogId = window.setTimeout(() => {
                                if (!isRenderContextActive()) {
                                    return;
                                }
                                logMermaidTrace(`watchdog id=${requestId} fallback=error`);
                                renderMermaidError(applyPreview, new Error("Mermaid preview watchdog timeout"));
                            }, MERMAID_LOADING_WATCHDOG_MS);
                            mermaidLoadingWatchdogTimers.set(applyPreview, watchdogId);

                            logMermaidTrace(
                                `queued id=${requestId} attempt=${attempt} revision=${scheduledRevision} theme=${scheduledTheme}`
                            );

                            enqueueMermaidRender(applyPreview, async () => {
                                if (!isRenderContextActive()) {
                                    clearMermaidLoadingWatchdog(applyPreview);
                                    logMermaidTrace(`staleContext id=${requestId} phase=beforeRender`);
                                    return;
                                }
                                logMermaidTrace(`start id=${requestId} svg=${svgId}`);
                                try {
                                    const output = await withTimeout(mermaid.render(svgId, content), MERMAID_RENDER_TIMEOUT_MS);
                                    if (!isRenderContextActive()) {
                                        clearMermaidLoadingWatchdog(applyPreview);
                                        logMermaidTrace(`staleContext id=${requestId} phase=afterRender`);
                                        return;
                                    }
                                    if (scheduledTheme !== lastAppliedMermaidTheme) {
                                        logMermaidTrace(
                                            `stale id=${requestId} scheduledTheme=${scheduledTheme} currentTheme=${lastAppliedMermaidTheme}`
                                        );
                                        return;
                                    }
                                    if (scheduledRevision !== lastAppliedSettingsRevision) {
                                        logMermaidTrace(
                                            `revisionAdvanced id=${requestId} scheduled=${scheduledRevision} current=${lastAppliedSettingsRevision} applying=true`
                                        );
                                    }

                                    logMermaidTrace(
                                        `success id=${requestId} theme=${lastAppliedMermaidTheme}`
                                    );
                                    settlePreview(wrapMermaidSvg(output.svg));
                                } catch (error) {
                                    if (!isRenderContextActive()) {
                                        clearMermaidLoadingWatchdog(applyPreview);
                                        logMermaidTrace(`staleContext id=${requestId} phase=error`);
                                        return;
                                    }
                                    const detail = error instanceof Error ? error.message : String(error);
                                    const timedOut = detail.includes("timed out");
                                    if (timedOut && attempt < MERMAID_RENDER_MAX_RETRIES) {
                                        logMermaidTrace(`retry id=${requestId} nextAttempt=${attempt + 1}`);
                                        window.setTimeout(() => {
                                            if (!isRenderContextActive()) {
                                                clearMermaidLoadingWatchdog(applyPreview);
                                                return;
                                            }
                                            renderNow(attempt + 1);
                                        }, MERMAID_RENDER_RETRY_DELAY_MS);
                                        return;
                                    }
                                    logMermaidTrace(`failed id=${requestId} detail=${detail}`);
                                    if (isRenderContextActive()) {
                                        renderMermaidError(applyPreview, error);
                                    }
                                }
                            });
                        };

                        registerMermaidPreviewRenderer(applyPreview, renderNow);

                        if (runtimeSettings.renderTriggerMode === "MANUAL_REFRESH") {
                            const previousManualId = manualPreviewIdByRenderer.get(applyPreview);
                            if (previousManualId) {
                                manualMermaidRenderers.delete(previousManualId);
                            }
                            const manualId = `manual-mermaid-${uid()}`;
                            manualPreviewIdByRenderer.set(applyPreview, manualId);
                            manualMermaidRenderers.set(manualId, () => {
                                if (!isRenderContextActive()) {
                                    manualMermaidRenderers.delete(manualId);
                                    manualPreviewIdByRenderer.delete(applyPreview);
                                    return;
                                }
                                manualMermaidRenderers.delete(manualId);
                                manualPreviewIdByRenderer.delete(applyPreview);
                                renderNow();
                            });
                            if (isRenderContextActive()) {
                                applyPreview(`<div class="markflow-manual-preview"><button type="button" class="markflow-manual-preview-button" onclick="window.__markflowRenderMermaidPreview && window.__markflowRenderMermaidPreview('${manualId}')">${runtimeSettings.manualRenderInlineLabel}</button><div class="markflow-manual-shortcut-hint">${runtimeSettings.manualRenderShortcutHint}</div></div>`);
                            }
                            return;
                        }

                        scheduleMermaidRender(renderNow, applyPreview);
                        return;
                    }

                    return null;
                }
            },
            [Crepe.Feature.Latex]: {}
        }
    });
}

function attachCrepeBridge(crepe: Crepe) {
    crepe.on((listener) => {
        listener.markdownUpdated((_ctx, markdown, prevMarkdown) => {
            if (!isCrepeReady || activeCrepe !== crepe) return;
            if (isUpdatingFromIntelliJ) return;
            if (markdown !== prevMarkdown) {
                sendToIntelliJ(markdown, captureEditorUiState(crepe));
            }
        });
    });
}

async function startCrepe(crepe: Crepe, layoutReason: string, restoreState?: EditorUiState) {
    markFlowStage("crepe:create:start");
    try {
        await crepe.create();
    } catch (error) {
        logCrepeCreateFailure(error);
        return;
    }

    isCrepeReady = true;
    markFlowStage("crepe:create:done");
    installMarkdownPasteHandler(crepe);
    if (restoreState) {
        applyEditorUiState(crepe, restoreState);
    }
    flushPendingIntelliJState(crepe);
    recoverEditorLayout(layoutReason);

    if (pendingSettingsRerenderRevision !== null) {
        console.info(`MARKFLOW_UI rerender:flushQueued revision=${pendingSettingsRerenderRevision}`);
        emitToIntelliJLog(`MARKFLOW_UI rerender:flushQueued revision=${pendingSettingsRerenderRevision}`);
        pendingSettingsRerenderRevision = null;
        rerenderPreviewsAfterSettingsChange();
    }
    if (pendingLayoutRecovery) {
        pendingLayoutRecovery = false;
        recoverEditorLayout("create:flushQueued");
    }
    if (pendingHostForceRerender) {
        pendingHostForceRerender = false;
        triggerForceRerender();
    }
}

async function recreateCrepeInstance(reason: string) {
    if (isRecreatingCrepe) {
        pendingCrepeRecreate = true;
        return;
    }

    const current = activeCrepe;
    if (!current || !isCrepeReady) {
        pendingCrepeRecreate = true;
        return;
    }

    isRecreatingCrepe = true;
    pendingCrepeRecreate = false;

    await requestRecoveryLease(`recreate:${reason}`);

    const fallbackMarkdown = pendingMarkdownFromIntelliJ ?? window.intelliJ_initialMarkdown ?? "";
    const markdown = safeReadMarkdown(current, fallbackMarkdown, `recreate:${reason}`);
    const uiState = captureEditorUiState(current);

    removeMarkdownPasteHandler?.();
    removeMarkdownPasteHandler = null;

    try {
        (current as unknown as { destroy?: () => void }).destroy?.();
    } catch (error) {
        emitToIntelliJLog(`MARKFLOW_UI crepe:destroy failed ${String(error)}`);
    }

    const app = document.getElementById("app");
    if (app) {
        app.innerHTML = "";
    }

    isCrepeReady = false;
    invalidateMermaidPreviewLifecycle(`recreate:${reason}`);
    const nextSessionId = ++crepeSessionSequence;
    const next = createCrepeInstance(markdown, nextSessionId);
    activeCrepeSessionId = nextSessionId;
    activeCrepe = next;
    attachCrepeBridge(next);

    const recoveryEpochForRun = activeRecoveryEpoch;
    const recoveryRoleForRun = activeRecoveryRole;
    markFlowStage("crepe:recreate:start", reason);
    await startCrepe(next, "recreate:done", uiState);
    markFlowStage("crepe:recreate:done", reason);

    if (recoveryRoleForRun === "leader" && recoveryEpochForRun !== null) {
        const succeeded = isCrepeReady && activeCrepe === next;
        notifyRecoveryOutcome(succeeded ? "complete" : "failed", recoveryEpochForRun, reason);
    }

    isRecreatingCrepe = false;
    if (pendingCrepeRecreate) {
        void recreateCrepeInstance("settings:queued");
    }
}

async function initEditor() {
    markFlowStage("init:start");
    setTimeout(() => {
        if (!window.cefQuery) {
            markFlowStage("bridge:missing");
            return;
        }
        markFlowStage("bridge:ready");
    }, 300);

    // 1) Initialize Mermaid.
    applyRuntimeSettingsFromHost(window.intelliJ_markFlowSettings);
    markFlowStage("mermaid:initialized");

    window.applyMarkFlowSettingsFromIntelliJ = (settings: MarkFlowRuntimeSettings) => {
        emitToIntelliJLog(`MARKFLOW_UI bridge:settingsReceived ${JSON.stringify(settings)}`);
        applyRuntimeSettingsFromHost(settings);
        markFlowStage("bridge:settingsApplied");
    };

    window.setMarkFlowEditorActive = (active: boolean) => {
        isEditorActive = active;
        markFlowStage("bridge:editorActive", active ? "true" : "false");
        if (active) {
            requestPreviewResumeRefresh("editorActive");
        }
    };

    window.addEventListener("visibilitychange", () => {
        if (document.visibilityState !== "visible" || !isEditorActive) {
            return;
        }
        markFlowStage("bridge:visible", "true");
        requestPreviewResumeRefresh("visibilitychange");
    });

    window.addEventListener("keydown", (event: KeyboardEvent) => {
        const isShortcut = (event.metaKey || event.ctrlKey)
            && event.altKey
            && event.shiftKey
            && event.key.toLowerCase() === MANUAL_MERMAID_SHORTCUT_KEY;
        if (!isShortcut) {
            return;
        }
        // Always allow Mermaid+LaTeX force re-render if shortcut is enabled
        if (!runtimeSettings.forceRerenderShortcutEnabled) {
            return;
        }
        event.preventDefault();
        renderAllMermaidAndLatexPreviews();
    });

    (window as { __markflowRenderMermaidPreview?: (manualId: string) => void }).__markflowRenderMermaidPreview = (manualId) => {
        manualMermaidRenderers.get(manualId)?.();
    };

    // 2) Load initial markdown injected by Kotlin.
    const initialText = window.intelliJ_initialMarkdown ?? "";
    markFlowStage("initialText:ready", initialText.slice(0, 48));

    // 3) Create the Crepe editor instance.
    const crepeSessionId = ++crepeSessionSequence;
    const crepe = createCrepeInstance(initialText, crepeSessionId);
    activeCrepeSessionId = crepeSessionId;
    activeCrepe = crepe;
    attachCrepeBridge(crepe);
    markFlowStage("crepe:constructed");

    window.updateFromIntelliJ = (newMarkdown: string) => {
        markFlowStage("bridge:updateFromIntelliJ", newMarkdown.slice(0, 32));
        if (!isCrepeReady || !activeCrepe) {
            pendingMarkdownFromIntelliJ = newMarkdown;
            return;
        }

        beginExternalUpdateGuard();
        try {
            replaceEditorMarkdown(activeCrepe, newMarkdown);
            if (activeRecoveryRole === "follower" && activeRecoveryEpoch !== null) {
                clearRecoveryState("follower:markdownApplied");
            }
        } finally {
            clearExternalUpdateGuardLater();
        }
    };

    window.applyEditorStateFromIntelliJ = (state: EditorUiState) => {
        markFlowStage("bridge:applyEditorState", `${state.scrollTop},${state.cursorOffset}`);
        if (!isCrepeReady || !activeCrepe) {
            pendingEditorStateFromIntelliJ = state;
            return;
        }

        applyEditorUiState(activeCrepe, state);
        if (activeRecoveryRole === "follower" && activeRecoveryEpoch !== null) {
            clearRecoveryState("follower:stateApplied");
        }
    };
    await startCrepe(crepe, "create:done");

    if (pendingCrepeRecreate) {
        void recreateCrepeInstance("settings:queuedAfterCreate");
    }

    setTimeout(() => {
        if (!isCrepeReady) {
            markFlowStage("crepe:create:pending", "still waiting for editor readiness");
        }
    }, BOOT_READY_TIMEOUT_MS);

    markFlowStage("init:done");
}


initEditor();

