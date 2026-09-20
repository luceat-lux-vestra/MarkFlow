"use strict";

const RULES = Object.freeze([
  [/^(fix|bug)(\([^)]*\))?:/i, "type:bug"],
  [/^(feat|feature)(\([^)]*\))?:/i, "type:feature"],
  [/^security(\([^)]*\))?:/i, "type:security"],
  [/^docs(\([^)]*\))?:/i, "type:docs"],
  [/^(research|rfc|adr|audit|design|spike|architecture)(\([^)]*\))?:/i, "type:research"],
  [/^(task|build|ci|test|refactor|chore|perf|release|track|epic|hardening)(\([^)]*\))?:/i, "type:task"]
]);

const MANAGED_TYPES = new Set([
  "type:bug", "type:feature", "type:security", "type:docs", "type:research", "type:task"
]);

function expectedType(title) {
  const value = String(title || "").trim();
  for (const [pattern, label] of RULES) {
    if (pattern.test(value)) return label;
  }
  return null;
}

function classify(issue) {
  const labels = (issue.labels || [])
    .map((label) => typeof label === "string" ? label : label?.name)
    .filter(Boolean);
  const expected = expectedType(issue.title);
  const types = labels.filter((label) => MANAGED_TYPES.has(label));
  const add = [];
  const remove = [];
  const diagnostics = [];

  if (!expected) {
    if (types.length === 0) diagnostics.push("unclassified-title");
    if (types.length > 1) diagnostics.push("multiple-managed-types");
    return { expectedType: null, add, remove, diagnostics };
  }

  if (!labels.includes(expected)) add.push(expected);
  for (const type of types) {
    if (type !== expected) remove.push(type);
  }

  return {
    expectedType: expected,
    add: add.sort(),
    remove: remove.sort(),
    diagnostics
  };
}

module.exports = { MANAGED_TYPES, expectedType, classify };
