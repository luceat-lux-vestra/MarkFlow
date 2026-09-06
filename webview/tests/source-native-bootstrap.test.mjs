import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {resolve} from "node:path";
import {JSDOM} from "jsdom";
import * as ts from "typescript";
import {test} from "node:test";

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
    transpile(readFileSync(localImageCapabilityPath, "utf8"), new Map([
        ["./preview-trust-policy.ts", policyUrl]
    ]))
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
const {EditorView} = await import("@codemirror/view");

const CAPABILITY_BASE = `http://127.0.0.1:31337/__markflow_source_image__/${"A".repeat(43)}/`;

function makeHostWindow(overrides = {}) {
    return Object.assign(
        {
            __markflowSourceNativeSend: undefined,
            __markflowSourceNativeReady: undefined,
            __markflowSourceNativeLocalImageBaseUrl: undefined,
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

function setCspNonceMetas(values) {
    document.head.querySelectorAll('meta[property="csp-nonce"]').forEach((meta) => meta.remove());
    for (const value of values) {
        const meta = document.createElement("meta");
        meta.setAttribute("property", "csp-nonce");
        meta.nonce = value;
        document.head.append(meta);
    }
}

function assertCspBootstrapFailure(values) {
    setCspNonceMetas(values);
    const hostWindow = makeHostWindow();
    const parent = makeParent();
    assert.throws(
        () => bootstrap.installSourceNativeBootstrap(parent, hostWindow, "?attachmentId=a-csp&runtimeToken=t-csp"),
        bootstrap.SourceNativeBootstrapError
    );
    assert.equal(parent.childElementCount, 0);
    assert.equal(hostWindow.__markflowSourceNativeReceive, undefined);
    assert.equal(hostWindow.__markflowSourceNativeInit, undefined);
    parent.remove();
}

test("valid single CSP nonce is accepted and reaches CodeMirror runtime style construction", () => {
    setCspNonceMetas([VALID_CSP_NONCE]);
    const hostWindow = makeHostWindow();
    const parent = makeParent();
    const attachment = bootstrap.installSourceNativeBootstrap(parent, hostWindow, "?attachmentId=a-csp&runtimeToken=t-csp");

    assert.equal(attachment.editor.view.state.facet(EditorView.cspNonce), VALID_CSP_NONCE);
    const runtimeStyles = [...document.head.querySelectorAll("style")];
    assert.ok(runtimeStyles.some((style) => style.nonce === VALID_CSP_NONCE));

    attachment.dispose();
    parent.remove();
});

test("missing duplicate placeholder and malformed CSP nonce metadata fail before bootstrap ownership", () => {
    try {
        assertCspBootstrapFailure([]);
        assertCspBootstrapFailure([VALID_CSP_NONCE, VALID_CSP_NONCE]);
        assertCspBootstrapFailure([bootstrap.SOURCE_NATIVE_CSP_NONCE_PLACEHOLDER]);
        for (const malformed of ["", "short", "A".repeat(42), "A".repeat(44), `+${"A".repeat(42)}`]) {
            assertCspBootstrapFailure([malformed]);
        }
    } finally {
        setCspNonceMetas([VALID_CSP_NONCE]);
    }
});

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

test("web-first capability is consumed at init exactly once and remains source-neutral", () => {
    const readyCalls = [];
    const sent = [];
    const hostWindow = makeHostWindow({
        __markflowSourceNativeSend: (raw) => sent.push(JSON.parse(raw)),
        __markflowSourceNativeReady: (raw, onSuccess) => {
            readyCalls.push(raw);
            onSuccess("{\"type\":\"runtimeReadyAck\"}");
        }
    });
    const parent = makeParent();
    const attachment = bootstrap.installSourceNativeBootstrap(parent, hostWindow, "?attachmentId=a-local&runtimeToken=t-local");

    assert.equal(parent.querySelectorAll(".cm-source-native-local-image").length, 0);
    hostWindow.__markflowSourceNativeLocalImageBaseUrl = CAPABILITY_BASE;
    hostWindow.__markflowHostGlueInstalled = true;
    hostWindow.__markflowSourceNativeInit();
    hostWindow.__markflowSourceNativeInit(); // preview install is idempotent even if init is repeated

    hostWindow.__markflowSourceNativeReceive(JSON.stringify({
        type: "bootstrapSnapshot",
        attachmentId: "a-local",
        documentRevision: "0",
        source: "plain\n![local](image.png)"
    }));

    assert.equal(parent.querySelectorAll(".cm-source-native-local-image").length, 1);
    assert.equal(attachment.editor.source, "plain\n![local](image.png)");
    assert.equal(sent.length, 0);
    assert.equal(readyCalls.length, 2);

    attachment.dispose();
    parent.remove();
});

test("host-first capability is available before immediate init and later bootstrap projection renders it", () => {
    const readyCalls = [];
    const hostWindow = makeHostWindow({
        __markflowHostGlueInstalled: true,
        __markflowSourceNativeLocalImageBaseUrl: CAPABILITY_BASE,
        __markflowSourceNativeReady: (raw, onSuccess) => {
            readyCalls.push(raw);
            onSuccess("{\"type\":\"runtimeReadyAck\"}");
        }
    });
    const parent = makeParent();
    const attachment = bootstrap.installSourceNativeBootstrap(parent, hostWindow, "?attachmentId=a-host&runtimeToken=t-host");

    hostWindow.__markflowSourceNativeReceive(JSON.stringify({
        type: "bootstrapSnapshot",
        attachmentId: "a-host",
        documentRevision: "0",
        source: "plain\n![local](nested/image.png)"
    }));

    assert.equal(readyCalls.length, 1);
    const image = parent.querySelector(".cm-source-native-local-image");
    assert.ok(image instanceof window.HTMLImageElement);
    assert.equal(image.src, CAPABILITY_BASE + "nested/image.png");
    assert.equal(attachment.editor.source, "plain\n![local](nested/image.png)");

    attachment.dispose();
    parent.remove();
});

test("stale init after attachment disposal cannot reinstall preview or signal readiness", () => {
    const readyCalls = [];
    const hostWindow = makeHostWindow({
        __markflowSourceNativeLocalImageBaseUrl: CAPABILITY_BASE,
        __markflowSourceNativeReady: (raw) => readyCalls.push(raw)
    });
    const parent = makeParent();
    const attachment = bootstrap.installSourceNativeBootstrap(parent, hostWindow, "?attachmentId=a-stale&runtimeToken=t-stale");
    const staleInit = hostWindow.__markflowSourceNativeInit;

    attachment.dispose();
    assert.doesNotThrow(() => staleInit());
    assert.equal(readyCalls.length, 0);
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
    assert.equal(JSON.stringify(sent[0]).includes(VALID_CSP_NONCE), false);
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