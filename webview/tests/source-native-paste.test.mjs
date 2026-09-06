import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {resolve} from "node:path";
import {test} from "node:test";
import {JSDOM} from "jsdom";
import * as ts from "typescript";
import {forceParsing} from "@codemirror/language";
import {EditorSelection} from "@codemirror/state";

const repositoryRoot = resolve(import.meta.dirname, "../..");
const editorSourcePath = resolve(repositoryRoot, "webview/src/editor/source-native-editor.ts");
const pasteSourcePath = resolve(repositoryRoot, "webview/src/editor/source-native-paste.ts");
const bootstrapSourcePath = resolve(repositoryRoot, "webview/src/runtime/source-native-bootstrap.ts");
const editorSourceText = readFileSync(editorSourcePath, "utf8");
const pasteSourceText = readFileSync(pasteSourcePath, "utf8");
const bootstrapSourceText = readFileSync(bootstrapSourcePath, "utf8");

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

function transpileSource(sourceText) {
    const transpiled = ts.transpileModule(sourceText, {
        compilerOptions: {
            target: ts.ScriptTarget.ES2023,
            module: ts.ModuleKind.ESNext
        }
    }).outputText;
    return [...packageUrls.entries()].reduce(
        (text, [specifier, url]) => text.replaceAll(`from "${specifier}"`, `from "${url}"`),
        transpiled
    );
}

const editorModuleUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(transpileSource(editorSourceText))}`;
const pasteModuleUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(transpileSource(pasteSourceText))}`;
const {SourceNativeEditorCore, SOURCE_NATIVE_MAX_INSERTED_UTF16} = await import(editorModuleUrl);
const {
    installSourceNativeMarkdownPaste,
    looksLikeSourceNativeMarkdownClipboard,
    normalizeSourceNativeClipboardMarkdown,
    sourceNativeClipboardPayload,
    sourceNativeSelectionTouchesCode
} = await import(pasteModuleUrl);

function withCore(initialSource, callback) {
    const parent = document.createElement("div");
    document.body.append(parent);
    const proposals = [];
    const core = new SourceNativeEditorCore({
        parent,
        initialSource,
        onLocalChange: (proposal) => proposals.push(proposal)
    });
    installSourceNativeMarkdownPaste(core.view);
    forceParsing(core.view, initialSource.length);
    try {
        return callback({core, parent, proposals});
    } finally {
        core.dispose();
        parent.remove();
    }
}

function clipboardData({markdown = "", plain = ""} = {}) {
    return {
        getData(type) {
            if (type === "text/markdown") return markdown;
            if (type === "text/plain") return plain;
            return "";
        }
    };
}

function dispatchPaste(core, data = null) {
    const event = new window.Event("paste", {bubbles: true, cancelable: true});
    Object.defineProperty(event, "clipboardData", {configurable: true, value: data});
    core.view.contentDOM.dispatchEvent(event);
    return event;
}

function select(core, from, to = from) {
    core.view.dispatch({selection: {anchor: from, head: to}});
}

test("source-native bootstrap wires the paste feature without legacy editor dependencies", () => {
    assert.match(bootstrapSourceText, /installSourceNativeMarkdownPaste\(attachment\.editor\.view\)/);
    assert.doesNotMatch(pasteSourceText, /@milkdown|prose|Crepe|sourceRevision|source-preserving|markdown-source-buffer|\bLCS\b/);
});

test("payload normalization removes one leading BOM and normalizes only clipboard line endings", () => {
    assert.equal(
        normalizeSourceNativeClipboardMarkdown("\uFEFF# one\r\ntwo\rthree"),
        "# one\ntwo\nthree"
    );
    assert.equal(normalizeSourceNativeClipboardMarkdown("\uFEFF\uFEFFx"), "\uFEFFx");
});

test("clipboard access exceptions fail back without fabricating a payload", () => {
    assert.equal(sourceNativeClipboardPayload({
        getData() {
            throw new Error("clipboard access denied");
        }
    }), null);
});

