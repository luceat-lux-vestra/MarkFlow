// Tests for the runtime-settings + crepe-theme layer that drives application
// appearance and typography. Covers defaults, resolution/normalization, and the
// Crepe theme-kind resolution so IDE_SYNC vs LIGHT/DARK stay distinct.

import {readFileSync} from "node:fs";
import {mkdtempSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join, resolve} from "node:path";
import {test} from "node:test";
import * as ts from "typescript";

const SRC = resolve(import.meta.dirname, "..", "src", "app");
const read = (relPath) => readFileSync(resolve(SRC, relPath), "utf8");

// Transpile each TS source to ESM JS, writing modules to a temp dir and
// rewriting relative imports to absolute file:// URLs so the whole import
// graph resolves under node --test (avoids nested data-URL resolution quirks).
const tmpDir = mkdtempSync(join(tmpdir(), "mf-runtime-settings-"));
const urlCache = new Map();
const transpileToTemp = (relPath) => {
    if (urlCache.has(relPath)) {
        return urlCache.get(relPath);
    }
    const out = ts.transpileModule(read(relPath), {
        compilerOptions: {module: "ESNext", target: "ES2020", moduleResolution: "Bundler"},
        fileName: relPath,
    }).outputText;
    const js = out.replace(/from\s+("([^"]+)")/g, (_m, _q, spec) => {
        if (!spec.startsWith(".")) {
            return `from ${JSON.stringify(spec)}`;
        }
        const depRel = (spec.replace(/\.js$/, "") + ".ts");
        return `from ${JSON.stringify(transpileToTemp(depRel))}`;
    });
    const filePath = join(tmpDir, relPath.replace(/\.ts$/, ".mjs"));
    writeFileSync(filePath, js);
    const url = new URL(`file://${filePath}`).href;
    urlCache.set(relPath, url);
    return url;
};

const runtimeSettingsUrl = transpileToTemp("runtime-settings.ts");
const crepeThemeUrl = transpileToTemp("crepe-theme.ts");

const {
    DEFAULT_RUNTIME_SETTINGS,
    resolveRuntimeSettings,
    resolveMermaidTheme,
    resolveDiagramSecurityLevel,
} = await import(runtimeSettingsUrl);
const {
    DEFAULT_FONT_FAMILY,
    DEFAULT_BASE_FONT_SIZE_PX,
    normalizeBaseFontSizePx,
    resolveFontFamily,
    resolveCrepeThemeKind,
    buildCrepeThemeVars,
    LIGHT_CREPE_VARS,
    DARK_CREPE_VARS,
} = await import(crepeThemeUrl);

const requiredKeys = [
    "mermaidSizeMode",
    "mermaidZoomPercent",
    "themeSource",
    "mermaidErrorDisplay",
    "katexDisplayDensity",
    "diagramSecurityLevel",
    "previewOnlyByDefault",
    "mermaidSyntaxErrorMessage",
    "fontFamily",
    "ideColorScheme",
    "ideFontFamily",
    "ideBaseFontSizePx",
    "ideDark",
    "settingsRevision",
];

test("DEFAULT_RUNTIME_SETTINGS carries every runtime key with sensible defaults", () => {
    for (const key of requiredKeys) {
        if (!(key in DEFAULT_RUNTIME_SETTINGS)) {
            throw new Error(`DEFAULT_RUNTIME_SETTINGS is missing key "${key}"`);
        }
    }
    // Theme defaults to LIGHT; IDE_SYNC is opt-in via settings.
    if (DEFAULT_RUNTIME_SETTINGS.themeSource !== "LIGHT") {
        throw new Error(`expected default themeSource LIGHT, got ${DEFAULT_RUNTIME_SETTINGS.themeSource}`);
        throw new Error(`expected default themeSource IDE_SYNC, got ${DEFAULT_RUNTIME_SETTINGS.themeSource}`);
    }
    if (DEFAULT_RUNTIME_SETTINGS.mermaidZoomPercent !== 100) {
        throw new Error(`expected default mermaidZoomPercent 100, got ${DEFAULT_RUNTIME_SETTINGS.mermaidZoomPercent}`);
    }
    if (DEFAULT_RUNTIME_SETTINGS.ideColorScheme === null) {
        throw new Error("ideColorScheme default must be an object (empty map), not null");
    }
});

