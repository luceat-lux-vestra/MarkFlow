import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {resolve} from "node:path";
import {test} from "node:test";
import {JSDOM} from "jsdom";
import * as ts from "typescript";
import {markdown} from "@codemirror/lang-markdown";
import {forceParsing, syntaxTree} from "@codemirror/language";
import {EditorSelection, EditorState} from "@codemirror/state";
import {EditorView} from "@codemirror/view";

const repositoryRoot = resolve(import.meta.dirname, "../..");
const sourcePath = resolve(repositoryRoot, "webview/src/editor/source-native-editor.ts");
const fixturePath = resolve(repositoryRoot, "fixtures/markdown-fidelity/cases/table-lexical-variants.md");
const sourceText = readFileSync(sourcePath, "utf8");
const tableFixture = readFileSync(fixturePath, "utf8");

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

function syntaxNames(state) {
    const names = [];
    syntaxTree(state).iterate({enter: (node) => names.push(node.name)});
    return names;
}

function strictCommonMarkSyntaxNames(source) {
    const parent = document.createElement("div");
    document.body.append(parent);
    const view = new EditorView({
        state: EditorState.create({doc: source, extensions: [markdown()]}),
        parent
    });
    try {
        forceParsing(view, source.length);
        return syntaxNames(view.state);
    } finally {
        view.destroy();
        parent.remove();
    }
}

function moveCaret(core, offset) {
    core.view.dispatch({selection: {anchor: offset}});
}

test("target grammar enables tables that strict CommonMark leaves as paragraphs", () => {
    const source = "plain\n\n| head | other |\n| --- | --- |\n| alpha | beta |";
    assert.equal(strictCommonMarkSyntaxNames(source).includes("Table"), false);

    withCore(source, ({core, proposals}) => {
        assert.equal(syntaxNames(core.view.state).includes("Table"), true);
        assert.equal(core.source, source);
        assert.deepEqual(proposals, []);
    });
});

test("table configuration does not silently enable unrelated GFM or Pandoc extensions", () => {
    const source = [
        "plain",
        "",
        "- [x] task",
        "",
        "~~strike~~",
        "",
        "https://example.com",
        "",
        "~sub~ ^sup^ :smile:"
    ].join("\n");

    withCore(source, ({core, proposals}) => {
        const names = new Set(syntaxNames(core.view.state));
        for (const forbidden of [
            "Task",
            "TaskMarker",
            "Strikethrough",
            "StrikethroughMark",
            "URL",
            "Subscript",
            "SubscriptMark",
            "Superscript",
            "SuperscriptMark",
            "Emoji"
        ]) {
            assert.equal(names.has(forbidden), false, forbidden);
        }
        assert.equal(core.source, source);
        assert.deepEqual(proposals, []);
    });
});

test("table fidelity fixture previews without lexical mutation and keeps escaped pipes as content", () => {
    withCore(tableFixture, ({core, proposals}) => {
        const rendered = renderedSource(core);
        assert.ok(core.view.dom.querySelector(".cm-source-native-table-header"));
        assert.ok(core.view.dom.querySelector(".cm-source-native-table-row"));
        assert.ok(core.view.dom.querySelector(".cm-source-native-table-cell"));
        assert.ok(core.view.dom.querySelector(".cm-source-native-table-delimiter-row"));
        assert.equal(rendered.includes("| left | center | right |"), false);
        assert.equal(rendered.includes("| :--- | :----: | ---: |"), true);
        assert.equal(rendered.includes("escaped \\| pipe"), true);
        assert.equal(rendered.includes("code \\| literal"), true);

        core.refreshPreview();
        core.refreshPreview();
        assert.equal(core.source, tableFixture);
        assert.deepEqual(proposals, []);
    });
});

test("caret movement reveals exact table row syntax and moving away restores preview", () => {
    const source = [
        "plain",
        "",
        "| head | other |",
        "| :--- | ---: |",
        "| alpha | beta |",
        "| gamma | delta |"
    ].join("\n");

    withCore(source, ({core, proposals}) => {
        assert.equal(renderedSource(core).includes("| head | other |"), false);
        assert.equal(renderedSource(core).includes("| alpha | beta |"), false);

        moveCaret(core, source.indexOf("head") + 1);
        assert.equal(renderedSource(core).includes("| head | other |"), true);
        assert.equal(renderedSource(core).includes("| alpha | beta |"), false);

        moveCaret(core, source.indexOf(":---") + 1);
        assert.equal(renderedSource(core).includes("| :--- | ---: |"), true);

        moveCaret(core, source.indexOf("alpha") + 1);
        assert.equal(renderedSource(core).includes("| alpha | beta |"), true);
        assert.equal(renderedSource(core).includes("| gamma | delta |"), false);

        moveCaret(core, 0);
        assert.equal(renderedSource(core).includes("| head | other |"), false);
        assert.equal(renderedSource(core).includes("| alpha | beta |"), false);
        assert.equal(core.source, source);
        assert.deepEqual(proposals, []);
    });
});

test("multiple selections reveal only their affected table rows", () => {
    const source = [
        "plain",
        "",
        "| head | other |",
        "| --- | --- |",
        "| alpha | beta |",
        "| gamma | delta |"
    ].join("\n");

    withCore(source, ({core, proposals}) => {
        core.view.dispatch({
            selection: EditorSelection.create([
                EditorSelection.cursor(source.indexOf("head") + 1),
                EditorSelection.cursor(source.indexOf("gamma") + 1)
            ], 0)
        });
        const rendered = renderedSource(core);
        assert.equal(rendered.includes("| head | other |"), true);
        assert.equal(rendered.includes("| alpha | beta |"), false);
        assert.equal(rendered.includes("| gamma | delta |"), true);
        assert.equal(core.source, source);
        assert.deepEqual(proposals, []);
    });
});

