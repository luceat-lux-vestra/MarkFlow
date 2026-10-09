import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const testDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(testDirectory, "../..");
const corpusRoot = resolve(repositoryRoot, "fixtures/differential-acceptance");
const manifestPath = resolve(corpusRoot, "corpus.tsv");
const packagePath = resolve(repositoryRoot, "webview/package.json");

const manifestColumns = ["case_id", "domain", "feature", "classification", "fixture_state", "fixture_path", "source_fidelity_expectation", "visual_reference_expectation", "intellij_preview", "interaction", "mermaid_detector", "categories"];
const allowedDomains = new Set(["markdown", "mermaid"]);
const allowedClassifications = new Set(["supported", "degraded", "unsupported"]);
const allowedStates = new Set(["covered", "gap"]);
const allowedBoolean = new Set(["yes", "no"]);
const allowedInteractions = new Set(["none", "click", "navigate"]);

const expectedMermaidVersion = "12.1.0";
const expectedMermaidDetectors = new Set(["agentflow", "architecture", "block", "c4", "class", "cynefin", "er", "eventmodeling", "flowchart", "flowchart-elk", "gantt", "git", "info", "ishikawa", "journey", "kanban", "mindmap", "packet", "pie", "quadrant", "radar", "railroad", "railroad-abnf", "railroad-ebnf", "railroad-peg", "requirement", "sankey", "sequence", "state", "swimlanes", "timeline", "treeview", "treemap", "usecase", "venn", "wardley", "xychart"]);
const expectedMarkdownFeatures = new Set(["atx-headings-h1-h6", "autolink", "autolink-email", "blank-lines-whitespace", "blockquote", "closing-atx-heading", "code-span-delimiter-edge", "combined-emphasis", "emphasis", "entity-character-reference", "escaped-punctuation", "fence-length-info", "fenced-code-backtick", "fenced-code-tilde", "footnote-extension", "gfm-autolink-literal", "hard-break", "image-syntax", "indented-code", "inline-code", "inline-image-title", "inline-link", "inline-link-title", "inline-raw-html", "katex-display", "katex-inline", "list-marker-variants", "local-image-resource", "mermaid-fenced-block", "nested-list", "ordered-list", "paragraph", "raw-html-hostile", "raw-html-safe", "reference-definition", "reference-image", "reference-link", "setext-headings", "soft-break", "strikethrough", "strong", "table-alignment", "table-basic", "table-escaped-pipe", "tabs-indentation", "task-list", "thematic-break", "tight-loose-lists", "unordered-list"]);

// Completeness is now closed: all declared Markdown/Mermaid features have fixtures.
const knownGapIds = new Set([]);
// Git blob SHA-1 of the reviewed 88-case corpus.tsv: pins every column and row,
// not merely detector/feature set membership. Deliberate changes require audited rebaseline.
const reviewedManifestGitBlobSha = "334cc91357825fa9f29f97656ec1e88b04f59c2c";
const reviewedManifestRows = 88;

const mermaidDetectorPrefix = new Map([
  ["eventmodeling", /^eventmodeling\b/],
  ["flowchart-elk", /^flowchart-elk\b/],
  ["railroad", /^railroad-beta\b/],
  ["railroad-abnf", /^railroad-abnf-beta\b/],
  ["railroad-ebnf", /^railroad-ebnf-beta\b/],
  ["railroad-peg", /^railroad-peg-beta\b/],
  ["info", /^info\b/],
  ["ishikawa", /^ishikawa(?:-beta)?\b/i],
  ["wardley", /^wardley-beta\b/],
  ["cynefin", /^cynefin-beta\b/],
  ["treeview", /^treeView-beta\b/],
  ["usecase", /^usecase-beta\b/],
  ["agentflow", /^agentflow-beta\b/],
  ["radar", /^radar-beta\b/],
  ["swimlanes", /^swimlane-beta\b/],
  ["treemap", /^treemap-beta\b/],
  ["architecture", /^architecture-beta\b/],
  ["block", /^block-beta\b/],
  ["c4", /^C4(?:Context|Container|Component|Dynamic|Deployment)\b/],
  ["class", /^classDiagram\b/],
  ["er", /^erDiagram\b/],
  ["flowchart", /^(?:flowchart|graph)\b/],
  ["gantt", /^gantt\b/],
  ["git", /^gitGraph\b/],
  ["journey", /^journey\b/],
  ["kanban", /^kanban\b/],
  ["mindmap", /^mindmap\b/],
  ["packet", /^packet-beta\b/],
  ["pie", /^pie\b/],
  ["quadrant", /^quadrantChart\b/],
  ["requirement", /^requirementDiagram\b/],
  ["sankey", /^sankey-beta\b/],
  ["sequence", /^sequenceDiagram\b/],
  ["state", /^stateDiagram(?:-v2)?\b/],
  ["timeline", /^timeline\b/],
  ["venn", /^venn-beta\b/],
  ["xychart", /^xychart-beta\b/],
]);

