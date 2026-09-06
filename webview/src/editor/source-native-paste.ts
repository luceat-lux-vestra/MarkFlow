import {syntaxTree, syntaxTreeAvailable} from "@codemirror/language";
import {StateEffect, type EditorState} from "@codemirror/state";
import {EditorView} from "@codemirror/view";

const CODE_NODE_NAMES = new Set(["FencedCode", "CodeBlock"]);

/** Normalize only a newly inserted clipboard payload. Existing document source is never read here. */
export function normalizeSourceNativeClipboardMarkdown(text: string): string {
    return text.replace(/^\uFEFF/, "").replace(/\r\n?/g, "\n");
}

function hasMarkdownTableStructure(lines: readonly string[]): boolean {
    const tableLikeLines = lines.filter((line) => /^\s*\|.*\|\s*$/.test(line));
    if (tableLikeLines.length < 2) {
        return false;
    }
    return lines.some((line) => /^\s*\|?\s*[:\-]{3,}(?:\s*\|\s*[:\-]{3,})+\s*\|?\s*$/.test(line));
}

/**
 * Conservative routing only: true means plain clipboard text should use the Markdown-payload
 * insertion path. This is not Markdown/source reconstruction authority.
 */
export function looksLikeSourceNativeMarkdownClipboard(text: string): boolean {
    const normalized = normalizeSourceNativeClipboardMarkdown(text);
    const lines = normalized.split("\n");

    if (/<\/?[A-Za-z][A-Za-z0-9-]*(?:\s[^<>]*)?>/m.test(normalized)) return true;
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

export function sourceNativeClipboardPayload(clipboardData: DataTransfer | null): string | null {
    if (clipboardData === null) {
        return null;
    }

    try {
        const markdown = clipboardData.getData("text/markdown");
        if (markdown.trim().length > 0) {
            return normalizeSourceNativeClipboardMarkdown(markdown);
        }

        const plain = clipboardData.getData("text/plain");
        if (plain.trim().length === 0) {
            return null;
        }
        const normalizedPlain = normalizeSourceNativeClipboardMarkdown(plain);
        return looksLikeSourceNativeMarkdownClipboard(normalizedPlain) ? normalizedPlain : null;
    } catch (_error) {
        // Clipboard access may be denied by the browser/host. Leave the event to CodeMirror's
        // maintained default path rather than consuming it or fabricating source.
        return null;
    }
}

function hasCodeAncestor(node: ReturnType<ReturnType<typeof syntaxTree>["resolveInner"]>): boolean {
    for (let current: typeof node | null = node; current !== null; current = current.parent) {
        if (CODE_NODE_NAMES.has(current.name)) {
            return true;
        }
    }
    return false;
}

/**
 * Treat parser boundaries conservatively. A true result means the custom Markdown path must defer
 * to CodeMirror default paste: either the selection is ambiguous/multiple, parser coverage is not
 * yet proven through the selection, or the selection touches fenced/indented code.
 */
export function sourceNativeSelectionTouchesCode(state: EditorState): boolean {
    if (state.selection.ranges.length !== 1) {
        return true;
    }

    const selection = state.selection.main;
    if (state.doc.length > 0) {
        // syntaxTree(state) is explicitly allowed to be incomplete. Requiring one code unit beyond
        // the selection endpoint where possible makes both resolveInner side-bias checks below rely
        // only on parser-proven source. If coverage is not ready, default literal paste is safer.
        const requiredUpto = Math.min(state.doc.length, selection.to + 1);
        if (!syntaxTreeAvailable(state, requiredUpto)) {
            return true;
        }
    }

    const tree = syntaxTree(state);
    const endpoints = selection.empty ? [selection.from] : [selection.from, selection.to];
    for (const position of endpoints) {
        if (hasCodeAncestor(tree.resolveInner(position, -1)) || hasCodeAncestor(tree.resolveInner(position, 1))) {
            return true;
        }
    }

    if (selection.empty) {
        return false;
    }

    let overlapsCode = false;
    tree.iterate({
        from: selection.from,
        to: selection.to,
        enter(node) {
            if (CODE_NODE_NAMES.has(node.name) && node.from < selection.to && node.to > selection.from) {
                overlapsCode = true;
            }
        }
    });
    return overlapsCode;
}

const sourceNativeMarkdownPasteExtension = EditorView.domEventHandlers({
    paste(event, view) {
        if (view.state.selection.ranges.length !== 1 || sourceNativeSelectionTouchesCode(view.state)) {
            return false;
        }

        const payload = sourceNativeClipboardPayload(event.clipboardData);
        if (payload === null) {
            return false;
        }

        const selection = view.state.selection.main;
        event.preventDefault();
        view.dispatch({
            changes: {from: selection.from, to: selection.to, insert: payload},
            selection: {anchor: selection.from + payload.length},
            scrollIntoView: true
        });
        return true;
    }
});

/** Install one realm-local paste extension. Destroying the owning EditorView removes its handlers. */
export function installSourceNativeMarkdownPaste(view: EditorView): void {
    view.dispatch({effects: StateEffect.appendConfig.of(sourceNativeMarkdownPasteExtension)});
}
