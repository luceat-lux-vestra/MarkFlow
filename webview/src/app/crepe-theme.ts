import {adjustForContrast, mix} from "./color";
import {mapIdeColorsToCrepeVars} from "./crepe-theme-mapping";
import type {MarkFlowRuntimeSettings} from "./types";

/**
 * Applies the active theme + typography to the Crepe editor at runtime.
 *
 * The bundled Crepe theme defines its design-system variables on the `.milkdown`
 * root (see @milkdown/crepe theme/crepe/style.css and theme/crepe-dark/style.css).
 * Those files are imported at build time only for structural CSS; the actual color
 * variables are injected here so LIGHT / DARK / IDE_SYNC can be swapped without a
 * reload. Overriding with `!important` wins over the imported class rules and any
 * inline values Crepe may set on its own `.milkdown` node.
 *
 * Single source of truth for the resolved theme kind lives in the resolved
 * `settings.themeSource` (IDE_SYNC / LIGHT / DARK), which the Kotlin backend
 * resolves before sending.
 */

export const DEFAULT_BASE_FONT_SIZE_PX = 16;
export const BASE_FONT_SIZE_MIN = 10;
export const BASE_FONT_SIZE_MAX = 32;

const CREPE_THEME_STYLE_ID = "markflow-crepe-theme";

/** Generic CSS family keywords accepted verbatim (case-insensitive). */
const GENERIC_FAMILIES = new Set(["serif", "sans-serif", "monospace", "cursive", "fantasy", "system-ui"]);

/**
 * Quote a single font family for use in a CSS `font-family` value. Generic families stay bare;
 * named families with spaces or non-identifier characters are wrapped in double quotes. Any embedded
 * quotes are stripped so a persisted family name can never break out of the value.
 */
const quoteFontFamily = (family: string): string => {
    const trimmed = family.trim();
    if (!trimmed) {
        return trimmed;
    }
    if (GENERIC_FAMILIES.has(trimmed.toLowerCase())) {
        return trimmed;
    }
    return /[^\w-]/.test(trimmed) ? `"${trimmed.replace(/["']/g, "")}"` : trimmed;
};

/**
 * The 22 Crepe design-system variables, per bundled theme. Source of truth is the
 * bundled CSS; keep these in lockstep with @milkdown/crepe theme/crepe/style.css
 * (light) and theme/crepe-dark/style.css (dark). A regression test asserts the
 * variable *names* match so drift is caught early.
 */

export const LIGHT_CREPE_VARS: Record<string, string> = {
    "--crepe-color-background": "#fffdfb",
    "--crepe-color-on-background": "#1f1b16",
    "--crepe-color-surface": "#fff8f4",
    "--crepe-color-surface-low": "#fff1e5",
    "--crepe-color-on-surface": "#201b13",
    "--crepe-color-on-surface-variant": "#4f4539",
    "--crepe-color-outline": "#817567",
    "--crepe-color-primary": "#805610",
    "--crepe-color-secondary": "#fbdebc",
    "--crepe-color-on-secondary": "#271904",
    "--crepe-color-inverse": "#362f27",
    "--crepe-color-on-inverse": "#fcefe2",
    "--crepe-color-inline-code": "#ba1a1a",
    "--crepe-color-error": "#ba1a1a",
    "--crepe-color-hover": "#f9ecdf",
    "--crepe-color-selected": "#ede0d4",
    "--crepe-color-inline-area": "#e4d8cc",
    "--crepe-font-title": "Georgia, Cambria, 'Times New Roman', Times, serif",
    "--crepe-font-default": "'Open Sans', Arial, Helvetica, sans-serif",
    "--crepe-font-code": "Fira Code, Menlo, Monaco, 'Courier New', Courier, monospace",
    "--crepe-shadow-1": "0px 1px 3px 1px rgba(0, 0, 0, 0.15), 0px 1px 2px 0px rgba(0, 0, 0, 0.3)",
    "--crepe-shadow-2": "0px 2px 6px 2px rgba(0, 0, 0, 0.15), 0px 1px 2px 0px rgba(0, 0, 0, 0.3)"
};

export const DARK_CREPE_VARS: Record<string, string> = {
    "--crepe-color-background": "#1f1b16",
    "--crepe-color-on-background": "#eae1d9",
    "--crepe-color-surface": "#18120b",
    "--crepe-color-surface-low": "#201b13",
    "--crepe-color-on-surface": "#ede0d4",
    "--crepe-color-on-surface-variant": "#d3c4b4",
    "--crepe-color-outline": "#9c8f80",
    "--crepe-color-primary": "#f4bd6f",
    "--crepe-color-secondary": "#56442a",
    "--crepe-color-on-secondary": "#fbdebc",
    "--crepe-color-inverse": "#ede0d4",
    "--crepe-color-on-inverse": "#362f27",
    "--crepe-color-inline-code": "#ffb4ab",
    "--crepe-color-error": "#ffb4ab",
    "--crepe-color-hover": "#251f17",
    "--crepe-color-selected": "#3b342b",
    "--crepe-color-inline-area": "#3f3830",
    "--crepe-font-title": "Georgia, Cambria, 'Times New Roman', Times, serif",
    "--crepe-font-default": "'Open Sans', Arial, Helvetica, sans-serif",
    "--crepe-font-code": "Fira Code, Menlo, Monaco, 'Courier New', Courier, monospace",
    "--crepe-shadow-1": "0px 1px 2px 0px rgba(255, 255, 255, 0.3), 0px 1px 3px 1px rgba(255, 255, 255, 0.15)",
    "--crepe-shadow-2": "0px 1px 2px 0px rgba(255, 255, 255, 0.3), 0px 2px 6px 2px rgba(255, 255, 255, 0.15)"
};

