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
        forceParsing(core.view, Math.min(initialSource.length, 6000));
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

test("inactive representative constructs render preview without mutating source", () => {
    const source = [
        "plain",
        "# Heading",
        "A *em* and **strong** with `code` and [label](https://example.com \"title\")."
    ].join("\n");

    withCore(source, ({core, proposals}) => {
        const rendered = renderedSource(core);

        assert.equal(core.source, source);
        assert.deepEqual(proposals, []);
        assert.ok(core.view.dom.querySelector(".cm-source-native-heading-1"));
        assert.ok(core.view.dom.querySelector(".cm-source-native-emphasis"));
        assert.ok(core.view.dom.querySelector(".cm-source-native-strong"));
        assert.ok(core.view.dom.querySelector(".cm-source-native-inline-code"));
        assert.ok(core.view.dom.querySelector(".cm-source-native-link"));
        assert.equal(rendered.includes("# Heading"), false);
        assert.equal(rendered.includes("*em*"), false);
        assert.equal(rendered.includes("**strong**"), false);
        assert.equal(rendered.includes("`code`"), false);
        assert.equal(rendered.includes("https://example.com"), false);
        assert.match(rendered, /Heading/);
        assert.match(rendered, /label/);
    });
});

test("caret entry reveals exact syntax and moving away restores preview", () => {
    const cases = [
        {source: "plain\n# Heading", target: "Heading", expected: "# Heading"},
        {source: "plain\n*em*", target: "em", expected: "*em*"},
        {source: "plain\n**strong**", target: "strong", expected: "**strong**"},
        {source: "plain\n`code`", target: "code", expected: "`code`"},
        {
            source: "plain\n[label](https://example.com \"title\")",
            target: "label",
            expected: "[label](https://example.com \"title\")"
        }
    ];

    for (const {source, target, expected} of cases) {
        withCore(source, ({core, proposals}) => {
            assert.equal(renderedSource(core).includes(expected), false);

            moveCaret(core, source.indexOf(target) + 1);
            assert.equal(renderedSource(core).includes(expected), true);
            assert.equal(core.source, source);
            assert.deepEqual(proposals, []);

            moveCaret(core, 0);
            assert.equal(renderedSource(core).includes(expected), false);
            assert.equal(core.source, source);
            assert.deepEqual(proposals, []);
        });
    }
});

test("multiple selections reveal every intersected construct deterministically", () => {
    const source = "plain\n*one* and `two`";
    const emphasisOffset = source.indexOf("one") + 1;
    const codeFrom = source.indexOf("two");

    withCore(source, ({core, proposals}) => {
        core.view.dispatch({
            selection: EditorSelection.create([
                EditorSelection.range(emphasisOffset, emphasisOffset),
                EditorSelection.range(codeFrom, codeFrom + 2)
            ])
        });

        const rendered = renderedSource(core);
        assert.equal(rendered.includes("*one*"), true);
        assert.equal(rendered.includes("`two`"), true);
        assert.equal(core.view.state.selection.ranges.length, 2);
        assert.equal(core.source, source);
        assert.deepEqual(proposals, []);
    });
});

test("editing revealed syntax keeps exact UTF-16 proposal coordinates and lexical locality", () => {
    const source = "plain\n*alpha* untouched **beta** 😀";
    const from = source.indexOf("alpha");
    const to = from + "alpha".length;

    withCore(source, ({core, proposals}) => {
        moveCaret(core, from + 1);
        assert.equal(renderedSource(core).includes("*alpha*"), true);

        core.view.dispatch({changes: {from, to, insert: "🙂"}, userEvent: "input.type"});

        assert.equal(core.source, source.slice(0, from) + "🙂" + source.slice(to));
        assert.match(core.source, /untouched \*\*beta\*\* 😀$/);
        assert.deepEqual(proposals, [{
            coordinateSpace: "pre-transaction",
            changes: [{from, to, inserted: "🙂"}]
        }]);
    });
});

test("nested emphasis and strong preview reveals exact source without invalid overlap", () => {
    const source = "plain\n***both***";
    const target = source.indexOf("both") + 1;

    withCore(source, ({core, proposals}) => {
        assert.ok(core.view.dom.querySelector(".cm-source-native-emphasis"));
        assert.ok(core.view.dom.querySelector(".cm-source-native-strong"));
        assert.equal(renderedSource(core).includes("***both***"), false);

        moveCaret(core, target);
        assert.equal(renderedSource(core).includes("***both***"), true);
        assert.equal(core.source, source);
        assert.deepEqual(proposals, []);
    });
});

