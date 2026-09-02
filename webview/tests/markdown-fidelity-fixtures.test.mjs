import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const testDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(testDirectory, "../..");
const fixtureRoot = resolve(repositoryRoot, "fixtures/markdown-fidelity");
const casesRoot = resolve(fixtureRoot, "cases");
const manifestPath = resolve(fixtureRoot, "manifest.tsv");

const allowedCapabilities = new Set([
  "supported",
  "degraded",
  "unsupported",
  "intentional-normalization",
]);
const allowedFidelities = new Set([
  "byte-stable",
  "lexically-local",
  "source-preserved-degraded",
  "inserted-payload-only",
]);
const allowedLineEndings = new Set(["LF", "CRLF"]);
const manifestColumns = [
  "fixture_id",
  "path",
  "capability",
  "fidelity",
  "line_endings",
  "trailing_newline",
  "categories",
];

function parseManifest() {
  const manifest = readFileSync(manifestPath, "utf8");
  assert.equal(manifest.includes("\r"), false, "manifest must use LF separators");
  assert.equal(manifest.endsWith("\n"), true, "manifest must end with a newline");

  const lines = manifest.trimEnd().split("\n");
  assert.deepEqual(lines[0].split("\t"), manifestColumns);

  return lines.slice(1).filter((line) => line.length > 0 && !line.startsWith("#")).map((line, index) => {
    const fields = line.split("\t");
    assert.equal(fields.length, manifestColumns.length, `manifest row ${index + 2} has the wrong column count`);
    return Object.fromEntries(manifestColumns.map((column, columnIndex) => [column, fields[columnIndex]]));
  });
}

function assertLineEndingMetadata(bytes, expected, fixtureId) {
  if (expected === "LF") {
    assert.equal(bytes.includes(0x0d), false, `${fixtureId} declares LF but contains CR`);
    return;
  }

  assert.equal(expected, "CRLF");
  for (let index = 0; index < bytes.length; index += 1) {
    if (bytes[index] === 0x0a) {
      assert.equal(bytes[index - 1], 0x0d, `${fixtureId} declares CRLF but has a bare LF`);
    }
    if (bytes[index] === 0x0d) {
      assert.equal(bytes[index + 1], 0x0a, `${fixtureId} declares CRLF but has a bare CR`);
    }
  }
}

test("Markdown fidelity manifest and corpus are deterministic and self-consistent", () => {
  const entries = parseManifest();
  const ids = entries.map((entry) => entry.fixture_id);
  const paths = entries.map((entry) => entry.path);
  assert.equal(new Set(ids).size, ids.length, "fixture IDs must be unique");
  assert.equal(new Set(paths).size, paths.length, "fixture paths must be unique");

  for (const entry of entries) {
    assert.match(entry.fixture_id, /^[a-z0-9]+(?:-[a-z0-9]+)*$/);
    assert.equal(entry.path.trim(), entry.path);
    assert.equal(entry.path.startsWith("fixtures/markdown-fidelity/cases/"), true);
    assert.equal(entry.path.includes(".."), false, `${entry.fixture_id} path must stay in the corpus`);
    assert.equal(allowedCapabilities.has(entry.capability), true, `${entry.fixture_id} has an invalid capability`);
    assert.equal(allowedFidelities.has(entry.fidelity), true, `${entry.fixture_id} has an invalid fidelity`);
    assert.equal(allowedLineEndings.has(entry.line_endings), true, `${entry.fixture_id} has invalid line endings`);
    assert.equal(entry.trailing_newline === "true" || entry.trailing_newline === "false", true, `${entry.fixture_id} has invalid trailing-newline metadata`);
    assert.ok(entry.categories.length > 0, `${entry.fixture_id} must declare categories`);
    assert.ok(entry.categories.split(",").every((category) => /^[a-z0-9-]+$/.test(category)));

    const fixturePath = resolve(repositoryRoot, entry.path);
    const relativeFixturePath = relative(repositoryRoot, fixturePath).split(sep).join("/");
    assert.equal(relativeFixturePath.startsWith("fixtures/markdown-fidelity/cases/"), true);
    assert.equal(statSync(fixturePath).isFile(), true, `${entry.fixture_id} fixture is missing`);

    const bytes = readFileSync(fixturePath);
    assertLineEndingMetadata(bytes, entry.line_endings, entry.fixture_id);
    const hasTrailingNewline = bytes.length > 0 && bytes[bytes.length - 1] === 0x0a;
    assert.equal(hasTrailingNewline, entry.trailing_newline === "true", `${entry.fixture_id} trailing newline mismatch`);
  }

  const declaredPaths = new Set(paths.map((path) => resolve(repositoryRoot, path)));
  const caseFiles = readdirSync(casesRoot)
    .filter((name) => name.endsWith(".md"))
    .map((name) => resolve(casesRoot, name));
  assert.deepEqual(caseFiles.sort(), [...declaredPaths].sort(), "every Markdown case must be declared exactly once");
});
