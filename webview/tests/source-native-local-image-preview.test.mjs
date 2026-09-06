import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {resolve} from "node:path";
import {test} from "node:test";
import {JSDOM} from "jsdom";
import * as ts from "typescript";
import {markdown} from "@codemirror/lang-markdown";
import {EditorSelection, EditorState} from "@codemirror/state";
import {EditorView} from "@codemirror/view";

const repositoryRoot = resolve(import.meta.dirname, "../..");
const policyPath = resolve(repositoryRoot, "webview/src/trust/preview-trust-policy.ts");
const capabilityPath = resolve(repositoryRoot, "webview/src/trust/source-native-local-image-capability.ts");
const previewPath = resolve(repositoryRoot, "webview/src/trust/source-native-local-image-preview.ts");
const policySource = readFileSync(policyPath, "utf8");
const capabilitySource = readFileSync(capabilityPath, "utf8");
const previewSource = readFileSync(previewPath, "utf8");

const dom = new JSDOM("<!doctype html><html><body></body></html>", {pretendToBeVisual: true});
for (const [name, value] of [
    ["window", dom.window],
    ["document", dom.window.document],
    ["navigator", dom.window.navigator],
    ["MutationObserver", dom.window.MutationObserver],
    ["Element", dom.window.Element],
    ["HTMLElement", dom.window.HTMLElement],
    ["HTMLImageElement", dom.window.HTMLImageElement],
    ["Node", dom.window.Node],
    ["Range", dom.window.Range],
    ["Event", dom.window.Event],
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

function transpile(source, replacements = new Map()) {
    let output = ts.transpileModule(source, {
        compilerOptions: {
            target: ts.ScriptTarget.ES2023,
            module: ts.ModuleKind.ESNext
        }
    }).outputText;
    for (const [from, to] of replacements) {
        output = output.replaceAll(from, to);
    }
    return output;
}

const packageUrls = new Map([
    ["@codemirror/language", await import.meta.resolve("@codemirror/language")],
    ["@codemirror/state", await import.meta.resolve("@codemirror/state")],
    ["@codemirror/view", await import.meta.resolve("@codemirror/view")]
]);
const policyUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(transpile(policySource))}`;
const capabilityUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(transpile(
    capabilitySource,
    new Map([["./preview-trust-policy.ts", policyUrl]])
))}`;
const previewUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(transpile(
    previewSource,
    new Map([
        ...packageUrls,
        ["./source-native-local-image-capability.ts", capabilityUrl]
    ])
))}`;
const preview = await import(previewUrl);

const CAPABILITY_BASE = `http://127.0.0.1:31337/__markflow_source_image__/${"A".repeat(43)}/`;

function withPreview(source, callback, capabilityBase = CAPABILITY_BASE) {
    const parent = document.createElement("div");
    document.body.append(parent);
    const view = new EditorView({
        state: EditorState.create({doc: source, extensions: [markdown()]}),
        parent
    });
    preview.installSourceNativeLocalImagePreview(view, capabilityBase);
    try {
        return callback({view, parent});
    } finally {
        view.destroy();
        parent.remove();
    }
}

function widgetCount(parent) {
    return parent.querySelectorAll(".cm-source-native-local-image").length;
}

test("parser-proven document-local Markdown image gains a presentation widget without changing source", () => {
    const source = "plain\n![alt](./img/file.png)\nafter";
    withPreview(source, ({view, parent}) => {
        const image = parent.querySelector(".cm-source-native-local-image");
        assert.ok(image instanceof window.HTMLImageElement);
        assert.equal(image.src, CAPABILITY_BASE + "img/file.png");
        assert.equal(image.referrerPolicy, "no-referrer");
        assert.equal(image.getAttribute("aria-hidden"), "true");
        assert.equal(view.state.doc.toString(), source);
    });
});

