import {syntaxTree} from "@codemirror/language";
import {StateEffect} from "@codemirror/state";
import type {EditorState} from "@codemirror/state";
import {EditorView, ViewPlugin} from "@codemirror/view";

const MAX_EXTERNAL_NAVIGATION_URL_LENGTH = 4096;
const ASCII_CONTROL_OR_SPACE = /[\u0000-\u0020\u007f]/u;
const POTENTIAL_MARKDOWN_CHARACTER_REFERENCE = /&(?:#[0-9]+|#[xX][0-9A-Fa-f]+|[A-Za-z][A-Za-z0-9]+);/u;

function eventTargetElement(event: Event): Element | null {
    const target = event.target;
    if (target instanceof Element) {
        return target;
    }
    return target instanceof Node ? target.parentElement : null;
}

function navigationAnchor(root: Element, event: Event): Element | null {
    const target = eventTargetElement(event);
    if (target === null || !root.contains(target)) {
        return null;
    }
    const anchor = target.closest("a[href], area[href]");
    return anchor !== null && root.contains(anchor) ? anchor : null;
}

function guardedForm(root: Element, event: Event): HTMLFormElement | null {
    const target = event.target;
    return target instanceof HTMLFormElement && root.contains(target) ? target : null;
}

/**
 * Browser-side navigation classification is deliberately narrower than resource classification.
 * It grants no capability by itself; the host independently validates the returned value again.
 */
export function resolveSourceNativeExternalNavigationUrl(rawUrl: string): string | null {
    if (rawUrl.length === 0
        || rawUrl.length > MAX_EXTERNAL_NAVIGATION_URL_LENGTH
        || rawUrl.trim() !== rawUrl
        || ASCII_CONTROL_OR_SPACE.test(rawUrl)
        || rawUrl.includes("\\")) {
        return null;
    }

    try {
        const parsed = new URL(rawUrl);
        if ((parsed.protocol !== "http:" && parsed.protocol !== "https:")
            || parsed.hostname.length === 0
            || parsed.username.length > 0
            || parsed.password.length > 0) {
            return null;
        }
        // Validation must not rewrite the already-decoded Markdown destination. The host validates
        // this exact value again before invoking the platform browser API.
        return rawUrl;
    } catch (_error) {
        return null;
    }
}

function isAsciiPunctuation(value: string): boolean {
    const code = value.charCodeAt(0);
    return (code >= 0x21 && code <= 0x2f)
        || (code >= 0x3a && code <= 0x40)
        || (code >= 0x5b && code <= 0x60)
        || (code >= 0x7b && code <= 0x7e);
}

/**
 * Convert the parser-owned CommonMark destination token into its URI value without touching source.
 * Lezer's URL range retains optional `<...>` delimiters and source backslash escapes. CommonMark
 * removes the delimiters and applies backslash escapes only to ASCII punctuation. Percent-encoded
 * bytes are deliberately left byte-for-byte intact.
 *
 * CommonMark character references require a separate HTML-entity decode step. This slice does not
 * add such a decoder to the trust boundary, so potential character-reference syntax fails closed
 * instead of opening a URL whose meaning differs from the Markdown destination.
 */
export function decodeParserOwnedMarkdownLinkDestination(source: string): string | null {
    let value = source;
    if (value.startsWith("<")) {
        if (!value.endsWith(">") || value.length < 2) {
            return null;
        }
        value = value.slice(1, -1);
    }
    if (POTENTIAL_MARKDOWN_CHARACTER_REFERENCE.test(value)) {
        return null;
    }

    let decoded = "";
    for (let index = 0; index < value.length; index += 1) {
        const current = value[index];
        const next = value[index + 1];
        if (current === "\\" && next !== undefined && isAsciiPunctuation(next)) {
            decoded += next;
            index += 1;
        } else {
            decoded += current;
        }
    }
    return decoded;
}

function selectionTouchesRange(state: EditorState, from: number, to: number): boolean {
    return state.selection.ranges.some((selection) => {
        if (selection.empty) {
            return selection.from >= from && selection.from <= to;
        }
        return selection.from < to && selection.to > from;
    });
}

/**
 * Resolve an external-navigation candidate only from the parser-owned ordinary Markdown Link node
 * at [position]. Image/raw/generated DOM and link-like plain/code text have no path through here.
 */
export function externalNavigationTargetAt(state: EditorState, position: number): string | null {
    if (!Number.isSafeInteger(position) || position < 0 || position > state.doc.length) {
        return null;
    }

    let node = syntaxTree(state).resolveInner(position, 1);
    while (node.name !== "Link") {
        const parent = node.parent;
        if (parent === null) {
            return null;
        }
        node = parent;
    }
    if (selectionTouchesRange(state, node.from, node.to)) {
        return null;
    }

    const urlNode = node.getChild("URL");
    if (urlNode === null || urlNode.from < node.from || urlNode.to > node.to) {
        return null;
    }
    const destination = decodeParserOwnedMarkdownLinkDestination(state.sliceDoc(urlNode.from, urlNode.to));
    return destination === null ? null : resolveSourceNativeExternalNavigationUrl(destination);
}

function isExplicitExternalNavigationGesture(event: MouseEvent): boolean {
    return event.button === 0
        && (event.ctrlKey || event.metaKey)
        && !event.altKey
        && !event.shiftKey;
}

class SourceNativeNavigationGuard {
    private readonly view: EditorView;
    private readonly root: Element;
    private readonly onExternalNavigation: ((url: string) => void) | undefined;

    private readonly onMouseDown = (event: Event): void => {
        if (!(event instanceof MouseEvent) || !isExplicitExternalNavigationGesture(event)) {
            return;
        }
        const targetElement = eventTargetElement(event);
        if (targetElement === null || !this.view.contentDOM.contains(targetElement)) {
            return;
        }
        const position = this.view.posAtCoords({x: event.clientX, y: event.clientY});
        if (position === null) {
            return;
        }
        const target = externalNavigationTargetAt(this.view.state, position);
        if (target === null || this.onExternalNavigation === undefined) {
            return;
        }

        // The approved action belongs to the host. Never allow the same gesture to become an
        // embedded browser navigation or a source-editing side effect.
        event.preventDefault();
        event.stopPropagation();
        this.onExternalNavigation(target);
    };

    private readonly onClick = (event: Event): void => {
        if (navigationAnchor(this.root, event) !== null) {
            event.preventDefault();
        }
    };

    private readonly onAuxClick = (event: Event): void => {
        if (navigationAnchor(this.root, event) !== null) {
            event.preventDefault();
        }
    };

    private readonly onKeyDown = (event: Event): void => {
        if (event instanceof KeyboardEvent
            && event.key === "Enter"
            && navigationAnchor(this.root, event) !== null) {
            event.preventDefault();
        }
    };

    private readonly onSubmit = (event: Event): void => {
        if (guardedForm(this.root, event) !== null) {
            event.preventDefault();
        }
    };

    constructor(view: EditorView, root: Element, onExternalNavigation?: (url: string) => void) {
        this.view = view;
        this.root = root;
        this.onExternalNavigation = onExternalNavigation;
        this.root.addEventListener("mousedown", this.onMouseDown, true);
        this.root.addEventListener("click", this.onClick, true);
        this.root.addEventListener("auxclick", this.onAuxClick, true);
        this.root.addEventListener("keydown", this.onKeyDown, true);
        this.root.addEventListener("submit", this.onSubmit, true);
    }

    destroy(): void {
        this.root.removeEventListener("mousedown", this.onMouseDown, true);
        this.root.removeEventListener("click", this.onClick, true);
        this.root.removeEventListener("auxclick", this.onAuxClick, true);
        this.root.removeEventListener("keydown", this.onKeyDown, true);
        this.root.removeEventListener("submit", this.onSubmit, true);
    }
}

function sourceNativeNavigationGuardExtension(
    root: Element,
    onExternalNavigation?: (url: string) => void
) {
    return ViewPlugin.define((view) => new SourceNativeNavigationGuard(view, root, onExternalNavigation));
}

/**
 * Install one source-native-root navigation guard whose listener lifetime is owned by `view`.
 * Destroying the EditorView destroys the plugin and removes every root listener.
 */
export function installSourceNativeNavigationGuard(
    view: EditorView,
    root: Element = view.dom,
    onExternalNavigation?: (url: string) => void
): void {
    view.dispatch({
        effects: StateEffect.appendConfig.of(sourceNativeNavigationGuardExtension(root, onExternalNavigation))
    });
}