export type CrepeThemeKind = "light" | "dark" | "ide";

export const resolveCrepeThemeKind = (settings: MarkFlowRuntimeSettings): CrepeThemeKind => {
    const ideColors = settings.ideColorScheme;
    if (settings.themeSource === "IDE_SYNC" && ideColors && Object.keys(ideColors).length > 0) {
        return "ide";
    }
    return settings.themeSource === "DARK" ? "dark" : "light";
};
export const resolveResolvedDark = (settings: MarkFlowRuntimeSettings): boolean => {
    // Logical source -> resolved appearance. LIGHT/DARK are explicit; IDE_SYNC is resolved from
    // the IDE palette the backend already captured (ideDark), never from the OS media query.
    if (settings.themeSource === "DARK") return true;
    if (settings.themeSource === "LIGHT") return false;
    return settings.ideDark ?? false;
};
export const normalizeBaseFontSizePx = (settings: MarkFlowRuntimeSettings): number => {
    // The user's MarkFlow base font size is the single source of truth. Old payloads that omit it
    // (or pass a non-finite value) resolve to the default so the setting never silently breaks.
    const raw = settings.baseFontSizePx;
    if (typeof raw === "number" && Number.isFinite(raw) && raw > 0) {
        return Math.min(Math.max(Math.round(raw), BASE_FONT_SIZE_MIN), BASE_FONT_SIZE_MAX);
    }
    return DEFAULT_BASE_FONT_SIZE_PX;
};

/**
 * Resolve the document font family to publish on `--crepe-font-default` / `--crepe-font-title`.
 * An empty persisted value ("MarkFlow Default") falls back to the active IDE editor font so
 * MarkFlow matches the IDE by default; an empty IDE font leaves Crepe's bundled default untouched.
 */
export const resolveDocumentFontFamily = (settings: MarkFlowRuntimeSettings): string => {
    const selected = settings.fontFamily?.trim();
    if (selected) {
        return `${quoteFontFamily(selected)}, system-ui, sans-serif`;
    }
    const ide = settings.ideFontFamily?.trim();
    return ide ? `${quoteFontFamily(ide)}, system-ui, sans-serif` : "";
};

/**
 * Build the full set of Crepe design-system variables for the active theme.
 * `IDE_SYNC` resolves against the IDE palette sent from the backend; LIGHT/DARK use
 * the bundled maps. Falls back to the bundled dark/light maps when the IDE palette
 * is empty (headless/test) so the editor is never left with transparent surfaces.
 */
export const buildCrepeThemeVars = (settings: MarkFlowRuntimeSettings): Record<string, string> => {
    const kind = resolveCrepeThemeKind(settings);
    if (kind === "ide") {
        return mapIdeColorsToCrepeVars(settings.ideColorScheme ?? {}, settings.ideDark ?? false);
    }
    return kind === "dark" ? DARK_CREPE_VARS : LIGHT_CREPE_VARS;
};

export const buildCrepeStyleBlock = (settings: MarkFlowRuntimeSettings): string => {
    const vars = buildCrepeThemeVars(settings);
    const baseSizePx = normalizeBaseFontSizePx(settings);

    const crepeLines = Object.entries(vars).map(
        ([name, value]) => `  ${name}: ${value} !important;`
    );
    // Primary mechanism: Crepe's base font size variable (spec #7 / #22).
    crepeLines.push(`  --crepe-base-font-size: ${baseSizePx}px;`);
    // Document font. Applied to --crepe-font-default (paragraphs) and --crepe-font-title
    // (headings). Empty when neither a family nor the IDE font is available, leaving Crepe's
    // bundled default untouched.
    const family = resolveDocumentFontFamily(settings);
    if (family) {
        crepeLines.push(`  --crepe-font-default: ${family};`);
        crepeLines.push(`  --crepe-font-title: ${family};`);
    }

    // Working override for Crepe 7.x, which hardcodes font-sizes and does not read
    // --crepe-base-font-size. Scale body text + headings relative to the base size so the
    // whole document changes coherently at each setting (spec #21). Multipliers derive from
    // Crepe's hardcoded 16px base (h1=42, h2=36, h3=32, h4=28, h5=24, h6=18).
    const baseVar = "var(--crepe-base-font-size)";
    const sizeLines = [
        `  .milkdown .ProseMirror p { font-size: ${baseVar} !important; }`,
        `  .milkdown .ProseMirror h1 { font-size: calc(${baseVar} * 2.625) !important; }`,
        `  .milkdown .ProseMirror h2 { font-size: calc(${baseVar} * 2.25) !important; }`,
        `  .milkdown .ProseMirror h3 { font-size: calc(${baseVar} * 2.0) !important; }`,
        `  .milkdown .ProseMirror h4 { font-size: calc(${baseVar} * 1.75) !important; }`,
        `  .milkdown .ProseMirror h5 { font-size: calc(${baseVar} * 1.5) !important; }`,
        `  .milkdown .ProseMirror h6 { font-size: calc(${baseVar} * 1.125) !important; }`
    ];

    return `.milkdown {\n${crepeLines.join("\n")}\n}\n${sizeLines.join("\n")}`;
};
/**
 * MarkFlow-owned control palette, per resolved appearance. These CSS custom properties are set on
 * `#app` at runtime so MarkFlow controls (buttons, notices, editors) stay coherent across LIGHT /
 * DARK / IDE_SYNC without duplicating dark-theme rulesheets.
 */