test("resolveRuntimeSettings fills missing fields from defaults", () => {
    const resolved = resolveRuntimeSettings({mermaidZoomPercent: 150});
    if (resolved.themeSource !== DEFAULT_RUNTIME_SETTINGS.themeSource) {
        throw new Error("resolveRuntimeSettings did not backfill themeSource from defaults");
    }
    if (resolved.fontFamily !== DEFAULT_RUNTIME_SETTINGS.fontFamily) {
        throw new Error("resolveRuntimeSettings did not backfill fontFamily from defaults");
    }
    if (resolved.diagramSecurityLevel !== DEFAULT_RUNTIME_SETTINGS.diagramSecurityLevel) {
        throw new Error("resolveRuntimeSettings did not backfill diagramSecurityLevel from defaults");
    }
});

test("resolveRuntimeSettings clamps zoom to the 50-200 range", () => {
    if (resolveRuntimeSettings({mermaidZoomPercent: 10}).mermaidZoomPercent !== 50) {
        throw new Error("expected zoom clamped up to 50");
    }
    if (resolveRuntimeSettings({mermaidZoomPercent: 999}).mermaidZoomPercent !== 200) {
        throw new Error("expected zoom clamped down to 200");
    }
    if (resolveRuntimeSettings({mermaidZoomPercent: 120}).mermaidZoomPercent !== 120) {
        throw new Error("expected in-range zoom to pass through unchanged");
    }
});

test("resolveRuntimeSettings(undefined) returns the defaults unchanged", () => {
    const resolved = resolveRuntimeSettings(undefined);
    if (resolved.mermaidSizeMode !== DEFAULT_RUNTIME_SETTINGS.mermaidSizeMode) {
        throw new Error("expected mermaidSizeMode to equal the default");
    }
    if (resolved.settingsRevision !== DEFAULT_RUNTIME_SETTINGS.settingsRevision) {
        throw new Error("expected settingsRevision to equal the default");
    }
});

test("normalizeBaseFontSizePx rounds finite positive sizes and falls back otherwise", () => {
    if (normalizeBaseFontSizePx({ideBaseFontSizePx: 13.7}) !== 14) {
        throw new Error("expected 13.7 to round to 14");
    }
    if (normalizeBaseFontSizePx({ideBaseFontSizePx: 0}) !== DEFAULT_BASE_FONT_SIZE_PX) {
        throw new Error("expected 0 to fall back to default");
    }
    if (normalizeBaseFontSizePx({ideBaseFontSizePx: -5}) !== DEFAULT_BASE_FONT_SIZE_PX) {
        throw new Error("expected negative to fall back to default");
    }
    if (normalizeBaseFontSizePx({ideBaseFontSizePx: NaN}) !== DEFAULT_BASE_FONT_SIZE_PX) {
        throw new Error("expected NaN to fall back to default");
    }
    if (normalizeBaseFontSizePx({ideBaseFontSizePx: undefined}) !== DEFAULT_BASE_FONT_SIZE_PX) {
        throw new Error("expected undefined to fall back to default");
    }
});

test("resolveFontFamily trims non-empty families and falls back to the default", () => {
    if (resolveFontFamily({fontFamily: "  Fira Code  "}) !== "Fira Code") {
        throw new Error("expected leading/trailing whitespace to be trimmed");
    }
    if (resolveFontFamily({fontFamily: ""}) !== DEFAULT_FONT_FAMILY) {
        throw new Error("expected empty family to fall back to default");
    }
    if (resolveFontFamily({fontFamily: "   "}) !== DEFAULT_FONT_FAMILY) {
        throw new Error("expected whitespace-only family to fall back to default");
    }
    if (resolveFontFamily({fontFamily: undefined}) !== DEFAULT_FONT_FAMILY) {
        throw new Error("expected undefined family to fall back to default");
    }
});

