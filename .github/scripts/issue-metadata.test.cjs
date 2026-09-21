"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const { expectedType, classify } = require("./issue-metadata.cjs");

test("classifies only explicit repository title protocol", () => {
  assert.equal(expectedType("bug(editor): lost undo"), "type:bug");
  assert.equal(expectedType("feat(renderer): new capability"), "type:feature");
  assert.equal(expectedType("security: harden HTML"), "type:security");
  assert.equal(expectedType("docs: update contract"), "type:docs");
  assert.equal(expectedType("design(leap): choose boundary"), "type:research");
  assert.equal(expectedType("architecture: change owner"), "type:research");
  assert.equal(expectedType("task(ci): add gate"), "type:task");
  assert.equal(expectedType("build(katex): bump engine"), "type:task");
  assert.equal(expectedType("track(governance): audit"), "type:task");
  assert.equal(expectedType("epic(repo): hardening"), "type:task");
  assert.equal(expectedType("hardening(reassessment): refresh controls"), "type:task");
  assert.equal(expectedType("Investigate rendering"), null);
});

test("explicit title repairs conflicting managed type", () => {
  assert.deepEqual(classify({
    title: "docs: contract",
    labels: ["type:task", "area:docs"]
  }), {
    expectedType: "type:docs",
    add: ["type:docs"],
    remove: ["type:task"],
    diagnostics: []
  });
});

test("unknown title preserves maintainer metadata and does not guess", () => {
  assert.deepEqual(classify({
    title: "Investigate rendering",
    labels: ["type:research", "area:webview"]
  }), {
    expectedType: null,
    add: [],
    remove: [],
    diagnostics: []
  });
});

test("unknown untyped title is diagnostic only", () => {
  assert.deepEqual(classify({
    title: "Investigate rendering",
    labels: []
  }), {
    expectedType: null,
    add: [],
    remove: [],
    diagnostics: ["unclassified-title"]
  });
});


test("manual backlog reconciliation is dry-run first and default-branch-only for mutation", () => {
  const workflow = fs.readFileSync(
    path.join(__dirname, "..", "workflows", "issue-labeler.yml"),
    "utf8"
  );
  assert.match(workflow, /dry_run:[\s\S]*?default:\s*true/);
  assert.match(workflow, /backfill:[\s\S]*?default:\s*false/);
  assert.ok(workflow.includes("const defaultBranchRef = `refs/heads/${context.payload.repository.default_branch}`;"));
  assert.ok(workflow.includes(
    'context.eventName === "workflow_dispatch" && backfill && !dryRun && context.ref !== defaultBranchRef'
  ));
  assert.ok(workflow.includes("Mutating backfill must run from"));
  assert.ok(workflow.includes("persist-credentials: false"));
  assert.ok(workflow.includes("ref: ${{ github.event.repository.default_branch }}"));
});
