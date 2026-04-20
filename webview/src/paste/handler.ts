import { editorViewCtx, parserCtx } from "@milkdown/core";
import { Slice } from "@milkdown/prose/model";
import { emitToIntelliJLog, isEditorViewContextError, logEditorViewContextError } from "../bridge";

export function normalizeClipboardMarkdown(text: string) {
    return text.replace(/^\uFEFF/, "").replace(/\r\n?/g, "\n");
}

export function hasMarkdownTableStructure(lines: string[]) {
    const tableLikeLines = lines.filter((line) => /^\s*\|.*\|\s*$/.test(line));
    if (tableLikeLines.length < 2) return false;

    return lines.some((line) => /^\s*\|?\s*[:\-]{3,}(?:\s*\|\s*[:\-]{3,})+\s*\|?\s*$/.test(line));
}

export function looksLikeMarkdownClipboard(text: string) {
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

export function getMarkdownClipboardText(event: ClipboardEvent) {
    const clipboardData = event.clipboardData;
    if (!clipboardData) return null;

    const markdownText = clipboardData.getData("text/markdown");
    if (markdownText.trim()) return normalizeClipboardMarkdown(markdownText);

    const plainText = clipboardData.getData("text/plain");
    if (!plainText.trim()) return null;

    const normalizedPlainText = normalizeClipboardMarkdown(plainText);
    return looksLikeMarkdownClipboard(normalizedPlainText) ? normalizedPlainText : null;
}

export function replaceSelectionWithMarkdown(crepe: { editor?: any }, markdownText: string) {
    try {
        crepe.editor?.action((ctx: any) => {
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

let removeMarkdownPasteHandler: (() => void) | null = null;

export function getRemoveMarkdownPasteHandler(): (() => void) | null {
    return removeMarkdownPasteHandler;
}

export function setRemoveMarkdownPasteHandler(handler: (() => void) | null): void {
    removeMarkdownPasteHandler = handler;
}

export function installMarkdownPasteHandler(crepe: { editor?: any }): (() => void) | undefined {
    if (typeof removeMarkdownPasteHandler === "function") {
        try {
            removeMarkdownPasteHandler();
        } catch (e) {} // ignore cleanup errors
    }

    let unbind: (() => void) | undefined;

    try {
        crepe.editor?.action((ctx: any) => {
            const view = ctx.get(editorViewCtx);

            unbind = () => {}; // placeholder for closure capture
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
            unbind = () => {
                try { view.dom.removeEventListener("paste", handler, true); } catch (e) {}
            };

            removeMarkdownPasteHandler = unbind;
        });
    } catch (error) {
        emitToIntelliJLog(`MARKFLOW_UI paste:install skipped ${String(error)}`);
        if (isEditorViewContextError(error)) {
            logEditorViewContextError("paste:install", error);
        }
    }

    return unbind;
}
