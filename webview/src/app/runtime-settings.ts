import type {MarkFlowRuntimeSettings} from "./types";

export const DEFAULT_RUNTIME_SETTINGS: Required<MarkFlowRuntimeSettings> = {
    mermaidSizeMode: "FIT_TO_VIEWPORT",
    mermaidZoomPercent: 100,
    themeSource: "LIGHT",
    mermaidErrorDisplay: "INLINE_ERROR_BOX",
    katexDisplayDensity: "COMFORTABLE",
    diagramSecurityLevel: "STRICT",
    previewOnlyByDefault: true,
    mermaidSyntaxErrorMessage: "Mermaid Syntax Error",
    settingsRevision: 1
};

export const resolveRuntimeSettings = (raw: MarkFlowRuntimeSettings | undefined): Required<MarkFlowRuntimeSettings> => {
    const overrides: MarkFlowRuntimeSettings = raw ?? {};
    const merged: Required<MarkFlowRuntimeSettings> = {...DEFAULT_RUNTIME_SETTINGS, ...overrides};
    return {
        ...merged,
        mermaidZoomPercent: Math.min(Math.max(merged.mermaidZoomPercent, 50), 200)
    };
};

export const resolveMermaidTheme = (runtimeSettings: Required<MarkFlowRuntimeSettings>): "default" | "dark" => {
    if (runtimeSettings.themeSource === "LIGHT") return "default";
    if (runtimeSettings.themeSource === "DARK") return "dark";
    return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "default";
};

export const resolveDiagramSecurityLevel = (runtimeSettings: Required<MarkFlowRuntimeSettings>): "strict" | "loose" => {
    return runtimeSettings.diagramSecurityLevel === "LOOSE" ? "loose" : "strict";
};

export const createMermaidPreviewConfig = (runtimeSettings: Required<MarkFlowRuntimeSettings>) => {
    const theme = resolveMermaidTheme(runtimeSettings);
    const themeVariables = theme === "dark"
        ? {
            primaryColor: "#1f2937",
            primaryTextColor: "#f9fafb",
            lineColor: "#f9fafb",
            textColor: "#f9fafb",
            background: "#111827"
        }
        : {
            primaryColor: "#e5e7eb",
            primaryTextColor: "#111827",
            lineColor: "#111827",
            textColor: "#111827",
            background: "#ffffff"
        };

    const useMaxWidth = runtimeSettings.mermaidSizeMode === "FIT_TO_VIEWPORT";

    return {
        startOnLoad: false,
        theme,
        themeVariables,
        securityLevel: resolveDiagramSecurityLevel(runtimeSettings),
        useMaxWidth,
        htmlLabels: false,
        flowchart: {
            htmlLabels: false,
            useMaxWidth
        },
        class: {
            htmlLabels: false,
            useMaxWidth
        },
        state: {
            htmlLabels: false,
            useMaxWidth
        },
        stateDiagram: {
            useMaxWidth
        },
        mindmap: {
            useMaxWidth
        },
        sequence: {
            useMaxWidth
        },
        sequenceDiagram: {
            useMaxWidth
        },
        gantt: {
            useMaxWidth
        },
        pie: {
            useMaxWidth
        },
        journey: {
            useMaxWidth
        },
        requirement: {
            useMaxWidth
        },
        requirementDiagram: {
            useMaxWidth
        },
        sankey: {
            useMaxWidth
        },
        block: {
            useMaxWidth
        },
        c4: {
            useMaxWidth
        },
        git: {
            useMaxWidth
        },
        gitGraph: {
            useMaxWidth
        },
        er: {
            useMaxWidth
        },
        erDiagram: {
            useMaxWidth
        },
        quadrantChart: {
            useMaxWidth
        },
        xychart: {
            useMaxWidth: true
        },
        timeline: {
            useMaxWidth
        },
        architecture: {
            useMaxWidth
        },
        kanban: {
            useMaxWidth
        },
        packet: {
            useMaxWidth
        },
        venn: {
            useMaxWidth
        },
        xyChart: {
            useMaxWidth: true
        }
    };
};

export const applyRuntimeUiSettings = (runtimeSettings: Required<MarkFlowRuntimeSettings>) => {
    const app = document.getElementById("app");
    if (!app) return;
    app.setAttribute("data-katex-density", runtimeSettings.katexDisplayDensity);
};

export const logThemeDiagnostics = (
    raw: MarkFlowRuntimeSettings | undefined,
    runtimeSettings: Required<MarkFlowRuntimeSettings>,
    appliedTheme: "default" | "dark",
    emitToIntelliJLog: (message: string) => void
) => {
    const payload = {
        source: raw?.themeSource ?? "<undefined>",
        resolvedSource: runtimeSettings.themeSource,
        appliedTheme,
        securityLevel: runtimeSettings.diagramSecurityLevel
    };
    emitToIntelliJLog(`MARKFLOW_UI theme:settings ${JSON.stringify(payload)}`);
};
