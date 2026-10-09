import assert from "node:assert/strict";
import {execFileSync, execFile} from "node:child_process";
import {mkdtempSync, readFileSync, rmSync} from "node:fs";
import {tmpdir} from "node:os";
import {dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {promisify} from "node:util";
import test from "node:test";
import {createServer} from "vite";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const webviewRoot = resolve(repoRoot, "webview");
const packageJson = JSON.parse(readFileSync(resolve(webviewRoot, "package.json"), "utf8"));
assert.equal(packageJson.dependencies.mermaid, "12.1.0", "Mermaid upgrade requires a fresh #354 detector audit");

const cases = ["agentflow", "swimlanes", "radar", "treemap", "cynefin", "treeview", "usecase", "info", "ishikawa", "wardley", "railroad", "railroad-abnf", "railroad-ebnf", "railroad-peg", "eventmodeling", "flowchart-elk", "architecture", "block", "c4", "class", "er", "flowchart", "gantt", "journey", "mindmap", "packet", "pie", "git", "kanban", "quadrant", "requirement", "sankey", "sequence", "state", "timeline", "venn", "xychart"];
const fixtureFiles = new Map([["git", "mermaid-gitgraph.md"]]);
// Detector families and supplemental examples have different coverage contracts.
// A supplemental flowchart never substitutes for a missing Mermaid detector case.
test("browser detector inventory exactly matches the reviewed corpus manifest", () => {
  const manifest = readFileSync(resolve(repoRoot, "fixtures/differential-acceptance/corpus.tsv"), "utf8");
  const [header, ...rows] = manifest.trimEnd().split("\n");
  const columns = header.split("\t");
  const domainColumn = columns.indexOf("domain");
  const detectorColumn = columns.indexOf("mermaid_detector");
  assert.ok(domainColumn >= 0 && detectorColumn >= 0, "corpus manifest is missing detector columns");
  const declared = new Set(rows.filter((row) => row && !row.startsWith("#"))
    .map((row) => row.split("\t"))
    .filter((cells) => cells[domainColumn] === "mermaid")
    .map((cells) => cells[detectorColumn]));
  assert.equal(cases.length, new Set(cases).size, "browser detector case names must be unique");
  assert.deepEqual([...new Set(cases)].sort(), [...declared].sort(),
    "every corpus Mermaid detector must have an explicit Chromium parse/render case");
});

const detectorSources = Object.fromEntries(cases.map((name) => {
  const filename = fixtureFiles.get(name) ?? "mermaid-" + name + ".md";
  const markdown = readFileSync(resolve(repoRoot, "examples", filename), "utf8");
  const match = markdown.match(/```mermaid\s*\n([\s\S]*?)```/);
  assert.ok(match, name + ": missing Mermaid fenced block");
  return [name, match[1]];
}));

// Protect every fenced block of the large example, not just its first diagram.
// Block-count assertions make future additions/removals require an explicit audit.
const supplementalExamples = [
  {name: "minimal", file: "mermaid-minimal.md", expectedBlocks: 1},
  {name: "large-flowchart", file: "mermaid-large-flowchart.md", expectedBlocks: 2},
];
const supplementalCases = supplementalExamples.flatMap(({name, file, expectedBlocks}) => {
  const markdown = readFileSync(resolve(repoRoot, "examples", file), "utf8");
  const blocks = [...markdown.matchAll(/```mermaid[ \t]*\r?\n([\s\S]*?)```/g)];
  assert.equal(blocks.length, expectedBlocks, file + ": Mermaid block count changed; audit all blocks");
  return blocks.map((match, index) => {
    const caseId = "supplemental-" + name + "-block-" + (index + 1);
    assert.match(match[1].trimStart(), /^(?:flowchart|graph)\b/,
      caseId + ": expected a flowchart; audit detector mapping before changing it");
    return [caseId, match[1]];
  });
});
const sources = {...detectorSources, ...Object.fromEntries(supplementalCases)};
const allCaseIds = [...cases, ...supplementalCases.map(([caseId]) => caseId)];
assert.equal(Object.keys(sources).length, allCaseIds.length, "browser case IDs must be unique");

function chromeExecutable() {
  for (const executable of [process.env.CHROME_BIN, "google-chrome", "google-chrome-stable", "chromium", "chromium-browser"].filter(Boolean)) {
    try {
      execFileSync(executable, ["--version"], {stdio: "ignore", timeout: 3000});
      return executable;
    } catch { /* try the next installed browser */ }
  }
  throw new Error("Chromium/Chrome is required for Mermaid acceptance; set CHROME_BIN or install Chrome. Do not skip this test.");
}

test("Mermaid 12.1.0 renders thirty-seven detectors and three supplemental blocks in Chromium", {timeout: 90000}, async () => {
  const chrome = chromeExecutable();
  const profile = mkdtempSync(join(tmpdir(), "markflow-mermaid-"));
  const server = await createServer({
    configFile: false,
    root: webviewRoot,
    logLevel: "error",
    server: {host: "127.0.0.1", port: 0, strictPort: false},
    plugins: [{
      name: "markflow-mermaid-acceptance-fixtures",
      configureServer(vite) {
        vite.middlewares.use("/__markflow_mermaid_cases", (_request, response) => {
          response.writeHead(200, {"Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store"});
          response.end(JSON.stringify(sources));
        });
      }
    }]
  });
  try {
    await server.listen();
    const address = server.httpServer.address();
    assert.ok(address && typeof address !== "string", "Vite test server did not bind a local port");
    const args = ["--headless=new", "--disable-gpu", "--disable-dev-shm-usage", "--disable-background-networking",
      "--no-first-run", "--no-default-browser-check", "--user-data-dir=" + profile,
      "--virtual-time-budget=25000", "--dump-dom",
      "http://127.0.0.1:" + address.port + "/tests/mermaid-browser-acceptance.html"];
    if (process.getuid?.() === 0) args.unshift("--no-sandbox");
    const {stdout} = await promisify(execFile)(chrome, args, {timeout: 70000, maxBuffer: 8 * 1024 * 1024});
    assert.match(stdout, /id="markflow-mermaid-test-output"[^>]*data-status="passed"/,
      "Chromium Mermaid validation failed: " + stdout.slice(0, 5500));
    assert.ok(stdout.includes("PASS:" + allCaseIds.join(",")), "Missing complete browser validation proof: " + stdout.slice(0, 5500));
  } finally {
    await server.close();
    rmSync(profile, {recursive: true, force: true});
  }
});
