import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {resolve} from "node:path";
import {JSDOM} from "jsdom";
import * as ts from "typescript";
import {test} from "node:test";

const repositoryRoot = resolve(import.meta.dirname, "../..");
const cspPath = resolve(repositoryRoot, "webview/src/trust/source-native-csp.ts");
const productionBootstrapPath = resolve(repositoryRoot, "webview/src/runtime/source-native-production-bootstrap.ts");
const editorPath = resolve(repositoryRoot, "webview/src/editor/source-native-editor.ts");

const dom = new JSDOM("<!doctype html><html><head></head><body></body></html>", {pretendToBeVisual: true});
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

const cspUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(
    transpile(readFileSync(cspPath, "utf8"))
)}`;
const csp = await import(cspUrl);

const bootstrapStubUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(`
export const calls = [];
export class SourceNativeBootstrapError extends Error {
    constructor(message) {
        super(message);
        this.name = "SourceNativeBootstrapError";
    }
}
export function installSourceNativeBootstrap(...args) {
    calls.push(args);
    return {kind: "stub-attachment"};
}
`)}`;
const bootstrapStub = await import(bootstrapStubUrl);
const productionBootstrapUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(
    transpile(readFileSync(productionBootstrapPath, "utf8"), new Map([
        ["../trust/source-native-csp.ts", cspUrl],
        ["./source-native-bootstrap.ts", bootstrapStubUrl]
    ]))
)}`;
const productionBootstrap = await import(productionBootstrapUrl);

const packageUrls = new Map([
    ["@codemirror/lang-markdown", await import.meta.resolve("@codemirror/lang-markdown")],
    ["@codemirror/language", await import.meta.resolve("@codemirror/language")],
    ["@codemirror/state", await import.meta.resolve("@codemirror/state")],
    ["@codemirror/view", await import.meta.resolve("@codemirror/view")]
]);
const editorUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(
    transpile(readFileSync(editorPath, "utf8"), packageUrls)
)}`;
const {SourceNativeEditorCore} = await import(editorUrl);

const VALID_NONCE = "A".repeat(43);

function resetDocument() {
    document.head.replaceChildren();
    document.body.replaceChildren();
}

function addNonceMeta(nonce) {
    const meta = document.createElement("meta");
    meta.setAttribute("property", "csp-nonce");
    meta.nonce = nonce;
    document.head.append(meta);
    return meta;
}

function makeParent() {
    const parent = document.createElement("div");
    document.body.append(parent);
    return parent;
}

function assertProductionRejectsWithoutBootstrap(setup) {
    resetDocument();
    setup?.();
    const before = bootstrapStub.calls.length;
    const parent = makeParent();
    const hostWindow = {};
    assert.throws(
        () => productionBootstrap.installProductionSourceNativeBootstrap(
            parent,
            hostWindow,
            "?attachmentId=a1&runtimeToken=t1"
        ),
        (error) => error?.name === "SourceNativeBootstrapError"
    );
    assert.equal(bootstrapStub.calls.length, before);
    assert.equal(parent.children.length, 0);
}

test("production bootstrap rejects missing duplicate placeholder and malformed nonce before ownership", () => {
    assertProductionRejectsWithoutBootstrap();
    assertProductionRejectsWithoutBootstrap(() => {
        addNonceMeta(VALID_NONCE);
        addNonceMeta(VALID_NONCE);
    });
    assertProductionRejectsWithoutBootstrap(() => addNonceMeta(csp.SOURCE_NATIVE_CSP_NONCE_PLACEHOLDER));
    assertProductionRejectsWithoutBootstrap(() => addNonceMeta("é".repeat(43)));
    assertProductionRejectsWithoutBootstrap(() => addNonceMeta("A".repeat(42)));
});

test("production bootstrap forwards exactly one validated response nonce as presentation metadata", () => {
    resetDocument();
    addNonceMeta(VALID_NONCE);
    const parent = makeParent();
    const hostWindow = {};
    const transition = () => {};
    const before = bootstrapStub.calls.length;

    const attachment = productionBootstrap.installProductionSourceNativeBootstrap(
        parent,
        hostWindow,
        "?attachmentId=a1&runtimeToken=t1",
        transition
    );

    assert.deepEqual(attachment, {kind: "stub-attachment"});
    assert.equal(bootstrapStub.calls.length, before + 1);
    const args = bootstrapStub.calls.at(-1);
    assert.equal(args[0], parent);
    assert.equal(args[1], hostWindow);
    assert.equal(args[2], "?attachmentId=a1&runtimeToken=t1");
    assert.equal(args[3], transition);
    assert.equal(args[4], VALID_NONCE);
    assert.equal(csp.readSourceNativeCspNonce(document), VALID_NONCE);
});

test("CodeMirror mounts runtime styles with the exact production nonce without changing source", () => {
    resetDocument();
    const parent = makeParent();
    const proposals = [];
    const core = new SourceNativeEditorCore({
        parent,
        initialSource: "# unchanged",
        cspNonce: VALID_NONCE,
        onLocalChange: (proposal) => proposals.push(proposal)
    });

    try {
        const nonceStyles = [...document.querySelectorAll("style")]
            .filter((style) => style.nonce === VALID_NONCE || style.getAttribute("nonce") === VALID_NONCE);
        assert.ok(nonceStyles.length > 0, "CodeMirror must mount at least one nonce-bearing runtime style");
        assert.equal(core.source, "# unchanged");
        assert.deepEqual(proposals, []);
    } finally {
        core.dispose();
    }
});
