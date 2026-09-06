import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {resolve} from "node:path";
import {test} from "node:test";
import {JSDOM} from "jsdom";
import * as ts from "typescript";
import {markdown} from "@codemirror/lang-markdown";
import {EditorState} from "@codemirror/state";
import {EditorView} from "@codemirror/view";

const dom = new JSDOM("<!doctype html><html><body></body></html>", {pretendToBeVisual: true});
for (const [name, value] of [
    ["window", dom.window],
    ["document", dom.window.document],
    ["navigator", dom.window.navigator],
    ["MutationObserver", dom.window.MutationObserver],
    ["Element", dom.window.Element],
    ["HTMLElement", dom.window.HTMLElement],
    ["Node", dom.window.Node],
    ["Range", dom.window.Range],
    ["MouseEvent", dom.window.MouseEvent],
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

const repositoryRoot = resolve(import.meta.dirname, "../..");
const sourcePath = resolve(repositoryRoot, "webview/src/trust/source-native-navigation-guard.ts");
const sourceText = readFileSync(sourcePath, "utf8");
const packageUrls = new Map([
    ["@codemirror/language", await import.meta.resolve("@codemirror/language")],
    ["@codemirror/state", await import.meta.resolve("@codemirror/state")],
    ["@codemirror/view", await import.meta.resolve("@codemirror/view")]
]);

function transpileSource(source) {
    let output = ts.transpileModule(source, {
        compilerOptions: {target: ts.ScriptTarget.ES2023, module: ts.ModuleKind.ESNext}
    }).outputText;
    for (const [specifier, url] of packageUrls) {
        output = output.replaceAll(`from "${specifier}"`, `from "${url}"`);
    }
    return output;
}

const moduleUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(transpileSource(sourceText))}`;
const {
    decodeParserOwnedMarkdownLinkDestination,
    externalNavigationTargetAt,
    installSourceNativeNavigationGuard
} = await import(moduleUrl);

function target(source) {
    const state = EditorState.create({
        doc: source,
        selection: {anchor: 0},
        extensions: [markdown()]
    });
    return externalNavigationTargetAt(state, source.indexOf("go") + 1);
}

test("CommonMark angle delimiters are removed while percent encoding remains byte-stable", () => {
    const token = "<https://example.com/a%20b?q=%E2%9C%93#frag>";
    assert.equal(
        decodeParserOwnedMarkdownLinkDestination(token),
        "https://example.com/a%20b?q=%E2%9C%93#frag"
    );
    assert.equal(
        target(`prefix [go](${token}) suffix`),
        "https://example.com/a%20b?q=%E2%9C%93#frag"
    );
});

test("CommonMark ASCII-punctuation backslash escapes produce the intended HTTP destination only in the capability payload", () => {
    const token = "https\\://example.com/a\\(b\\)?q=x\\&y#z\\:1";
    const intended = "https://example.com/a(b)?q=x&y#z:1";
    assert.equal(decodeParserOwnedMarkdownLinkDestination(token), intended);

    const source = `prefix [go](${token}) suffix`;
    assert.equal(target(source), intended);
    assert.equal(source, `prefix [go](${token}) suffix`);
});

test("escaped executable schemes become visible to policy and still fail closed", () => {
    const token = "javascript\\:alert(1)";
    assert.equal(decodeParserOwnedMarkdownLinkDestination(token), "javascript:alert(1)");
    assert.equal(target(`prefix [go](${token}) suffix`), null);
});

test("backslashes before non-ASCII-punctuation remain literal and fail browser-side validation", () => {
    const token = "https://example.com/foo\\bar";
    assert.equal(decodeParserOwnedMarkdownLinkDestination(token), token);
    assert.equal(target(`prefix [go](${token}) suffix`), null);
});

test("potential Markdown character references degrade instead of opening a semantically different URL", () => {
    for (const token of [
        "https://example.com/?a=1&amp;b=2",
        "https://example.com/&#x70;ath",
        "https://example.com/&#112;ath"
    ]) {
        assert.equal(decodeParserOwnedMarkdownLinkDestination(token), null, token);
        assert.equal(target(`prefix [go](${token}) suffix`), null, token);
    }

    assert.equal(
        target("prefix [go](https://example.com/?a=1&b=2) suffix"),
        "https://example.com/?a=1&b=2"
    );
});

test("DOM outside CodeMirror content cannot trigger the Markdown-link capability even when coordinates map onto a link", () => {
    const source = "prefix [go](https://example.com/path) suffix";
    const parent = document.createElement("div");
    document.body.append(parent);
    const view = new EditorView({
        state: EditorState.create({doc: source, selection: {anchor: 0}, extensions: [markdown()]}),
        parent
    });
    const opened = [];
    installSourceNativeNavigationGuard(view, parent, (url) => opened.push(url));
    Object.defineProperty(view, "posAtCoords", {
        configurable: true,
        value: () => source.indexOf("go") + 1
    });

    const foreign = document.createElement("a");
    foreign.href = "https://attacker.invalid/";
    parent.append(foreign);
    const foreignEvent = new window.MouseEvent("mousedown", {
        bubbles: true,
        cancelable: true,
        button: 0,
        ctrlKey: true
    });
    foreign.dispatchEvent(foreignEvent);
    assert.equal(foreignEvent.defaultPrevented, false);
    assert.deepEqual(opened, []);

    const editorEvent = new window.MouseEvent("mousedown", {
        bubbles: true,
        cancelable: true,
        button: 0,
        ctrlKey: true
    });
    view.contentDOM.dispatchEvent(editorEvent);
    assert.equal(editorEvent.defaultPrevented, true);
    assert.deepEqual(opened, ["https://example.com/path"]);
    assert.equal(view.state.doc.toString(), source);

    view.destroy();
    parent.remove();
});
