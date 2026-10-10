import assert from "node:assert/strict";
import {mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {dirname, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import test from "node:test";
import {buildCapturePlan, MANIFEST_COLUMNS} from "../../tools/differential-acceptance/plan.mjs";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const exactHead = "a".repeat(40);

test("#353 capture plan consumes every reviewed corpus row and every Mermaid block", () => {
  const plan = buildCapturePlan(root, exactHead);
  const manifest = readFileSync(resolve(root, "fixtures/differential-acceptance/corpus.tsv"), "utf8");
  const manifestRows = manifest.trimEnd().split("\n").slice(1);
  assert.equal(plan.manifest_cases, 88);
  assert.equal(plan.manifest_cases, manifestRows.length);
  assert.ok(plan.expanded_cases >= 88);
  assert.equal(plan.cases.length, plan.expanded_cases);
  assert.equal(plan.source_head, exactHead);
  assert.equal(plan.execution_status, "not-captured");
  assert.ok(plan.cases.every((item) => item.capture_status === "not-captured"));
  const allManifestIds = new Set(manifestRows.map((line) => line.split("\t")[0]));
  assert.deepEqual(new Set(plan.cases.map((item) => item.manifest_case_id)), allManifestIds);
  const large = plan.cases.filter((item) => item.manifest_case_id === "mermaid-flowchart-large");
  assert.deepEqual(large.map((c) => c.block_index), [1, 2]);
  assert.deepEqual(large.map((c) => c.case_id), [
    "mermaid-flowchart-large--block-01", "mermaid-flowchart-large--block-02"
  ]);
  assert.ok(large[0].source_anchor.source_start_line < large[1].source_anchor.fence_line);
  const minimal = plan.cases.filter((item) => item.manifest_case_id === "mermaid-flowchart-minimal");
  assert.equal(minimal.length, 1);
  const mermaidFamilies = new Set(plan.cases
    .filter((item) => item.domain === "mermaid")
    .map((item) => item.detector));
  assert.equal(mermaidFamilies.size, 37);
  assert.ok(plan.cases.every((c) => /^[a-f0-9]{64}$/.test(c.fixture_sha256)));
  assert.ok(plan.cases.every((c) => c.source_fidelity === "exact-source"));
});

test("#353 capture plan fails closed on tampering, missing and truncated blocks", () => {
  const temp = mkdtempSync(resolve(tmpdir(), "markflow-differential-plan-"));
  try {
    const fixtureDir = resolve(temp, "examples");
    mkdirSync(fixtureDir);
    const fixture = resolve(fixtureDir, "mermaid-flowchart.md");
    const blocks = "```mermaid\nflowchart TD\n A-->B\n```\n\n```mermaid\nflowchart LR\n C-->D\n```\n";
    writeFileSync(fixture, blocks);
    const values = [
      "mermaid-flowchart", "mermaid", "flowchart", "supported", "covered",
      "examples/mermaid-flowchart.md", "exact-source",
      "diagram-semantic-perceptual", "yes", "click", "flowchart", "mermaid,flowchart"
    ];
    const mkManifest = (rows) =>
      MANIFEST_COLUMNS.join("\t") + "\n" + rows.map((r) => r.join("\t")).join("\n") + "\n";
    const manifest = mkManifest([values]);
    const plan = buildCapturePlan(temp, exactHead, manifest);
    assert.equal(plan.manifest_cases, 1);
    assert.equal(plan.expanded_cases, 2);
    assert.deepEqual(plan.cases.map((c) => c.block_index), [1, 2]);
    assert.notEqual(plan.cases[0].source_anchor.block_sha256,
      plan.cases[1].source_anchor.block_sha256);
    assert.throws(() => buildCapturePlan(temp, exactHead, mkManifest([values, values])), /duplicate corpus case ID/);
    const traversal = values.slice();
    traversal[5] = "../outside.md";
    assert.throws(() => buildCapturePlan(temp, exactHead, mkManifest([traversal])),
      /escapes repository/);
    const absent = values.slice();
    absent[5] = "examples/missing.md";
    assert.throws(() => buildCapturePlan(temp, exactHead, mkManifest([absent])), /ENOENT/);
    const gap = values.slice();
    gap[4] = "gap";
    assert.throws(() => buildCapturePlan(temp, exactHead, mkManifest([gap])),
      /fixture gap cannot enter capture plan/);
    const empty = values.slice();
    empty[11] = "mermaid";
    writeFileSync(fixture, "# not a Mermaid diagram\n");
    assert.throws(() => buildCapturePlan(temp, exactHead, mkManifest([empty])),
      /Mermaid case has no fenced block/);
    writeFileSync(fixture, "```mermaid\nflowchart TD\n A-->B\n");
    assert.throws(() => buildCapturePlan(temp, exactHead, manifest),
      /unclosed Mermaid fenced block/);
    writeFileSync(fixture, blocks + "extra\n");
    assert.notEqual(buildCapturePlan(temp, exactHead, manifest).cases[0].fixture_sha256,
      plan.cases[0].fixture_sha256, "fixture substitution must change its identity");
    assert.throws(() => buildCapturePlan(temp, "main", manifest), /exact 40-hex/);
  } finally {
    rmSync(temp, {recursive: true, force: true});
  }
});
