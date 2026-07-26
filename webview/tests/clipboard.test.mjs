import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import * as ts from "typescript";
import {test} from "node:test";

const sourcePath = new URL("../src/app/clipboard.ts", import.meta.url);
const sourceText = await readFile(sourcePath, "utf8");
const crepeUrl = await import.meta.resolve("@milkdown/crepe");
const coreUrl = await import.meta.resolve("@milkdown/core");
const proseModelUrl = await import.meta.resolve("@milkdown/prose/model");
const editorStateUrl = new URL("../src/app/editor-state.ts", import.meta.url);
const editorStateText = await readFile(editorStateUrl, "utf8");
const editorStateTranspiled = ts.transpileModule(editorStateText, {
    compilerOptions: {
        target: ts.ScriptTarget.ES2023,
        module: ts.ModuleKind.ESNext
    }
}).outputText
    .replace(/from "@milkdown\/core"/g, `from "${coreUrl}"`)
    .replace(/from "@milkdown\/prose\/state"/g, `from "${await import.meta.resolve("@milkdown/prose/state")}"`)
    .replace(/from "\.\/editor-state"/g, "");
const editorStateModuleUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(editorStateTranspiled)}`;
const editorStateModule = await import(editorStateModuleUrl);

const transpiled = ts.transpileModule(sourceText, {
    compilerOptions: {
        target: ts.ScriptTarget.ES2023,
        module: ts.ModuleKind.ESNext
    }
}).outputText
    .replace(/from "@milkdown\/crepe"/g, `from "${crepeUrl}"`)
    .replace(/from "@milkdown\/core"/g, `from "${coreUrl}"`)
    .replace(/from "@milkdown\/prose\/model"/g, `from "${proseModelUrl}"`)
    .replace(/from "\.\/editor-state"/g, `from "${editorStateModuleUrl}"`);

const moduleUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(transpiled)}`;
const {
    looksLikeMarkdownClipboard,
    looksLikeRawHtmlClipboard
} = await import(moduleUrl);

test("detects markdown and raw html clipboard text", () => {
    assert.equal(looksLikeRawHtmlClipboard("<details><summary>Title</summary>body</details>"), true);
    assert.equal(looksLikeRawHtmlClipboard("<custom-tag>text</custom-tag>"), true);
    assert.equal(looksLikeRawHtmlClipboard("<https://example.com>"), false);

    assert.equal(looksLikeMarkdownClipboard("<details><summary>Title</summary>body</details>"), true);
    assert.equal(looksLikeMarkdownClipboard("plain text only"), false);
});
