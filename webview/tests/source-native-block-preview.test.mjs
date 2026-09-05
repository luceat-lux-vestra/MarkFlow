import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {resolve} from "node:path";
import {test} from "node:test";
import {JSDOM} from "jsdom";
import * as ts from "typescript";
import {forceParsing} from "@codemirror/language";
import {EditorSelection} from "@codemirror/state";

const repositoryRoot = resolve(import.meta.dirname, "../..");
const sourcePath = resolve(repositoryRoot, "webview/src/editor/source-native-editor.ts");
const sourceText = readFileSync(sourcePath, "utf8");

const dom = new JSDOM("<!doctype html><html><body></body></html>", {pretendToBeVisual: true});
for (const [name, value] of [
    ["window", dom.window],
    ["document", dom.window.document],
    ["navigator", dom.window.navigator],
    ["MutationObserver", dom.window.MutationObserver],
    ["DOMParser", dom.window.DOMParser],
    ["Element", dom.window.Element],
    ["HTMLElement", dom.window.HTMLElement],
    ["Node", dom.window.Node],
    ["Range", dom.window.Range],
    ["getComputedStyle", dom.window.getComputedStyle.bind(dom.window)],
    ["requestAnimationFrame", dom.window.requestAnimationFrame.bind(dom.window)],
    ["cancelAnimationFrame", dom.window.cancelAnimationFrame.bind(dom.window)]
]) {
    Object.defineProperty(globalThis, name, {configurable: true, value});
}
if (globalThis.ResizeObserver === undefined) {
    Object.defineProperty(globalThis, "ResizeObserver", {
        configurable: true,
        value: class {
            observe() {}
            unobserve() {}
            disconnect() {}
        }
    });
}

const packageUrls = new Map([
    ["@codemirror/lang-markdown", await import.meta.resolve("@codemirror/lang-markdown")],
    ["@codemirror/language", await import.meta.resolve("@codemirror/language")],
    ["@codemirror/state", await import.meta.resolve("@codemirror/state")],
    ["@codemirror/view", await import.meta.resolve("@codemirror/view")]
]);
const transpiledSource = [...packageUrls.entries()].reduce(
    (text, [specifier, url]) => text.replaceAll(`from "${specifier}"`, `from "${url}"`),
    ts.transpileModule(sourceText, {
        compilerOptions: {
            target: ts.ScriptTarget.ES2023,
            module: ts.ModuleKind.ESNext
        }
    }).outputText
);
const sourceModuleUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(transpiledSource)}`;
const {SourceNativeEditorCore} = await import(sourceModuleUrl);

function withCore(initialSource, callback, {recordPreviewScans = false} = {}) {
    const parent = document.createElement("div");
    document.body.append(parent);
    const proposals = [];
    const previewScans = [];
    const core = new SourceNativeEditorCore({
        parent,
        initialSource,
        onLocalChange: (proposal) => proposals.push(proposal),
        onPreviewRangeScanned: recordPreviewScans ? (range) => previewScans.push(range) : undefined
    });
    try {
        forceParsing(core.view, Math.min(initialSource.length, 12000));
        core.refreshPreview();
        return callback({core, parent, proposals, previewScans});
    } finally {
        core.dispose();
        parent.remove();
    }
}

function renderedSource(core) {
    return core.view.contentDOM.textContent ?? "";
}

function moveCaret(core, offset) {
    core.view.dispatch({selection: {anchor: offset}});
}

test("inactive block constructs preview without mutating source", () => {
    const source = [
        "plain",
        "- dash item",
        "1) ordered item",
        "",
        "> quoted",
        "> second",
        "",
        "```js",
        "const value = '*literal*';",
        "```",
        "",
        "    indented *literal*",
        "",
        "* * *"
    ].join("\n");

    withCore(source, ({core, proposals}) => {
        const rendered = renderedSource(core);
        assert.equal(core.source, source);
        assert.deepEqual(proposals, []);
        assert.ok(core.view.dom.querySelector(".cm-source-native-list-item"));
        assert.ok(core.view.dom.querySelector(".cm-source-native-blockquote"));
        assert.ok(core.view.dom.querySelector(".cm-source-native-fenced-code"));
        assert.ok(core.view.dom.querySelector(".cm-source-native-indented-code"));
        assert.ok(core.view.dom.querySelector(".cm-source-native-thematic-break"));
        assert.equal(rendered.includes("- dash item"), false);
        assert.equal(rendered.includes("1) ordered item"), false);
        assert.equal(rendered.includes("> quoted"), false);
        assert.equal(rendered.includes("```js"), false);
        assert.equal(rendered.includes("    indented *literal*"), true);
        assert.equal(rendered.includes("* * *"), true);
    });
});

