import assert from "node:assert/strict";
import {readdir, readFile} from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import {fileURLToPath} from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../src/app");
const read = (name) => readFile(path.join(root, name), "utf8");

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

test("renderer core has no editor, DOM, bridge, navigation, network or filesystem authority", async () => {
    const source = await read("derived-renderer-service.ts");
    for (const forbidden of [
        "crepeSessionId",
        "CodeMirror",
        "IntersectionObserver",
        "document.",
        "window.",
        "cefQuery",
        "DocumentSession",
        "fetch(",
        "XMLHttpRequest",
        "WebSocket",
        "localStorage",
        "sessionStorage"
    ]) {
        assert.equal(source.includes(forbidden), false, `renderer core contains forbidden authority: ${forbidden}`);
    }
});

test("Mermaid and KaTeX engine calls have one MarkFlow production owner", async () => {
    const files = await collectTypeScript(root);
    const owners = {mermaid: [], katex: []};
    for (const file of files) {
        const source = await readFile(file, "utf8");
        if (/\bmermaid\.render\s*\(/.test(source)) owners.mermaid.push(path.relative(root, file));
        if (/\bkatex\.render(?:ToString)?\s*\(/.test(source)) owners.katex.push(path.relative(root, file));
    }
    assert.deepEqual(owners.mermaid, ["browser-derived-renderer-backends.ts"]);
    assert.deepEqual(owners.katex, ["browser-derived-renderer-backends.ts"]);
});

test("legacy Mermaid adapter delegates derived work and has no editor-session identity", async () => {
    const source = await read("mermaid-renderer.ts");
    assert.match(source, /rendererService\.render\s*\(/);
    assert.doesNotMatch(source, /import\(["']mermaid["']\)/);
    assert.doesNotMatch(source, /\bmermaid\.render\s*\(/);
    assert.doesNotMatch(source, /crepeSessionId|activeCrepeSessionId|setActiveCrepeSessionId/);
});

test("legacy KaTeX adapter delegates artifacts and preserves adapter-only DOM ownership", async () => {
    const source = await read("legacy-crepe-katex-adapter.ts");
    assert.match(source, /rendererService\.render\s*\(/);
    assert.doesNotMatch(source, /from ["']katex["']|\bkatex\.render/);
    assert.match(source, /ctx\.update<CodeBlockConfigLike, "codeBlockConfigCtx">/);
    assert.match(source, /ctx\.update<NodeViewEntry\[\], "nodeView">/);
});

test("editor session creates one shared renderer service through the Mermaid adapter", async () => {
    const source = await read("editor-session.ts");
    assert.equal((source.match(/new MarkFlowMermaidRenderer\s*\(/g) ?? []).length, 1);
    assert.equal((source.match(/getRendererService\s*\(/g) ?? []).length, 1);
    assert.match(source, /installLegacyCrepeKatexAdapter/);
    assert.doesNotMatch(source, /setActiveCrepeSessionId/);
});
