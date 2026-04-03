import {Crepe} from "@milkdown/crepe";
import {editorViewCtx, parserCtx} from "@milkdown/core";
import {TextSelection} from "@milkdown/prose/state";
import "@milkdown/crepe/theme/common/style.css";
import "@milkdown/crepe/theme/frame.css";
import mermaid from "mermaid";

// 🚩 무한루프 방지 플래그 (IntelliJ -> JS 주입 중일 때 true)
let isUpdatingFromIntelliJ = false;
let isCrepeReady = false;
let pendingMarkdownFromIntelliJ: string | null = null;
let pendingEditorStateFromIntelliJ: EditorUiState | null = null;

// Mermaid 다이어그램 고유 ID 생성기
const uid = () => Math.random().toString(36).substring(7);

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

const clamp = (value: number, min: number, max: number) => Math.min(Math.max(value, min), max);

const getScrollElement = () => {
    const app = document.getElementById("app");
    return app ?? document.scrollingElement ?? document.documentElement;
};

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
            setTimeout(() => {
                isUpdatingFromIntelliJ = false;
            }, 50);
        }
    }

    const pendingState = pendingEditorStateFromIntelliJ;
    pendingEditorStateFromIntelliJ = null;
    if (pendingState !== null) {
        markFlowStage("bridge:applyEditorState:flush", `${pendingState.scrollTop},${pendingState.cursorOffset}`);
        applyEditorUiState(crepe, pendingState);
    }
}

async function initEditor() {
    markFlowStage("init:start");
    if (!window.cefQuery) {
        markFlowStage("bridge:missing");
    }

    // 1. Mermaid 초기화
    mermaid.initialize({startOnLoad: false, theme: "default"});
    markFlowStage("mermaid:initialized");

    // 2. 초기 마크다운 데이터 로드 (Kotlin에서 주입해 준 전역 변수)
    const initialText = window.intelliJ_initialMarkdown || "# Welcome to MarkFlow Editor!";
    markFlowStage("initialText:ready", initialText.slice(0, 48));

    // 3. Crepe 에디터 인스턴스 생성
    const crepe = new Crepe({
        root: document.getElementById("app"),
        defaultValue: initialText,
        featureConfigs: {
            // Mermaid 다이어그램 실시간 렌더링 훅
            [Crepe.Feature.CodeMirror]: {
                renderPreview: (language, content, applyPreview) => {
                    if (language === "mermaid" && content.trim()) {
                        markFlowStage("mermaid:renderPreview", content.slice(0, 32));
                        const svgId = `mermaid-svg-${uid()}`;
                        mermaid.render(svgId, content)
                            .then((output) => {
                                applyPreview(output.svg);
                            })
                            .catch(() => {
                                applyPreview("<div class=\"mermaid-error\">Mermaid Syntax Error</div>");
                            });
                    }
                }
            }
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
            setTimeout(() => {
                isUpdatingFromIntelliJ = false;
            }, 50);
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

    // 4. [Web -> IntelliJ] 에디터 내용 변경 감지 (타이핑 시)
    crepe.on((listener) => {
        listener.markdownUpdated((_ctx, markdown, prevMarkdown) => {
            // 🔒 IntelliJ에서 외부 주입 중일 때는 다시 쏘지 않음 (무한루프 방지)
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
        console.error("MARKFLOW_UI crepe:create failed", error);
        showBootError("crepe:create", String(error));
        return;
    }
    createPromise
        .then(() => {
            isCrepeReady = true;
            markFlowStage("crepe:create:done");
            flushPendingIntelliJState(crepe);
        })
        .catch((error) => {
            console.error("MARKFLOW_UI crepe:create failed", error);
            showBootError("crepe:create", String(error));
        });

    setTimeout(() => {
        if (!isCrepeReady) {
            markFlowStage("crepe:create:pending", "still waiting for editor readiness");
        }
    }, 5000);

    markFlowStage("init:done");
}

function sanitizeUiState(uiState: EditorUiState): EditorUiState {
    return {
        version: Number.isFinite(uiState.version) ? uiState.version : 1,
        scrollTop: Number.isFinite(uiState.scrollTop) ? uiState.scrollTop : 0,
        cursorOffset: Number.isFinite(uiState.cursorOffset) ? uiState.cursorOffset : -1,
        selectionStart: Number.isFinite(uiState.selectionStart) ? uiState.selectionStart : -1,
        selectionEnd: Number.isFinite(uiState.selectionEnd) ? uiState.selectionEnd : -1
    };
}

// JCEF 브릿지를 통해 Kotlin으로 JSON 전송
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

initEditor();