import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {resolve} from "node:path";
import {test} from "node:test";
import {JSDOM} from "jsdom";
import * as ts from "typescript";

const repositoryRoot = resolve(import.meta.dirname, "../..");
const corePath = resolve(repositoryRoot, "webview/src/editor/source-native-editor.ts");
const pastePath = resolve(repositoryRoot, "webview/src/editor/source-native-paste.ts");
const syncPath = resolve(repositoryRoot, "webview/src/sync/source-native-sync.ts");
const policyPath = resolve(repositoryRoot, "webview/src/trust/preview-trust-policy.ts");
const localImageCapabilityPath = resolve(repositoryRoot, "webview/src/trust/source-native-local-image-capability.ts");
const localImagePreviewPath = resolve(repositoryRoot, "webview/src/trust/source-native-local-image-preview.ts");
const navigationGuardPath = resolve(repositoryRoot, "webview/src/trust/source-native-navigation-guard.ts");
const bootstrapPath = resolve(repositoryRoot, "webview/src/runtime/source-native-bootstrap.ts");
const VALID_CSP_NONCE = "A".repeat(43);

const dom = new JSDOM(
    `<!doctype html><html><head><meta property="csp-nonce" nonce="${VALID_CSP_NONCE}"></head><body></body></html>`,
    {pretendToBeVisual: true}
);
for (const [name, value] of [
    ["window", dom.window],
    ["document", dom.window.document],
    ["navigator", dom.window.navigator],
    ["MutationObserver", dom.window.MutationObserver],
    ["DOMParser", dom.window.DOMParser],
    ["Element", dom.window.Element],
    ["HTMLElement", dom.window.HTMLElement],
    ["HTMLImageElement", dom.window.HTMLImageElement],
    ["HTMLFormElement", dom.window.HTMLFormElement],
    ["Node", dom.window.Node],
    ["Range", dom.window.Range],
    ["Event", dom.window.Event],
    ["MouseEvent", dom.window.MouseEvent],
    ["KeyboardEvent", dom.window.KeyboardEvent],
    ["SubmitEvent", dom.window.SubmitEvent],
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

function transpile(source, replacements = new Map()) {
    let output = ts.transpileModule(source, {
        compilerOptions: {target: ts.ScriptTarget.ES2023, module: ts.ModuleKind.ESNext}
    }).outputText;
    for (const [from, to] of replacements) {
        output = output.replaceAll(from, to);
    }
    return output;
}

const coreUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(
    transpile(readFileSync(corePath, "utf8"), packageUrls)
)}`;
const pasteUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(
    transpile(readFileSync(pastePath, "utf8"), packageUrls)
)}`;
const syncUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(
    transpile(readFileSync(syncPath, "utf8"), new Map([["../editor/source-native-editor.ts", coreUrl]]))
)}`;
const policyUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(
    transpile(readFileSync(policyPath, "utf8"))
)}`;
const localImageCapabilityUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(
    transpile(readFileSync(localImageCapabilityPath, "utf8"), new Map([["./preview-trust-policy.ts", policyUrl]]))
)}`;
const localImagePreviewUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(
    transpile(readFileSync(localImagePreviewPath, "utf8"), new Map([
        ...packageUrls,
        ["./source-native-local-image-capability.ts", localImageCapabilityUrl]
    ]))
)}`;
const navigationGuardUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(
    transpile(readFileSync(navigationGuardPath, "utf8"), packageUrls)
)}`;
const bootstrapUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(
    transpile(readFileSync(bootstrapPath, "utf8"), new Map([
        ["../editor/source-native-paste.ts", pasteUrl],
        ["../trust/source-native-local-image-preview.ts", localImagePreviewUrl],
        ["../trust/source-native-navigation-guard.ts", navigationGuardUrl],
        ["../sync/source-native-sync.ts", syncUrl]
    ]))
)}`;
const bootstrap = await import(bootstrapUrl);

function parentElement() {
    const parent = document.createElement("div");
    document.body.append(parent);
    return parent;
}

function modifierMouseDown(target, modifiers) {
    const event = new window.MouseEvent("mousedown", {
        bubbles: true,
        cancelable: true,
        button: 0,
        ...modifiers
    });
    target.dispatchEvent(event);
    return event;
}

test("production bootstrap serializes one parser-owned external-navigation request with exact runtime identity", () => {
    const externalRequests = [];
    const syncRequests = [];
    const hostWindow = {
        __markflowSourceNativeSend: (raw) => syncRequests.push(raw),
        __markflowSourceNativeOpenExternal: (raw, onSuccess) => {
            externalRequests.push(raw);
            onSuccess("{\"type\":\"openExternalAccepted\"}");
        }
    };
    const parent = parentElement();
    const attachment = bootstrap.installSourceNativeBootstrap(
        parent,
        hostWindow,
        "?attachmentId=attachment-nav&runtimeToken=runtime-nav"
    );
    const source = "prefix [go](https://example.com/%ED%95%9C%EA%B8%80?q=%E2%9C%93#frag) suffix";
    hostWindow.__markflowSourceNativeReceive(JSON.stringify({
        type: "bootstrapSnapshot",
        attachmentId: "attachment-nav",
        documentRevision: "0",
        source
    }));

    const position = source.indexOf("go") + 1;
    Object.defineProperty(attachment.editor.view, "posAtCoords", {
        configurable: true,
        value: () => position
    });
    const event = modifierMouseDown(attachment.editor.view.contentDOM, {ctrlKey: true});

    assert.equal(event.defaultPrevented, true);
    assert.equal(externalRequests.length, 1);
    assert.deepEqual(JSON.parse(externalRequests[0]), {
        type: "openExternal",
        attachmentId: "attachment-nav",
        runtimeToken: "runtime-nav",
        url: "https://example.com/%ED%95%9C%EA%B8%80?q=%E2%9C%93#frag"
    });
    assert.equal(syncRequests.length, 0);
    assert.equal(attachment.editor.source, source);

    attachment.dispose();
    parent.remove();
});

test("production bootstrap never falls back to sync or embedded navigation when external bridge is absent", () => {
    const syncRequests = [];
    const hostWindow = {
        __markflowSourceNativeSend: (raw) => syncRequests.push(raw)
    };
    const parent = parentElement();
    const attachment = bootstrap.installSourceNativeBootstrap(
        parent,
        hostWindow,
        "?attachmentId=attachment-no-bridge&runtimeToken=runtime-no-bridge"
    );
    const source = "prefix [go](https://example.com/path) suffix";
    hostWindow.__markflowSourceNativeReceive(JSON.stringify({
        type: "bootstrapSnapshot",
        attachmentId: "attachment-no-bridge",
        documentRevision: "0",
        source
    }));

    Object.defineProperty(attachment.editor.view, "posAtCoords", {
        configurable: true,
        value: () => source.indexOf("go") + 1
    });
    const event = modifierMouseDown(attachment.editor.view.contentDOM, {metaKey: true});

    assert.equal(event.defaultPrevented, true);
    assert.equal(syncRequests.length, 0);
    assert.equal(attachment.editor.source, source);

    attachment.dispose();
    parent.remove();
});