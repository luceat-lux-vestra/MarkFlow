import type { EditorUiState } from "../state";
import { app } from "../state";

// JCEF bridge payload serialization.
export function sanitizeUiState(uiState: EditorUiState): EditorUiState {
    return {
        version: Number.isFinite(uiState.version) ? uiState.version : 1,
        scrollTop: Number.isFinite(uiState.scrollTop) ? uiState.scrollTop : 0,
        cursorOffset: Number.isFinite(uiState.cursorOffset) ? uiState.cursorOffset : -1,
        selectionStart: Number.isFinite(uiState.selectionStart) ? uiState.selectionStart : -1,
        selectionEnd: Number.isFinite(uiState.selectionEnd) ? uiState.selectionEnd : -1
    };
}

// Send JSON payloads to Kotlin via the JCEF bridge.
export function sendToIntelliJ(markdownText: string, uiState: EditorUiState) {
    if (!window.cefQuery) {
        return;
    }

    const safeState = sanitizeUiState(uiState);
    const sessionId = (window as any).__markflowSessionId;
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

// Diagnostics.
export function emitToIntelliJLog(message: string) {
    const logger = (window as any).markflowLog;
    if (typeof logger !== "function") return;
    try {
        logger(message);
    } catch {
        // Ignore diagnostics bridge failures so editor boot is unaffected.
    }
}

export function markFlowStage(stage: string, detail = "") {
    const message = detail ? `MARKFLOW_UI ${stage}: ${detail}` : `MARKFLOW_UI ${stage}`;
    console.info(message);
    emitToIntelliJLog(message);
    const app = document.getElementById("app");
    if (app) {
        app.setAttribute("data-markflow-stage", stage);
    }
}

export function logMermaidTrace(detail: string) {
    const line = `MARKFLOW_UI mermaid:${detail}`;
    console.info(line);
    emitToIntelliJLog(line);
}

export function isEditorViewContextError(error: unknown): boolean {
    const message = error instanceof Error ? error.message : String(error);
    return message.includes("Context \"editorView\" not found");
}

export function logEditorViewContextError(reason: string, error: unknown) {
    emitToIntelliJLog(`MARKFLOW_UI ${reason} editorView context missing: ${String(error)}`);
}

export function showBootError(stage: string, detail: string) {
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
}

export async function withTimeout<T>(promise: Promise<T>, timeoutMs: number): Promise<T> {
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
}

export function safeReadMarkdown(crepe: any, fallback: string, reason: string): string {
    try {
        return (crepe as any).getMarkdown();
    } catch (error) {
        emitToIntelliJLog(`MARKFLOW_UI markdown:read failed reason=${reason} error=${String(error)}`);
        if (isEditorViewContextError(error)) {
            logEditorViewContextError(`markdownRead:${reason}`, error);
        }
        return fallback;
    }
}

export function beginExternalUpdateGuard() {
    app.externalUpdateGuardToken += 1;
    app.isUpdatingFromIntelliJ = true;
}

export function clearExternalUpdateGuardLater() {
    const token = app.externalUpdateGuardToken;
    setTimeout(() => {
        if (token !== app.externalUpdateGuardToken) {
            return;
        }
        app.isUpdatingFromIntelliJ = false;
    }, 50);
}
