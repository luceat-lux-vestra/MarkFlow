import {Crepe} from "@milkdown/crepe";
import "@milkdown/crepe/theme/common/style.css";
import "@milkdown/crepe/theme/frame.css";
import "katex/dist/katex.min.css";
import mermaid from "mermaid";
import "./style.css";
import "./styles/mermaid.css";
import {
    applyRuntimeUiSettings,
    createMermaidPreviewConfig,
    logThemeDiagnostics,
    resolveRuntimeSettings,
    resolveMermaidTheme
} from "./app/runtime-settings";
import {
    applyEditorUiState,
    captureEditorUiState,
    isEditorViewContextError,
    logEditorViewContextError,
    recoverEditorLayout,
    replaceEditorMarkdown,
    safeReadMarkdown
} from "./app/editor-state";
import {installMarkdownPasteHandler} from "./app/clipboard";
import {createRecoveryController} from "./app/recovery";

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
let activeCrepe: Crepe | null = null;
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
let externalUpdateGuardToken = 0;
let isRecreatingCrepe = false;
let pendingCrepeRecreate = false;
let hasAppliedRuntimeSettingsOnce = false;
let lastAppliedPreviewOnlyByDefault = true;
let previewResumeRetryToken = 0;
let crepeSessionSequence = 0;
let activeCrepeSessionId = 0;
const mermaidLoadingWatchdogTimers = new WeakMap<(html: string) => void, number>();
const mermaidPreviewRenderers = new Map<string, () => void>();
let mermaidPreviewIdByRenderer = new WeakMap<(html: string) => void, string>();

let runtimeSettings = resolveRuntimeSettings(window.intelliJ_markFlowSettings);

// Generate unique ids for Mermaid preview rendering.
const uid = () => Math.random().toString(36).substring(7);

const normalizePreviewSnippet = (value: string, maxLength = 160) => value.replace(/\s+/g, " ").trim().slice(0, maxLength);

const isMermaidLanguage = (language: string) => language.trim().toLowerCase() === "mermaid";

