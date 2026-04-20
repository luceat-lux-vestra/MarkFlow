import { app } from "../state";
import { MANUAL_MERMAID_SHORTCUT_KEY } from "../state";

export function installForceRerenderShortcut(
    renderAllMermaidAndLatexPreviews: () => void,
): (() => void) {
    const handler = (event: KeyboardEvent) => {
        const isShortcut = (event.metaKey || event.ctrlKey)
            && event.altKey
            && event.shiftKey
            && event.key.toLowerCase() === MANUAL_MERMAID_SHORTCUT_KEY;

        if (!isShortcut) {
            return;
        }

        // Always allow Mermaid+LaTeX force re-render if shortcut is enabled
        if (!app.runtimeSettings.forceRerenderShortcutEnabled) {
            return;
        }

        event.preventDefault();
        renderAllMermaidAndLatexPreviews();
    };

    window.addEventListener("keydown", handler);

    return () => {
        window.removeEventListener("keydown", handler);
    };
}
