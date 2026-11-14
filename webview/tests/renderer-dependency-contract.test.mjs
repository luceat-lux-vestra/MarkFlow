import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {dirname, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import test from "node:test";

import katex from "katex";

import {
  declaredMermaidDetectors,
  loadMermaidAcceptanceSources,
  mermaidDetectorCases,
} from "./mermaid-acceptance-fixtures.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const packageJson = JSON.parse(readFileSync(resolve(repoRoot, "webview/package.json"), "utf8"));
const browserBackendSource = readFileSync(
  resolve(repoRoot, "webview/src/app/browser-derived-renderer-backends.ts"),
  "utf8",
);

const katexManualExample = readFileSync(resolve(repoRoot, "examples/katex-math.md"), "utf8");

function between(source, prefix, suffix) {
  const start = source.indexOf(prefix);
  assert.ok(start >= 0, "missing manual example prefix: " + prefix);
  const contentStart = start + prefix.length;
  const end = source.indexOf(suffix, contentStart);
  assert.ok(end >= contentStart, "missing manual example suffix after: " + prefix);
  return source.slice(contentStart, end);
}

test("reviewed Mermaid dependency version remains explicit", () => {
  assert.equal(
    packageJson.dependencies.mermaid,
    "12.3.0",
    "Mermaid upgrade requires a fresh #354 detector audit",
  );
});

test("browser detector inventory exactly matches the reviewed corpus manifest", () => {
  const declared = declaredMermaidDetectors(repoRoot);
  assert.equal(
    mermaidDetectorCases.length,
    new Set(mermaidDetectorCases).size,
    "browser detector case names must be unique",
  );
  assert.deepEqual(
    [...new Set(mermaidDetectorCases)].sort(),
    [...declared].sort(),
    "every corpus Mermaid detector must have an explicit Chromium parse/render case",
  );
});

test("reviewed Mermaid acceptance fixtures remain structurally complete", () => {
  const {sources, allCaseIds} = loadMermaidAcceptanceSources(repoRoot);
  assert.deepEqual(Object.keys(sources).sort(), [...allCaseIds].sort());
});

test("production KaTeX backend keeps the fail-soft and untrusted rendering contract", () => {
  assert.match(browserBackendSource, /katex\.renderToString\(request\.source,/);
  assert.match(browserBackendSource, /throwOnError:\s*false/);
  assert.match(browserBackendSource, /trust:\s*false/);
  assert.match(browserBackendSource, /displayMode/);
});

test("installed KaTeX renders inline and display math through the production API", () => {
  const inline = katex.renderToString(String.raw`E=mc^2`, {
    throwOnError: false,
    trust: false,
    displayMode: false,
  });
  assert.match(inline, /class="katex"/);
  assert.doesNotMatch(inline, /katex-display/);

  const display = katex.renderToString(String.raw`\frac{a}{b}`, {
    throwOnError: false,
    trust: false,
    displayMode: true,
  });
  assert.match(display, /katex-display/);
  assert.match(display, /class="katex"/);
});

test("installed KaTeX preserves malformed-input fail-soft behavior", () => {
  const malformed = katex.renderToString(String.raw`\frac{`, {
    throwOnError: false,
    trust: false,
    displayMode: false,
  });
  assert.match(malformed, /katex-error/);
});

test("repository KaTeX manual example keeps malformed formulas fail-soft and later valid math renderable", () => {
  const malformedInline = between(
    katexManualExample,
    "Malformed inline math: $",
    "$.",
  );
  const malformedDisplay = between(
    katexManualExample,
    "Malformed display math:\n\n$$\n",
    "\n$$",
  );
  const validAfterFailure = between(
    katexManualExample,
    "A valid formula after the failures must still render: $",
    "$.",
  );

  const inlineError = katex.renderToString(malformedInline, {
    throwOnError: false,
    trust: false,
    displayMode: false,
  });
  assert.match(inlineError, /katex-error/);

  const displayError = katex.renderToString(malformedDisplay, {
    throwOnError: false,
    trust: false,
    displayMode: true,
  });
  assert.match(displayError, /katex-error/);

  const recovered = katex.renderToString(validAfterFailure, {
    throwOnError: false,
    trust: false,
    displayMode: false,
  });
  assert.match(recovered, /class="katex"/);
  assert.doesNotMatch(recovered, /katex-error/);
  assert.match(recovered, /sqrt/);
});

test("installed KaTeX does not create trusted links when trust is disabled", () => {
  const untrusted = katex.renderToString(String.raw`\href{javascript:alert(1)}{x}`, {
    throwOnError: false,
    trust: false,
    displayMode: false,
  });
  assert.doesNotMatch(untrusted, /<a\b/i);
});
