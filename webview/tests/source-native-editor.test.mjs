import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {resolve} from "node:path";
import {JSDOM} from "jsdom";
import * as ts from "typescript";
import {test} from "node:test";
import {EditorState} from "@codemirror/state";

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

const fixture = (name) => readFileSync(
    resolve(repositoryRoot, "fixtures/markdown-fidelity/cases", `${name}.md`),
    "utf8"
);

function withCore(initialSource, callback) {
    const parent = document.createElement("div");
    document.body.append(parent);
    const proposals = [];
    const core = new SourceNativeEditorCore({
        parent,
        initialSource,
        onLocalChange: (proposal) => proposals.push(proposal)
    });
    try {
        return callback({core, parent, proposals});
    } finally {
        core.dispose();
        parent.remove();
    }
}

for (const fixtureName of [
    "mixed-list-markers",
    "headings-and-delimiters",
    "code-forms-and-fences",
    "whitespace-and-blank-lines",
    "line-endings-lf",
    "line-endings-no-trailing-newline",
    "repeated-similar-blocks"
]) {
    test(`installs ${fixtureName} exactly as source text`, () => {
        const source = fixture(fixtureName);
        withCore(source, ({core, proposals}) => {
            assert.equal(core.source, source);
            assert.deepEqual(proposals, []);
        });
    });
}

test("local insertion exposes pre-transaction UTF-16 coordinates", () => {
    withCore("Hello", ({core, proposals}) => {
        core.view.dispatch({changes: {from: 5, insert: "!"}, userEvent: "input.type"});

        assert.equal(core.source, "Hello!");
        assert.deepEqual(proposals, [{
            coordinateSpace: "pre-transaction",
            changes: [{from: 5, to: 5, inserted: "!"}]
        }]);
    });
});

test("local replacement exposes the replaced range and inserted text", () => {
    withCore("abcdef", ({core, proposals}) => {
        core.view.dispatch({changes: {from: 2, to: 4, insert: "XY"}, userEvent: "input.type"});

        assert.equal(core.source, "abXYef");
        assert.deepEqual(proposals[0], {
            coordinateSpace: "pre-transaction",
            changes: [{from: 2, to: 4, inserted: "XY"}]
        });
    });
});

test("local deletion exposes an empty inserted string", () => {
    withCore("abcdef", ({core, proposals}) => {
        core.view.dispatch({changes: {from: 2, to: 4}, userEvent: "delete.selection"});

        assert.equal(core.source, "abef");
        assert.deepEqual(proposals[0], {
            coordinateSpace: "pre-transaction",
            changes: [{from: 2, to: 4, inserted: ""}]
        });
    });
});

test("surrogate-pair edits use JavaScript UTF-16 positions", () => {
    const source = "A😀BC";
    assert.equal(source.length, 5);

    withCore(source, ({core, proposals}) => {
        core.view.dispatch({changes: {from: 3, insert: "x"}, userEvent: "input.type"});
        assert.deepEqual(proposals.at(-1), {
            coordinateSpace: "pre-transaction",
            changes: [{from: 3, to: 3, inserted: "x"}]
        });

        core.applyHostSource(source);
        core.view.dispatch({changes: {from: 1, to: 3, insert: "🙂"}, userEvent: "input.type"});
        assert.deepEqual(proposals.at(-1), {
            coordinateSpace: "pre-transaction",
            changes: [{from: 1, to: 3, inserted: "🙂"}]
        });

        core.applyHostSource(source);
        core.view.dispatch({changes: {from: 1, to: 3}, userEvent: "delete.selection"});
        assert.deepEqual(proposals.at(-1), {
            coordinateSpace: "pre-transaction",
            changes: [{from: 1, to: 3, inserted: ""}]
        });
        assert.equal(core.source, "ABC");
    });
});

test("one transaction preserves multiple disjoint source changes", () => {
    withCore("0123456789", ({core, proposals}) => {
        core.view.dispatch({
            changes: [
                {from: 1, to: 2, insert: "A"},
                {from: 7, to: 8, insert: "B"}
            ],
            userEvent: "input.type"
        });

        assert.equal(core.source, "0A23456B89");
        assert.deepEqual(proposals, [{
            coordinateSpace: "pre-transaction",
            changes: [
                {from: 1, to: 2, inserted: "A"},
                {from: 7, to: 8, inserted: "B"}
            ]
        }]);
    });
});

test("host-authoritative updates do not echo and later edits use current coordinates", () => {
    withCore("old source", ({core, proposals}) => {
        core.applyHostSource("xy😀z");
        assert.equal(core.source, "xy😀z");
        assert.deepEqual(proposals, []);

        core.view.dispatch({changes: {from: 4, to: 5, insert: "!"}, userEvent: "input.type"});
        assert.equal(core.source, "xy😀!");
        assert.deepEqual(proposals, [{
            coordinateSpace: "pre-transaction",
            changes: [{from: 4, to: 5, inserted: "!"}]
        }]);
    });
});

