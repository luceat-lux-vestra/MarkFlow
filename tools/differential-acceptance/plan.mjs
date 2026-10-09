import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {execFileSync} from "node:child_process";
import {readFileSync, realpathSync, statSync, writeFileSync} from "node:fs";
import {relative, resolve, sep} from "node:path";
import {fileURLToPath} from "node:url";

export const MANIFEST_COLUMNS = [
  "case_id", "domain", "feature", "classification", "fixture_state",
  "fixture_path", "source_fidelity_expectation", "visual_reference_expectation",
  "intellij_preview", "interaction", "mermaid_detector", "categories"
];

const sha256 = (bytes) => createHash("sha256").update(bytes).digest("hex");

function mermaidBlocks(source, fixture) {
  // Explicitly account for every Mermaid fenced block, not just the first match.
  // Preserve the source spelling and line numbers; never normalize fixture bytes.
  const lines = source.split("\n");
  const blocks = [];
  for (let i = 0; i < lines.length; i++) {
    const opening = /^ {0,3}([`~]{3,})mermaid[ \t]*\r?$/.exec(lines[i]);
    if (!opening || new Set(opening[1]).size !== 1) continue;
    let end = i + 1;
    for (; end < lines.length; end++) {
      const closing = /^ {0,3}([`~]{3,})[ \t]*\r?$/.exec(lines[end]);
      if (closing && new Set(closing[1]).size === 1 &&
          closing[1][0] === opening[1][0] &&
          closing[1].length >= opening[1].length) break;
    }
    assert.ok(end < lines.length, fixture + ": unclosed Mermaid fenced block at line " + (i + 1));
    const diagram = lines.slice(i + 1, end).join("\n");
    assert.ok(diagram.trim(), fixture + ": empty Mermaid fenced block at line " + (i + 1));
    blocks.push({
      fence_line: i + 1,
      source_start_line: i + 2,
      source_end_line: end,
      block_sha256: sha256(Buffer.from(diagram, "utf8"))
    });
    i = end;
  }
  return blocks;
}

function safeFixturePath(root, rawPath) {
  assert.match(rawPath, /^[A-Za-z0-9._/-]+$/, "unsafe manifest fixture path");
  assert.ok(!rawPath.startsWith("/") && !rawPath.includes("//") &&
    rawPath.split("/").every((part) => part !== ".." && part !== "."),
    "manifest fixture path escapes repository: " + rawPath);
  const absolute = resolve(root, rawPath);
  const realRoot = realpathSync(root);
  const realFile = realpathSync(absolute);
  const rel = relative(realRoot, realFile).split(sep).join("/");
  assert.ok(rel && !rel.startsWith("../") && rel !== ".." && !rel.startsWith("/"),
    "fixture symlink or path escapes repository: " + rawPath);
  assert.ok(statSync(realFile).isFile(), "fixture is not a regular file: " + rawPath);
  return realFile;
}

