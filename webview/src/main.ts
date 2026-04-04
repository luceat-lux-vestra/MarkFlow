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
let mermaidRenderQueue: Promise<void> = Promise.resolve();
let mermaidRenderRequestId = 0;
let lastAppliedMermaidTheme: "default" | "dark" = "default";
let lastAppliedSettingsRevision = -1;
let pendingSettingsRerenderRevision: number | null = null;
let pendingLayoutRecovery = false;
let externalUpdateGuardToken = 0;
const mermaidDebounceTimers = new WeakMap<(html: string) => void, number>();
const latestMermaidRequestByPreview = new WeakMap<(html: string) => void, number>();
const manualPreviewIdByRenderer = new WeakMap<(html: string) => void, string>();

const DEFAULT_RUNTIME_SETTINGS: Required<MarkFlowRuntimeSettings> = {
    mermaidSizeMode: "FIT_TO_VIEWPORT",
    mermaidZoomPercent: 100,
    themeSource: "LIGHT",
    renderTriggerMode: "LIVE",
    renderDebounceMs: 500,
    backgroundPreviewPolicy: "PAUSE_WHEN_TAB_INACTIVE",
    mermaidErrorDisplay: "INLINE_ERROR_BOX",
    katexDisplayDensity: "COMFORTABLE",
    diagramSecurityLevel: "STRICT",
    manualRenderToolbarLabel: "Render Mermaid",
    manualRenderInlineLabel: "Render Mermaid Preview",
    manualRenderShortcutHint: "Shortcut: Cmd/Ctrl+Shift+R",
    previewPausedMessage: "Preview paused while tab is inactive.",
    mermaidSyntaxErrorMessage: "Mermaid Syntax Error",
    settingsRevision: 1
};

const resolveRuntimeSettings = (raw: MarkFlowRuntimeSettings | undefined): Required<MarkFlowRuntimeSettings> => {
    const merged = {...DEFAULT_RUNTIME_SETTINGS, ...(raw ?? {})};
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

    return {
    startOnLoad: false,
    theme,
    themeVariables,
    securityLevel: resolveDiagramSecurityLevel(),
    htmlLabels: false,
    flowchart: {
        htmlLabels: false,
        useMaxWidth: runtimeSettings.mermaidSizeMode === "FIT_TO_VIEWPORT"
    },
    class: {
        htmlLabels: false,
        useMaxWidth: runtimeSettings.mermaidSizeMode === "FIT_TO_VIEWPORT"
    },
    state: {
        htmlLabels: false,
        useMaxWidth: runtimeSettings.mermaidSizeMode === "FIT_TO_VIEWPORT"
    },
    mindmap: {
        useMaxWidth: runtimeSettings.mermaidSizeMode === "FIT_TO_VIEWPORT"
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

const rerenderPreviewsAfterSettingsChange = () => {
    console.info(
        `MARKFLOW_UI rerender:start ready=${isCrepeReady} hasCrepe=${activeCrepe !== null} revision=${lastAppliedSettingsRevision}`
    );
    emitToIntelliJLog(
        `MARKFLOW_UI rerender:start ready=${isCrepeReady} hasCrepe=${activeCrepe !== null} revision=${lastAppliedSettingsRevision}`
    );
    if (!activeCrepe || !isCrepeReady) return;

    const currentMarkdown = activeCrepe.getMarkdown();
    const toggleMarkdown = currentMarkdown.endsWith("\n")
        ? currentMarkdown.slice(0, -1)
        : `${currentMarkdown}\n`;

    beginExternalUpdateGuard();
    try {
        if (toggleMarkdown !== currentMarkdown) {
            replaceEditorMarkdown(activeCrepe, toggleMarkdown, true);
        }
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
    const nextRevision = Number.isFinite(runtimeSettings.settingsRevision)
        ? Number(runtimeSettings.settingsRevision)
        : -1;
    const nextTheme = resolveMermaidTheme();

    // Ignore duplicated pushes for the same applied revision/theme to prevent rerender storms.
    if (nextRevision === lastAppliedSettingsRevision && nextTheme === lastAppliedMermaidTheme) {
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
    if (!isCrepeReady || !activeCrepe) {
        pendingSettingsRerenderRevision = lastAppliedSettingsRevision;
        console.info(`MARKFLOW_UI rerender:queued revision=${lastAppliedSettingsRevision}`);
        emitToIntelliJLog(`MARKFLOW_UI rerender:queued revision=${lastAppliedSettingsRevision}`);
        return;
    }
    rerenderPreviewsAfterSettingsChange();
};

const wrapMermaidSvg = (svg: string) => {
    const sizeClass = runtimeSettings.mermaidSizeMode === "ACTUAL_SIZE_SCROLL" ? "actual" : "fit";
    const zoomScale = runtimeSettings.mermaidZoomPercent / 100;
    return `<div class="markflow-mermaid-preview markflow-mermaid-size-${sizeClass}" style="--markflow-mermaid-zoom:${zoomScale}">${svg}</div>`;
};

const renderMermaidError = (applyPreview: (html: string) => void, error: unknown) => {
    console.error("MARKFLOW_UI mermaid:renderError", error);
    emitToIntelliJLog(`MARKFLOW_UI mermaid:renderError ${String(error)}`);
    if (runtimeSettings.mermaidErrorDisplay === "INLINE_ERROR_BOX") {
        applyPreview(`<div class="mermaid-error">${runtimeSettings.mermaidSyntaxErrorMessage}</div>`);
        return;
    }
    applyPreview("");
};

const shouldPausePreviewRender = () => {
    return runtimeSettings.backgroundPreviewPolicy === "PAUSE_WHEN_TAB_INACTIVE" && !isEditorActive;
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
            }
        }
        const timerId = window.setTimeout(() => {
            if (applyPreviewKey) {
                mermaidDebounceTimers.delete(applyPreviewKey);
            }
            renderNow();
        }, runtimeSettings.renderDebounceMs);
        if (applyPreviewKey) {
            mermaidDebounceTimers.set(applyPreviewKey, timerId);
        }
        return;
    }

    renderNow();
};

const enqueueMermaidRender = (task: () => Promise<void>) => {
    mermaidRenderQueue = mermaidRenderQueue
        .catch(() => {
            // Keep queue progressing even after a failed render.
        })
        .then(task)
        .catch((error) => {
            const detail = error instanceof Error ? error.message : String(error);
            console.warn(`MARKFLOW_UI mermaid:queueFailure ${detail}`);
            emitToIntelliJLog(`MARKFLOW_UI mermaid:queueFailure ${detail}`);
        });
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
    markFlowStage("window:error", event.message || "unknown error");
    showBootError("window:error", event.message || String(event.error ?? "unknown error"));
});

window.addEventListener("unhandledrejection", (event) => {
    const reason = event.reason instanceof Error ? event.reason.message : String(event.reason);
    markFlowStage("window:unhandledrejection", reason);
    showBootError("window:unhandledrejection", reason);
});

type EditorUiState = {
    version: number;
    scrollTop: number;
    cursorOffset: number;
    selectionStart: number;
    selectionEnd: number;
};

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
        rerenderPreviewsAfterSettingsChange();
        requestAnimationFrame(() => {
            window.dispatchEvent(new Event("resize"));
            emitToIntelliJLog(`MARKFLOW_UI layout:done reason=${reason}`);
        });
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

    crepe.editor.action((ctx) => {
        const view = ctx.get(editorViewCtx);
        const selection = view.state.selection;
        cursorOffset = selection.head;
        selectionStart = Math.min(selection.from, selection.to);
        selectionEnd = Math.max(selection.from, selection.to);
    });

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

    requestAnimationFrame(() => {
        getScrollElement().scrollTop = scrollTop;
    });
}

