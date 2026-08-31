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
    DEFAULT_BASE_FONT_SIZE_PX,
    normalizeBaseFontSizePx,
    resolveDocumentFontFamily,
    resolveCrepeThemeKind,
    buildCrepeStyleBlock,
    buildCrepeThemeVars,
    LIGHT_CREPE_VARS,
    DARK_CREPE_VARS,
    BASE_FONT_SIZE_MIN,
    BASE_FONT_SIZE_MAX,
    resolveResolvedDark,
    buildMarkFlowAppearanceVars,
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
    "ideDark",
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

test("normalizeBaseFontSizePx rounds, clamps to [MIN, MAX], and falls back otherwise", () => {
    if (normalizeBaseFontSizePx({baseFontSizePx: 13.7}) !== 14) {
        throw new Error("expected 13.7 to round to 14");
    }
    if (normalizeBaseFontSizePx({baseFontSizePx: 10}) !== BASE_FONT_SIZE_MIN) {
        throw new Error("expected min boundary to be preserved");
    }
    if (normalizeBaseFontSizePx({baseFontSizePx: 32}) !== BASE_FONT_SIZE_MAX) {
        throw new Error("expected max boundary to be preserved");
    }
    if (normalizeBaseFontSizePx({baseFontSizePx: 5}) !== BASE_FONT_SIZE_MIN) {
        throw new Error("expected below-min to clamp to MIN");
    }
    if (normalizeBaseFontSizePx({baseFontSizePx: 999}) !== BASE_FONT_SIZE_MAX) {
        throw new Error("expected above-max to clamp to MAX");
    }
    if (normalizeBaseFontSizePx({baseFontSizePx: 0}) !== DEFAULT_BASE_FONT_SIZE_PX) {
        throw new Error("expected 0 to fall back to default");
    }
    if (normalizeBaseFontSizePx({baseFontSizePx: -5}) !== DEFAULT_BASE_FONT_SIZE_PX) {
        throw new Error("expected negative to fall back to default");
    }
    if (normalizeBaseFontSizePx({baseFontSizePx: NaN}) !== DEFAULT_BASE_FONT_SIZE_PX) {
        throw new Error("expected NaN to fall back to default");
    }
    if (normalizeBaseFontSizePx({}) !== DEFAULT_BASE_FONT_SIZE_PX) {
        throw new Error("expected missing field to fall back to default");
    }
});

test("resolveDocumentFontFamily quotes named families and appends the fallback stack", () => {
    // Named family with a space is quoted; generic families stay bare.
    if (resolveDocumentFontFamily({fontFamily: "Fira Code"}) !== `"Fira Code", system-ui, sans-serif`) {
        throw new Error(`expected named family to be quoted, got ${resolveDocumentFontFamily({fontFamily: "Fira Code"})}`);
    }
    // Simple identifier family stays unquoted.
    if (resolveDocumentFontFamily({fontFamily: "Inter"}) !== "Inter, system-ui, sans-serif") {
        throw new Error(`expected bare family, got ${resolveDocumentFontFamily({fontFamily: "Inter"})}`);
    }
    // Generic keyword stays unquoted and is trimmed.
    if (resolveDocumentFontFamily({fontFamily: "  monospace  "}) !== "monospace, system-ui, sans-serif") {
        throw new Error(`expected generic family unquoted, got ${resolveDocumentFontFamily({fontFamily: "  monospace  "})}`);
    }
});

