import {editorViewCtx, parserCtx} from "@milkdown/core";
import {TextSelection} from "@milkdown/prose/state";
import type {Crepe} from "@milkdown/crepe";
import type {EditorUiState} from "./types";

export const clamp = (value: number, min: number, max: number) => Math.min(Math.max(value, min), max);

export const getScrollElement = () => {
    const app = document.getElementById("app");
    return app ?? document.scrollingElement ?? document.documentElement;
};

export const isEditorViewContextError = (error: unknown): boolean => {
    const message = error instanceof Error ? error.message : String(error);
    return message.includes('Context "editorView" not found');
};

export const logEditorViewContextError = (reason: string, error: unknown, emitToIntelliJLog: (message: string) => void) => {
    emitToIntelliJLog(`MARKFLOW_UI ${reason} editorView context missing: ${String(error)}`);
};

export const safeReadMarkdown = (
    crepe: Crepe,
    fallback: string,
    reason: string,
    emitToIntelliJLog: (message: string) => void
): string => {
    try {
        return crepe.getMarkdown();
    } catch (error) {
        emitToIntelliJLog(`MARKFLOW_UI markdown:read failed reason=${reason} error=${String(error)}`);
        if (isEditorViewContextError(error)) {
            logEditorViewContextError(`markdownRead:${reason}`, error, emitToIntelliJLog);
        }
        return fallback;
    }
};

export const recoverEditorLayout = (
    reason: string,
    isCrepeReady: boolean,
    activeCrepe: Crepe | null,
    emitToIntelliJLog: (message: string) => void
) => {
    if (!activeCrepe || !isCrepeReady) {
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

export const captureEditorUiState = (crepe: Crepe, emitToIntelliJLog: (message: string) => void): EditorUiState => {
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
};

export const applyEditorUiState = (
    crepe: Crepe,
    state: Partial<EditorUiState>,
    emitToIntelliJLog: (message: string) => void
) => {
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
            logEditorViewContextError("state:apply", error, emitToIntelliJLog);
        }
    }

    requestAnimationFrame(() => {
        getScrollElement().scrollTop = scrollTop;
    });
};

export const replaceEditorMarkdown = (
    crepe: Crepe,
    newMarkdown: string,
    emitToIntelliJLog: (message: string) => void,
    skipHistory = false
) => {
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
            logEditorViewContextError("markdown:replace", error, emitToIntelliJLog);
        }
    }
};
