import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {dirname, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import test from "node:test";

import {
  declaredMermaidDetectors,
  loadMermaidAcceptanceSources,
  mermaidDetectorCases,
} from "./mermaid-acceptance-fixtures.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const packageJson = JSON.parse(readFileSync(resolve(repoRoot, "webview/package.json"), "utf8"));

test("reviewed Mermaid dependency version remains explicit", () => {
  assert.equal(
    packageJson.dependencies.mermaid,
    "12.2.0",
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
