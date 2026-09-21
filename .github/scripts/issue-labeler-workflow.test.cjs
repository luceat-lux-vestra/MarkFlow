"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const source = fs.readFileSync(path.resolve(__dirname, "../workflows/issue-labeler.yml"), "utf8");

test("manual backlog reconciliation is opt-in and dry-run first", () => {
  assert.match(source, /dry_run:\n[\s\S]*?default: true/);
  assert.match(source, /backfill:\n[\s\S]*?default: false/);
});

test("mutating dispatch is bound to the default branch", () => {
  assert.ok(source.includes("const defaultBranchRef = `refs/heads/${context.payload.repository.default_branch}`;"));
  assert.ok(source.includes('context.eventName === "workflow_dispatch" && backfill && !dryRun && context.ref !== defaultBranchRef'));
  assert.ok(source.includes("Mutating backfill must run from"));
});

test("write authority stays narrow and trusted code is used", () => {
  assert.match(source, /^permissions:\n  contents: read$/m);
  assert.ok(source.includes("issues: write # Required only for canonical issue-label reconciliation."));
  assert.ok(source.includes("ref: ${{ github.event.repository.default_branch }}"));
  assert.ok(source.includes("persist-credentials: false"));
});

test("ordinary issue events remain independent of manual backfill", () => {
  assert.ok(source.includes('if (context.eventName === "issues")'));
  assert.ok(source.includes('else if (context.eventName === "workflow_dispatch" && backfill)'));
});
