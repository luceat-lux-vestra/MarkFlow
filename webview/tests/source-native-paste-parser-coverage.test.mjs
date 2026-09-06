import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {resolve} from "node:path";
import {test} from "node:test";
import * as ts from "typescript";
import {EditorState} from "@codemirror/state";

const repositoryRoot = resolve(import.meta.dirname, "../..");
const pasteSourcePath = resolve(repositoryRoot, "webview/src/editor/source-native-paste.ts");
const pasteSourceText = readFileSync(pasteSourcePath, "utf8");
const packageUrls = new Map([
    ["@codemirror/language", await import.meta.resolve("@codemirror/language")],
    ["@codemirror/state", await import.meta.resolve("@codemirror/state")],
    ["@codemirror/view", await import.meta.resolve("@codemirror/view")]
]);

const transpiled = ts.transpileModule(pasteSourceText, {
    compilerOptions: {
        target: ts.ScriptTarget.ES2023,
        module: ts.ModuleKind.ESNext
    }
}).outputText;
const rewritten = [...packageUrls.entries()].reduce(
    (text, [specifier, url]) => text.replaceAll(`from "${specifier}"`, `from "${url}"`),
    transpiled
);
const paste = await import(`data:text/javascript;charset=utf-8,${encodeURIComponent(rewritten)}`);

test("unproven parser coverage forces default paste before custom Markdown routing", () => {
    const stateWithoutLanguageParser = EditorState.create({doc: "# source"});
    assert.equal(paste.sourceNativeSelectionTouchesCode(stateWithoutLanguageParser), true);
});

test("empty source needs no parser coverage fence", () => {
    const emptyStateWithoutLanguageParser = EditorState.create({doc: ""});
    assert.equal(paste.sourceNativeSelectionTouchesCode(emptyStateWithoutLanguageParser), false);
});
