import { app } from "../state";
import { renderAllManualMermaidPreviews } from "../mermaid";

export function applyRuntimeUiSettings() {
    const domApp = document.getElementById("app");
    if (!domApp) return;
    domApp.setAttribute("data-katex-density", app.runtimeSettings.katexDisplayDensity);
}

export function ensureManualPreviewToolbar() {
    const existing = document.getElementById("markflow-manual-refresh");
    if (app.runtimeSettings.renderTriggerMode !== "MANUAL_REFRESH") {
        app.manualMermaidRenderers.clear();
        existing?.remove();
        return;
    }

    if (existing) {
        const existingButton = existing.querySelector<HTMLButtonElement>(".markflow-manual-refresh-button");
        if (existingButton) {
            existingButton.textContent = app.runtimeSettings.manualRenderToolbarLabel;
            existingButton.title = app.runtimeSettings.manualRenderShortcutHint;
        }
        return;
    }

    const toolbar = document.createElement("div");
    toolbar.id = "markflow-manual-refresh";
    toolbar.className = "markflow-manual-refresh-toolbar";

    const button = document.createElement("button");
    button.type = "button";
    button.className = "markflow-manual-refresh-button";
    button.textContent = app.runtimeSettings.manualRenderToolbarLabel;
    button.title = app.runtimeSettings.manualRenderShortcutHint;
    button.addEventListener("click", () => {
        renderAllManualMermaidPreviews();
    });

    toolbar.append(button);
    document.body.append(toolbar);
}

export function ensureShortcutConflictNotice() {
    const conflictNoticeId = "markflow-shortcut-conflict-notice";
    const existing = document.getElementById(conflictNoticeId);

    if (!app.runtimeSettings.forceRerenderShortcutEnabled || !app.runtimeSettings.shortcutConflictDetected) {
        existing?.remove();
        return;
    }

    if (existing) {
        return;
    }

    const notice = document.createElement("div");
    notice.id = conflictNoticeId;
    notice.className = "markflow-shortcut-conflict-notice";
    notice.innerHTML = `
        <div class="markflow-notice-content">
            <span class="markflow-notice-icon">⚠️</span>
            <span class="markflow-notice-text">${app.runtimeSettings.shortcutConflictMessage}</span>
        </div>
    `;

    const domApp = document.getElementById("app");
    if (domApp) {
        domApp.insertBefore(notice, domApp.firstChild);
    }
}
