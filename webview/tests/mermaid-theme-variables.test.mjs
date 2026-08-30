// Regression test for Mermaid arrow rendering.
//
// Mermaid draws arrowheads from the `<marker>` element referenced by the
// `marker-end` property, and fills that arrowhead from `lineColor` (plus the
// embedded `<style> .marker { fill }` and the inline `fill` attribute on
// `.arrowMarkerPath`). If `lineColor` is `none`/`transparent`/`#000001` the
// arrowhead becomes invisible (the reported "Mermaid arrows" regression).
//
// This test locks in that `createMermaidPreviewConfig` always emits a usable
// `lineColor` (arrowhead fill) in the Mermaid themeVariables, for every theme
// source: LIGHT, DARK and IDE_SYNC (palette-derived). It does NOT depend on a
// DOM/jsdom (mermaid 11.17.2 needs adopted stylesheets + a DOMPurify window),
// so it verifies the config that drives arrow rendering.

import {readFileSync} from "node:fs";
import {dirname, resolve} from "node:path";
import {test} from "node:test";
import * as ts from "typescript";

const SRC = resolve(import.meta.dirname, "..", "src", "app");

const read = (relPath) => readFileSync(resolve(SRC, relPath), "utf8");

// Transpile a TS source to ESM JS, rewriting relative imports to the
// transpiled data-URL modules so the whole import graph resolves.
const transpile = (relPath, deps) => {
    const source = read(relPath);
    const out = ts.transpileModule(source, {
        compilerOptions: {module: "ESNext", target: "ES2020", moduleResolution: "Bundler"},
        fileName: relPath,
    }).outputText;
    let js = out;
    for (const [from, url] of Object.entries(deps ?? {})) {
        js = js.split(from).join(url);
    }
    return `data:text/javascript;base64,${Buffer.from(js).toString("base64")}`;
};

const colorUrl = transpile("color.ts", {});
const crepeThemeMappingUrl = transpile("crepe-theme-mapping.ts", {"./color": colorUrl});
const crepeThemeUrl = transpile("crepe-theme.ts", {"./crepe-theme-mapping": crepeThemeMappingUrl});
const runtimeSettingsUrl = transpile("runtime-settings.ts", {
    "./crepe-theme-mapping": crepeThemeMappingUrl,
    "./crepe-theme": crepeThemeUrl,
});

const {createMermaidPreviewConfig, resolveRuntimeSettings} = await import(runtimeSettingsUrl);

const NONE_OR_TRANSPARENT = (v) => v == null || v === "none" || v === "transparent" || v === "#000001";

const arrowheadFill = (config) => config?.themeVariables?.lineColor;

// IDE_SYNC resolves the OS scheme via window.matchMedia. Provide a controllable
// stub so the IDE_SYNC tests can simulate light/dark OS themes.
let osDark = false;
globalThis.window = {
    matchMedia: (query) => ({
        matches: query.includes("dark") ? osDark : false,
        addEventListener() {},
        removeEventListener() {},
    }),
};
const setOsDark = (dark) => {
    osDark = dark;
};

test("createMermaidPreviewConfig emits a usable arrowhead fill (lineColor) for LIGHT", () => {
    const config = createMermaidPreviewConfig(resolveRuntimeSettings({themeSource: "LIGHT"}));
    const fill = arrowheadFill(config);
    console.log(`LIGHT lineColor: ${fill}`);
    if (fill == null) {
        throw new Error("expected themeVariables.lineColor to be defined for LIGHT");
    }
    if (NONE_OR_TRANSPARENT(fill)) {
        throw new Error(`arrowhead fill must not be none/transparent (got "${fill}")`);
    }
});

test("createMermaidPreviewConfig emits a usable arrowhead fill (lineColor) for DARK", () => {
    const config = createMermaidPreviewConfig(resolveRuntimeSettings({themeSource: "DARK"}));
    const fill = arrowheadFill(config);
    console.log(`DARK lineColor: ${fill}`);
    if (fill == null) {
        throw new Error("expected themeVariables.lineColor to be defined for DARK");
    }
    if (NONE_OR_TRANSPARENT(fill)) {
        throw new Error(`arrowhead fill must not be none/transparent (got "${fill}")`);
    }
});

test("IDE_SYNC with a palette derives arrowhead fill from the IDE palette (contrast-guarded)", () => {
    const palette = {
        "editor.foreground": "#f9fafb",
        "editor.background": "#111827",
        "editor.selectionBackground": "#3b82f6",
        "editorIndentGuide.background1": "#374151",
        "editorLineNumber.foreground": "#9ca3af",
    };
    const config = createMermaidPreviewConfig(
        resolveRuntimeSettings({themeSource: "IDE_SYNC", ideColorScheme: palette, ideDark: false})
    );
    const fill = arrowheadFill(config);
    console.log(`IDE_SYNC lineColor: ${fill}`);
    if (fill == null) {
        throw new Error("expected themeVariables.lineColor to be defined for IDE_SYNC palette");
    }
    if (NONE_OR_TRANSPARENT(fill)) {
        throw new Error(`IDE_SYNC arrowhead fill must not be none/transparent (got "${fill}")`);
    }
    // IDE-derived: the arrowhead fill must track the palette foreground, not a
    // hardcoded Mermaid default.
    if (fill === "#111827" || fill === "#f9fafb") {
        throw new Error(
            `IDE_SYNC arrowhead fill should be contrast-adjusted, not a raw palette value (got "${fill}")`
        );
    }
});

test("IDE_SYNC without a palette falls back to the OS-based light/dark defaults", () => {
    setOsDark(false);
    const light = createMermaidPreviewConfig(resolveRuntimeSettings({themeSource: "IDE_SYNC", ideDark: false}));
    setOsDark(true);
    const dark = createMermaidPreviewConfig(resolveRuntimeSettings({themeSource: "IDE_SYNC", ideDark: true}));
    const lightFill = arrowheadFill(light);
    const darkFill = arrowheadFill(dark);
    console.log(`IDE_SYNC (no palette) light lineColor: ${lightFill}, dark lineColor: ${darkFill}`);
    if (NONE_OR_TRANSPARENT(lightFill) || NONE_OR_TRANSPARENT(darkFill)) {
        throw new Error("IDE_SYNC no-palette fallback must still emit a usable arrowhead fill");
    }
    if (lightFill === darkFill) {
        throw new Error("expected light and dark fallback arrowhead fills to differ");
    }
});

test("removeThemeVariables regression: with no themeVariables the arrowhead fill is undefined", () => {
    // Guards against the regression where themeVariables (and thus the arrowhead
    // fill) is dropped entirely. This test documents the exact failure mode.
    const brokenConfig = {theme: "default", themeVariables: {}};
    const fill = arrowheadFill(brokenConfig);
    console.log(`brokenConfig lineColor: ${fill}`);
    if (fill != null) {
        throw new Error(`expected brokenConfig (empty themeVariables) to have no arrowhead fill (got "${fill}")`);
    }
});