test("resolveCrepeThemeKind returns ide only for IDE_SYNC with a populated palette", () => {
    if (resolveCrepeThemeKind({themeSource: "LIGHT"}) !== "light") {
        throw new Error("expected LIGHT -> light");
    }
    if (resolveCrepeThemeKind({themeSource: "DARK"}) !== "dark") {
        throw new Error("expected DARK -> dark");
    }
    if (resolveCrepeThemeKind({themeSource: "IDE_SYNC", ideColorScheme: {}}) !== "light") {
        throw new Error("expected IDE_SYNC with empty palette -> light");
    }
    if (resolveCrepeThemeKind({themeSource: "IDE_SYNC", ideColorScheme: {"editor.background": "#fff"}}) !== "ide") {
        throw new Error("expected IDE_SYNC with palette -> ide");
    }
});

test("buildCrepeThemeVars returns the bundled palettes for LIGHT/DARK", () => {
    const lightVars = buildCrepeThemeVars({themeSource: "LIGHT"});
    const darkVars = buildCrepeThemeVars({themeSource: "DARK"});
    if (JSON.stringify(lightVars) !== JSON.stringify(LIGHT_CREPE_VARS)) {
        throw new Error("expected LIGHT vars to equal LIGHT_CREPE_VARS");
    }
    if (JSON.stringify(darkVars) !== JSON.stringify(DARK_CREPE_VARS)) {
        throw new Error("expected DARK vars to equal DARK_CREPE_VARS");
    }
});

test("IDE_SYNC palette vars differ from both bundled palettes (real IDE mapping)", () => {
    const ideVars = buildCrepeThemeVars({
        themeSource: "IDE_SYNC",
        ideColorScheme: {"editor.background": "#1e1e1e", "editor.foreground": "#d4d4d4"},
        ideDark: true,
    });
    if (JSON.stringify(ideVars) === JSON.stringify(LIGHT_CREPE_VARS)) {
        throw new Error("IDE_SYNC palette must not equal the bundled LIGHT palette");
    }
    if (JSON.stringify(ideVars) === JSON.stringify(DARK_CREPE_VARS)) {
        throw new Error("IDE_SYNC palette must not equal the bundled DARK palette (palette is the source of truth)");
    }
});

test("resolveMermaidTheme maps theme source to mermaid theme with OS fallback for IDE_SYNC", () => {
    if (resolveMermaidTheme({themeSource: "LIGHT"}) !== "default") {
        throw new Error("expected LIGHT -> default");
    }
    if (resolveMermaidTheme({themeSource: "DARK"}) !== "dark") {
        throw new Error("expected DARK -> dark");
    }
    // IDE_SYNC falls back to the OS scheme via window.matchMedia.
    let osDark = false;
    globalThis.window = {
        matchMedia: (query) => ({
            matches: query.includes("dark") ? osDark : false,
            addEventListener() {},
            removeEventListener() {},
        }),
    };
    // Light OS: IDE_SYNC resolves to the default (light) mermaid theme.
    if (resolveMermaidTheme({themeSource: "IDE_SYNC"}) !== "default") {
        throw new Error("expected IDE_SYNC on a light OS -> default");
    }
    osDark = true;
    // Dark OS: IDE_SYNC resolves to the dark mermaid theme.
    if (resolveMermaidTheme({themeSource: "IDE_SYNC"}) !== "dark") {
        throw new Error("expected IDE_SYNC on a dark OS -> dark");
    }
});

test("resolveDiagramSecurityLevel maps LOOSE through and defaults to strict", () => {
    if (resolveDiagramSecurityLevel({diagramSecurityLevel: "LOOSE"}) !== "loose") {
        throw new Error("expected LOOSE -> loose");
    }
    if (resolveDiagramSecurityLevel({diagramSecurityLevel: "STRICT"}) !== "strict") {
        throw new Error("expected STRICT -> strict");
    }
    if (resolveDiagramSecurityLevel({diagramSecurityLevel: "WEIRD"}) !== "strict") {
        throw new Error("expected unknown value to default to strict");
    }
});
