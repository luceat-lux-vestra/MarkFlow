import assert from "node:assert/strict";
import {access, readdir, readFile} from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import {fileURLToPath} from "node:url";

const webviewRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const appRoot = path.join(webviewRoot, "src/app");

const collectTypeScript = async (dir) => {
    const entries = await readdir(dir, {withFileTypes: true});
    const files = [];
    for (const entry of entries) {
        const next = path.join(dir, entry.name);
        if (entry.isDirectory()) files.push(...await collectTypeScript(next));
        else if (entry.isFile() && entry.name.endsWith(".ts")) files.push(next);
    }
    return files;
};

const absent = async (relative) => {
    try {
        await access(path.join(webviewRoot, relative));
        return false;
    } catch {
        return true;
    }
};

test("renderer core has no editor, bridge, navigation, network or filesystem authority", async () => {
    const source = await readFile(path.join(appRoot, "derived-renderer-service.ts"), "utf8");
    for (const forbidden of [
        "crepeSessionId", "CodeMirror", "IntersectionObserver", "document.", "window.", "cefQuery",
        "DocumentSession", "fetch(", "XMLHttpRequest", "WebSocket", "localStorage", "sessionStorage"
    ]) {
        assert.equal(source.includes(forbidden), false, `renderer core contains forbidden authority: ${forbidden}`);
    }
});

test("Mermaid and KaTeX engine calls have one production owner", async () => {
    const files = await collectTypeScript(appRoot);
    const owners = {mermaid: [], katex: []};
    for (const file of files) {
        const source = await readFile(file, "utf8");
        if (/\bmermaid\.render\s*\(/.test(source)) owners.mermaid.push(path.relative(appRoot, file));
        if (/\bkatex\.render(?:ToString)?\s*\(/.test(source)) owners.katex.push(path.relative(appRoot, file));
    }
    assert.deepEqual(owners.mermaid, ["browser-derived-renderer-backends.ts"]);
    assert.deepEqual(owners.katex, ["browser-derived-renderer-backends.ts"]);
});

test("obsolete browser editor entrypoints and toolchains are absent", async () => {
    for (const relative of [
        "index.html",
        "source-native.html",
        "vite.config.ts",
        "vite.source-native.config.ts",
        "src/main.ts",
        "src/editor",
        "src/runtime",
        "src/sync",
        "src/trust",
        "src/app/editor-session.ts",
        "src/app/bridge.ts",
        "src/app/legacy-crepe-katex-adapter.ts",
        "src/app/mermaid-renderer.ts"
    ]) {
        assert.equal(await absent(relative), true, `obsolete browser editor residue remains: ${relative}`);
    }
});

test("retained frontend graph contains no Milkdown, Crepe or CodeMirror imports", async () => {
    const files = await collectTypeScript(path.join(webviewRoot, "src"));
    for (const file of files) {
        const source = await readFile(file, "utf8");
        assert.doesNotMatch(source, /@milkdown|\bCrepe\b|@codemirror|\bCodeMirror\b/, path.relative(webviewRoot, file));
    }
});
