import { app } from "../state";
import { clearMermaidLoadingWatchdog } from "./queue";
import { emitToIntelliJLog } from "../bridge";

export const wrapMermaidSvg = (svg: string) => {
    const isXyChartSvg = /xychart/i.test(svg);
    const sizeClassByMode: Record<string, string> = {
        FIT_TO_VIEWPORT: "fit-to-viewport",
        SHRINK_TO_FIT: "shrink-to-fit",
        ACTUAL_SIZE_SCROLL: "actual-size-scroll"
    };
    const sizeClass = sizeClassByMode[app.runtimeSettings.mermaidSizeMode] ?? "fit-to-viewport";
    const chartTypeClass = isXyChartSvg ? " markflow-mermaid-chart-xychart" : "";
    const zoomScale = app.runtimeSettings.mermaidZoomPercent / 100;
    return `<div class="markflow-mermaid-preview markflow-mermaid-size-${sizeClass}${chartTypeClass}" style="transform: scale(${zoomScale}); transform-origin: top left;">${svg}</div>`;
};

export const renderMermaidError = (applyPreview: (html: string) => void, error: unknown) => {
    clearMermaidLoadingWatchdog(applyPreview);
    console.error("MARKFLOW_UI mermaid:renderError", error);
    emitToIntelliJLog(`MARKFLOW_UI mermaid:renderError ${String(error)}`);
    if (app.runtimeSettings.mermaidErrorDisplay === "INLINE_ERROR_BOX") {
        applyPreview(`<div class="mermaid-error">${app.runtimeSettings.mermaidSyntaxErrorMessage}</div>`);
        return;
    }
    applyPreview("");
};
