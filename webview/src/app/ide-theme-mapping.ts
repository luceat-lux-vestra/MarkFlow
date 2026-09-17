import {adjustForContrast, mix} from "./color";
import type {IdeColors} from "./types";

type ResolvedIdeColors = {
    background: string;
    foreground: string;
    border: string;
};

const FALLBACK = {
    light: {background: "#ffffff", foreground: "#1e1e1e", border: "#d4d4d4"},
    dark: {background: "#1e1e1e", foreground: "#d4d4d4", border: "#3f3f3f"}
} as const;

const resolve = (ideColors: IdeColors, dark: boolean): ResolvedIdeColors => {
    const palette = FALLBACK[dark ? "dark" : "light"];
    return {
        background: ideColors.background ?? palette.background,
        foreground: ideColors.foreground ?? palette.foreground,
        border: ideColors.border ?? palette.border
    };
};

/** Build Mermaid theme variables from the stable IDE palette contract. */
export const buildIdeThemeVariables = (ideColors: IdeColors, dark: boolean): Record<string, string> => {
    const colors = resolve(ideColors, dark);
    const surface = mix(colors.background, dark ? "#000000" : "#ffffff", dark ? 0.08 : 0.05);
    return {
        background: colors.background,
        lineColor: adjustForContrast(colors.foreground, colors.background, 4.5),
        textColor: adjustForContrast(colors.foreground, colors.background, 4.5),
        primaryColor: surface,
        primaryTextColor: adjustForContrast(colors.foreground, surface, 4.5),
        nodeBorder: colors.border,
        mainBkg: surface,
        borderColor: colors.border
    };
};
