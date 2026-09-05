import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {resolve} from "node:path";
import {JSDOM} from "jsdom";
import * as ts from "typescript";
import {test} from "node:test";

const repositoryRoot = resolve(import.meta.dirname, "../..");
const corePath = resolve(repositoryRoot, "webview/src/editor/source-native-editor.ts");
const syncPath = resolve(repositoryRoot, "webview/src/sync/source-native-sync.ts");
const bootstrapPath = resolve(repositoryRoot, "webview/src/runtime/source-native-bootstrap.ts");

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

const coreUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(
    transpile(readFileSync(corePath, "utf8"), packageUrls)
)}`;
const syncUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(
    transpile(readFileSync(syncPath, "utf8"), new Map([["../editor/source-native-editor.ts", coreUrl]]))
)}`;
const bootstrapUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(
    transpile(readFileSync(bootstrapPath, "utf8"), new Map([["../sync/source-native-sync.ts", syncUrl]]))
)}`;
const bootstrap = await import(bootstrapUrl);

function makeHostWindow(overrides = {}) {
    return Object.assign(
        {
            __markflowSourceNativeSend: undefined,
            __markflowSourceNativeReady: undefined,
            __markflowSourceNativeReceive: undefined,
            __markflowSourceNativeInit: undefined,
            __markflowHostGlueInstalled: undefined
        },
        overrides
    );
}

function makeParent() {
    const parent = document.createElement("div");
    document.body.append(parent);
    return parent;
}

test("missing attachmentId or runtimeToken in location.search fails closed without installing anything", () => {
    const hostWindow = makeHostWindow();
    const parent = makeParent();
    assert.throws(
        () => bootstrap.installSourceNativeBootstrap(parent, hostWindow, "?runtimeToken=t1"),
        bootstrap.SourceNativeBootstrapError
    );
    assert.equal(hostWindow.__markflowSourceNativeReceive, undefined);
    assert.equal(hostWindow.__markflowSourceNativeInit, undefined);

    assert.throws(
        () => bootstrap.installSourceNativeBootstrap(parent, hostWindow, "?attachmentId=a1"),
        bootstrap.SourceNativeBootstrapError
    );
    parent.remove();
});

test("install defines the receive/init seam and does not signal readiness before host glue arrives", () => {
    const hostWindow = makeHostWindow();
    const parent = makeParent();

    const attachment = bootstrap.installSourceNativeBootstrap(parent, hostWindow, "?attachmentId=a1&runtimeToken=t1");

    assert.equal(typeof hostWindow.__markflowSourceNativeReceive, "function");
    assert.equal(typeof hostWindow.__markflowSourceNativeInit, "function");
    assert.equal(attachment.state, "BOOTSTRAP");

    attachment.dispose();
    parent.remove();
});

test("web-first ordering: host glue arriving later still produces exactly one readiness signal", () => {
    const readyCalls = [];
    const hostWindow = makeHostWindow({
        __markflowSourceNativeReady: (raw, onSuccess) => {
            readyCalls.push(raw);
            onSuccess("{\"type\":\"runtimeReadyAck\"}");
        }
    });
    const parent = makeParent();
    const attachment = bootstrap.installSourceNativeBootstrap(parent, hostWindow, "?attachmentId=a1&runtimeToken=t1");

    // Host glue installs after the bootstrap module already ran (the ordering this bootstrap must
    // tolerate without any timer/poll): the host calls the bootstrap-defined init function once.
    hostWindow.__markflowHostGlueInstalled = true;
    hostWindow.__markflowSourceNativeInit();

    assert.equal(readyCalls.length, 1);
    assert.deepEqual(JSON.parse(readyCalls[0]), {type: "runtimeReady", attachmentId: "a1", runtimeToken: "t1"});

    attachment.dispose();
    parent.remove();
});

test("host-first ordering: glue already installed before bootstrap runs signals readiness immediately", () => {
    const readyCalls = [];
    const hostWindow = makeHostWindow({
        __markflowHostGlueInstalled: true,
        __markflowSourceNativeReady: (raw, onSuccess) => {
            readyCalls.push(raw);
            onSuccess("{\"type\":\"runtimeReadyAck\"}");
        }
    });
    const parent = makeParent();
    const attachment = bootstrap.installSourceNativeBootstrap(parent, hostWindow, "?attachmentId=a2&runtimeToken=t2");

    assert.equal(readyCalls.length, 1);
    assert.deepEqual(JSON.parse(readyCalls[0]), {type: "runtimeReady", attachmentId: "a2", runtimeToken: "t2"});

    attachment.dispose();
    parent.remove();
});

test("receive seam forwards host messages into the current attachment and rejects unrelated ids", () => {
    const hostWindow = makeHostWindow();
    const parent = makeParent();
    const attachment = bootstrap.installSourceNativeBootstrap(parent, hostWindow, "?attachmentId=a1&runtimeToken=t1");

    hostWindow.__markflowSourceNativeReceive(JSON.stringify({
        type: "bootstrapSnapshot",
        attachmentId: "someone-else",
        documentRevision: "0",
        source: "should not apply"
    }));
    assert.equal(attachment.state, "BOOTSTRAP");

    hostWindow.__markflowSourceNativeReceive(JSON.stringify({
        type: "bootstrapSnapshot",
        attachmentId: "a1",
        documentRevision: "0",
        source: "hello"
    }));
    assert.equal(attachment.state, "READY");
    assert.equal(attachment.editor.source, "hello");

    attachment.dispose();
    parent.remove();
});

test("local mutation is sent through the host bridge exactly once and ACK is consumed strictly", () => {
    const sent = [];
    const hostWindow = makeHostWindow({
        __markflowSourceNativeSend: (raw, onSuccess) => {
            sent.push(JSON.parse(raw));
            onSuccess(JSON.stringify({
                type: "mutationAccepted",
                attachmentId: "a1",
                requestId: JSON.parse(raw).requestId,
                finalDocumentRevision: "1"
            }));
        }
    });
    const parent = makeParent();
    const attachment = bootstrap.installSourceNativeBootstrap(parent, hostWindow, "?attachmentId=a1&runtimeToken=t1");
    hostWindow.__markflowSourceNativeReceive(JSON.stringify({
        type: "bootstrapSnapshot", attachmentId: "a1", documentRevision: "0", source: "0123456789"
    }));

    attachment.editor.view.dispatch({changes: {from: 0, insert: "Z"}, userEvent: "input.type"});

    assert.equal(sent.length, 1);
    assert.equal(sent[0].type, "mutationRequest");
    assert.equal(sent[0].attachmentId, "a1");
    assert.equal(attachment.state, "READY");
    assert.equal(attachment.currentRevision, "1");

    attachment.dispose();
    parent.remove();
});

test("transport uncertainty when the host bridge is unavailable feeds receiveTransportFailure and fails closed", () => {
    const hostWindow = makeHostWindow(); // no __markflowSourceNativeSend installed
    const parent = makeParent();
    const attachment = bootstrap.installSourceNativeBootstrap(parent, hostWindow, "?attachmentId=a1&runtimeToken=t1");
    hostWindow.__markflowSourceNativeReceive(JSON.stringify({
        type: "bootstrapSnapshot", attachmentId: "a1", documentRevision: "0", source: "abc"
    }));

    attachment.editor.view.dispatch({changes: {from: 0, insert: "Z"}, userEvent: "input.type"});

    // No transport available to carry the mutation: the attachment must fail closed rather than
    // silently succeed or retry.
    assert.equal(attachment.state, "DISPOSED");

    attachment.dispose();
    parent.remove();
});

test("an explicit host transport failure callback also feeds receiveTransportFailure and fails closed", () => {
    const hostWindow = makeHostWindow({
        __markflowSourceNativeSend: (_raw, _onSuccess, onFailure) => {
            onFailure(500, "transport failed");
        }
    });
    const parent = makeParent();
    const attachment = bootstrap.installSourceNativeBootstrap(parent, hostWindow, "?attachmentId=a1&runtimeToken=t1");
    hostWindow.__markflowSourceNativeReceive(JSON.stringify({
        type: "bootstrapSnapshot", attachmentId: "a1", documentRevision: "0", source: "abc"
    }));

    attachment.editor.view.dispatch({changes: {from: 0, insert: "Z"}, userEvent: "input.type"});

    assert.equal(attachment.state, "DISPOSED");

    attachment.dispose();
    parent.remove();
});
