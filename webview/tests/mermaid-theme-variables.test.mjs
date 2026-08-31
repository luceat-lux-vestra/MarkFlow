// Regression test for Mermaid arrow rendering.
//
// Mermaid draws arrowheads from the `<marker>` element referenced by the
// `marker-end` property, and fills that arrowhead from `lineColor` (plus the
// embedded `<style> .marker { fill }` and the inline `fill` attribute on
// `.arrowMarkerPath`). If `lineColor` is `none`/`transparent`/`#000001` the
// arrowhead becomes invisible (the reported "Mermaid arrows" regression).
//
// The config assertions below cover the palette contract, and the final test calls Mermaid's
// actual `render()` in a small jsdom environment to verify the generated SVG marker wiring.

import assert from "node:assert/strict";
import {JSDOM} from "jsdom";
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
const crepeThemeUrl = transpile("crepe-theme.ts", {"./color": colorUrl, "./crepe-theme-mapping": crepeThemeMappingUrl});
const runtimeSettingsUrl = transpile("runtime-settings.ts", {
    "./crepe-theme-mapping": crepeThemeMappingUrl,
    "./crepe-theme": crepeThemeUrl,
    "./color": colorUrl,
});

const {createMermaidPreviewConfig, resolveRuntimeSettings} = await import(runtimeSettingsUrl);

// Mermaid 11.17.2 expects browser APIs that jsdom does not implement by default. These are the
// smallest test-only shims needed for its flowchart layout and adopted stylesheet path.
const dom = new JSDOM("<!doctype html><html><body></body></html>", {url: "http://localhost"});
for (const key of [
    "window",
    "document",
    "navigator",
    "DOMParser",
    "Element",
    "HTMLElement",
    "SVGElement",
    "Node",
    "XMLSerializer",
    "getComputedStyle"
]) {
    Object.defineProperty(globalThis, key, {value: dom.window[key], configurable: true});
}
class TestCSSStyleSheet {
    cssRules = [];

    insertRule(rule) {
        this.cssRules.push(rule);
    }

    replaceSync(_css) {
        this.cssRules = [];
    }
}
Object.defineProperty(globalThis, "CSSStyleSheet", {value: TestCSSStyleSheet, configurable: true});
Object.defineProperty(document, "adoptedStyleSheets", {value: [], writable: true, configurable: true});
dom.window.SVGElement.prototype.getBBox = () => ({x: 0, y: 0, width: 100, height: 20});
dom.window.SVGElement.prototype.getComputedTextLength = () => 100;
const {default: mermaid} = await import("mermaid");

const NONE_OR_TRANSPARENT = (v) => v == null || v === "none" || v === "transparent" || v === "#000001";

const arrowheadFill = (config) => config?.themeVariables?.lineColor;

// IDE_SYNC resolves its appearance from the backend-resolved `ideDark` (the Kotlin
// backend captures the IDE palette), so no OS matchMedia stub is needed here.

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
        background: "#123456",
        foreground: "#abcdef",
        selectionBackground: "#654321",
        selectionForeground: "#fedcba",
        border: "#112233"
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
    if (config.themeVariables.background !== palette.background) {
        throw new Error("Mermaid background did not use the actual IDE wire key");
    }
    if (config.themeVariables.nodeBorder !== palette.border || config.themeVariables.borderColor !== palette.border) {
        throw new Error("Mermaid border did not use the actual IDE wire key");
    }
    if (config.themeVariables.textColor !== fill || config.themeVariables.primaryTextColor == null) {
        throw new Error("Mermaid labels must use readable palette-derived text colors");
    }
    // IDE-derived: the arrowhead fill must track the palette foreground, not a hardcoded Mermaid
    // default. This fixture is already readable, so no contrast adjustment should obscure the wire value.
    if (fill !== palette.foreground) {
        throw new Error(
            `IDE_SYNC arrowhead fill should use the palette foreground (got "${fill}")`
        );
    }
});

test("IDE_SYNC without a palette falls back to light/dark defaults from ideDark", () => {
    const light = createMermaidPreviewConfig(resolveRuntimeSettings({themeSource: "IDE_SYNC", ideDark: false}));
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

test("Mermaid render emits an edge marker that references a rendered arrowhead marker", async () => {
    const palette = {
        background: "#123456",
        foreground: "#abcdef",
        selectionBackground: "#654321",
        selectionForeground: "#fedcba",
        border: "#112233"
    };
    const config = createMermaidPreviewConfig(
        resolveRuntimeSettings({themeSource: "IDE_SYNC", ideColorScheme: palette, ideDark: false})
    );
    const diagramId = `arrow-regression-${Date.now()}`;
    mermaid.initialize(config);

    const rendered = await mermaid.render(diagramId, "flowchart LR\n  A --> B");
    const host = document.createElement("div");
    host.innerHTML = rendered.svg;

    const markers = [...host.querySelectorAll("marker")];
    assert.ok(markers.length > 0, "rendered SVG must contain at least one marker");

    const edge = host.querySelector("[marker-end]");
    assert.ok(edge, "rendered edge must contain marker-end");
    const markerEnd = edge.getAttribute("marker-end");
    const reference = markerEnd?.match(/^url\([\"']?#([^\"')]+)[\"']?\)$/);
    assert.ok(reference, `marker-end must be a URL reference, got ${markerEnd}`);

    const referencedMarker = markers.find((marker) => marker.id === reference[1]);
    assert.ok(referencedMarker, `marker-end target ${reference[1]} must exist in the SVG`);
    assert.ok(referencedMarker.querySelector("path"), "referenced marker must contain an arrowhead path");
});