function manifestGitBlobSha(source) {
  const bytes = Buffer.from(source, "utf8");
  return createHash("sha1").update("blob " + bytes.length + "\0").update(bytes).digest("hex");
}

function assertReviewedManifest(source) {
  assert.equal(
    manifestGitBlobSha(source),
    reviewedManifestGitBlobSha,
    "corpus.tsv changed: audit every affected case/classification/fixture and deliberately rebaseline the Git blob SHA"
  );
}

test("88-case corpus snapshot rejects deletions, reclassification and fixture substitutions", () => {
  const source = readFileSync(manifestPath, "utf8");
  assert.equal(parseManifest().length, reviewedManifestRows, "reviewed 88-case coverage shrank or expanded");
  assertReviewedManifest(source);

  const lines = source.split("\n");
  const removed = [lines[0], ...lines.slice(2)].join("\n");
  const reclassified = source.replace("\tsupported\tcovered\t", "\tunsupported\tcovered\t");
  const substituted = source.replace(
    "fixtures/differential-acceptance/markdown/headings-atx-h1-h6.md",
    "fixtures/differential-acceptance/markdown/lists-tight-loose.md"
  );

  for (const [kind, mutated] of [
    ["removed row", removed],
    ["reclassified support", reclassified],
    ["substituted fixture path", substituted],
  ]) {
    assert.notEqual(mutated, source, kind + ": mutation fixture had no effect");
    assert.throws(
      () => assertReviewedManifest(mutated),
      /corpus\.tsv changed: audit every affected case/,
      kind + " must require explicit manifest rebaseline"
    );
  }
});

function parseManifest() {
  const manifest = readFileSync(manifestPath, "utf8");
  assert.equal(manifest.includes("\r"), false, "differential corpus manifest must use LF separators");
  assert.equal(manifest.endsWith("\n"), true, "differential corpus manifest must end with a newline");
  const lines = manifest.trimEnd().split("\n");
  assert.deepEqual(lines[0].split("\t"), manifestColumns);

  return lines.slice(1).filter((line) => line.length > 0 && !line.startsWith("#")).map((line, index) => {
    const fields = line.split("\t");
    assert.equal(fields.length, manifestColumns.length, `manifest row ${index + 2} has the wrong column count`);
    return Object.fromEntries(manifestColumns.map((column, columnIndex) => [column, fields[columnIndex]]));
  });
}

function repositoryPath(path) {
  const absolute = resolve(repositoryRoot, path);
  const relativePath = relative(repositoryRoot, absolute).split(sep).join("/");
  assert.equal(relativePath.startsWith("../"), false, `${path} escapes the repository`);
  assert.notEqual(relativePath, "..", `${path} escapes the repository`);
  return absolute;
}

function firstMermaidBlock(source, fixtureId) {
  const match = source.match(/```mermaid\s*\n([\s\S]*?)```/);
  assert.ok(match, `${fixtureId} does not contain a Mermaid fenced block`);
  return match[1].trimStart();
}