test("nested strong link preview has no overlapping-invalid decoration and reveals exact nested source", () => {
    const source = "plain\n[**label**](https://example.com)";
    const label = source.indexOf("label");

    withCore(source, ({core, proposals}) => {
        const inactive = renderedSource(core);
        assert.ok(core.view.dom.querySelector(".cm-source-native-link"));
        assert.ok(core.view.dom.querySelector(".cm-source-native-strong"));
        assert.equal(inactive.includes("https://example.com"), false);
        assert.equal(inactive.includes("**label**"), false);

        moveCaret(core, label + 1);
        assert.equal(renderedSource(core).includes("[**label**](https://example.com)"), true);
        assert.equal(core.source, source);
        assert.deepEqual(proposals, []);
    });
});

test("nested link-marker shapes degrade to visible source instead of claiming inner syntax", () => {
    const source = "plain\n[![alt][img]](https://outer.example)\n\n[img]: /image.png";

    withCore(source, ({core, proposals}) => {
        assert.equal(core.view.dom.querySelector(".cm-source-native-link"), null);
        assert.equal(renderedSource(core).includes("[![alt][img]](https://outer.example)"), true);
        assert.equal(core.source, source);
        assert.deepEqual(proposals, []);
    });
});

test("reference and incomplete links degrade to visible source instead of guessed preview", () => {
    const referenceSource = "plain\n[label][ref]\n\n[ref]: /target";
    withCore(referenceSource, ({core, proposals}) => {
        assert.equal(core.view.dom.querySelector(".cm-source-native-link"), null);
        assert.equal(renderedSource(core).includes("[label][ref]"), true);
        assert.equal(core.source, referenceSource);
        assert.deepEqual(proposals, []);
    });

    const incompleteSource = "plain\n[broken](<\n*unterminated\n`unterminated";
    withCore(incompleteSource, ({core, proposals}) => {
        const rendered = renderedSource(core);
        assert.equal(rendered.includes("[broken](<"), true);
        assert.equal(rendered.includes("*unterminated"), true);
        assert.equal(rendered.includes("`unterminated"), true);
        assert.equal(core.source, incompleteSource);
        assert.deepEqual(proposals, []);
    });
});

test("host-authoritative edit refreshes preview without echoing a local proposal", () => {
    const source = "plain\n*old*";
    const from = source.indexOf("old");

    withCore(source, ({core, proposals}) => {
        assert.equal(core.applyHostEdit({from, to: from + 3, inserted: "new"}), true);
        assert.equal(core.source, "plain\n*new*");
        assert.deepEqual(proposals, []);
        assert.equal(renderedSource(core).includes("*new*"), false);

        moveCaret(core, from + 1);
        assert.equal(renderedSource(core).includes("*new*"), true);
        assert.deepEqual(proposals, []);
    });
});

test("selection-driven preview refresh remains bounded in a large document", () => {
    const source = [
        "plain *target*",
        ...Array.from({length: 4000}, (_, index) => `unrelated line ${index} **strong**`)
    ].join("\n");
    const target = source.indexOf("target") + 1;

    withCore(source, ({core, proposals, previewScans}) => {
        const before = previewScans.length;
        moveCaret(core, target);
        const selectionScans = previewScans.slice(before);

        assert.ok(selectionScans.length > 0);
        assert.ok(selectionScans.every((range) => range.to - range.from < source.length / 10));
        assert.equal(core.source, source);
        assert.deepEqual(proposals, []);
    }, {recordPreviewScans: true});
});

test("disposed preview ignores later explicit refresh work", () => {
    const source = "plain\n*em*";
    const parent = document.createElement("div");
    document.body.append(parent);
    const previewScans = [];
    const proposals = [];
    const core = new SourceNativeEditorCore({
        parent,
        initialSource: source,
        onLocalChange: (proposal) => proposals.push(proposal),
        onPreviewRangeScanned: (range) => previewScans.push(range)
    });
    forceParsing(core.view, 1000);
    core.refreshPreview();
    const beforeDispose = previewScans.length;

    core.dispose();
    core.refreshPreview();

    assert.equal(previewScans.length, beforeDispose);
    assert.deepEqual(proposals, []);
    parent.remove();
});
