"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const workflow = fs.readFileSync(
  path.resolve(__dirname, "../workflows/issue-labeler.yml"),
  "utf8"
);

test("manual backlog reconciliation is review-first and opt-in", () => {
  assert.match(workflow, /dry_run:[\s\S]*?default:\s*true/);
  assert.match(workflow, /backfill:[\s\S]*?default:\s*false/);
  assert.match(
    workflow,
    /context\.eventName === "workflow_dispatch" && backfill && !dryRun && context\.ref !== defaultBranchRef/
  );
  assert.ok(workflow.includes("Mutating backfill must run from"));
  assert.ok(workflow.includes("github.event.repository.default_branch"));
});

test("manual dispatch without backfill has no mutation path", () => {
  assert.ok(workflow.includes('context.eventName === "workflow_dispatch" && backfill'));
  assert.ok(workflow.includes("No backlog reconciliation selected; no issue mutation performed."));
});
