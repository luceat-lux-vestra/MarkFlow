/**
 * Maps the IDE's solid colors into Crepe's CSS variables and Mermaid's theme variables.
 *
 * The Kotlin backend provides an opaque color provider (background/foreground/border/...). This
 * module derives a coherent light/dark palette so that text always meets WCAG AA against its
 * background, and wires those colors into both the Crepe editor and Mermaid diagrams.
 */

import {adjustForContrast, mix, readableTextColor} from "./color";

/** IDE color keys captured by the Kotlin backend (subset used here). */
export type IdeColors = {
    background?: string;
    foreground?: string;
    border?: string;
    textLink?: string;
    selectionBackground?: string;
    selectionForeground?: string;
};

/** Fallback palette when the backend snapshot is empty (keeps the webview usable pre-first-sync). */
const FALLBACK = {
    light: {
        background: "#ffffff",
        foreground: "#1e1e1e",
        border: "#d4d4d4",
        textLink: "#1565c0",
        selectionBackground: "#add8e6"
    },
    dark: {
        background: "#1e1e1e",
        foreground: "#d4d4d4",
        border: "#3f3f3f",
        textLink: "#6cb6ff",
        selectionBackground: "#264f7a"
    }
} as const;

const resolve = (ideColors: IdeColors, dark: boolean): Required<IdeColors> => {
    const palette = FALLBACK[dark ? "dark" : "light"];
    return {
        background: ideColors.background ?? palette.background,
        foreground: ideColors.foreground ?? palette.foreground,
        border: ideColors.border ?? palette.border,
        textLink: ideColors.textLink ?? palette.textLink,
        selectionBackground: ideColors.selectionBackground ?? palette.selectionBackground,
        selectionForeground: ideColors.selectionForeground ?? (dark ? "#ffffff" : "#1e1e1e")
    };
};

/**
 * Direct IDE color -> Crepe variable mappings. Everything else is derived from these anchors so the
 * whole palette stays consistent.
 */
const DIRECT_MAPPINGS: ReadonlyArray<[keyof Required<IdeColors>, string]> = [
    ["background", "--crepe-color-background"],
    ["foreground", "--crepe-color-on-background"],
    ["selectionBackground", "--crepe-color-selected"],
    ["border", "--crepe-color-outline"],
    ["textLink", "--crepe-color-primary"]
];

/**
 * Build the Crepe theme variables from the IDE colors. `dark` selects the light/dark derivation.
 * Text colors are contrast-guarded against their backgrounds (WCAG AA).
 */
export const mapIdeColorsToCrepeVars = (ideColors: IdeColors, dark: boolean): Record<string, string> => {
    const c = resolve(ideColors, dark);
    const {background: bg, foreground: fg, textLink: accent} = c;

    const vars: Record<string, string> = {};
    for (const [key, cssVar] of DIRECT_MAPPINGS) {
        vars[cssVar] = c[key];
    }

    // Surfaces are subtly shifted from the background so cards read as distinct planes.
    const surface = mix(bg, dark ? "#000000" : "#ffffff", dark ? 0.08 : 0.05);
    const surfaceLow = mix(bg, dark ? "#000000" : "#ffffff", dark ? 0.04 : 0.025);
    const hover = mix(bg, dark ? "#ffffff" : "#000000", 0.08);
    const inlineArea = mix(bg, dark ? "#ffffff" : "#000000", 0.12);

    vars["--crepe-color-surface"] = surface;
    vars["--crepe-color-surface-low"] = surfaceLow;
    vars["--crepe-color-on-surface"] = adjustForContrast(fg, surface, 4.5);
    vars["--crepe-color-on-surface-variant"] = adjustForContrast(mix(fg, dark ? "#ffffff" : "#000000", 0.3), surface, 4.5);
    vars["--crepe-color-hover"] = hover;
    vars["--crepe-color-inline-area"] = inlineArea;
    vars["--crepe-color-inline-code"] = adjustForContrast(mix(fg, dark ? "#ffffff" : "#000000", 0.25), inlineArea, 4.5);
    vars["--crepe-color-error"] = dark ? "#ef5350" : "#d32f2f";

    // Inverse surface: text on inverted background.
    vars["--crepe-color-inverse"] = fg;
    vars["--crepe-color-on-inverse"] = bg;

    // Secondary accent derived from the primary accent, with readable text.
    const secondary = mix(accent, dark ? "#ffffff" : "#000000", 0.25);
    vars["--crepe-color-secondary"] = secondary;
    vars["--crepe-color-on-secondary"] = readableTextColor(secondary);

    return vars;
};

/**
 * Build Mermaid flowchart theme variables from the IDE colors. Arrows use the foreground so they
 * contrast against the IDE background; nodes use a surface shade distinct from the page background.
 */
export const buildIdeThemeVariables = (ideColors: IdeColors, dark: boolean): Record<string, string> => {
    const c = resolve(ideColors, dark);
    const surface = mix(c.background, dark ? "#000000" : "#ffffff", dark ? 0.08 : 0.05);
    return {
        background: c.background,
        lineColor: adjustForContrast(c.foreground, c.background, 4.5),
        textColor: adjustForContrast(c.foreground, c.background, 4.5),
        primaryColor: surface,
        primaryTextColor: adjustForContrast(c.foreground, surface, 4.5),
        nodeBorder: c.border,
        mainBkg: surface,
        borderColor: c.border
    };
};
