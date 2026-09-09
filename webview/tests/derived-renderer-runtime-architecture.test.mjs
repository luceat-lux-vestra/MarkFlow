import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import test from "node:test";

const bootstrap = await readFile(new URL("../src/renderer/derived-renderer-bootstrap.ts", import.meta.url), "utf8");
const html = await readFile(new URL("../derived-renderer.html", import.meta.url), "utf8");
const vite = await readFile(new URL("../vite.derived-renderer.config.ts", import.meta.url), "utf8");

test("isolated renderer realm has no editor/source-sync authority", () => {
    for (const forbidden of [
        "editor-session",
        "SourceNative",
        "DocumentSession",
        "mutationRequest",
        "recovery",
        "local-image",
        "cefQuery",
        "fetch(",
        "XMLHttpRequest",
        "WebSocket"
    ]) {
        assert.equal(bootstrap.includes(forbidden), false, `isolated renderer bootstrap contains forbidden authority: ${forbidden}`);
    }
});

test("isolated renderer CSP denies ambient network/navigation capabilities", () => {
    assert.match(html, /default-src 'none'/);
    assert.match(html, /connect-src 'none'/);
    assert.match(html, /frame-src 'none'/);
    assert.match(html, /object-src 'none'/);
    assert.match(html, /base-uri 'none'/);
    assert.match(html, /form-action 'none'/);
});

test("isolated renderer bundle stays in a dedicated asset namespace", () => {
    assert.match(vite, /derived-renderer-assets\//);
    assert.match(vite, /derived-renderer\.html/);
    assert.doesNotMatch(vite, /source-native\.html|index\.html/);
});
