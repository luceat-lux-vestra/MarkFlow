import mermaid from "mermaid";
import { app } from "../state";
import { emitToIntelliJLog } from "../bridge";

export const resolveMermaidTheme = (): "default" | "dark" => {
    if (app.runtimeSettings.themeSource === "LIGHT") return "default";
    if (app.runtimeSettings.themeSource === "DARK") return "dark";
    return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "default";
};

const resolveDiagramSecurityLevel = (): "strict" | "loose" => {
    return app.runtimeSettings.diagramSecurityLevel === "LOOSE" ? "loose" : "strict";
};

export const createMermaidPreviewConfig = () => {
    const theme = resolveMermaidTheme();
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

    const useMaxWidth = app.runtimeSettings.mermaidSizeMode === "FIT_TO_VIEWPORT";

    return {
        startOnLoad: false,
        theme,
        themeVariables,
        securityLevel: resolveDiagramSecurityLevel(),
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

export const reconfigureMermaid = () => {
    const nextConfig = createMermaidPreviewConfig();
    emitToIntelliJLog(
        `MARKFLOW_UI mermaid:initialize theme=${nextConfig.theme} security=${nextConfig.securityLevel}`
    );
    mermaid.initialize(nextConfig);
};
