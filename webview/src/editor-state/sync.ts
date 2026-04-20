import { editorViewCtx, parserCtx } from "@milkdown/core";
import { TextSelection } from "@milkdown/prose/state";
import type { EditorUiState } from "../state";
import { emitToIntelliJLog, isEditorViewContextError, logEditorViewContextError } from "../bridge";
import { app } from "../state";
import { clamp, getScrollElement } from "./capture";

export function applyEditorUiState(crepe: { editor?: any }, state: Partial<EditorUiState>) {
    const scrollTop = Math.max(0, state.scrollTop ?? 0);

    try {
        crepe.editor?.action((ctx: any) => {
            const view = ctx.get(editorViewCtx);
            const docSize = (view as any).state.doc.content.size;

            const fallbackCursor = state.cursorOffset ?? -1;
            const rawStart = state.selectionStart ?? fallbackCursor;
            const rawEnd = state.selectionEnd ?? fallbackCursor;

            if (rawStart == null || rawEnd == null || rawStart < 0 || rawEnd < 0) {
                return;
            }

            const start = clamp(rawStart, 0, docSize);
            const end = clamp(rawEnd, 0, docSize);

            view.state.tr.setSelection(TextSelection.create((view as any).state.doc, start, end));
            view.dispatch(view.state.tr);
            view.focus();
        });
    } catch (error) {
        emitToIntelliJLog(`state:apply skipped ${String(error)}`);
        if (isEditorViewContextError(error)) {
            logEditorViewContextError("state:apply", error);
        }
    }

    requestAnimationFrame(() => {
        getScrollElement().scrollTop = scrollTop;
    });
}

export function replaceEditorMarkdown(crepe: { editor?: any }, newMarkdown: string, skipHistory = false) {
    try {
        crepe.editor?.action((ctx: any) => {
            const view = ctx.get(editorViewCtx);
            const parser = ctx.get(parserCtx);

            const doc = parser(newMarkdown);
            if (!doc) return;

            const state = view.state as any;
            const tr = state.tr.replaceWith(0, (state.doc?.content ?? {}).size || 0, doc);
            if (skipHistory) {
                tr.setMeta("addToHistory", false);
            }
            view.dispatch(tr);
        });
    } catch (error) {
        emitToIntelliJLog(`markdown:replace skipped ${String(error)}`);
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