test("mixed and nested list markers reveal exact source only in active list item", () => {
    const source = [
        "plain",
        "- dash item",
        "+ plus item",
        "* star item",
        "1. dot item",
        "2) paren item",
        "- outer",
        "  + nested plus",
        "    1) nested ordered"
    ].join("\n");

    withCore(source, ({core, proposals}) => {
        assert.equal(renderedSource(core).includes("- dash item"), false);
        const nested = source.indexOf("+ nested plus") + 2;
        moveCaret(core, nested);
        const active = renderedSource(core);
        assert.equal(core.source, source);
        assert.deepEqual(proposals, []);
        assert.equal(active.includes("+ nested plus"), true);
        assert.equal(active.includes("- dash item"), false);
        assert.equal(active.includes("1) nested ordered"), false);

        moveCaret(core, 0);
        assert.equal(renderedSource(core).includes("+ nested plus"), false);
    });
});

test("multiple selections reveal each independently active list item", () => {
    const source = "plain\n- alpha\n+ beta\n* gamma";

    withCore(source, ({core, proposals}) => {
        core.view.dispatch({
            selection: EditorSelection.create([
                EditorSelection.cursor(source.indexOf("alpha")),
                EditorSelection.cursor(source.indexOf("gamma"))
            ], 0)
        });
        const rendered = renderedSource(core);
        assert.equal(rendered.includes("- alpha"), true);
        assert.equal(rendered.includes("+ beta"), false);
        assert.equal(rendered.includes("* gamma"), true);
        assert.equal(core.source, source);
        assert.deepEqual(proposals, []);
    });
});

test("block quote markers hide outside and reveal across the active quote", () => {
    const source = "plain\n> first\n> second with *emphasis*\n> third";

    withCore(source, ({core, proposals}) => {
        assert.equal(renderedSource(core).includes("> first"), false);
        moveCaret(core, source.indexOf("second"));
        const active = renderedSource(core);
        assert.equal(active.includes("> first"), true);
        assert.equal(active.includes("> second"), true);
        assert.equal(active.includes("> third"), true);
        assert.equal(core.source, source);
        assert.deepEqual(proposals, []);
    });
});

test("fenced code preserves exact fence forms and keeps Markdown-looking code opaque", () => {
    const source = [
        "plain",
        "````ts extra",
        "*not emphasis* [not link](x)",
        "````",
        "",
        "~~~~ shell",
        "- not a list",
        "~~~~"
    ].join("\n");

    withCore(source, ({core, proposals}) => {
        // CodeMirror may split one multiline mark decoration into multiple DOM spans at line
        // boundaries, so DOM element cardinality is not a construct-count invariant.
        assert.ok(core.view.dom.querySelector(".cm-source-native-fenced-code"));
        assert.equal(core.view.dom.querySelectorAll(".cm-source-native-emphasis").length, 0);
        assert.equal(core.view.dom.querySelectorAll(".cm-source-native-link").length, 0);
        assert.equal(renderedSource(core).includes("````ts extra"), false);
        assert.equal(renderedSource(core).includes("~~~~ shell"), false);

        moveCaret(core, source.indexOf("not emphasis"));
        const active = renderedSource(core);
        assert.equal(active.includes("````ts extra"), true);
        assert.equal(active.includes("````"), true);
        assert.equal(active.includes("~~~~ shell"), false);
        assert.equal(core.source, source);
        assert.deepEqual(proposals, []);
    });
});

test("unterminated fenced code degrades to exact visible source", () => {
    const source = "plain\n```lang\n*still code*\nno closing fence";

    withCore(source, ({core, proposals}) => {
        assert.equal(renderedSource(core).includes("```lang"), true);
        assert.equal(core.view.dom.querySelectorAll(".cm-source-native-fenced-code").length, 0);
        assert.equal(core.view.dom.querySelectorAll(".cm-source-native-emphasis").length, 0);
        assert.equal(core.source, source);
        assert.deepEqual(proposals, []);
    });
});