test("text/markdown wins over text/plain and replacement emits one exact source proposal", () => {
    const source = "before RIGHT after";
    withCore(source, ({core, proposals}) => {
        const from = source.indexOf("RIGHT");
        select(core, from, from + "RIGHT".length);
        const event = dispatchPaste(core, clipboardData({
            markdown: "\uFEFF## markdown\r\nnext",
            plain: "plain fallback"
        }));

        assert.equal(event.defaultPrevented, true);
        assert.equal(core.source, "before ## markdown\nnext after");
        assert.deepEqual(proposals, [{
            coordinateSpace: "pre-transaction",
            changes: [{from, to: from + 5, inserted: "## markdown\nnext"}]
        }]);
    });
});

test("payload-only normalization preserves pre-existing CRLF source around the selection", () => {
    const source = "left\r\nRIGHT\r\ntail";
    withCore(source, ({core, proposals}) => {
        const from = source.indexOf("RIGHT");
        select(core, from, from + 5);
        dispatchPaste(core, clipboardData({markdown: "\uFEFF- one\r\n- two\r- three"}));

        assert.equal(core.source, "left\r\n- one\n- two\n- three\r\ntail");
        assert.equal(core.source.startsWith("left\r\n"), true);
        assert.equal(core.source.endsWith("\r\ntail"), true);
        assert.deepEqual(proposals[0].changes, [{
            from,
            to: from + 5,
            inserted: "- one\n- two\n- three"
        }]);
    });
});

test("conservative plain Markdown routing leaves ordinary prose to CodeMirror default paste", () => {
    for (const sample of [
        "# heading",
        "- list item",
        "[label](https://example.com)",
        "| a | b |\n| --- | --- |\n| 1 | 2 |",
        "$$\nx^2\n$$",
        "<div>source html</div>"
    ]) {
        assert.equal(looksLikeSourceNativeMarkdownClipboard(sample), true, sample);
    }
    assert.equal(looksLikeSourceNativeMarkdownClipboard("ordinary prose only"), false);
    assert.equal(sourceNativeClipboardPayload(clipboardData({plain: "ordinary prose only"})), null);

    withCore("prefix suffix", ({core, proposals}) => {
        const from = "prefix ".length;
        select(core, from);
        dispatchPaste(core, clipboardData({plain: "ordinary prose only"}));
        assert.equal(core.source, "prefix ordinary prose onlysuffix");
        assert.deepEqual(proposals, [{
            coordinateSpace: "pre-transaction",
            changes: [{from, to: from, inserted: "ordinary prose only"}]
        }]);
    });
});

test("Markdown-like text/plain is inserted through the normal local transaction path", () => {
    withCore("start end", ({core, proposals}) => {
        select(core, 6);
        const event = dispatchPaste(core, clipboardData({plain: "\uFEFF> quote\r\n> next"}));
        assert.equal(event.defaultPrevented, true);
        assert.equal(core.source, "start > quote\n> nextend");
        assert.deepEqual(proposals, [{
            coordinateSpace: "pre-transaction",
            changes: [{from: 6, to: 6, inserted: "> quote\n> next"}]
        }]);
    });
});

test("fenced and indented code contexts decline custom Markdown paste and keep default literal text", () => {
    for (const source of ["```md\ninside\n```", "    inside code"]) {
        withCore(source, ({core, proposals}) => {
            const from = source.indexOf("inside") + 2;
            select(core, from);
            forceParsing(core.view, source.length);
            assert.equal(sourceNativeSelectionTouchesCode(core.view.state), true, source);
            dispatchPaste(core, clipboardData({
                markdown: "\uFEFF# should-not-custom-handle\r\n",
                plain: "literal"
            }));
            assert.equal(core.source, `${source.slice(0, from)}literal${source.slice(from)}`);
            assert.deepEqual(proposals, [{
                coordinateSpace: "pre-transaction",
                changes: [{from, to: from, inserted: "literal"}]
            }]);
        });
    }
});

test("selection crossing a code boundary conservatively falls back to default literal paste", () => {
    const source = "before\n\n```\ninside\n```\n\nafter";
    withCore(source, ({core, proposals}) => {
        const from = source.indexOf("before") + 2;
        const to = source.indexOf("inside") + 2;
        select(core, from, to);
        forceParsing(core.view, source.length);
        assert.equal(sourceNativeSelectionTouchesCode(core.view.state), true);
        dispatchPaste(core, clipboardData({
            markdown: "# replacement",
            plain: "literal-default"
        }));
        assert.equal(core.source, `${source.slice(0, from)}literal-default${source.slice(to)}`);
        assert.deepEqual(proposals, [{
            coordinateSpace: "pre-transaction",
            changes: [{from, to, inserted: "literal-default"}]
        }]);
    });
});

