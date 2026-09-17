import assert from "node:assert/strict";
import {mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import ts from "typescript";
import {pathToFileURL, fileURLToPath} from "node:url";

const appRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../src/app");

const transpile = async (sourceName, outputName, replacements = []) => {
    const source = await readFile(path.join(appRoot, sourceName), "utf8");
    let output = ts.transpileModule(source, {
        compilerOptions: {target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022}
    }).outputText;
    for (const [from, to] of replacements) output = output.replaceAll(from, to);
    await writeFile(path.join(tempRoot, outputName), output, "utf8");
};

const tempRoot = await mkdtemp(path.join(os.tmpdir(), "markflow-renderer-settings-"));
await transpile("color.ts", "color.mjs");
await transpile("ide-theme-mapping.ts", "ide-theme-mapping.mjs", [["./color", "./color.mjs"]]);
await transpile("runtime-settings.ts", "runtime-settings.mjs", [
    ["./ide-theme-mapping", "./ide-theme-mapping.mjs"],
    ["./color", "./color.mjs"]
]);
const settings = await import(pathToFileURL(path.join(tempRoot, "runtime-settings.mjs")).href);

test.after(async () => rm(tempRoot, {recursive: true, force: true}));

test("renderer defaults are fail-closed and deterministic", () => {
    const resolved = settings.resolveRuntimeSettings(undefined);
    assert.equal("diagramSecurityLevel" in resolved, false);
    assert.equal("previewOnlyByDefault" in resolved, false);
    assert.equal(resolved.themeSource, "LIGHT");
    assert.equal(resolved.mermaidZoomPercent, 100);
    const config = settings.createMermaidPreviewConfig(resolved);
    assert.equal(config.securityLevel, "strict");
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