const reconfigureMermaid = () => {
    const nextConfig = createMermaidPreviewConfig(runtimeSettings);
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

const recovery = createRecoveryController(emitToIntelliJLog);

lastAppliedMermaidTheme = resolveMermaidTheme(runtimeSettings);

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
    mermaidPreviewRenderers.clear();
    mermaidPreviewIdByRenderer = new WeakMap();
    mermaidRenderQueues = new WeakMap();
    emitToIntelliJLog(`MARKFLOW_UI mermaid:lifecycleInvalidated reason=${reason} epoch=${mermaidPreviewEpoch}`);
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
    const currentMarkdown = safeReadMarkdown(activeCrepe, fallbackMarkdown, "rerender", emitToIntelliJLog);

    beginExternalUpdateGuard();
    try {
        replaceEditorMarkdown(activeCrepe, currentMarkdown, emitToIntelliJLog, true);
    } finally {
        clearExternalUpdateGuardLater();
    }

    // Some preview nodes cache rendered HTML; run a second invalidation pass on next frame.
    requestAnimationFrame(() => {
        if (!activeCrepe || !isCrepeReady) return;
        beginExternalUpdateGuard();
        try {
            replaceEditorMarkdown(activeCrepe, currentMarkdown, emitToIntelliJLog, true);
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
    const nextTheme = resolveMermaidTheme(runtimeSettings);

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
    logThemeDiagnostics(raw, runtimeSettings, nextTheme, emitToIntelliJLog);
    reconfigureMermaid();
    lastAppliedMermaidTheme = nextTheme;
    lastAppliedSettingsRevision = nextRevision;
    const app = document.getElementById("app");
    if (app) {
        app.setAttribute("data-markflow-theme", runtimeSettings.themeSource);
        app.setAttribute("data-markflow-settings-revision", String(lastAppliedSettingsRevision));
    }
    applyRuntimeUiSettings(runtimeSettings);
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
        recoverEditorLayout(reason, isCrepeReady, activeCrepe, emitToIntelliJLog);
    });
};

const scheduleMermaidRender = (renderNow: () => void) => {
    logMermaidTrace("trigger live");
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

window.addEventListener("error", (event) => {
    const detail = event.message || String(event.error ?? "unknown error");
    markFlowStage("window:error", detail);
    if (isEditorViewContextError(event.error ?? detail)) {
        logEditorViewContextError("window:error", event.error ?? detail, emitToIntelliJLog);
        return;
    }
    showBootError("window:error", detail);
});

window.addEventListener("unhandledrejection", (event) => {
    const reason = event.reason instanceof Error ? event.reason.message : String(event.reason);
    markFlowStage("window:unhandledrejection", reason);
    if (isEditorViewContextError(event.reason ?? reason)) {
        logEditorViewContextError("window:unhandledrejection", event.reason ?? reason, emitToIntelliJLog);
        return;
    }
    showBootError("window:unhandledrejection", reason);
});

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

function flushPendingIntelliJState(crepe: Crepe) {
    if (!isCrepeReady) return;

    const pendingMarkdown = pendingMarkdownFromIntelliJ;
    pendingMarkdownFromIntelliJ = null;
    if (pendingMarkdown !== null) {
        markFlowStage("bridge:updateFromIntelliJ:flush", pendingMarkdown.slice(0, 32));
        beginExternalUpdateGuard();
        try {
            // Host-driven sync should not become a user-undo step.
            replaceEditorMarkdown(crepe, pendingMarkdown, emitToIntelliJLog, true);
        } finally {
            clearExternalUpdateGuardLater();
        }
    }

    const pendingState = pendingEditorStateFromIntelliJ;
    pendingEditorStateFromIntelliJ = null;
    if (pendingState !== null) {
        markFlowStage("bridge:applyEditorState:flush", `${pendingState.scrollTop},${pendingState.cursorOffset}`);
        applyEditorUiState(crepe, pendingState, emitToIntelliJLog);
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
    console.info(`MARKFLOW_UI SAVE:ENTRY cefQuery=${typeof window.cefQuery} len=${markdownText.length}`);
    if (!window.cefQuery) {
        console.info("MARKFLOW_UI SAVE:BLOCKED cefQuery missing");
        emitToIntelliJLog("MARKFLOW_SAVE sendToIntelliJ:BLOCKED cefQuery missing");
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
        console.info("MARKFLOW_UI SAVE:BLOCKED request invalid");
        emitToIntelliJLog("MARKFLOW_SAVE sendToIntelliJ:BLOCKED request invalid");
        return;
    }

    console.info(`MARKFLOW_UI SAVE:CEF_QUERY sessionId=${sessionId} contentLen=${markdownText.length}`);
    emitToIntelliJLog(`MARKFLOW_SAVE sendToIntelliJ:CEF_QUERY sessionId=${sessionId} contentLen=${markdownText.length}`);
    window.cefQuery({
        request,
        onSuccess: () => {
            console.info("MARKFLOW_UI SAVE:ACK received");
            emitToIntelliJLog("MARKFLOW_SAVE sendToIntelliJ:ACK received");
        },
        onFailure: (_errCode, errMsg) => {
            console.info(`MARKFLOW_UI SAVE:FAIL ${errMsg}`);
            emitToIntelliJLog(`MARKFLOW_SAVE sendToIntelliJ:FAIL ${errMsg}`);
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
                        scheduleMermaidRender(renderNow);
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
    markFlowStage("bridge:attachCrepeBridge:start", `crepeSession=${crepeSessionSequence}`);
    crepe.on((listener) => {
        listener.markdownUpdated((_ctx, markdown, prevMarkdown) => {
            if (!isCrepeReady || activeCrepe !== crepe) {
                console.info(`MARKFLOW_UI SAVE:BLOCKED listener isCrepeReady=${isCrepeReady} activeCrepeMatch=${activeCrepe === crepe}`);
                emitToIntelliJLog(`MARKFLOW_SAVE markdownUpdated:BLOCKED isCrepeReady=${isCrepeReady} activeCrepeMatch=${activeCrepe === crepe}`);
                return;
            }
            if (isUpdatingFromIntelliJ) {
                console.info(`MARKFLOW_UI SAVE:BLOCKED isUpdatingFromIntelliJ=true`);
                emitToIntelliJLog(`MARKFLOW_SAVE markdownUpdated:BLOCKED isUpdatingFromIntelliJ=true`);
                return;
            }
            if (markdown !== prevMarkdown) {
                console.info(`MARKFLOW_UI SAVE:FIRING len=${markdown.length} prevLen=${prevMarkdown.length}`);
                emitToIntelliJLog(`MARKFLOW_SAVE markdownUpdated:SEND len=${markdown.length} prevLen=${prevMarkdown.length}`);
                sendToIntelliJ(markdown, captureEditorUiState(crepe, emitToIntelliJLog));
            }
        });
    });
    markFlowStage("bridge:attachCrepeBridge:done");
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
    removeMarkdownPasteHandler = installMarkdownPasteHandler(crepe, emitToIntelliJLog);
    if (restoreState) {
        applyEditorUiState(crepe, restoreState, emitToIntelliJLog);
    }
    flushPendingIntelliJState(crepe);
    recoverEditorLayout(layoutReason, isCrepeReady, activeCrepe, emitToIntelliJLog);

    if (pendingSettingsRerenderRevision !== null) {
        console.info(`MARKFLOW_UI rerender:flushQueued revision=${pendingSettingsRerenderRevision}`);
        emitToIntelliJLog(`MARKFLOW_UI rerender:flushQueued revision=${pendingSettingsRerenderRevision}`);
        pendingSettingsRerenderRevision = null;
        rerenderPreviewsAfterSettingsChange();
    }
    if (pendingLayoutRecovery) {
        pendingLayoutRecovery = false;
        recoverEditorLayout("create:flushQueued", isCrepeReady, activeCrepe, emitToIntelliJLog);
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

    // Capture current session ID for race condition detection
    const recreateSessionId = window.__markflowSessionId;
    const recoveryEpochAtStart = recovery.state.activeRecoveryEpoch;
    const recoveryRoleAtStart = recovery.state.activeRecoveryRole;

    try {
        await recovery.requestRecoveryLease(`recreate:${reason}`);

        // Verify session hasn't changed during recovery request
        if (window.__markflowSessionId !== recreateSessionId) {
            emitToIntelliJLog(`MARKFLOW_UI recreate:sessionChanged during recovery oldSession=${recreateSessionId} newSession=${window.__markflowSessionId}`);
            recovery.clearRecoveryState("recreate:sessionChanged");
            return;
        }

        const fallbackMarkdown = pendingMarkdownFromIntelliJ ?? window.intelliJ_initialMarkdown ?? "";
        const markdown = safeReadMarkdown(current, fallbackMarkdown, `recreate:${reason}`, emitToIntelliJLog);
        const uiState = captureEditorUiState(current, emitToIntelliJLog);

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

        markFlowStage("crepe:recreate:start", reason);
        await startCrepe(next, "recreate:done", uiState);
        markFlowStage("crepe:recreate:done", reason);

        // Notify recovery outcome only if we were leader AND session hasn't changed
        if (recoveryRoleAtStart === "leader" && recoveryEpochAtStart !== null && window.__markflowSessionId === recreateSessionId) {
            const succeeded = isCrepeReady && activeCrepe === next;
            recovery.notifyRecoveryOutcome(succeeded ? "complete" : "failed", recoveryEpochAtStart, reason);
        } else if (window.__markflowSessionId === recreateSessionId) {
            // Always clear recovery state when recreate completes (leader or follower)
            recovery.clearRecoveryState(`recreate:completed role=${recoveryRoleAtStart}`);
        }
    } finally {
        isRecreatingCrepe = false;
    }

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

    window.getMarkdown = () => {
        if (!activeCrepe || !isCrepeReady) return "";
        return safeReadMarkdown(activeCrepe, "", "window.getMarkdown", emitToIntelliJLog);
    };

    window.sendToIntelliJ = (markdownText: string, uiState: EditorUiState) => {
        sendToIntelliJ(markdownText, uiState);
    };

    window.addEventListener("visibilitychange", () => {
        if (document.visibilityState !== "visible" || !isEditorActive) {
            return;
        }
        markFlowStage("bridge:visible", "true");
        requestPreviewResumeRefresh("visibilitychange");
    });

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
            // Host-driven sync should not become a user-undo step.
            replaceEditorMarkdown(activeCrepe, newMarkdown, emitToIntelliJLog, true);
            if (recovery.state.activeRecoveryRole === "follower" && recovery.state.activeRecoveryEpoch !== null) {
                const followerEpoch = recovery.state.activeRecoveryEpoch;
                recovery.clearRecoveryState("follower:markdownApplied");
                // Notify backend that follower successfully applied markdown
                recovery.notifyRecoveryOutcome("complete", followerEpoch, "follower:markdownApplied");
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

        applyEditorUiState(activeCrepe, state, emitToIntelliJLog);
        if (recovery.state.activeRecoveryRole === "follower" && recovery.state.activeRecoveryEpoch !== null) {
            const followerEpoch = recovery.state.activeRecoveryEpoch;
            recovery.clearRecoveryState("follower:stateApplied");
            // Notify backend that follower successfully applied state
            recovery.notifyRecoveryOutcome("complete", followerEpoch, "follower:stateApplied");
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
