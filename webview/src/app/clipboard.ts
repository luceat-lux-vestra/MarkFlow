import type {Crepe} from "@milkdown/crepe";
import {editorViewCtx, parserCtx} from "@milkdown/core";
import {Slice} from "@milkdown/prose/model";
import {isEditorViewContextError, logEditorViewContextError} from "./editor-state";

export const normalizeClipboardMarkdown = (text: string) => {
    return text.replace(/^\uFEFF/, "").replace(/\r\n?/g, "\n");
};

export const hasMarkdownTableStructure = (lines: string[]) => {
    const tableLikeLines = lines.filter((line) => /^\s*\|.*\|\s*$/.test(line));
    if (tableLikeLines.length < 2) return false;

    return lines.some((line) => /^\s*\|?\s*[:\-]{3,}(?:\s*\|\s*[:\-]{3,})+\s*\|?\s*$/.test(line));
};

export const looksLikeRawHtmlClipboard = (text: string) => {
    if (!/<\/?[A-Za-z][A-Za-z0-9-]*(?:\s[^<>]*)?>/m.test(text)) {
        return false;
    }

    if (/\bscript\b|\bstyle\b|\biframe\b|\bobject\b|\bembed\b/i.test(text)) {
        return true;
    }

    return /<\/?[A-Za-z][A-Za-z0-9-]*(?:\s[^<>]*)?>/m.test(text);
};

export const looksLikeMarkdownClipboard = (text: string) => {
    const normalized = normalizeClipboardMarkdown(text);
    const lines = normalized.split("\n");

    if (looksLikeRawHtmlClipboard(normalized)) return true;
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
};

export const getMarkdownClipboardText = (event: ClipboardEvent) => {
    const clipboardData = event.clipboardData;
    if (!clipboardData) return null;

    const markdownText = clipboardData.getData("text/markdown");
    if (markdownText.trim()) return normalizeClipboardMarkdown(markdownText);

    const plainText = clipboardData.getData("text/plain");
    if (!plainText.trim()) return null;

    const normalizedPlainText = normalizeClipboardMarkdown(plainText);
    return looksLikeMarkdownClipboard(normalizedPlainText) ? normalizedPlainText : null;
};

export const replaceSelectionWithMarkdown = (
    crepe: Crepe,
    markdownText: string,
    emitToIntelliJLog: (message: string) => void
) => {
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
            logEditorViewContextError("paste:replaceSelection", error, emitToIntelliJLog);
        }
    }
};

export const installMarkdownPasteHandler = (crepe: Crepe, emitToIntelliJLog: (message: string) => void) => {
    let removeMarkdownPasteHandler: (() => void) | null = null;

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
                replaceSelectionWithMarkdown(crepe, markdownText, emitToIntelliJLog);
            };

            view.dom.addEventListener("paste", handler, true);
            removeMarkdownPasteHandler = () => view.dom.removeEventListener("paste", handler, true);
        });
    } catch (error) {
        emitToIntelliJLog(`MARKFLOW_UI paste:install skipped ${String(error)}`);
        if (isEditorViewContextError(error)) {
            logEditorViewContextError("paste:install", error, emitToIntelliJLog);
        }
    }

    return () => {
        removeMarkdownPasteHandler?.();
        removeMarkdownPasteHandler = null;
    };
};
