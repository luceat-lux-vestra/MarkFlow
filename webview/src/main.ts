import {Crepe} from "@milkdown/crepe";
import {editorViewCtx, parserCtx} from "@milkdown/core";
import {Slice} from "@milkdown/prose/model";
import {TextSelection} from "@milkdown/prose/state";
import "@milkdown/crepe/theme/common/style.css";
import "@milkdown/crepe/theme/frame.css";
import "katex/dist/katex.min.css";
import mermaid from "mermaid";

// Shared editor state.
// Prevent feedback loops while applying external IntelliJ updates.
let isUpdatingFromIntelliJ = false;
let isCrepeReady = false;
let pendingMarkdownFromIntelliJ: string | null = null;
let pendingEditorStateFromIntelliJ: EditorUiState | null = null;
let removeMarkdownPasteHandler: (() => void) | null = null;
const EXTERNAL_UPDATE_GUARD_MS = 50;
const BOOT_READY_TIMEOUT_MS = 5000;

// Generate unique ids for Mermaid preview rendering.
const uid = () => Math.random().toString(36).substring(7);

const normalizePreviewSnippet = (value: string, maxLength = 160) => value.replace(/\s+/g, " ").trim().slice(0, maxLength);

const FLOWCHART_FALLBACK_STYLE = `<style>
  text, tspan, .nodeLabel, .edgeLabel, .label, .titleText {
    color: CanvasText !important;
    fill: CanvasText !important;
    opacity: 1 !important;
    visibility: visible !important;
    stroke: none !important;
  }
</style>`;

const isFlowchartSource = (source: string) => /^\s*flowchart\b/i.test(source);

function injectFlowchartFallback(svg: string) {
    if (!svg.includes("<svg")) return svg;

    return svg.replace(/<svg\b([^>]*)>/i, (match) => `${match}${FLOWCHART_FALLBACK_STYLE}`);
}

function logFlowchartLiveDiagnostics(svgId: string) {
    const svg = document.getElementById(svgId) as SVGSVGElement | null;
    if (!svg) {
        emitToIntelliJLog(`MARKFLOW_UI mermaid:liveDiagnostics missingSvg ${svgId}`);
        return;
    }

    const firstNodeLabel = svg.querySelector(".nodeLabel") as Element | null;
    const firstEdgeLabel = svg.querySelector(".edgeLabel") as Element | null;
    const nodeStyles = firstNodeLabel ? getComputedStyle(firstNodeLabel) : null;
    const edgeStyles = firstEdgeLabel ? getComputedStyle(firstEdgeLabel) : null;

    const payload = {
        svgId,
        svgClass: svg.getAttribute("class") ?? "",
        svgWidth: svg.getAttribute("width") ?? "",
        svgHeight: svg.getAttribute("height") ?? "",
        nodeLabelTag: firstNodeLabel?.tagName ?? "",
        nodeLabelText: firstNodeLabel?.textContent?.trim().slice(0, 120) ?? "",
        nodeLabelFill: nodeStyles?.fill ?? "",
        nodeLabelColor: nodeStyles?.color ?? "",
        nodeLabelOpacity: nodeStyles?.opacity ?? "",
        nodeLabelVisibility: nodeStyles?.visibility ?? "",
        edgeLabelTag: firstEdgeLabel?.tagName ?? "",
        edgeLabelText: firstEdgeLabel?.textContent?.trim().slice(0, 120) ?? "",
        edgeLabelFill: edgeStyles?.fill ?? "",
        edgeLabelColor: edgeStyles?.color ?? "",
        edgeLabelOpacity: edgeStyles?.opacity ?? "",
        edgeLabelVisibility: edgeStyles?.visibility ?? ""
    };

    emitToIntelliJLog(`MARKFLOW_UI mermaid:liveDiagnostics ${JSON.stringify(payload)}`);
}

