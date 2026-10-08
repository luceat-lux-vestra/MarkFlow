import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import mermaid from "mermaid";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const expectedVersion = "12.0.0";
const packageJson = JSON.parse(readFileSync(resolve(root, "webview/package.json"), "utf8"));
assert.equal(packageJson.dependencies.mermaid, expectedVersion, "Re-audit fixtures when Mermaid is upgraded");

const cases = ["agentflow", "swimlanes", "radar", "treemap"];
mermaid.initialize({ startOnLoad: false });

for (const kind of cases) {
  test(`Mermaid 12.0.0 parses corpus fixture: ${kind}`, async () => {
    const fixture = readFileSync(resolve(root, `examples/mermaid-${kind}.md`), "utf8");
    const match = fixture.match(/```mermaid\s*\n([\s\S]*?)```/);
    assert.ok(match, `${kind}: missing Mermaid fence`);
    const parsed = await mermaid.parse(match[1], { suppressErrors: false });
    assert.ok(parsed, `${kind}: Mermaid returned no parse result`);
  });
}