test("preview refresh and emphasis decorations do not mutate source", () => {
    const source = "This is *emphasis* and **strong**.";
    withCore(source, ({core, proposals}) => {
        core.refreshPreview();
        core.refreshPreview();

        assert.equal(core.source, source);
        assert.deepEqual(proposals, []);
        assert.ok(core.view.dom.querySelector(".cm-source-native-emphasis"));
        assert.ok(core.view.dom.querySelector(".cm-source-native-strong"));
    });
});

test("preview decoration removal follows a source edit without extra mutation", () => {
    withCore("*em*", ({core, proposals}) => {
        assert.ok(core.view.dom.querySelector(".cm-source-native-emphasis"));

        core.view.dispatch({changes: {from: 0, to: 4, insert: "plain"}, userEvent: "input.type"});

        assert.equal(core.source, "plain");
        assert.equal(core.view.dom.querySelector(".cm-source-native-emphasis"), null);
        assert.deepEqual(proposals, [{
            coordinateSpace: "pre-transaction",
            changes: [{from: 0, to: 4, inserted: "plain"}]
        }]);
    });
});

test("cursor and edit behavior remains source-coordinate based inside a marked range", () => {
    withCore("*em*", ({core, proposals}) => {
        core.view.dispatch({selection: {anchor: 2}});
        assert.equal(core.view.state.selection.main.anchor, 2);

        core.view.dispatch({changes: {from: 1, to: 3, insert: "X"}, userEvent: "input.type"});
        assert.equal(core.source, "*X*");
        // A non-atomic mark leaves source positions editable; a cursor inside
        // a replaced range follows CodeMirror's deterministic start mapping.
        assert.equal(core.view.state.selection.main.anchor, 1);
        assert.deepEqual(proposals[0].changes, [{from: 1, to: 3, inserted: "X"}]);
    });
});

test("repeated similar blocks use the directly edited source range", () => {
    const source = fixture("repeated-similar-blocks");
    const target = "second edit target";
    const from = source.indexOf(target);
    assert.notEqual(from, -1);

    withCore(source, ({core, proposals}) => {
        core.view.dispatch({
            changes: {from, to: from + target.length, insert: "second changed target"},
            userEvent: "input.type"
        });

        const expected = source.replace(target, "second changed target");
        assert.equal(core.source, expected);
        assert.match(core.source, /first edit target/);
        assert.match(core.source, /third edit target/);
        assert.deepEqual(proposals[0].changes, [{
            from,
            to: from + target.length,
            inserted: "second changed target"
        }]);
    });
});

test("mixed lexical styles remain unchanged around a local edit", () => {
    const source = fixture("mixed-list-markers");
    const target = "plus item";
    const from = source.indexOf(target);
    assert.notEqual(from, -1);

    withCore(source, ({core}) => {
        core.view.dispatch({changes: {from, to: from + target.length, insert: "plus item changed"}, userEvent: "input.type"});
        assert.equal(core.source, source.replace(target, "plus item changed"));
        assert.match(core.source, /- dash item/);
        assert.match(core.source, /\* star item/);
        assert.match(core.source, /1\) parenthesis ordered item/);
        assert.match(core.source, /  \+ nested plus item/);
    });
});

test("trailing-newline semantics are preserved without implicit normalization", () => {
    const source = fixture("line-endings-no-trailing-newline");
    assert.equal(source.endsWith("\n"), false);

    withCore(source, ({core, proposals}) => {
        assert.equal(core.source, source);
        core.applyHostSource(source);
        assert.deepEqual(proposals, []);

        core.view.dispatch({changes: {from: source.length, insert: "\nnext"}, userEvent: "input.type"});
        assert.equal(core.source, `${source}\nnext`);
        assert.deepEqual(proposals[0].changes, [{from: source.length, to: source.length, inserted: "\nnext"}]);
    });
});

test("CRLF source is retained while default CodeMirror behavior is documented", () => {
    const source = fixture("line-endings-crlf");
    const defaultState = EditorState.create({doc: source});
    assert.equal(defaultState.doc.toString(), source.replaceAll("\r\n", "\n"));

    withCore(source, ({core, proposals}) => {
        assert.equal(core.view.state.facet(EditorState.lineSeparator), "\n");
        assert.equal(core.view.state.doc.lines, 4);
        assert.equal(core.source, source);

        const target = "line two";
        const from = source.indexOf(target);
        core.view.dispatch({changes: {from, to: from + target.length, insert: "line TWO"}, userEvent: "input.type"});
        assert.equal(core.source, source.replace(target, "line TWO"));
        assert.equal(core.source.includes("\r\n"), true);
        assert.deepEqual(proposals[0].changes, [{from, to: from + target.length, inserted: "line TWO"}]);
    });
});

test("disposal detaches the editor and prevents later projection callbacks", () => {
    const parent = document.createElement("div");
    document.body.append(parent);
    const proposals = [];
    const core = new SourceNativeEditorCore({
        parent,
        initialSource: "initial",
        onLocalChange: (proposal) => proposals.push(proposal)
    });

    core.dispose();
    core.dispose();
    core.applyHostSource("must not apply");
    core.refreshPreview();
    core.view.dispatch({changes: {from: 0, to: 7, insert: "must not apply"}, userEvent: "input.type"});

    assert.equal(core.source, "initial");
    assert.deepEqual(proposals, []);
    parent.remove();
});