function logMermaidPreviewDiagnostics(svgId: string, language: string, source: string, svg: string) {
    const parser = new DOMParser();
    const parsed = parser.parseFromString(svg, "image/svg+xml");
    const root = parsed.documentElement;
    const isParserError = root.tagName.toLowerCase() === "parsererror";

    const counts = {
        text: root.querySelectorAll("text").length,
        tspan: root.querySelectorAll("tspan").length,
        foreignObject: root.getElementsByTagName("foreignObject").length,
        nodeLabel: root.querySelectorAll(".nodeLabel").length,
        edgeLabel: root.querySelectorAll(".edgeLabel").length,
        label: root.querySelectorAll(".label").length,
        titleText: root.querySelectorAll(".titleText").length
    };

    const firstNodeLabel = root.querySelector(".nodeLabel");
    const firstEdgeLabel = root.querySelector(".edgeLabel");

    const summary = {
        svgId,
        language,
        isFlowchart: isFlowchartSource(source),
        sourceLength: source.length,
        svgLength: svg.length,
        rootTag: root.tagName,
        rootClass: root.getAttribute("class") ?? "",
        isParserError,
        counts,
        firstText: root.querySelector("text")?.outerHTML?.slice(0, 200) ?? "",
        firstForeignObject: root.getElementsByTagName("foreignObject")[0]?.outerHTML?.slice(0, 200) ?? "",
        firstNodeLabel: firstNodeLabel?.outerHTML?.slice(0, 240) ?? "",
        firstNodeLabelText: firstNodeLabel?.textContent?.trim().slice(0, 120) ?? "",
        firstEdgeLabel: firstEdgeLabel?.outerHTML?.slice(0, 240) ?? "",
        firstEdgeLabelText: firstEdgeLabel?.textContent?.trim().slice(0, 120) ?? "",
        snippet: normalizePreviewSnippet(svg)
    };

    const serialized = JSON.stringify(summary);
    console.info(`MARKFLOW_UI mermaid:diagnostics ${serialized}`);
    emitToIntelliJLog(`MARKFLOW_UI mermaid:diagnostics ${serialized}`);
}

// Keep Mermaid preview rendering readable across SVG- and HTML-label based diagram families.
const MERMAID_PREVIEW_CONFIG = {
    startOnLoad: false,
    theme: "default",
    securityLevel: "strict",
    htmlLabels: false,
    flowchart: {
        htmlLabels: false,
        useMaxWidth: true
    },
    class: {
        htmlLabels: false,
        useMaxWidth: true
    },
    state: {
        htmlLabels: false,
        useMaxWidth: true
    },
    mindmap: {
        useMaxWidth: true
    }
} as const;

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

const markFlowStage = (stage: string, detail = "") => {
    const message = detail ? `MARKFLOW_UI ${stage}: ${detail}` : `MARKFLOW_UI ${stage}`;
    console.info(message);
    emitToIntelliJLog(message);
    const app = document.getElementById("app");
    if (app) {
        app.setAttribute("data-markflow-stage", stage);
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

function clearExternalUpdateGuardLater() {
    setTimeout(() => {
        isUpdatingFromIntelliJ = false;
    }, EXTERNAL_UPDATE_GUARD_MS);
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

function replaceEditorMarkdown(crepe: Crepe, newMarkdown: string) {
    crepe.editor.action((ctx) => {
        const view = ctx.get(editorViewCtx);
        const parser = ctx.get(parserCtx);

        const doc = parser(newMarkdown);
        if (!doc) return;

        const state = view.state;
        view.dispatch(state.tr.replaceWith(0, state.doc.content.size, doc));
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
        isUpdatingFromIntelliJ = true;
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
    if (!window.cefQuery) {
        markFlowStage("bridge:missing");
    }

    // 1) Initialize Mermaid.
    mermaid.initialize(MERMAID_PREVIEW_CONFIG);
    markFlowStage("mermaid:initialized");

    // 2) Load initial markdown injected by Kotlin.
    const initialText = window.intelliJ_initialMarkdown || "# Welcome to MarkFlow Editor!";
    markFlowStage("initialText:ready", initialText.slice(0, 48));

    // 3) Create the Crepe editor instance.
    const crepe = new Crepe({
        root: document.getElementById("app"),
        defaultValue: initialText,
        featureConfigs: {
            // Hook for live Mermaid preview rendering.
            [Crepe.Feature.CodeMirror]: {
                renderPreview: (language, content, applyPreview) => {
                    if (language === "mermaid" && content.trim()) {
                        markFlowStage("mermaid:renderPreview", normalizePreviewSnippet(content, 32));
                        const svgId = `mermaid-svg-${uid()}`;
                        mermaid.render(svgId, content)
                            .then((output) => {
                                const renderedSvg = injectFlowchartFallback(output.svg);
                                logMermaidPreviewDiagnostics(svgId, language, content, renderedSvg);
                                applyPreview(renderedSvg);
                                if (isFlowchartSource(content)) {
                                    requestAnimationFrame(() => logFlowchartLiveDiagnostics(svgId));
                                }
                            })
                            .catch((error) => {
                                console.error("MARKFLOW_UI mermaid:renderError", error);
                                emitToIntelliJLog(`MARKFLOW_UI mermaid:renderError ${String(error)}`);
                                applyPreview("<div class=\"mermaid-error\">Mermaid Syntax Error</div>");
                            });
                    }
                }
            },
            [Crepe.Feature.Latex]: {}
        }
    });
    markFlowStage("crepe:constructed");

    window.updateFromIntelliJ = (newMarkdown: string) => {
        markFlowStage("bridge:updateFromIntelliJ", newMarkdown.slice(0, 32));
        if (!isCrepeReady) {
            pendingMarkdownFromIntelliJ = newMarkdown;
            return;
        }

        isUpdatingFromIntelliJ = true;
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