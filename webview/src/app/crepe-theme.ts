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

export const DEFAULT_FONT_FAMILY =
    "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif";
export const DEFAULT_BASE_FONT_SIZE_PX = 16;

const ROOT_FONT_FAMILY_VAR = "--markflow-font-family";
const ROOT_BASE_FONT_SIZE_VAR = "--markflow-base-font-size";
const CREPE_THEME_STYLE_ID = "markflow-crepe-theme";

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

export const normalizeBaseFontSizePx = (settings: MarkFlowRuntimeSettings): number => {
    const raw = settings.ideBaseFontSizePx;
    if (typeof raw === "number" && Number.isFinite(raw) && raw > 0) {
        return Math.round(raw);
    }
    return DEFAULT_BASE_FONT_SIZE_PX;
};

export const resolveFontFamily = (settings: MarkFlowRuntimeSettings): string => {
    const family = settings.fontFamily;
    return family && family.trim().length > 0 ? family.trim() : DEFAULT_FONT_FAMILY;
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

const buildCrepeStyleBlock = (settings: MarkFlowRuntimeSettings): string => {
    const vars = buildCrepeThemeVars(settings);
    const fontFamily = resolveFontFamily(settings);
    const baseSizePx = normalizeBaseFontSizePx(settings);

    const crepeLines = Object.entries(vars).map(
        ([name, value]) => `  ${name}: ${value} !important;`
    );
    // Override the default body font so the user's family takes effect on editor text.
    crepeLines.push(`  --crepe-font-default: ${JSON.stringify(fontFamily)};`);

    const selectorLines = [
        `#app, #app .milkdown, .milkdown { font-family: var(${ROOT_FONT_FAMILY_VAR}, ${JSON.stringify(fontFamily)}); }`,
        `#app, #app .milkdown, .milkdown, body { font-size: var(${ROOT_BASE_FONT_SIZE_VAR}, ${baseSizePx}px); }`
    ];

    return `.milkdown {\n${crepeLines.join("\n")}\n}\n${selectorLines.join("\n")}`;
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