const MARKFLOW_LIGHT_VARS: Record<string, string> = {
    "--markflow-background": "#ffffff",
    "--markflow-foreground": "#111827",
    "--markflow-surface": "#f9fafb",
    "--markflow-border": "#e5e7eb",
    "--markflow-muted": "#6b7280",
    "--markflow-accent": "#2563eb",
    "--markflow-warning-background": "#fef3c7",
    "--markflow-warning-border": "#fcd34d",
    "--markflow-warning-foreground": "#92400e"
};

const MARKFLOW_dark_VARS: Record<string, string> = {
    "--markflow-background": "#111827",
    "--markflow-foreground": "#e5e7eb",
    "--markflow-surface": "#1f2937",
    "--markflow-border": "#374151",
    "--markflow-muted": "#9ca3af",
    "--markflow-accent": "#60a5fa",
    "--markflow-warning-background": "#422006",
    "--markflow-warning-border": "#b45309",
    "--markflow-warning-foreground": "#fcd34d"
};

/**
 * Build MarkFlow-level CSS custom properties from the same resolved appearance source as the Crepe
 * palette. IDE_SYNC derives control colors from the live IDE palette; LIGHT/DARK use the bundled
 * maps. Warning colors are semantic (amber) and tied to the resolved light/dark appearance.
 */
export const buildMarkFlowAppearanceVars = (settings: MarkFlowRuntimeSettings): Record<string, string> => {
    const dark = resolveResolvedDark(settings);
    const ideColors = settings.ideColorScheme;
    const hasIdeColors =
        settings.themeSource === "IDE_SYNC" && ideColors && Object.keys(ideColors).length > 0;

    const base = dark ? MARKFLOW_dark_VARS : MARKFLOW_LIGHT_VARS;
    if (!hasIdeColors) {
        return base;
    }

    const bg = ideColors?.background ?? base["--markflow-background"];
    const fg = ideColors?.foreground ?? base["--markflow-foreground"];
    const border = ideColors?.border ?? base["--markflow-border"];
    const accent = ideColors?.textLink ?? base["--markflow-accent"];
    return {
        "--markflow-background": bg,
        "--markflow-foreground": fg,
        "--markflow-surface": mix(bg, dark ? "#000000" : "#ffffff", dark ? 0.12 : 0.05),
        "--markflow-border": border,
        "--markflow-muted": adjustForContrast(fg, bg, 3.0),
        "--markflow-accent": accent,
        "--markflow-warning-background": base["--markflow-warning-background"],
        "--markflow-warning-border": base["--markflow-warning-border"],
        "--markflow-warning-foreground": base["--markflow-warning-foreground"]
    };
};

/**
 * Inject (or replace) the runtime theme + typography override stylesheet. Idempotent:
 * calling it repeatedly with newer settings just rewrites the same `<style>` node, so
 * it is safe to call on every settings revision without accumulating nodes.
 */
export const applyRuntimeAppearance = (settings: MarkFlowRuntimeSettings): void => {
    if (typeof document === "undefined") {
        return;
    }

    const app = document.getElementById("app");
    if (app) {
        app.setAttribute("data-markflow-theme", settings.themeSource ?? "IDE_SYNC");
        for (const [name, value] of Object.entries(buildMarkFlowAppearanceVars(settings))) {
            app.style.setProperty(name, value);
        }
    }


    const style = (document.getElementById(CREPE_THEME_STYLE_ID) as HTMLStyleElement | null)
        ?? document.createElement("style");
    style.id = CREPE_THEME_STYLE_ID;
    style.textContent = buildCrepeStyleBlock(settings);

    const head = document.head ?? document.documentElement;
    if (!head.contains(style)) {
        head.appendChild(style);
    }
};
