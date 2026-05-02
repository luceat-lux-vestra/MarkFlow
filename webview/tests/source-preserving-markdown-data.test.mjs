import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import * as ts from "typescript";
import {test} from "node:test";

const sourcePath = new URL("../src/app/source-preserving-markdown-data.ts", import.meta.url);
const sourceText = await readFile(sourcePath, "utf8");
const transpiled = ts.transpileModule(sourceText, {
    compilerOptions: {
        target: ts.ScriptTarget.ES2023,
        module: ts.ModuleKind.ESNext
    }
}).outputText;

const moduleUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(transpiled)}`;
const {deriveMarkdownSourceDefaults} = await import(moduleUrl);

test("derives list and fence defaults from source markdown", () => {
    const defaults = deriveMarkdownSourceDefaults([
        "- item",
        "1) ordered",
        "```ts",
        "***",
        "# Heading #",
        "Setext heading",
        "---------",
        "This has _emphasis_ and __strong__"
    ].join("\n"));

    assert.equal(defaults.bullet, "-");
    assert.equal(defaults.bulletOther, "*");
    assert.equal(defaults.bulletOrdered, ")");
    assert.equal(defaults.fence, "`");
    assert.equal(defaults.rule, "-");
    assert.equal(defaults.setext, true);
    assert.equal(defaults.closeAtx, true);
    assert.equal(defaults.emphasis, "_");
    assert.equal(defaults.strong, "_");
});

test("derives alternate marker preferences from a source that uses plus bullets, parenthesized ordering, tildes, and underscore rules", () => {
    const defaults = deriveMarkdownSourceDefaults([
        "+ plus bullet",
        "7) ordered",
        "~~~js",
        "___",
        "# Heading #",
        "Setext heading",
        "========",
        "This has _emphasis_ and __strong__"
    ].join("\n"));

    assert.equal(defaults.bullet, "+");
    assert.equal(defaults.bulletOther, "*");
    assert.equal(defaults.bulletOrdered, ")");
    assert.equal(defaults.fence, "~");
    assert.equal(defaults.rule, "_");
    assert.equal(defaults.setext, true);
    assert.equal(defaults.closeAtx, true);
    assert.equal(defaults.emphasis, "_");
    assert.equal(defaults.strong, "_");
});

test("falls back to defaults when syntax markers are absent", () => {
    const defaults = deriveMarkdownSourceDefaults("plain text only");

    assert.equal(defaults.bullet, "-");
    assert.equal(defaults.bulletOther, "*");
    assert.equal(defaults.bulletOrdered, ".");
    assert.equal(defaults.fence, "`");
    assert.equal(defaults.rule, "*");
    assert.equal(defaults.setext, false);
    assert.equal(defaults.closeAtx, false);
    assert.equal(defaults.emphasis, "*");
    assert.equal(defaults.strong, "*");
});

test("detects tab and mixed list indentation", () => {
    const tabDefaults = deriveMarkdownSourceDefaults("\t- nested");
    assert.equal(tabDefaults.listItemIndent, "tab");

    const mixedDefaults = deriveMarkdownSourceDefaults("    - nested");
    assert.equal(mixedDefaults.listItemIndent, "mixed");
});
