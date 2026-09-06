import {StateEffect} from "@codemirror/state";
import {EditorView, ViewPlugin} from "@codemirror/view";

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

class SourceNativeNavigationGuard {
    private readonly root: Element;

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

    constructor(root: Element) {
        this.root = root;
        this.root.addEventListener("click", this.onClick, true);
        this.root.addEventListener("auxclick", this.onAuxClick, true);
        this.root.addEventListener("keydown", this.onKeyDown, true);
        this.root.addEventListener("submit", this.onSubmit, true);
    }

    destroy(): void {
        this.root.removeEventListener("click", this.onClick, true);
        this.root.removeEventListener("auxclick", this.onAuxClick, true);
        this.root.removeEventListener("keydown", this.onKeyDown, true);
        this.root.removeEventListener("submit", this.onSubmit, true);
    }
}

function sourceNativeNavigationGuardExtension(root: Element) {
    return ViewPlugin.define(() => new SourceNativeNavigationGuard(root));
}

/**
 * Install one source-native-root navigation guard whose listener lifetime is owned by `view`.
 * Destroying the EditorView destroys the plugin and removes every root listener.
 */
export function installSourceNativeNavigationGuard(view: EditorView, root: Element = view.dom): void {
    view.dispatch({effects: StateEffect.appendConfig.of(sourceNativeNavigationGuardExtension(root))});
}