test("missing clipboard data is inert while blank custom payload falls through literally", () => {
    withCore("unchanged", ({core, proposals}) => {
        select(core, 3);
        dispatchPaste(core, null);
        assert.equal(core.source, "unchanged");
        assert.deepEqual(proposals, []);

        const blankCustomPayload = clipboardData({markdown: "   ", plain: "\t"});
        assert.equal(sourceNativeClipboardPayload(blankCustomPayload), null);
        dispatchPaste(core, blankCustomPayload);
        assert.equal(core.source, "unc\thanged");
        assert.deepEqual(proposals, [{
            coordinateSpace: "pre-transaction",
            changes: [{from: 3, to: 3, inserted: "\t"}]
        }]);
    });
});

test("UTF-16 coordinates remain exact around non-BMP source and payload", () => {
    const source = "😀AA replace ZZ";
    withCore(source, ({core, proposals}) => {
        const from = source.indexOf("replace");
        select(core, from, from + "replace".length);
        dispatchPaste(core, clipboardData({markdown: "**🌐 value**"}));

        assert.equal(core.source, "😀AA **🌐 value** ZZ");
        assert.deepEqual(proposals, [{
            coordinateSpace: "pre-transaction",
            changes: [{from, to: from + 7, inserted: "**🌐 value**"}]
        }]);
    });
});

test("multiple selections decline the custom paste path rather than guessing payload duplication", () => {
    withCore("abcdef", ({core, proposals}) => {
        core.view.dispatch({
            selection: EditorSelection.create([
                EditorSelection.cursor(1),
                EditorSelection.cursor(4)
            ], 0)
        });
        assert.equal(sourceNativeSelectionTouchesCode(core.view.state), true);
        dispatchPaste(core, clipboardData({markdown: "# x"}));
        assert.equal(core.source, "abcdef");
        assert.deepEqual(proposals, []);
    });
});

test("oversized handled payload is rejected by the existing pre-commit source envelope", () => {
    withCore("safe", ({core, proposals}) => {
        select(core, 2);
        const oversized = "#" + "x".repeat(SOURCE_NATIVE_MAX_INSERTED_UTF16);
        assert.equal(oversized.length, SOURCE_NATIVE_MAX_INSERTED_UTF16 + 1);
        const event = dispatchPaste(core, clipboardData({markdown: oversized}));
        assert.equal(event.defaultPrevented, true);
        assert.equal(core.source, "safe");
        assert.deepEqual(proposals, []);
    });
});

test("raw-HTML-looking plain payload remains source text and does not create a parallel preview path", () => {
    const payload = "<div onclick=\"alert(1)\">source only</div>";
    withCore("x", ({core, proposals}) => {
        select(core, 1);
        const event = dispatchPaste(core, clipboardData({plain: payload}));
        assert.equal(event.defaultPrevented, true);
        assert.equal(core.source, `x${payload}`);
        assert.deepEqual(proposals[0].changes, [{from: 1, to: 1, inserted: payload}]);
        assert.equal(core.view.dom.querySelector("script"), null);
    });
});

test("destroying the editor view removes the realm-local paste handler", () => {
    const parent = document.createElement("div");
    document.body.append(parent);
    const proposals = [];
    const core = new SourceNativeEditorCore({
        parent,
        initialSource: "disposed",
        onLocalChange: (proposal) => proposals.push(proposal)
    });
    installSourceNativeMarkdownPaste(core.view);
    const contentDOM = core.view.contentDOM;
    core.dispose();

    const event = new window.Event("paste", {bubbles: true, cancelable: true});
    Object.defineProperty(event, "clipboardData", {
        configurable: true,
        value: clipboardData({markdown: "# stale"})
    });
    contentDOM.dispatchEvent(event);

    assert.deepEqual(proposals, []);
    parent.remove();
});
