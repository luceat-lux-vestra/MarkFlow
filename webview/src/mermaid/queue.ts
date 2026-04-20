import { app } from "../state";
import { emitToIntelliJLog, logMermaidTrace } from "../bridge";

export const registerMermaidPreviewRenderer = (applyPreview: (html: string) => void, renderNow: () => void) => {
    const existingId = app.mermaidPreviewIdByRenderer.get(applyPreview);
    const previewId = existingId ?? `mermaid-preview-${uid()}`;
    app.mermaidPreviewIdByRenderer.set(applyPreview, previewId);
    app.mermaidPreviewRenderers.set(previewId, () => {
        if (!app.activeCrepe || !app.isCrepeReady) {
            return;
        }
        renderNow();
    });
};

export const renderAllRegisteredMermaidPreviews = () => {
    Array.from(app.mermaidPreviewRenderers.values()).forEach((render) => render());
};

export const renderAllManualMermaidPreviews = () => {
    const renderers = Array.from(app.manualMermaidRenderers.values());
    app.manualMermaidRenderers.clear();
    renderers.forEach((render) => render());
};

export const renderAllMermaidAndLatexPreviews = () => {
    emitToIntelliJLog("MARKFLOW_UI forceRerender:triggered");
    renderAllManualMermaidPreviews();
    renderAllRegisteredMermaidPreviews();
    if (app.activeCrepe && app.isCrepeReady) {
        requestAnimationFrame(() => {
            if (!app.activeCrepe || !app.isCrepeReady) return;
            window.dispatchEvent(new Event("resize"));
            emitToIntelliJLog("MARKFLOW_UI forceRerender:done");
        });
        return;
    }
    app.pendingHostForceRerender = true;
    emitToIntelliJLog("MARKFLOW_UI forceRerender:queued");
};

export const triggerForceRerender = () => {
    renderAllMermaidAndLatexPreviews();
};

export const clearAllMermaidDebounceTimers = () => {
    app.allMermaidDebounceTimerIds.forEach((timerId) => window.clearTimeout(timerId));
    app.allMermaidDebounceTimerIds.clear();
};

export const clearMermaidLoadingWatchdog = (applyPreview: (html: string) => void) => {
    const timerId = app.mermaidLoadingWatchdogTimers.get(applyPreview);
    if (timerId !== undefined) {
        window.clearTimeout(timerId);
        app.mermaidLoadingWatchdogTimers.delete(applyPreview);
    }
};

export const invalidateMermaidPreviewLifecycle = (reason: string) => {
    app.mermaidPreviewEpoch += 1;
    app.mermaidRenderRequestId += 1;
    app.manualMermaidRenderers.clear();
    app.mermaidPreviewRenderers.clear();
    app.mermaidPreviewIdByRenderer = new WeakMap();
    clearAllMermaidDebounceTimers();
    app.mermaidRenderQueues = new WeakMap();
    emitToIntelliJLog(`MARKFLOW_UI mermaid:lifecycleInvalidated reason=${reason} epoch=${app.mermaidPreviewEpoch}`);
};

export const scheduleMermaidRender = (renderNow: () => void, applyPreviewKey?: (html: string) => void) => {
    if (app.runtimeSettings.renderTriggerMode === "LIVE") {
        logMermaidTrace("trigger live");
        renderNow();
        return;
    }

    if (app.runtimeSettings.renderTriggerMode === "DEBOUNCED") {
        logMermaidTrace(`trigger debounced ${app.runtimeSettings.renderDebounceMs}ms`);
        if (applyPreviewKey) {
            const previousTimerId = app.mermaidDebounceTimers.get(applyPreviewKey);
            if (previousTimerId !== undefined) {
                window.clearTimeout(previousTimerId);
                app.allMermaidDebounceTimerIds.delete(previousTimerId);
            }
        }
        const timerId = window.setTimeout(() => {
            app.allMermaidDebounceTimerIds.delete(timerId);
            if (applyPreviewKey) {
                app.mermaidDebounceTimers.delete(applyPreviewKey);
            }
            renderNow();
        }, app.runtimeSettings.renderDebounceMs);
        app.allMermaidDebounceTimerIds.add(timerId);
        if (applyPreviewKey) {
            app.mermaidDebounceTimers.set(applyPreviewKey, timerId);
        }
        return;
    }

    renderNow();
};

export const enqueueMermaidRender = (applyPreview: (html: string) => void, task: () => Promise<void>) => {
    const previousQueue = app.mermaidRenderQueues.get(applyPreview) ?? Promise.resolve();
    const nextQueue = previousQueue
        .catch(() => {})
        .then(task)
        .catch((error: unknown) => {
            const detail = error instanceof Error ? error.message : String(error);
            console.warn(`MARKFLOW_UI mermaid:queueFailure ${detail}`);
            emitToIntelliJLog(`MARKFLOW_UI mermaid:queueFailure ${detail}`);
        });
    app.mermaidRenderQueues.set(applyPreview, nextQueue);
};

const uid = () => Math.random().toString(36).substring(7);
