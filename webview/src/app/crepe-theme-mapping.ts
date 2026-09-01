/**
 * Maps the IDE's solid colors into Crepe's CSS variables and Mermaid's theme variables.
 *
 * The Kotlin backend provides an opaque color provider (background/foreground/selection/border). This
 * module derives a coherent light/dark palette so that text always meets WCAG AA against its
 * background, and wires those colors into both the Crepe editor and Mermaid diagrams.
 */

import {adjustForContrast, mix, readableTextColor} from "./color";
import type {IdeColors} from "./types";

type ResolvedIdeColors = {
    background: string;
    foreground: string;
    border: string;
    selectionBackground: string;
    selectionForeground: string;
};

/** Fallback palette when the backend snapshot is empty (keeps the webview usable pre-first-sync). */
const FALLBACK = {
    light: {
        background: "#ffffff",
        foreground: "#1e1e1e",
        border: "#d4d4d4",
        selectionBackground: "#add8e6"
    },
    dark: {
        background: "#1e1e1e",
        foreground: "#d4d4d4",
        border: "#3f3f3f",
        selectionBackground: "#264f7a"
    }
} as const;

const resolve = (ideColors: IdeColors, dark: boolean): ResolvedIdeColors => {
    const palette = FALLBACK[dark ? "dark" : "light"];
    return {
        background: ideColors.background ?? palette.background,
        foreground: ideColors.foreground ?? palette.foreground,
        border: ideColors.border ?? palette.border,
        selectionBackground: ideColors.selectionBackground ?? palette.selectionBackground,
        selectionForeground: ideColors.selectionForeground ?? (dark ? "#ffffff" : "#1e1e1e")
    };
};

/**
 * Direct IDE color -> Crepe variable mappings. Everything else is derived from these anchors so the
 * whole palette stays consistent.
 */
const DIRECT_MAPPINGS: ReadonlyArray<[keyof ResolvedIdeColors, string]> = [
    ["background", "--crepe-color-background"],
    ["foreground", "--crepe-color-on-background"],
    ["selectionBackground", "--crepe-color-selected"],
    ["border", "--crepe-color-outline"]
];

/**
 * Build the Crepe theme variables from the IDE colors. `dark` selects the light/dark derivation.
 * Text colors are contrast-guarded against their backgrounds (WCAG AA).
 */
export const mapIdeColorsToCrepeVars = (ideColors: IdeColors, dark: boolean): Record<string, string> => {
    const c = resolve(ideColors, dark);
    const {background: bg, foreground: fg} = c;

    const vars: Record<string, string> = {};
    for (const [key, cssVar] of DIRECT_MAPPINGS) {
        vars[cssVar] = c[key];
    }
    // Keep the captured foreground when it is already usable, but protect body text when an IDE
    // scheme supplies nearly identical foreground/background values.
    vars["--crepe-color-on-background"] = adjustForContrast(fg, bg, 4.5);

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
    vars["--crepe-color-on-inverse"] = adjustForContrast(bg, fg, 4.5);

    // Secondary/link accents are derived from the captured foreground and background. There is no
    // extra backend color key because the current MarkFlow UI does not need the entire IDE scheme.
    const accentSeed = mix(fg, dark ? "#ffffff" : "#000000", 0.25);
    const accent = adjustForContrast(accentSeed, bg, 4.5);
    vars["--crepe-color-primary"] = accent;
    const secondary = mix(accent, dark ? "#ffffff" : "#000000", 0.25);
    vars["--crepe-color-secondary"] = secondary;
    vars["--crepe-color-on-secondary"] = adjustForContrast(readableTextColor(secondary), secondary, 4.5);

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