export function buildCapturePlan(repositoryRoot, headSha, manifestText = null) {
  assert.match(headSha, /^[0-9a-f]{40}$/, "capture plan requires one exact 40-hex source HEAD");
  const root = resolve(repositoryRoot);
  const manifest = manifestText ?? readFileSync(
    resolve(root, "fixtures/differential-acceptance/corpus.tsv"), "utf8");
  assert.ok(manifest.endsWith("\n") && !manifest.includes("\r"),
    "corpus.tsv must use complete LF lines");
  const lines = manifest.trimEnd().split("\n");
  assert.deepEqual(lines.shift().split("\t"), MANIFEST_COLUMNS,
    "corpus schema changed; audit capture planner");
  const manifestIds = new Set();
  const cases = [];
  for (const line of lines) {
    assert.ok(line && !line.startsWith("#"), "unreviewed blank/comment manifest row");
    const fields = line.split("\t");
    assert.equal(fields.length, MANIFEST_COLUMNS.length, "corpus manifest column count drift");
    const row = Object.fromEntries(MANIFEST_COLUMNS.map((key, n) => [key, fields[n]]));
    assert.match(row.case_id, /^[a-z0-9]+(?:-[a-z0-9]+)*$/, "unsafe corpus case ID");
    assert.ok(!manifestIds.has(row.case_id), "duplicate corpus case ID: " + row.case_id);
    manifestIds.add(row.case_id);
    assert.ok(["markdown", "mermaid"].includes(row.domain), row.case_id + ": unknown domain");
    assert.ok(["supported", "degraded", "unsupported"].includes(row.classification),
      row.case_id + ": unknown classification");
    assert.equal(row.fixture_state, "covered", row.case_id + ": fixture gap cannot enter capture plan");
    assert.equal(row.source_fidelity_expectation, "exact-source",
      row.case_id + ": source fidelity contract changed");
    assert.ok(["yes", "no"].includes(row.intellij_preview),
      row.case_id + ": malformed platform comparison flag");
    if (row.domain === "mermaid") {
      assert.ok(row.mermaid_detector !== "-", row.case_id + ": detector missing");
    } else {
      assert.equal(row.mermaid_detector, "-", row.case_id + ": Markdown detector mismatch");
    }
    const fixture = safeFixturePath(root, row.fixture_path);
    const sourceBytes = readFileSync(fixture);
    assert.ok(sourceBytes.length > 0, row.case_id + ": empty source fixture");
    const source = sourceBytes.toString("utf8");
    assert.ok(Buffer.from(source, "utf8").equals(sourceBytes),
      row.case_id + ": fixture must be valid UTF-8");
    const blocks = row.domain === "mermaid" ? mermaidBlocks(source, row.fixture_path) : [null];
    assert.ok(blocks.length > 0, row.case_id + ": Mermaid case has no fenced block");

    for (const [index, block] of blocks.entries()) {
      const id = blocks.length > 1
        ? row.case_id + "--block-" + String(index + 1).padStart(2, "0")
        : row.case_id;
      cases.push({
        case_id: id,
        manifest_case_id: row.case_id,
        domain: row.domain,
        detector: row.mermaid_detector,
        classification: row.classification,
        fixture_path: row.fixture_path,
        fixture_sha256: sha256(sourceBytes),
        source_fidelity: row.source_fidelity_expectation,
        visual_reference: row.visual_reference_expectation,
        platform_comparable: row.intellij_preview === "yes",
        interaction: row.interaction,
        block_index: block === null ? null : index + 1,
        block_count: block === null ? null : blocks.length,
        source_anchor: block,
        capture_status: "not-captured"
      });
    }
  }
  assert.equal(new Set(cases.map((c) => c.case_id)).size, cases.length,
    "expanded capture case IDs collided");
  assert.ok(cases.length >= manifestIds.size, "capture plan silently dropped manifest cases");
  return {
    schema: "markflow-differential-capture-plan/v1",
    source_head: headSha,
    platform_release: "IDEA 2026.2.3",
    manifest_sha256: sha256(Buffer.from(manifest, "utf8")),
    manifest_cases: manifestIds.size,
    expanded_cases: cases.length,
    execution_status: "not-captured",
    cases
  };
}

function main(argv) {
  const headFlag = argv.indexOf("--head");
  const outFlag = argv.indexOf("--output");
  assert.ok(headFlag >= 0 && argv[headFlag + 1], "usage: node plan.mjs --head <exact_SHA> --output <path>");
  assert.ok(outFlag >= 0 && argv[outFlag + 1], "output path required");
  assert.equal(argv.length, 4, "only --head and --output options are accepted");
  const root = resolve(fileURLToPath(new URL("../../", import.meta.url)));
  const actualHead = execFileSync("git", ["rev-parse", "HEAD"], {cwd: root, encoding: "utf8"}).trim();
  assert.equal(actualHead, argv[headFlag + 1],
    "checked-out Git HEAD differs from requested capture plan identity");
  const plan = buildCapturePlan(root, actualHead);
  writeFileSync(resolve(argv[outFlag + 1]), JSON.stringify(plan, null, 2) + "\n");
  process.stdout.write("Capture plan only: " + plan.manifest_cases + " manifest rows, " +
    plan.expanded_cases + " cases; zero real paired screenshots captured.\n");
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2));
}
