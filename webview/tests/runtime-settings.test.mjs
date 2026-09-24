import assert from "node:assert/strict";
import {mkdtemp, rm} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {pathToFileURL} from "node:url";
import {compileTypeScriptFixtures} from "./typescript-cli.mjs";

const tempRoot = await mkdtemp(path.join(os.tmpdir(), "markflow-renderer-settings-"));
await compileTypeScriptFixtures({
    sourceNames: ["runtime-settings.ts"],
    outDir: tempRoot,
    target: "ES2022"
});
const settings = await import(pathToFileURL(path.join(tempRoot, "runtime-settings.js")).href);

test.after(async () => rm(tempRoot, {recursive: true, force: true}));

test("renderer defaults are fail-closed and deterministic", () => {
    const resolved = settings.resolveRuntimeSettings(undefined);
    assert.equal("diagramSecurityLevel" in resolved, false);
    assert.equal("previewOnlyByDefault" in resolved, false);
    assert.equal(resolved.themeSource, "LIGHT");
    assert.equal(resolved.mermaidZoomPercent, 100);
    const config = settings.createMermaidPreviewConfig(resolved);
    assert.equal(config.securityLevel, "strict");
    assert.equal(config.layout, "dagre");
    assert.equal(config.look, "classic");
    assert.equal(config.theme, "default");
    assert.equal(config.htmlLabels, false);
    assert.equal(config.flowchart.htmlLabels, false);
});

test("legacy preview and Mermaid security keys are ignored and cannot weaken renderer policy", () => {
    const resolved = settings.resolveRuntimeSettings({
        themeSource: "LIGHT",
        diagramSecurityLevel: "LOOSE",
        previewOnlyByDefault: false
    });
    assert.equal("diagramSecurityLevel" in resolved, false);
    assert.equal("previewOnlyByDefault" in resolved, false);
    assert.equal(settings.createMermaidPreviewConfig(resolved).securityLevel, "strict");
});

test("runtime settings clamp zoom and reject non-hex palette values", () => {
    const resolved = settings.resolveRuntimeSettings({
        mermaidZoomPercent: 999,
        themeSource: "IDE_SYNC",
        ideDark: true,
        ideColorScheme: {background: "#101010", foreground: "javascript:bad", border: "#444444"}
    });
    assert.equal(resolved.mermaidZoomPercent, 200);
    assert.deepEqual(resolved.ideColorScheme, {background: "#101010", border: "#444444"});
    assert.equal(settings.resolveMermaidTheme(resolved), "dark");
});

test("IDE_SYNC palette produces visible Mermaid line and text colors", () => {
    const resolved = settings.resolveRuntimeSettings({
        themeSource: "IDE_SYNC",
        ideDark: true,
        ideColorScheme: {background: "#111111", foreground: "#eeeeee", border: "#555555"}
    });
    const config = settings.createMermaidPreviewConfig(resolved);
    assert.equal(config.theme, "dark");
    assert.equal(config.themeVariables.background, "#111111");
    assert.ok(typeof config.themeVariables.lineColor === "string");
    assert.ok(typeof config.themeVariables.primaryTextColor === "string");
});

test("settings identity ignores revision and non-authoritative IDE palette outside IDE_SYNC", () => {
    const a = settings.resolveRuntimeSettings({themeSource: "LIGHT", settingsRevision: 1, ideColorScheme: {background: "#000000"}});
    const b = settings.resolveRuntimeSettings({themeSource: "LIGHT", settingsRevision: 2, ideColorScheme: {background: "#ffffff"}});
    assert.equal(settings.runtimeSettingsIdentity(a), settings.runtimeSettingsIdentity(b));

    const c = settings.resolveRuntimeSettings({themeSource: "IDE_SYNC", ideColorScheme: {background: "#000000"}});
    const d = settings.resolveRuntimeSettings({themeSource: "IDE_SYNC", ideColorScheme: {background: "#ffffff"}});
    assert.notEqual(settings.runtimeSettingsIdentity(c), settings.runtimeSettingsIdentity(d));
});