test("links, escaped text and inline code that resemble images do not create image widgets", () => {
    const source = [
        "plain",
        "[link](image.png)",
        "\\![escaped](image.png)",
        "`![code](image.png)`",
        "![real](image.png)"
    ].join("\n");
    withPreview(source, ({view, parent}) => {
        assert.equal(widgetCount(parent), 1);
        assert.equal(view.state.doc.toString(), source);
    });
});

test("denied URL classes never create a local image widget", () => {
    const source = [
        "plain",
        "![remote](https://example.com/image.png)",
        "![file](file:///tmp/image.png)",
        "![data](data:image/png;base64,AA==)",
        "![blob](blob:https://example.com/id)",
        "![absolute](/image.png)",
        "![parent](../image.png)",
        "![encoded](%2e%2e/image.png)",
        "![protocol](//example.com/image.png)"
    ].join("\n");
    withPreview(source, ({view, parent}) => {
        assert.equal(widgetCount(parent), 0);
        assert.equal(view.state.doc.toString(), source);
    });
});

test("missing or invalid capability degrades to exact source without a widget", () => {
    const source = "plain\n![local](image.png)";
    for (const capability of [null, "", "file:///tmp/", "http://127.0.0.1:31337/not-a-capability/"]) {
        withPreview(source, ({view, parent}) => {
            assert.equal(widgetCount(parent), 0, String(capability));
            assert.equal(view.state.doc.toString(), source);
        }, capability);
    }
});

test("image source cursor boundaries and overlapping selection suppress preview without mutation", () => {
    const imageSource = "![alt](img/file.png)";
    const source = `plain\n${imageSource}\ntail`;
    const imageStart = source.indexOf(imageSource);
    const imageEnd = imageStart + imageSource.length;

    withPreview(source, ({view, parent}) => {
        assert.equal(widgetCount(parent), 1);

        for (const position of [imageStart, imageStart + 4, imageEnd]) {
            view.dispatch({selection: EditorSelection.cursor(position)});
            assert.equal(widgetCount(parent), 0, `cursor=${position}`);
            assert.equal(view.state.doc.toString(), source);

            view.dispatch({selection: EditorSelection.cursor(0)});
            assert.equal(widgetCount(parent), 1, `restored after cursor=${position}`);
            assert.equal(view.state.doc.toString(), source);
        }

        view.dispatch({selection: EditorSelection.range(imageStart - 1, imageStart + 1)});
        assert.equal(widgetCount(parent), 0);
        assert.equal(view.state.doc.toString(), source);

        view.dispatch({selection: EditorSelection.cursor(0)});
        assert.equal(widgetCount(parent), 1);
    });
});

test("multiple images and repeated selection refreshes do not duplicate widgets", () => {
    const source = "plain\n![one](one.png)\n![two](nested/two.png)\ntail";
    withPreview(source, ({view, parent}) => {
        assert.equal(widgetCount(parent), 2);
        for (const position of [1, 2, 3, 4, 0]) {
            view.dispatch({selection: EditorSelection.cursor(position)});
            assert.equal(widgetCount(parent), 2);
            assert.equal(view.state.doc.toString(), source);
        }
    });
});

test("image load failure hides only the failed projection and preserves exact Markdown", () => {
    const source = "plain\n![local](image.png)";
    withPreview(source, ({view, parent}) => {
        const image = parent.querySelector(".cm-source-native-local-image");
        assert.ok(image instanceof window.HTMLImageElement);
        image.dispatchEvent(new window.Event("error"));
        assert.equal(image.hidden, true);
        assert.equal(view.state.doc.toString(), source);
    });
});

test("target preview contains no legacy renderer, generic base, direct fetch or navigation capability", () => {
    assert.doesNotMatch(
        previewSource,
        /@milkdown|Crepe|raw-html-support|document\.createElement\(["']base["']\)|fetch\s*\(|XMLHttpRequest|window\.open|location\s*=|file:/
    );
    assert.match(previewSource, /resolveSourceNativeLocalImageUrl\(destination, capabilityBaseUrl\)/);
    assert.match(previewSource, /node\.name !== "Image"/);
});