test("indented code is style-only and never hides indentation or parses inline Markdown", () => {
    const source = "plain\n\n    *literal* [link](x)\n    - literal list marker";

    withCore(source, ({core, proposals}) => {
        const rendered = renderedSource(core);
        assert.ok(core.view.dom.querySelector(".cm-source-native-indented-code"));
        assert.equal(core.view.dom.querySelectorAll(".cm-source-native-emphasis").length, 0);
        assert.equal(core.view.dom.querySelectorAll(".cm-source-native-link").length, 0);
        assert.equal(rendered.includes("    *literal* [link](x)"), true);
        assert.equal(rendered.includes("    - literal list marker"), true);
        assert.equal(core.source, source);
        assert.deepEqual(proposals, []);
    });
});

test("thematic break variants stay source-visible and source-neutral", () => {
    const source = "plain\n\n***\n\n* * *\n\n---\n\n- - -\n\n___\n\n_ _ _";

    withCore(source, ({core, proposals}) => {
        assert.ok(core.view.dom.querySelectorAll(".cm-source-native-thematic-break").length >= 6);
        const rendered = renderedSource(core);
        for (const marker of ["***", "* * *", "---", "- - -", "___", "_ _ _"]) {
            assert.equal(rendered.includes(marker), true, marker);
        }
        core.refreshPreview();
        core.refreshPreview();
        assert.equal(core.source, source);
        assert.deepEqual(proposals, []);
    });
});

test("local edits inside block content retain exact pre-transaction UTF-16 coordinates", () => {
    const source = "😀 prefix\n- alpha\n\n```txt\nbeta\n```\nunchanged";

    withCore(source, ({core, proposals}) => {
        const alphaFrom = source.indexOf("alpha") + 1;
        core.view.dispatch({changes: {from: alphaFrom, to: alphaFrom + 2, insert: "LP"}});
        assert.deepEqual(proposals.shift(), {
            coordinateSpace: "pre-transaction",
            changes: [{from: alphaFrom, to: alphaFrom + 2, inserted: "LP"}]
        });

        const afterListEdit = core.source;
        const betaFrom = afterListEdit.indexOf("beta") + 1;
        core.view.dispatch({changes: {from: betaFrom, to: betaFrom + 2, insert: "ET"}});
        assert.deepEqual(proposals.shift(), {
            coordinateSpace: "pre-transaction",
            changes: [{from: betaFrom, to: betaFrom + 2, inserted: "ET"}]
        });
        assert.equal(core.source.includes("- aLPha"), true);
        assert.equal(core.source.includes("bETa"), true);
        assert.equal(core.source.endsWith("unchanged"), true);
    });
});

test("host-authoritative block edits refresh preview without local echo", () => {
    const source = "plain\n- alpha";

    withCore(source, ({core, proposals}) => {
        assert.equal(renderedSource(core).includes("- alpha"), false);
        assert.equal(core.applyHostSource("plain\n> quoted"), true);
        forceParsing(core.view, core.source.length);
        core.refreshPreview();
        assert.ok(core.view.dom.querySelector(".cm-source-native-blockquote"));
        assert.equal(renderedSource(core).includes("> quoted"), false);
        assert.deepEqual(proposals, []);
    });
});

test("selection refresh in a large document remains bounded", () => {
    const lines = Array.from({length: 5000}, (_, index) => `line ${index}`);
    lines[1] = "- target item";
    const source = lines.join("\n");

    withCore(source, ({core, previewScans}) => {
        previewScans.length = 0;
        moveCaret(core, source.indexOf("target"));
        assert.ok(previewScans.length > 0);
        assert.ok(previewScans.every((range) => range.to - range.from < source.length / 10));
        assert.equal(core.source, source);
    }, {recordPreviewScans: true});
});

test("disposal makes later block preview refresh inert", () => {
    const parent = document.createElement("div");
    document.body.append(parent);
    const proposals = [];
    const core = new SourceNativeEditorCore({
        parent,
        initialSource: "plain\n- alpha",
        onLocalChange: (proposal) => proposals.push(proposal)
    });
    forceParsing(core.view, core.source.length);
    core.refreshPreview();
    core.dispose();

    assert.doesNotThrow(() => core.refreshPreview());
    assert.equal(core.applyHostSource("- changed"), false);
    assert.deepEqual(proposals, []);
    parent.remove();
});
