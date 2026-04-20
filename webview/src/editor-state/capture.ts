import { editorViewCtx } from "@milkdown/core";
import type { EditorUiState } from "../state";
import { app } from "../state";
import { emitToIntelliJLog } from "../bridge";

export function recoverEditorLayout(reason: string) {
    if (!app.activeCrepe || !app.isCrepeReady) {
        app.pendingLayoutRecovery = true;
        emitToIntelliJLog(`MARKFLOW_UI layout:queued reason=${reason}`);
        return;
    }

    emitToIntelliJLog(`MARKFLOW_UI layout:start reason=${reason}`);
    window.dispatchEvent(new Event("resize"));

    requestAnimationFrame(() => {
        if (!app.activeCrepe || !app.isCrepeReady) return;
        window.dispatchEvent(new Event("resize"));
        emitToIntelliJLog(`MARKFLOW_UI layout:done reason=${reason}`);
    });
}

export function clamp(value: number, min: number, max: number) {
    return Math.min(Math.max(value, min), max);
}

export function getScrollElement() {
    const app = document.getElementById("app");
    return (app ?? (document as unknown as { scrollingElement: Element | null }).scrollingElement) || document.documentElement;
}

export function captureEditorUiState(crepe: { editor?: any }): EditorUiState {
    let cursorOffset = -1;
    let selectionStart = -1;
    let selectionEnd = -1;

    try {
        crepe.editor?.action((ctx: any) => {
            const view = ctx.get(editorViewCtx);
            const selection = (view as any).state.selection;
            cursorOffset = selection.head;
            selectionStart = Math.min(selection.from, selection.to);
            selectionEnd = Math.max(selection.from, selection.to);
        });
    } catch { /* editorViewCtx not available yet */ }

    return {
        version: 1,
        scrollTop: getScrollElement().scrollTop,
        cursorOffset,
        selectionStart,
        selectionEnd,
    };
}