test("differential acceptance corpus inventory is explicit and self-consistent", () => {
  const entries = parseManifest();
  const ids = entries.map((entry) => entry.case_id);
  assert.equal(new Set(ids).size, ids.length, "differential corpus case IDs must be unique");

  for (const entry of entries) {
    assert.match(entry.case_id, /^[a-z0-9]+(?:-[a-z0-9]+)*$/);
    assert.equal(allowedDomains.has(entry.domain), true, `${entry.case_id} has invalid domain`);
    assert.equal(allowedClassifications.has(entry.classification), true, `${entry.case_id} has invalid classification`);
    assert.equal(allowedStates.has(entry.fixture_state), true, `${entry.case_id} has invalid fixture_state`);
    assert.equal(allowedBoolean.has(entry.intellij_preview), true, `${entry.case_id} has invalid intellij_preview`);
    assert.equal(allowedInteractions.has(entry.interaction), true, `${entry.case_id} has invalid interaction`);
    assert.equal(entry.source_fidelity_expectation, "exact-source",
      `${entry.case_id} must retain exact source bytes for the #353 differential harness`);
    const visualExpected = entry.classification === "supported"
      ? (entry.domain === "mermaid" ? "diagram-semantic-perceptual" : "source-anchored-geometry")
      : (entry.classification === "degraded" ? "diagnostic-only" : "no-parity-expected");
    assert.equal(entry.visual_reference_expectation, visualExpected,
      `${entry.case_id} visual-reference expectation disagrees with explicit product classification`);
    if (visualExpected === "source-anchored-geometry" || visualExpected === "diagram-semantic-perceptual") {
      assert.equal(entry.intellij_preview, "yes", `${entry.case_id} needs platform preview for parity comparison`);
    }
    assert.ok(entry.categories.split(",").every((category) => /^[a-z0-9-]+$/.test(category)), `${entry.case_id} has invalid categories`);

    if (entry.fixture_state === "gap") {
      assert.equal(entry.fixture_path, "-", `${entry.case_id} gap must not pretend to have a fixture`);
      continue;
    }

    assert.notEqual(entry.fixture_path, "-", `${entry.case_id} covered row must name a fixture`);
    const fixturePath = repositoryPath(entry.fixture_path);
    assert.equal(existsSync(fixturePath), true, `${entry.case_id} fixture is missing: ${entry.fixture_path}`);
    assert.equal(statSync(fixturePath).isFile(), true, `${entry.case_id} fixture is not a file`);
  }

  const observedGaps = new Set(entries.filter((entry) => entry.fixture_state === "gap").map((entry) => entry.case_id));
  assert.deepEqual([...observedGaps].sort(), [...knownGapIds].sort(), "reviewed #354 gap inventory changed");

  const markdownFeatures = new Set(entries.filter((entry) => entry.domain === "markdown").map((entry) => entry.feature));
  assert.deepEqual([...markdownFeatures].sort(), [...expectedMarkdownFeatures].sort(), "Markdown feature-family inventory drifted");

  const mermaidDetectors = new Set(entries.filter((entry) => entry.domain === "mermaid").map((entry) => entry.mermaid_detector));
  assert.deepEqual([...mermaidDetectors].sort(), [...expectedMermaidDetectors].sort(), "Mermaid detector inventory drifted");
});

test("Mermaid 12.1.0 accepted detector surface stays classified and example files stay declared", () => {
  const packageJson = JSON.parse(readFileSync(packagePath, "utf8"));
  assert.equal(packageJson.dependencies?.mermaid, expectedMermaidVersion, "Mermaid version changed; re-audit detector coverage before updating the corpus snapshot");

  const entries = parseManifest();
  const mermaidEntries = entries.filter((entry) => entry.domain === "mermaid");

  for (const entry of mermaidEntries.filter((entry) => entry.fixture_state === "covered")) {
    const detector = entry.mermaid_detector;
    const prefix = mermaidDetectorPrefix.get(detector);
    assert.ok(prefix, `${entry.case_id} has no reviewed detector-prefix assertion`);
    const source = readFileSync(repositoryPath(entry.fixture_path), "utf8");
    assert.match(firstMermaidBlock(source, entry.case_id), prefix, `${entry.case_id} fixture does not match declared Mermaid detector ${detector}`);
  }

  const declaredExamplePaths = new Set(
    mermaidEntries
      .filter((entry) => entry.fixture_state === "covered" && entry.fixture_path.startsWith("examples/mermaid-"))
      .map((entry) => entry.fixture_path)
  );
  const maintainedExamplePaths = readdirSync(resolve(repositoryRoot, "examples"))
    .filter((name) => name.startsWith("mermaid-") && name.endsWith(".md"))
    .map((name) => `examples/${name}`);

  assert.deepEqual(
    maintainedExamplePaths.sort(),
    [...declaredExamplePaths].sort(),
    "every maintained examples/mermaid-*.md file must be declared in the differential corpus"
  );
});


