import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import * as ts from "typescript";
import {test} from "node:test";

const sourcePath = new URL("../src/app/raw-html-support.ts", import.meta.url);
const sourceText = await readFile(sourcePath, "utf8");
const utilsUrl = await import.meta.resolve("@milkdown/utils");
const visitUrl = await import.meta.resolve("unist-util-visit");
const transpiled = ts.transpileModule(sourceText, {
    compilerOptions: {
        target: ts.ScriptTarget.ES2023,
        module: ts.ModuleKind.ESNext
    }
}).outputText
    .replace(/from "@milkdown\/utils"/g, `from "${utilsUrl}"`)
    .replace(/from "unist-util-visit"/g, `from "${visitUrl}"`);

const moduleUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(transpiled)}`;
const {
    annotateRawHtmlKinds,
    createHtmlNodeSchema,
    createRawHtmlNodeSchema,
    decodeRawHtmlData,
    encodeRawHtmlData,
    inferRawHtmlKind,
    inferHtmlDisplayKind,
    sanitizeRawHtmlUrl
} = await import(moduleUrl);

test("infers raw html kind from parent context and source fallback", () => {
    assert.equal(inferRawHtmlKind({type: "paragraph"}, "<span>inline</span>"), "inline");
    assert.equal(inferRawHtmlKind({type: "paragraph"}, "<details>\n<summary>Title</summary>\nbody\n</details>"), "block");
    assert.equal(inferRawHtmlKind({type: "root"}, "<details>\n  body\n</details>"), "block");
    assert.equal(inferRawHtmlKind(undefined, "<custom-tag>text</custom-tag>"), "inline");
});

test("annotates html nodes with inferred kind", () => {
    const tree = {
        type: "root",
        children: [
            {
                type: "paragraph",
                children: [
                    {type: "text", value: "Hello "},
                    {type: "html", value: "<span>world</span>"}
                ]
            },
            {type: "html", value: "<details>\n<summary>Title</summary>\nbody\n</details>"}
        ]
    };

    annotateRawHtmlKinds(tree);

    assert.equal(tree.children[0].children[1].data.markflowHtmlKind, "inline");
    assert.equal(tree.children[1].data.markflowHtmlKind, "block");
});

test("round-trips html node attrs through parseMarkdown and toMarkdown", () => {
    const htmlSchema = createHtmlNodeSchema();
    const inlineSchema = createRawHtmlNodeSchema("inline");
    const blockSchema = createRawHtmlNodeSchema("block");

    const htmlParsed = [];
    htmlSchema.parseMarkdown.runner({
        addNode: (_type, attrs) => {
            htmlParsed.push(attrs);
        }
    }, {
        type: "html",
        value: "<span>inline</span>",
        data: {markflowHtmlKind: "inline"}
    }, {name: "html"});

    assert.deepEqual(htmlParsed, [{value: "<span>inline</span>"}]);

    const inlineParsed = [];
    inlineSchema.parseMarkdown.runner({
        addNode: (_type, attrs) => {
            inlineParsed.push(attrs);
        }
    }, {
        type: "html",
        value: "<span>inline</span>",
        data: {markflowHtmlKind: "inline"}
    }, {name: "markflow-html-inline"});

    const blockParsed = [];
    blockSchema.parseMarkdown.runner({
        addNode: (_type, attrs) => {
            blockParsed.push(attrs);
        }
    }, {
        type: "html",
        value: "<details>\n<summary>Title</summary>\nbody\n</details>",
        data: {markflowHtmlKind: "block"}
    }, {name: "markflow-html-block"});

    assert.deepEqual(inlineParsed, [{html: "<span>inline</span>"}]);
    assert.deepEqual(blockParsed, [{html: "<details>\n<summary>Title</summary>\nbody\n</details>"}]);

    const htmlSerialized = [];
    htmlSchema.toMarkdown.runner({
        addNode: (...args) => {
            htmlSerialized.push(args);
        }
    }, {
        type: {name: "html"},
        attrs: {value: "<span>inline</span>"}
    });

    const inlineSerialized = [];
    inlineSchema.toMarkdown.runner({
        addNode: (...args) => {
            inlineSerialized.push(args);
        }
    }, {
        type: {name: "markflow-html-inline"},
        attrs: {html: "<span>inline</span>"}
    });

    const blockSerialized = [];
    blockSchema.toMarkdown.runner({
        addNode: (...args) => {
            blockSerialized.push(args);
        }
    }, {
        type: {name: "markflow-html-block"},
        attrs: {html: "<details>\n<summary>Title</summary>\nbody\n</details>"}
    });

    assert.deepEqual(htmlSerialized, [["html", undefined, "<span>inline</span>"]]);
    assert.deepEqual(inlineSerialized, [["html", undefined, "<span>inline</span>"]]);
    assert.deepEqual(blockSerialized, [["html", undefined, "<details>\n<summary>Title</summary>\nbody\n</details>"]]);
});

test("encodes and decodes raw html data attributes safely", () => {
    const raw = '<details open><summary title="a b">Title</summary></details>';
    const encoded = encodeRawHtmlData(raw);

    assert.equal(decodeRawHtmlData(encoded), raw);
    assert.equal(sanitizeRawHtmlUrl("https://example.com"), "https://example.com");
    assert.equal(sanitizeRawHtmlUrl("javascript:alert(1)"), null);
});

test("infers html display kind from markup shape", () => {
    assert.equal(inferHtmlDisplayKind("<br/>"), "inline");
    assert.equal(inferHtmlDisplayKind("<details>\n<summary>Title</summary>\nbody\n</details>"), "block");
});
