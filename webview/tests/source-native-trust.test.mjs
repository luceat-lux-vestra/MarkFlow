import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {resolve} from "node:path";
import {test} from "node:test";
import {JSDOM} from "jsdom";
import * as ts from "typescript";
import {EditorState} from "@codemirror/state";
import {EditorView} from "@codemirror/view";

const repositoryRoot = resolve(import.meta.dirname, "../..");
const policySourcePath = resolve(repositoryRoot, "webview/src/trust/preview-trust-policy.ts");
const guardSourcePath = resolve(repositoryRoot, "webview/src/trust/source-native-navigation-guard.ts");
const bootstrapSourcePath = resolve(repositoryRoot, "webview/src/runtime/source-native-bootstrap.ts");
const policySourceText = readFileSync(policySourcePath, "utf8");
const guardSourceText = readFileSync(guardSourcePath, "utf8");
const bootstrapSourceText = readFileSync(bootstrapSourcePath, "utf8");

const dom = new JSDOM("<!doctype html><html><body></body></html>", {
    pretendToBeVisual: true,
    url: "https://markflow.invalid/source-native"
});
for (const [name, value] of [
    ["window", dom.window],
    ["document", dom.window.document],
    ["navigator", dom.window.navigator],
    ["MutationObserver", dom.window.MutationObserver],
    ["DOMParser", dom.window.DOMParser],
    ["Element", dom.window.Element],
    ["HTMLElement", dom.window.HTMLElement],
    ["HTMLFormElement", dom.window.HTMLFormElement],
    ["Node", dom.window.Node],
    ["Range", dom.window.Range],
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

function transpileSource(sourceText, packageUrls = new Map()) {
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

const policyModuleUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(transpileSource(policySourceText))}`;
const policy = await import(policyModuleUrl);

const guardPackageUrls = new Map([
    ["@codemirror/state", await import.meta.resolve("@codemirror/state")],
    ["@codemirror/view", await import.meta.resolve("@codemirror/view")]
]);
const guardModuleUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(transpileSource(guardSourceText, guardPackageUrls))}`;
const {installSourceNativeNavigationGuard} = await import(guardModuleUrl);

function withGuard(callback) {
    const parent = document.createElement("div");
    document.body.append(parent);
    const view = new EditorView({
        state: EditorState.create({doc: "source-stays"}),
        parent
    });
    installSourceNativeNavigationGuard(view, parent);
    try {
        return callback({view, root: parent});
    } finally {
        view.destroy();
        parent.remove();
    }
}

function dispatchMouse(target, type, button) {
    const event = new window.MouseEvent(type, {bubbles: true, cancelable: true, button});
    target.dispatchEvent(event);
    return event;
}

test("source-native bootstrap installs the trust guard on its dedicated root without legacy renderer dependencies", () => {
    assert.match(bootstrapSourceText, /installSourceNativeNavigationGuard\(attachment\.editor\.view, parent\)/);
    assert.doesNotMatch(guardSourceText, /@milkdown|prose|Crepe|raw-html-support|mermaid|window\.open|location\s*=/);
    assert.doesNotMatch(policySourceText, /@codemirror|@milkdown|prose|Crepe|raw-html-support|mermaid/);
});

test("executable, local-file, opaque and protocol-relative URL forms fail closed", () => {
    for (const sample of [
        "javascript:alert(1)",
        "JaVaScRiPt:alert(1)",
        "\u0000 \tjava\nscript:alert(1)",
        "%6a%61vascript%3Aalert(1)",
        "%256a%2561vascript%253Aalert(1)",
        "java%09script:alert(1)",
        "vbscript:msgbox(1)"
    ]) {
        assert.deepEqual(policy.classifyPreviewUrl(sample), {kind: "executable", requirement: "denied"}, sample);
    }

    assert.deepEqual(policy.classifyPreviewUrl("data:text/html,<script>1</script>"), {kind: "data", requirement: "denied"});
    assert.deepEqual(policy.classifyPreviewUrl("blob:https://example.com/id"), {kind: "blob", requirement: "denied"});
    assert.deepEqual(policy.classifyPreviewUrl("file:///etc/passwd"), {kind: "file", requirement: "denied"});
    assert.deepEqual(policy.classifyPreviewUrl("//example.com/image.png"), {kind: "protocol-relative", requirement: "denied"});
    assert.deepEqual(policy.classifyPreviewUrl("%2F%2Fexample.com/image.png"), {kind: "protocol-relative", requirement: "denied"});
    assert.deepEqual(policy.classifyPreviewUrl("ftp://example.com/file"), {kind: "external-scheme", requirement: "denied"});
});

test("HTML entity-decoded executable attribute values remain denied", () => {
    const holder = document.createElement("div");
    holder.innerHTML = "<a href=\"java&#x73;cript:alert(1)\">x</a>";
    const href = holder.querySelector("a")?.getAttribute("href");
    assert.equal(href, "javascript:alert(1)");
    assert.deepEqual(policy.classifyPreviewUrl(href), {kind: "executable", requirement: "denied"});
});

test("remote, fragment and local syntax are classified but never implicitly authorized", () => {
    assert.deepEqual(policy.classifyPreviewUrl("https://example.com/a.png"), {
        kind: "remote-https",
        requirement: "remote-resource-opt-in"
    });
    assert.deepEqual(policy.classifyPreviewUrl("http://example.com/a.png"), {
        kind: "remote-http",
        requirement: "remote-resource-opt-in"
    });
    assert.deepEqual(policy.classifyPreviewUrl("#section"), {
        kind: "fragment",
        requirement: "navigation-capability"
    });
    for (const sample of ["image.png", "./image.png", "assets/image.png", "?variant=1"]) {
        assert.deepEqual(policy.classifyPreviewUrl(sample), {
            kind: "document-relative",
            requirement: "local-resource-capability"
        }, sample);
    }
    for (const sample of ["..", "../secret.png", "..\\secret.png", "%2e%2e/secret.png"]) {
        assert.deepEqual(policy.classifyPreviewUrl(sample), {kind: "parent-relative", requirement: "denied"}, sample);
    }
    for (const sample of ["/etc/passwd", "C:\\secret.txt", "\\\\server\\share\\secret.txt"]) {
        assert.deepEqual(policy.classifyPreviewUrl(sample), {kind: "absolute-path", requirement: "denied"}, sample);
    }
    assert.deepEqual(policy.classifyPreviewUrl("https://[::1"), {kind: "malformed", requirement: "denied"});
    assert.deepEqual(policy.classifyPreviewUrl("http ://example.com"), {kind: "malformed", requirement: "denied"});
    assert.deepEqual(policy.classifyPreviewUrl(""), {kind: "malformed", requirement: "denied"});
});

test("active DOM surfaces are denied and unknown elements fail closed", () => {
    for (const tagName of ["script", "style", "iframe", "object", "embed", "form", "base", "meta", "link"]) {
        assert.equal(policy.classifyPreviewElement(tagName), "denied-active", tagName);
    }
    for (const tagName of ["markflow-widget", "svg", "math", "video", ""]) {
        assert.equal(policy.classifyPreviewElement(tagName), "denied-unknown", tagName);
    }
    for (const tagName of ["div", "span", "a", "img", "table", "code"]) {
        assert.equal(policy.classifyPreviewElement(tagName), "passive", tagName);
    }
});

test("active attributes are denied, URL attributes stay gated, and unknown attributes fail closed", () => {
    for (const attributeName of [
        "onclick", "onload", "style", "srcdoc", "srcset", "target", "download",
        "form", "formaction", "formtarget", "ping"
    ]) {
        assert.equal(policy.classifyPreviewAttribute(attributeName), "denied-active", attributeName);
    }
    for (const attributeName of [
        "href", "src", "poster", "cite", "action", "background", "data", "longdesc", "usemap", "xlink:href"
    ]) {
        assert.equal(policy.classifyPreviewAttribute(attributeName), "url-bearing", attributeName);
    }
    for (const attributeName of ["class", "aria-label", "data-markflow", "title", "alt", "colspan"]) {
        assert.equal(policy.classifyPreviewAttribute(attributeName), "passive", attributeName);
    }
    for (const attributeName of ["id", "name", "contenteditable", "autofocus", "unknown-capability", ""]) {
        assert.equal(policy.classifyPreviewAttribute(attributeName), "denied-unknown", attributeName);
    }
});

test("primary, auxiliary and keyboard anchor activation cannot navigate the source-native realm", () => {
    withGuard(({view, root}) => {
        const anchor = document.createElement("a");
        anchor.href = "https://example.com/escape";
        anchor.textContent = "escape";
        root.append(anchor);

        assert.equal(dispatchMouse(anchor, "click", 0).defaultPrevented, true);
        assert.equal(dispatchMouse(anchor, "auxclick", 1).defaultPrevented, true);
        const keyboard = new window.KeyboardEvent("keydown", {bubbles: true, cancelable: true, key: "Enter"});
        anchor.dispatchEvent(keyboard);
        assert.equal(keyboard.defaultPrevented, true);
        assert.equal(view.state.doc.toString(), "source-stays");
    });
});

test("submit events and requestSubmit are prevented inside the guarded source-native root", () => {
    withGuard(({view, root}) => {
        const form = document.createElement("form");
        form.action = "https://example.com/escape";
        const submit = document.createElement("button");
        submit.type = "submit";
        form.append(submit);
        root.append(form);

        const directSubmit = new window.SubmitEvent("submit", {bubbles: true, cancelable: true});
        form.dispatchEvent(directSubmit);
        assert.equal(directSubmit.defaultPrevented, true);

        let requestSubmitPrevented = null;
        form.addEventListener("submit", (event) => {
            requestSubmitPrevented = event.defaultPrevented;
        }, {once: true});
        form.requestSubmit(submit);
        assert.equal(requestSubmitPrevented, true);
        assert.equal(view.state.doc.toString(), "source-stays");
    });
});

test("non-navigation editor interactions are unaffected", () => {
    withGuard(({view, root}) => {
        const inert = document.createElement("span");
        inert.textContent = "inert";
        root.append(inert);

        assert.equal(dispatchMouse(inert, "click", 0).defaultPrevented, false);
        const key = new window.KeyboardEvent("keydown", {bubbles: true, cancelable: true, key: "ArrowDown"});
        inert.dispatchEvent(key);
        assert.equal(key.defaultPrevented, false);
        assert.equal(view.state.doc.toString(), "source-stays");
    });
});

test("destroying the EditorView removes navigation listeners from its stale source-native root", () => {
    const parent = document.createElement("div");
    document.body.append(parent);
    const view = new EditorView({state: EditorState.create({doc: "disposed"}), parent});
    installSourceNativeNavigationGuard(view, parent);
    view.destroy();

    // Append after destroy so this cannot pass merely because EditorView cleanup removed children.
    const anchor = document.createElement("a");
    anchor.href = "#stale";
    parent.append(anchor);
    const staleClick = dispatchMouse(anchor, "click", 0);
    assert.equal(staleClick.defaultPrevented, false);
    parent.remove();
});