test("Mermaid 12.1.0 runtime sizing matrix covers all detectors and checked-in config keys", () => {
  const source = readFileSync(resolve(corpusRoot, "mermaid-runtime-config-matrix.tsv"), "utf8");
  assert.equal(source.includes("\r"), false, "Mermaid config matrix must use LF line endings");
  assert.equal(source.endsWith("\n"), true, "Mermaid config matrix must end with newline");
  const [header, ...lines] = source.trimEnd().split("\n");
  assert.deepEqual(header.split("\t"), [
    "detector", "classification", "fixture_path", "preview_config_keys", "config_status"
  ]);
  const matrix = lines.map((line) => {
    const cells = line.split("\t");
    assert.equal(cells.length, 5, "Mermaid config matrix row must have 5 columns");
    return cells;
  });
  const manifest = parseManifest();
  const declarations = new Set(manifest
    .filter((entry) => entry.domain === "mermaid")
    .map((entry) => entry.mermaid_detector));
  assert.equal(new Set(matrix.map((row) => row[0])).size, matrix.length, "duplicate Mermaid detector in sizing matrix");
  assert.deepEqual(matrix.map((row) => row[0]).sort(), [...declarations].sort(),
    "each Mermaid detector needs a reviewed per-diagram configuration mapping");

  const runtimeSource = readFileSync(resolve(repositoryRoot, "webview/src/app/runtime-settings.ts"), "utf8");
  const factory = runtimeSource.split("export const createMermaidPreviewConfig =")[1];
  assert.ok(factory, "Mermaid runtime config factory changed; re-audit the sizing matrix");
  // Restrict the source-level contract to explicit object literals with a useMaxWidth field.
  // This detects source changes but does not claim the Mermaid engine honors every option.
  const actual = new Map([...factory.matchAll(/^\s{8}([A-Za-z][A-Za-z0-9]*):\s*\{([^\n}]*)\},?\s*$/gm)]
    .filter((match) => /\buseMaxWidth\b/.test(match[2]))
    .map((match) => [match[1], match[2]]));
  assert.ok(actual.size > 0, "no per-diagram config keys found; inspect the factory");
  const accountedFor = new Set();

  for (const [detector, classification, fixturePath, configKeys, status] of matrix) {
    const canonical = manifest.find((entry) => entry.case_id === "mermaid-" + detector);
    assert.ok(canonical, detector + ": canonical manifest fixture missing");
    assert.equal(classification, canonical.classification, detector + ": classification changed");
    assert.equal(fixturePath, canonical.fixture_path, detector + ": canonical fixture path changed");
    const allowed = ["no-per-type-key", "shared-unverified", "explicit-mode", "fixed-true"];
    assert.ok(allowed.includes(status), detector + ": unreviewed runtime config status");
    if (status === "no-per-type-key") {
      assert.equal(configKeys, "-", detector + ": no-per-type-key must not assert a key");
      continue;
    }
    const keys = configKeys.split(",");
    assert.ok(keys.every(Boolean) && new Set(keys).size === keys.length,
      detector + ": invalid duplicate/empty config key");
    if (status === "shared-unverified") {
      assert.equal(detector, "flowchart-elk", "only the reviewed ELK shared-config caveat is allowed");
      assert.deepEqual(keys, ["flowchart"], "ELK config mapping needs renewed audit");
    }
    if (status === "fixed-true") {
      assert.equal(detector, "xychart", "only xychart has reviewed fixed sizing overrides");
    }
    for (const key of keys) {
      const definition = actual.get(key);
      assert.ok(definition, detector + ": key " + key + " is absent from runtime-settings.ts");
      assert.equal(/\buseMaxWidth\s*:\s*true\b/.test(definition), status === "fixed-true",
        detector + ": " + key + " sizing override changed");
      accountedFor.add(key);
    }
  }
  assert.deepEqual([...accountedFor].sort(), [...actual.keys()].sort(),
    "new or removed explicit Mermaid per-diagram config key requires audited matrix update");
});
