import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {resolve} from "node:path";

export const mermaidDetectorCases = ["agentflow", "swimlanes", "radar", "treemap", "cynefin", "treeview", "usecase", "info", "ishikawa", "wardley", "railroad", "railroad-abnf", "railroad-ebnf", "railroad-peg", "eventmodeling", "flowchart-elk", "architecture", "block", "c4", "class", "er", "flowchart", "gantt", "journey", "mindmap", "packet", "pie", "git", "kanban", "quadrant", "requirement", "sankey", "sequence", "state", "timeline", "venn", "xychart"];

const fixtureFiles = new Map([["git", "mermaid-gitgraph.md"]]);

export const supplementalExamples = [
  {name: "minimal", file: "mermaid-minimal.md", expectedBlocks: 1},
  {name: "large-flowchart", file: "mermaid-large-flowchart.md", expectedBlocks: 2},
];

export function declaredMermaidDetectors(repoRoot) {
  const manifest = readFileSync(resolve(repoRoot, "fixtures/differential-acceptance/corpus.tsv"), "utf8");
  const [header, ...rows] = manifest.trimEnd().split("\n");
  const columns = header.split("\t");
  const domainColumn = columns.indexOf("domain");
  const detectorColumn = columns.indexOf("mermaid_detector");
  assert.ok(domainColumn >= 0 && detectorColumn >= 0, "corpus manifest is missing detector columns");
  return new Set(rows.filter((row) => row && !row.startsWith("#"))
    .map((row) => row.split("\t"))
    .filter((cells) => cells[domainColumn] === "mermaid")
    .map((cells) => cells[detectorColumn]));
}

export function loadMermaidAcceptanceSources(repoRoot) {
  const detectorSources = Object.fromEntries(mermaidDetectorCases.map((name) => {
    const filename = fixtureFiles.get(name) ?? "mermaid-" + name + ".md";
    const markdown = readFileSync(resolve(repoRoot, "examples", filename), "utf8");
    const match = markdown.match(/```mermaid\s*\n([\s\S]*?)```/);
    assert.ok(match, name + ": missing Mermaid fenced block");
    return [name, match[1]];
  }));

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
  const allCaseIds = [...mermaidDetectorCases, ...supplementalCases.map(([caseId]) => caseId)];
  assert.equal(Object.keys(sources).length, allCaseIds.length, "browser case IDs must be unique");
  return {sources, allCaseIds};
}
