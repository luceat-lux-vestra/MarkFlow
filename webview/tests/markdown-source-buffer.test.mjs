import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import * as ts from "typescript";
import {test} from "node:test";

const sourcePath = new URL("../src/app/markdown-source-buffer.ts", import.meta.url);
const sourceText = await readFile(sourcePath, "utf8");
const remarkParseUrl = await import.meta.resolve("remark-parse");
const unifiedUrl = await import.meta.resolve("unified");
const transpiled = ts.transpileModule(sourceText, {
    compilerOptions: {
        target: ts.ScriptTarget.ES2023,
        module: ts.ModuleKind.ESNext
    }
}).outputText
    .replace(/from "remark-parse"/g, `from "${remarkParseUrl}"`)
    .replace(/from "unified"/g, `from "${unifiedUrl}"`);

const moduleUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(transpiled)}`;
const {updateRawMarkdownFromSerialized} = await import(moduleUrl);

test("preserves raw markdown when only serializer markers differ", () => {
    const raw = [
        "- item one",
        "- item two",
        "",
        "Paragraph text."
    ].join("\n");

    const serialized = [
        "* item one",
        "* item two",
        "",
        "Paragraph text."
    ].join("\n");

    assert.equal(updateRawMarkdownFromSerialized(raw, serialized), raw);
});

test("keeps untouched blocks byte-stable while updating changed blocks", () => {
    const raw = [
        "- item one",
        "- item two",
        "",
        "Paragraph text."
    ].join("\n");

    const serialized = [
        "* item one",
        "* item two",
        "",
        "Paragraph text changed."
    ].join("\n");

    const next = updateRawMarkdownFromSerialized(raw, serialized);

    assert.ok(next.startsWith("- item one"));
    assert.ok(next.includes("Paragraph text changed."));
    assert.ok(!next.includes("* item one"));
    assert.ok(!next.includes("* item two"));
});

test("preserves original blank-line spacing around changed blocks", () => {
    const raw = [
        "Paragraph one.",
        "",
        "",
        "Paragraph two."
    ].join("\n");

    const serialized = [
        "Paragraph one.",
        "",
        "Paragraph two changed."
    ].join("\n");

    const next = updateRawMarkdownFromSerialized(raw, serialized);

    assert.equal(next, [
        "Paragraph one.",
        "",
        "",
        "Paragraph two changed."
    ].join("\n"));
});

test("preserves list spacing when an item changes", () => {
    const raw = [
        "- item one",
        "",
        "- item two",
        ""
    ].join("\n");

    const serialized = [
        "* item one",
        "",
        "* item two changed",
        ""
    ].join("\n");

    const next = updateRawMarkdownFromSerialized(raw, serialized);

    assert.equal(next, [
        "- item one",
        "",
        "- item two changed",
        ""
    ].join("\n"));
    assert.ok(next.includes("- item one"));
    assert.ok(next.includes("- item two changed"));
    assert.ok(!next.includes("* item one"));
    assert.ok(!next.includes("* item two changed"));
});