function replaceEditorMarkdown(crepe: Crepe, newMarkdown: string, skipHistory = false) {
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
}

function installMarkdownPasteHandler(crepe: Crepe) {
    removeMarkdownPasteHandler?.();
    removeMarkdownPasteHandler = null;

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
    const request = JSON.stringify({
        action: "update",
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
            recoverEditorLayout("editorActive");
        }
    };

    window.addEventListener("keydown", (event: KeyboardEvent) => {
        const isShortcut = (event.metaKey || event.ctrlKey)
            && event.shiftKey
            && event.key.toLowerCase() === MANUAL_MERMAID_SHORTCUT_KEY;
        if (!isShortcut || runtimeSettings.renderTriggerMode !== "MANUAL_REFRESH") {
            return;
        }
        event.preventDefault();
        renderAllManualMermaidPreviews();
    });

    (window as { __markflowRenderMermaidPreview?: (manualId: string) => void }).__markflowRenderMermaidPreview = (manualId) => {
        manualMermaidRenderers.get(manualId)?.();
    };

    // 2) Load initial markdown injected by Kotlin.
    const initialText = window.intelliJ_initialMarkdown ?? "";
    markFlowStage("initialText:ready", initialText.slice(0, 48));

    // 3) Create the Crepe editor instance.
    const crepe = new Crepe({
        root: document.getElementById("app"),
        defaultValue: initialText,
        featureConfigs: {
            // Hook for live Mermaid preview rendering.
            [Crepe.Feature.CodeMirror]: {
                // Use Milkdown's built-in preview toggle state (session-only) for preview-capable blocks.
                previewOnlyByDefault: true,
                renderPreview: (language, content, applyPreview) => {
                    if (isMermaidLanguage(language) && content.trim()) {
                        const requestId = ++mermaidRenderRequestId;
                        latestMermaidRequestByPreview.set(applyPreview, requestId);
                        const isLatestRequest = () => latestMermaidRequestByPreview.get(applyPreview) === requestId;
                        markFlowStage("mermaid:renderPreview", normalizePreviewSnippet(content, 32));
                        logMermaidTrace(
                            `renderPreview id=${requestId} theme=${lastAppliedMermaidTheme} len=${content.length}`
                        );
                        if (shouldPausePreviewRender()) {
                            logMermaidTrace(`paused id=${requestId} tabInactive`);
                            applyPreview(`<div class="markflow-preview-paused">${runtimeSettings.previewPausedMessage}</div>`);
                            return;
                        }

                        const renderNow = () => {
                            const scheduledRevision = lastAppliedSettingsRevision;
                            const scheduledTheme = lastAppliedMermaidTheme;
                            const svgId = `mermaid-svg-${uid()}`;

                            logMermaidTrace(
                                `queued id=${requestId} revision=${scheduledRevision} theme=${scheduledTheme}`
                            );

                            enqueueMermaidRender(async () => {
                                logMermaidTrace(`start id=${requestId} svg=${svgId}`);
                                try {
                                    const output = await withTimeout(mermaid.render(svgId, content), MERMAID_RENDER_TIMEOUT_MS);
                                    if (!isLatestRequest()) {
                                        logMermaidTrace(`superseded id=${requestId}`);
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
                                    applyPreview(wrapMermaidSvg(output.svg));
                                } catch (error) {
                                    if (!isLatestRequest()) {
                                        logMermaidTrace(`supersededError id=${requestId}`);
                                        return;
                                    }
                                    const detail = error instanceof Error ? error.message : String(error);
                                    logMermaidTrace(`failed id=${requestId} detail=${detail}`);
                                    renderMermaidError(applyPreview, error);
                                }
                            });
                        };

                        if (runtimeSettings.renderTriggerMode === "MANUAL_REFRESH") {
                            const previousManualId = manualPreviewIdByRenderer.get(applyPreview);
                            if (previousManualId) {
                                manualMermaidRenderers.delete(previousManualId);
                            }
                            const manualId = `manual-mermaid-${uid()}`;
                            manualPreviewIdByRenderer.set(applyPreview, manualId);
                            manualMermaidRenderers.set(manualId, () => {
                                manualMermaidRenderers.delete(manualId);
                                manualPreviewIdByRenderer.delete(applyPreview);
                                renderNow();
                            });
                            applyPreview(`<div class="markflow-manual-preview"><button type="button" class="markflow-manual-preview-button" onclick="window.__markflowRenderMermaidPreview && window.__markflowRenderMermaidPreview('${manualId}')">${runtimeSettings.manualRenderInlineLabel}</button><div class="markflow-manual-shortcut-hint">${runtimeSettings.manualRenderShortcutHint}</div></div>`);
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
    activeCrepe = crepe;
    markFlowStage("crepe:constructed");

    window.updateFromIntelliJ = (newMarkdown: string) => {
        markFlowStage("bridge:updateFromIntelliJ", newMarkdown.slice(0, 32));
        if (!isCrepeReady) {
            pendingMarkdownFromIntelliJ = newMarkdown;
            return;
        }

        beginExternalUpdateGuard();
        try {
            replaceEditorMarkdown(crepe, newMarkdown);
        } finally {
            clearExternalUpdateGuardLater();
        }
    };

    window.applyEditorStateFromIntelliJ = (state: EditorUiState) => {
        markFlowStage("bridge:applyEditorState", `${state.scrollTop},${state.cursorOffset}`);
        if (!isCrepeReady) {
            pendingEditorStateFromIntelliJ = state;
            return;
        }

        applyEditorUiState(crepe, state);
    };

    // 4) Propagate editor changes from webview to IntelliJ.
    crepe.on((listener) => {
        listener.markdownUpdated((_ctx, markdown, prevMarkdown) => {
            // Skip bridge callbacks while external updates are being applied.
            if (isUpdatingFromIntelliJ) return;

            if (markdown !== prevMarkdown) {
                sendToIntelliJ(markdown, captureEditorUiState(crepe));
            }
        });
    });

    markFlowStage("crepe:create:start");
    let createPromise: Promise<unknown>;
    try {
        createPromise = crepe.create();
    } catch (error) {
        logCrepeCreateFailure(error);
        return;
    }
    createPromise
        .then(() => {
            isCrepeReady = true;
            markFlowStage("crepe:create:done");
            installMarkdownPasteHandler(crepe);
            flushPendingIntelliJState(crepe);
            recoverEditorLayout("create:done");
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
        })
        .catch((error) => {
            logCrepeCreateFailure(error);
        });

    setTimeout(() => {
        if (!isCrepeReady) {
            markFlowStage("crepe:create:pending", "still waiting for editor readiness");
        }
    }, BOOT_READY_TIMEOUT_MS);

    markFlowStage("init:done");
}


initEditor();