test("inline preview composes inside table cells without claiming table delimiters", () => {
    const source = [
        "plain",
        "",
        "| emphasis | link | code |",
        "| --- | --- | --- |",
        "| *value* | [label](https://example.com) | `literal` |"
    ].join("\n");

    withCore(source, ({core, proposals}) => {
        assert.ok(core.view.dom.querySelector(".cm-source-native-table-cell"));
        assert.ok(core.view.dom.querySelector(".cm-source-native-emphasis"));
        assert.ok(core.view.dom.querySelector(".cm-source-native-link"));
        assert.ok(core.view.dom.querySelector(".cm-source-native-inline-code"));

        moveCaret(core, source.indexOf("value") + 1);
        assert.equal(renderedSource(core).includes("*value*"), true);
        assert.equal(core.source, source);
        assert.deepEqual(proposals, []);
    });
});

test("header and delimiter column mismatch degrades to exact source instead of guessed table preview", () => {
    const source = "plain\n\nleft | right\n---\nvalue | other";

    withCore(source, ({core, proposals}) => {
        assert.equal(syntaxNames(core.view.state).includes("Table"), false);
        assert.equal(core.view.dom.querySelector(".cm-source-native-table-header"), null);
        const rendered = renderedSource(core);
        assert.equal(rendered.includes("left | right"), true);
        assert.equal(rendered.includes("---"), true);
        assert.equal(rendered.includes("value | other"), true);
        assert.equal(core.source, source);
        assert.deepEqual(proposals, []);
    });
});

test("GFM variable-width body rows remain supported without source synthesis or dropping", () => {
    const source = [
        "plain",
        "",
        "| head | other |",
        "| --- | --- |",
        "| short |",
        "| alpha | beta | excess |"
    ].join("\n");

    withCore(source, ({core, proposals}) => {
        const names = syntaxNames(core.view.state);
        assert.equal(names.includes("Table"), true);
        assert.equal(names.filter((name) => name === "TableRow").length, 2);
        assert.equal(core.source, source);

        const rendered = renderedSource(core);
        assert.equal(rendered.includes("short"), true);
        assert.equal(rendered.includes("alpha"), true);
        assert.equal(rendered.includes("beta"), true);
        assert.equal(rendered.includes("excess"), true);

        moveCaret(core, source.indexOf("excess") + 1);
        assert.equal(renderedSource(core).includes("| alpha | beta | excess |"), true);

        core.refreshPreview();
        assert.equal(core.source, source);
        assert.deepEqual(proposals, []);
    });
});

test("local table edits retain exact pre-transaction UTF-16 coordinates and unrelated syntax", () => {
    const source = [
        "😀 prefix",
        "",
        "| head | other |",
        "| :--- | ---: |",
        "| alpha | beta |"
    ].join("\n");

    withCore(source, ({core, proposals}) => {
        const from = source.indexOf("alpha") + 1;
        core.view.dispatch({changes: {from, to: from + 2, insert: "LP"}});
        assert.deepEqual(proposals, [{
            coordinateSpace: "pre-transaction",
            changes: [{from, to: from + 2, inserted: "LP"}]
        }]);
        assert.equal(core.source.includes("| :--- | ---: |"), true);
        assert.equal(core.source.includes("| aLPha | beta |"), true);
    });
});

test("host-authoritative table edits refresh preview without local echo", () => {
    const source = "plain\n\n| head | other |\n| --- | --- |\n| alpha | beta |";

    withCore(source, ({core, proposals}) => {
        const from = source.indexOf("alpha");
        assert.equal(core.applyHostEdit({from, to: from + 5, inserted: "gamma"}), true);
        forceParsing(core.view, core.source.length);
        core.refreshPreview();
        assert.equal(core.source.includes("| gamma | beta |"), true);
        assert.ok(core.view.dom.querySelector(".cm-source-native-table-row"));
        assert.deepEqual(proposals, []);
    });
});

test("table selection refresh remains bounded in a large document", () => {
    const table = [
        "| head | other |",
        "| --- | --- |",
        "| target | value |"
    ];
    const source = [
        "plain",
        "",
        ...table,
        ...Array.from({length: 5000}, (_, index) => `unrelated line ${index}`)
    ].join("\n");

    withCore(source, ({core, proposals, previewScans}) => {
        previewScans.length = 0;
        moveCaret(core, source.indexOf("target") + 1);
        assert.ok(previewScans.length > 0);
        assert.ok(previewScans.every((range) => range.to - range.from < source.length / 10));
        assert.equal(core.source, source);
        assert.deepEqual(proposals, []);
    }, {recordPreviewScans: true});
});

test("disposed table preview ignores later refresh and host work", () => {
    const source = "plain\n\n| head | other |\n| --- | --- |\n| alpha | beta |";
    const parent = document.createElement("div");
    document.body.append(parent);
    const proposals = [];
    const previewScans = [];
    const core = new SourceNativeEditorCore({
        parent,
        initialSource: source,
        onLocalChange: (proposal) => proposals.push(proposal),
        onPreviewRangeScanned: (range) => previewScans.push(range)
    });
    forceParsing(core.view, core.source.length);
    core.refreshPreview();
    const scansBeforeDispose = previewScans.length;

    core.dispose();
    core.refreshPreview();

    assert.equal(previewScans.length, scansBeforeDispose);
    assert.equal(core.applyHostSource("changed"), false);
    assert.deepEqual(proposals, []);
    parent.remove();
});
