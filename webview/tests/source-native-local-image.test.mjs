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
const localImagePath = resolve(repositoryRoot, "webview/src/trust/source-native-local-image.ts");
const policySource = readFileSync(policyPath, "utf8");
const localImageSource = readFileSync(localImagePath, "utf8");

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

const policyUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(transpile(policySource))}`;
const localImageUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(transpile(
    localImageSource,
    new Map([
        ["@codemirror/language", await import.meta.resolve("@codemirror/language")],
        ["@codemirror/state", await import.meta.resolve("@codemirror/state")],
        ["@codemirror/view", await import.meta.resolve("@codemirror/view")],
        ["./preview-trust-policy.ts", policyUrl]
    ])
))}`;
const localImage = await import(localImageUrl);

const CAPABILITY_BASE = "http://127.0.0.1:31337/__markflow_source_native_image__/123e4567-e89b-42d3-a456-426614174000/";

function withPreview(source, callback) {
    const parent = document.createElement("div");
    document.body.append(parent);
    const hostWindow = {};
    const view = new EditorView({
        state: EditorState.create({doc: source, extensions: [markdown()]}),
        parent
    });
    localImage.installSourceNativeLocalImagePreview(view, hostWindow);
    try {
        return callback({view, parent, hostWindow});
    } finally {
        view.destroy();
        parent.remove();
    }
}

test("document-relative destinations resolve only inside a validated capability prefix", () => {
    assert.equal(
        localImage.resolveSourceNativeLocalImageUrl("./img/file.png", CAPABILITY_BASE),
        CAPABILITY_BASE + "img/file.png"
    );
    assert.equal(
        localImage.resolveSourceNativeLocalImageUrl("nested/../file.png", CAPABILITY_BASE),
        CAPABILITY_BASE + "file.png"
    );

    for (const blocked of [
        "../outside.png",
        "a/../../outside.png",
        "%2e%2e/outside.png",
        "/absolute.png",
        "//example.com/image.png",
        "file:///tmp/image.png",
        "data:image/png;base64,AA==",
        "blob:https://example.com/id",
        "javascript:alert(1)",
        "https://example.com/image.png"
    ]) {
        assert.equal(localImage.resolveSourceNativeLocalImageUrl(blocked, CAPABILITY_BASE), null, blocked);
    }

    const doubleEncoded = localImage.resolveSourceNativeLocalImageUrl("%252e%252e/outside.png", CAPABILITY_BASE);
    assert.ok(doubleEncoded === null || doubleEncoded.startsWith(CAPABILITY_BASE));
});

test("capability base validation rejects non-loopback, wrong-path and credential-bearing values", () => {
    for (const invalidBase of [
        null,
        "https://127.0.0.1:31337/__markflow_source_native_image__/123e4567-e89b-42d3-a456-426614174000/",
        "http://localhost:31337/__markflow_source_native_image__/123e4567-e89b-42d3-a456-426614174000/",
        "http://127.0.0.1:31337/not-a-capability/123e4567-e89b-42d3-a456-426614174000/",
        "http://user:pass@127.0.0.1:31337/__markflow_source_native_image__/123e4567-e89b-42d3-a456-426614174000/",
        "http://127.0.0.1:31337/__markflow_source_native_image__/not-a-uuid/"
    ]) {
        assert.equal(localImage.resolveSourceNativeLocalImageUrl("image.png", invalidBase), null, String(invalidBase));
    }
});

test("local Markdown image gains a bounded preview widget without changing source", () => {
    const source = "before\n![alt](./img/file.png)\nafter";
    withPreview(source, ({view, parent, hostWindow}) => {
        assert.equal(parent.querySelector(".cm-source-native-local-image"), null);
        assert.equal(typeof hostWindow.__markflowSourceNativeSetLocalImageCapability, "function");

        hostWindow.__markflowSourceNativeSetLocalImageCapability(CAPABILITY_BASE);

        const image = parent.querySelector(".cm-source-native-local-image");
        assert.ok(image instanceof window.HTMLImageElement);
        assert.equal(image.src, CAPABILITY_BASE + "img/file.png");
        assert.equal(image.referrerPolicy, "no-referrer");
        assert.equal(view.state.doc.toString(), source);
    });
});

test("remote and denied image destinations never consume the local capability", () => {
    withPreview("![remote](https://example.com/image.png)\n![file](file:///tmp/x.png)", ({parent, hostWindow}) => {
        hostWindow.__markflowSourceNativeSetLocalImageCapability(CAPABILITY_BASE);
        assert.equal(parent.querySelectorAll(".cm-source-native-local-image").length, 0);
    });
});

test("active image source suppresses the widget and moving away restores it without mutation", () => {
    const source = "![alt](img/file.png)\nplain";
    withPreview(source, ({view, parent, hostWindow}) => {
        hostWindow.__markflowSourceNativeSetLocalImageCapability(CAPABILITY_BASE);
        assert.equal(parent.querySelectorAll(".cm-source-native-local-image").length, 1);

        view.dispatch({selection: EditorSelection.cursor(4)});
        assert.equal(parent.querySelectorAll(".cm-source-native-local-image").length, 0);
        assert.equal(view.state.doc.toString(), source);

        view.dispatch({selection: EditorSelection.cursor(source.length)});
        assert.equal(parent.querySelectorAll(".cm-source-native-local-image").length, 1);
        assert.equal(view.state.doc.toString(), source);
    });
});

test("destroy clears the capability seam and stale setter is inert", () => {
    const parent = document.createElement("div");
    document.body.append(parent);
    const hostWindow = {};
    const view = new EditorView({
        state: EditorState.create({doc: "![alt](image.png)", extensions: [markdown()]}),
        parent
    });
    localImage.installSourceNativeLocalImagePreview(view, hostWindow);
    const staleSetter = hostWindow.__markflowSourceNativeSetLocalImageCapability;
    assert.equal(typeof staleSetter, "function");

    view.destroy();
    assert.equal(hostWindow.__markflowSourceNativeSetLocalImageCapability, undefined);
    assert.doesNotThrow(() => staleSetter(CAPABILITY_BASE));
    parent.remove();
});

test("target local image implementation contains no legacy renderer or direct network/navigation capability", () => {
    assert.doesNotMatch(localImageSource, /@milkdown|Crepe|raw-html-support|window\.open|location\s*=|fetch\s*\(|XMLHttpRequest|file:/);
    assert.match(localImageSource, /classifyPreviewUrl\(destination\)/);
    assert.match(localImageSource, /resolved\.pathname\.startsWith\(base\.pathname\)/);
});
