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

const cases = ["agentflow", "swimlanes", "radar", "treemap"];
const sources = Object.fromEntries(cases.map((name) => {
  const markdown = readFileSync(resolve(repoRoot, "examples", "mermaid-" + name + ".md"), "utf8");
  const match = markdown.match(/```mermaid\s*\n([\s\S]*?)```/);
  assert.ok(match, name + ": missing Mermaid fenced block");
  return [name, match[1]];
}));

function chromeExecutable() {
  for (const executable of [process.env.CHROME_BIN, "google-chrome", "google-chrome-stable", "chromium", "chromium-browser"].filter(Boolean)) {
    try {
      execFileSync(executable, ["--version"], {stdio: "ignore", timeout: 3000});
      return executable;
    } catch { /* try the next installed browser */ }
  }
  throw new Error("Chromium/Chrome is required for Mermaid acceptance; set CHROME_BIN or install Chrome. Do not skip this test.");
}

test("Mermaid 12.1.0 parses and renders the four added fixtures in Chromium", {timeout: 90000}, async () => {
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
    assert.ok(stdout.includes("PASS:" + cases.join(",")), "Missing complete browser validation proof: " + stdout.slice(0, 5500));
  } finally {
    await server.close();
    rmSync(profile, {recursive: true, force: true});
  }
});
