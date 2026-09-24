import {buildIdeThemeVariables} from "./ide-theme-mapping";
import {parseHex} from "./color";
import type {IdeColors, MarkFlowRuntimeSettings} from "./types";

export const DEFAULT_RUNTIME_SETTINGS: Required<MarkFlowRuntimeSettings> = {
    mermaidSizeMode: "FIT_TO_VIEWPORT",
    mermaidZoomPercent: 100,
    themeSource: "LIGHT",
    mermaidErrorDisplay: "INLINE_ERROR_BOX",
    katexDisplayDensity: "COMFORTABLE",
    mermaidSyntaxErrorMessage: "Mermaid Syntax Error",
    fontFamily: "",
    baseFontSizePx: 16,
    ideColorScheme: {},
    ideFontFamily: null,
    ideDark: false,
    settingsRevision: 1
};

const IDE_COLOR_KEYS = [
    "background",
    "foreground",
    "selectionBackground",
    "selectionForeground",
    "border"
] as const;

export const normalizeIdeColorScheme = (raw: IdeColors | null | undefined): IdeColors => {
    if (!raw) return {};
    return Object.fromEntries(
        IDE_COLOR_KEYS
            .filter((key) => typeof raw[key] === "string" && parseHex(raw[key]) !== null)
            .map((key) => [key, raw[key]])
    );
};

export const resolveRuntimeSettings = (raw: MarkFlowRuntimeSettings | undefined): Required<MarkFlowRuntimeSettings> => {
    const current: MarkFlowRuntimeSettings & {diagramSecurityLevel?: unknown; previewOnlyByDefault?: unknown} = {...(raw ?? {})};
    delete current.diagramSecurityLevel;
    delete current.previewOnlyByDefault;
    const merged: Required<MarkFlowRuntimeSettings> = {...DEFAULT_RUNTIME_SETTINGS, ...current};
    return {
        ...merged,
        ideColorScheme: normalizeIdeColorScheme(merged.ideColorScheme),
        mermaidZoomPercent: Math.min(Math.max(merged.mermaidZoomPercent, 50), 200)
    };
};

export const runtimeSettingsIdentity = (settings: Required<MarkFlowRuntimeSettings>): string => {
    const {settingsRevision: _settingsRevision, ideColorScheme, ideDark, ...stableSettings} = settings;
    return JSON.stringify({
        ...stableSettings,
        ideColorScheme: settings.themeSource === "IDE_SYNC" ? normalizeIdeColorScheme(ideColorScheme) : {},
        ideDark: settings.themeSource === "IDE_SYNC" ? Boolean(ideDark) : false
    });
};

export const resolveMermaidTheme = (settings: Required<MarkFlowRuntimeSettings>): "default" | "dark" => {
    if (settings.themeSource === "LIGHT") return "default";
    if (settings.themeSource === "DARK") return "dark";
    return settings.ideDark ? "dark" : "default";
};

const buildMermaidThemeVariables = (
    settings: Required<MarkFlowRuntimeSettings>,
    theme: "default" | "dark"
): Record<string, string> => {
    const ideColors = settings.ideColorScheme;
    if (settings.themeSource === "IDE_SYNC" && Object.keys(ideColors).length > 0) {
        return buildIdeThemeVariables(ideColors, settings.ideDark);
    }
    return theme === "dark"
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
};

export const createMermaidPreviewConfig = (settings: Required<MarkFlowRuntimeSettings>) => {
    const theme = resolveMermaidTheme(settings);
    const themeVariables = buildMermaidThemeVariables(settings, theme);
    const useMaxWidth = settings.mermaidSizeMode === "FIT_TO_VIEWPORT";
    return {
        startOnLoad: false,
        layout: "dagre" as const,
        look: "classic" as const,
        theme,
        themeVariables,
        securityLevel: "strict" as const,
        useMaxWidth,
        htmlLabels: false,
        flowchart: {htmlLabels: false, useMaxWidth},
        class: {htmlLabels: false, useMaxWidth},
        state: {htmlLabels: false, useMaxWidth},
        stateDiagram: {useMaxWidth},
        mindmap: {useMaxWidth},
        sequence: {useMaxWidth},
        sequenceDiagram: {useMaxWidth},
        gantt: {useMaxWidth},
        pie: {useMaxWidth},
        journey: {useMaxWidth},
        requirement: {useMaxWidth},
        requirementDiagram: {useMaxWidth},
        sankey: {useMaxWidth},
        block: {useMaxWidth},
        c4: {useMaxWidth},
        git: {useMaxWidth},
        gitGraph: {useMaxWidth},
        er: {useMaxWidth},
        erDiagram: {useMaxWidth},
        quadrantChart: {useMaxWidth},
        xychart: {useMaxWidth: true},
        timeline: {useMaxWidth},
        architecture: {useMaxWidth},
        kanban: {useMaxWidth},
        packet: {useMaxWidth},
        venn: {useMaxWidth},
        xyChart: {useMaxWidth: true}
    };
};
