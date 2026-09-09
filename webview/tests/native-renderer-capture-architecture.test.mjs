import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import test from "node:test";

const bootstrap = await readFile(new URL("../src/renderer/derived-renderer-bootstrap.ts", import.meta.url), "utf8");
const packageJson = JSON.parse(await readFile(new URL("../package.json", import.meta.url), "utf8"));

test("native presentation capture is optional alongside the original renderer artifact", () => {
    assert.match(bootstrap, /mediaType: result\.mediaType/);
    assert.match(bootstrap, /content: result\.content/);
    assert.match(bootstrap, /presentationMediaType: presentation\?\.mediaType \?\? null/);
    assert.match(bootstrap, /presentationContentBase64: presentation\?\.contentBase64 \?\? null/);
    assert.match(bootstrap, /presentationWidth: presentation\?\.width \?\? null/);
    assert.match(bootstrap, /presentationHeight: presentation\?\.height \?\? null/);
    assert.match(bootstrap, /captureForNativePresentation\(request\.kind, config, result\)\.catch\(\(\) => null\)/);
});

test("capture is bounded before raster allocation", () => {
    assert.match(bootstrap, /MAX_CAPTURE_DIMENSION = 4096/);
    assert.match(bootstrap, /MAX_CAPTURE_PIXELS = 8 \* 1024 \* 1024/);
    assert.match(bootstrap, /Math\.sqrt\(MAX_CAPTURE_PIXELS \/ \(width \* height\)\)/);
    assert.match(bootstrap, /canvas\.width = width/);
    assert.match(bootstrap, /canvas\.height = height/);
});

test("KaTeX capture reuses packaged KaTeX CSS without a second rendering engine", () => {
    assert.match(bootstrap, /import "katex\/dist\/katex\.min\.css"/);
    assert.equal(bootstrap.includes("renderToString"), false);
    assert.equal(bootstrap.includes("mermaid.render"), false);
    assert.equal(packageJson.dependencies.katex, "^0.18.5");
    assert.equal(packageJson.dependencies.mermaid, "11.17.2");
});

test("capture adds no ambient transport or mutation authority", () => {
    for (const forbidden of ["fetch(", "XMLHttpRequest", "WebSocket", "document.write", "localStorage", "sessionStorage"]) {
        assert.equal(bootstrap.includes(forbidden), false, `capture contains forbidden authority: ${forbidden}`);
    }
});
