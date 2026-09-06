import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {resolve} from "node:path";
import {test} from "node:test";
import * as ts from "typescript";

const repositoryRoot = resolve(import.meta.dirname, "../..");
const policyPath = resolve(repositoryRoot, "webview/src/trust/preview-trust-policy.ts");
const capabilityPath = resolve(repositoryRoot, "webview/src/trust/source-native-local-image-capability.ts");
const policySource = readFileSync(policyPath, "utf8");
const capabilitySource = readFileSync(capabilityPath, "utf8");

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
const capabilityUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(
    transpile(capabilitySource, new Map([["./preview-trust-policy.ts", policyUrl]]))
)}`;
const capability = await import(capabilityUrl);

const token = "A".repeat(43);
const baseUrl = `http://127.0.0.1:41234/__markflow_source_image__/${token}/`;

test("resolver accepts only plain document-relative values under an exact target capability prefix", () => {
    assert.equal(
        capability.resolveSourceNativeLocalImageUrl("image.png", baseUrl),
        `${baseUrl}image.png`
    );
    assert.equal(
        capability.resolveSourceNativeLocalImageUrl("./assets/image%20one.png?variant=1#preview", baseUrl),
        `${baseUrl}assets/image%20one.png?variant=1#preview`
    );
    assert.equal(
        capability.resolveSourceNativeLocalImageUrl("assets/./image.png", baseUrl),
        `${baseUrl}assets/image.png`
    );
});

test("every non-document-relative trust class is rejected", () => {
    for (const sample of [
        "../outside.png",
        "%2e%2e/outside.png",
        "%252e%252e%252foutside.png",
        "/absolute.png",
        "//evil.example/image.png",
        "http://example.com/image.png",
        "https://example.com/image.png",
        "file:///tmp/image.png",
        "data:image/png;base64,AAAA",
        "blob:https://example.com/id",
        "javascript:alert(1)",
        "vbscript:msgbox(1)",
        "mailto:test@example.com",
        "#fragment"
    ]) {
        assert.equal(capability.resolveSourceNativeLocalImageUrl(sample, baseUrl), null, sample);
    }
});

test("parent traversal is rejected even when normalization would remain beneath the capability", () => {
    for (const sample of [
        "nested/../image.png",
        "nested/%2e%2e/image.png",
        "nested/%252e%252e%252fimage.png",
        "folder/%2e%2e/%2e%2e/outside.png",
        "folder/../../outside.png",
        "folder/%252e%252e%252foutside.png",
        "assets\\..\\image.png",
        "assets%255c..%255cimage.png"
    ]) {
        assert.equal(capability.resolveSourceNativeLocalImageUrl(sample, baseUrl), null, sample);
    }
});

test("malformed, legacy, or non-loopback capability bases fail closed", () => {
    const invalidBases = [
        null,
        undefined,
        "",
        `https://127.0.0.1:41234/__markflow_source_image__/${token}/`,
        `http://localhost:41234/__markflow_source_image__/${token}/`,
        `http://127.0.0.1/__markflow_source_image__/${token}/`,
        `http://127.0.0.1:41234/__markflow_local__/${token}/`,
        "http://127.0.0.1:41234/__markflow_source_image__/short/",
        `http://user@127.0.0.1:41234/__markflow_source_image__/${token}/`,
        `http://127.0.0.1:41234/__markflow_source_image__/${token}`,
        `http://127.0.0.1:41234/__markflow_source_image__/${token}/?leak=1`,
        `http://127.0.0.1:41234/__markflow_source_image__/${token}/#fragment`
    ];

    for (const invalidBase of invalidBases) {
        assert.equal(capability.resolveSourceNativeLocalImageUrl("image.png", invalidBase), null, String(invalidBase));
    }
});

test("control-bearing paths and directory-only references fail closed", () => {
    for (const sample of ["image\u0000.png", "folder\n/image.png", ".", "./", "?variant=1"]) {
        assert.equal(capability.resolveSourceNativeLocalImageUrl(sample, baseUrl), null, JSON.stringify(sample));
    }
});

test("resolver is a pure authorization transform and contains no fetch side path", () => {
    assert.doesNotMatch(capabilitySource, /\bfetch\s*\(|XMLHttpRequest|window\.open|location\s*=|@milkdown|Crepe|prose/i);
});