test("resolveDocumentFontFamily falls back to the IDE font when the family is empty", () => {
    // Empty family -> IDE font (MarkFlow matches the IDE by default).
    if (resolveDocumentFontFamily({fontFamily: "", ideFontFamily: "Inter"}) !== "Inter, system-ui, sans-serif") {
        throw new Error("expected empty family to fall back to the IDE font");
    }
    // Empty family + null IDE font -> no override (empty string).
    if (resolveDocumentFontFamily({fontFamily: "", ideFontFamily: null}) !== "") {
        throw new Error("expected empty family + null IDE font to leave Crepe's default untouched");
    }
    // Neither family nor IDE font -> empty (no override).
    if (resolveDocumentFontFamily({}) !== "") {
        throw new Error("expected missing family + missing IDE font to leave Crepe's default untouched");
    }
});
test("buildCrepeStyleBlock emits --crepe-base-font-size and scales body + headings", () => {
    const block = buildCrepeStyleBlock({
        themeSource: "IDE_SYNC",
        ideColorScheme: {},
        ideDark: false,
        baseFontSizePx: 24,
        fontFamily: "Fira Code",
        ideFontFamily: "Inter",
    });

    // Spec #7 / #22: the base-size variable is emitted on .milkdown.
    if (!/\.milkdown\s*\{/.test(block)) {
        throw new Error("expected .milkdown block");
    }
    if (!/--crepe-base-font-size:\s*24px/.test(block)) {
        throw new Error(`expected --crepe-base-font-size: 24px, got:\n${block}`);
    }
    // Spec #8: selected family is quoted and applied to both default + title.
    if (!/--crepe-font-default:\s*"Fira Code",\s*system-ui,\s*sans-serif/.test(block)) {
        throw new Error(`expected quoted default family, got:\n${block}`);
    }
    if (!/--crepe-font-title:\s*"Fira Code",\s*system-ui,\s*sans-serif/.test(block)) {
        throw new Error(`expected quoted title family, got:\n${block}`);
    }
    // Spec #21: body + headings scale via calc() against the base-size variable.
    if (!/\.milkdown\s+\.ProseMirror\s+p\s*\{\s*font-size:\s*var\(--crepe-base-font-size\)\s*!important/.test(block)) {
        throw new Error("expected p font-size override");
    }
    if (!/\.milkdown\s+\.ProseMirror\s+h1\s*\{\s*font-size:\s*calc\(var\(--crepe-base-font-size\)\s*\*\s*2\.625\)\s*!important/.test(block)) {
        throw new Error("expected h1 calc override");
    }
});
test("buildCrepeStyleBlock omits font-family overrides when IDE mapping has none and no family resolves", () => {
    // IDE_SYNC with a populated palette resolves to the IDE mapping, which carries no font vars.
    // With no resolved family (empty + null IDE font), we must not inject any --crepe-font-* lines.
    const block = buildCrepeStyleBlock({
        themeSource: "IDE_SYNC",
        ideColorScheme: {background: "#ffffff", foreground: "#1e1e1e"},
        ideDark: false,
        baseFontSizePx: 16,
        fontFamily: "",
        ideFontFamily: null,
    });
    if (/(^|\n)\s*--crepe-font-(default|title|code):/.test(block)) {
        throw new Error("expected no font-family overrides when IDE mapping has none and no family resolves");
    }
    // Base-size variable is still emitted.
    if (!/--crepe-base-font-size:\s*16px/.test(block)) {
        throw new Error("expected --crepe-base-font-size even without a family");
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

test("resolveMermaidTheme maps theme source to mermaid theme, trusting ideDark for IDE_SYNC", () => {
    if (resolveMermaidTheme({themeSource: "LIGHT"}) !== "default") {
        throw new Error("expected LIGHT -> default");
    }
    if (resolveMermaidTheme({themeSource: "DARK"}) !== "dark") {
        throw new Error("expected DARK -> dark");
    }
    // IDE_SYNC resolves from the backend-resolved ideDark, not the OS media query.
    if (resolveMermaidTheme({themeSource: "IDE_SYNC", ideDark: false}) !== "default") {
        throw new Error("expected IDE_SYNC with ideDark:false -> default");
    }
    if (resolveMermaidTheme({themeSource: "IDE_SYNC", ideDark: true}) !== "dark") {
        throw new Error("expected IDE_SYNC with ideDark:true -> dark");
    }
    // Missing ideDark defaults to light (undefined is falsy).
    if (resolveMermaidTheme({themeSource: "IDE_SYNC"}) !== "default") {
        throw new Error("expected IDE_SYNC without ideDark -> default");
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

test("resolveResolvedDark maps the logical source to a resolved light/dark boolean", () => {
    if (resolveResolvedDark({themeSource: "LIGHT"}) !== false) {
        throw new Error("expected LIGHT -> resolved light (false)");
    }
    if (resolveResolvedDark({themeSource: "DARK"}) !== true) {
        throw new Error("expected DARK -> resolved dark (true)");
    }
    if (resolveResolvedDark({themeSource: "IDE_SYNC", ideDark: false}) !== false) {
        throw new Error("expected IDE_SYNC with ideDark:false -> resolved light");
    }
    if (resolveResolvedDark({themeSource: "IDE_SYNC", ideDark: true}) !== true) {
        throw new Error("expected IDE_SYNC with ideDark:true -> resolved dark");
    }
    // Missing ideDark resolves to light (undefined is falsy).
    if (resolveResolvedDark({themeSource: "IDE_SYNC"}) !== false) {
        throw new Error("expected IDE_SYNC without ideDark -> resolved light");
    }
});

test("buildMarkFlowAppearanceVars returns the bundled palette for LIGHT/DARK", () => {
    const light = buildMarkFlowAppearanceVars({themeSource: "LIGHT"});
    if (light["--markflow-background"] !== "#ffffff") {
        throw new Error(`expected LIGHT background to be white (got ${light["--markflow-background"]})`);
    }
    if (light["--markflow-foreground"] !== "#111827") {
        throw new Error(`expected LIGHT foreground to be dark (got ${light["--markflow-foreground"]})`);
    }
    if (light["--markflow-warning-foreground"] !== "#92400e") {
        throw new Error(`expected LIGHT warning foreground to be amber-800 (got ${light["--markflow-warning-foreground"]})`);
    }

    const dark = buildMarkFlowAppearanceVars({themeSource: "DARK"});
    if (dark["--markflow-background"] !== "#111827") {
        throw new Error(`expected DARK background to be dark (got ${dark["--markflow-background"]})`);
    }
    if (dark["--markflow-foreground"] !== "#e5e7eb") {
        throw new Error(`expected DARK foreground to be light (got ${dark["--markflow-foreground"]})`);
    }
    if (dark["--markflow-warning-foreground"] !== "#fcd34d") {
        throw new Error(`expected DARK warning foreground to be amber-300 (got ${dark["--markflow-warning-foreground"]})`);
    }
});

test("buildMarkFlowAppearanceVars derives IDE colors from the palette for IDE_SYNC", () => {
    const vars = buildMarkFlowAppearanceVars({
        themeSource: "IDE_SYNC",
        ideColorScheme: {
            background: "#1e1e1e",
            foreground: "#d4d4d4",
            border: "#333333",
            textLink: "#4da3ff"
        },
        ideDark: true
    });
    if (vars["--markflow-background"] !== "#1e1e1e") {
        throw new Error("expected IDE_SYNC background to track the IDE background");
    }
    if (vars["--markflow-foreground"] !== "#d4d4d4") {
        throw new Error("expected IDE_SYNC foreground to track the IDE foreground");
    }
    if (vars["--markflow-border"] !== "#333333") {
        throw new Error("expected IDE_SYNC border to track the IDE border");
    }
    if (vars["--markflow-accent"] !== "#4da3ff") {
        throw new Error("expected IDE_SYNC accent to track the IDE textLink");
    }
    // Warning colors stay semantic (amber) regardless of the IDE palette.
    if (vars["--markflow-warning-foreground"] !== "#fcd34d") {
        throw new Error("expected IDE_SYNC warning foreground to stay the semantic amber");
    }
});

test("buildMarkFlowAppearanceVars falls back to the bundled palette from ideDark when IDE_SYNC has no palette", () => {
    const light = buildMarkFlowAppearanceVars({themeSource: "IDE_SYNC", ideDark: false});
    if (light["--markflow-background"] !== "#ffffff") {
        throw new Error("expected IDE_SYNC without palette + ideDark:false -> light bundled background");
    }
    const dark = buildMarkFlowAppearanceVars({themeSource: "IDE_SYNC", ideDark: true});
    if (dark["--markflow-background"] !== "#111827") {
        throw new Error("expected IDE_SYNC without palette + ideDark:true -> dark bundled background");
    }
});
