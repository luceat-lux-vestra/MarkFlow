import {spawnSync} from "node:child_process";
import {readFile, readdir, writeFile} from "node:fs/promises";
import path from "node:path";
import {fileURLToPath} from "node:url";

const webviewRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const appRoot = path.join(webviewRoot, "src", "app");
const tscExecutable = path.join(
    webviewRoot,
    "node_modules",
    ".bin",
    process.platform === "win32" ? "tsc.cmd" : "tsc"
);

const addJsExtension = (specifier) =>
    path.posix.extname(specifier) === "" ? specifier + ".js" : specifier;

const rewriteRelativeImports = (source) => source
    .replace(/(\bfrom\s+["'])(\.{1,2}\/[^"']+)(["'])/g, (_match, prefix, specifier, suffix) =>
        prefix + addJsExtension(specifier) + suffix
    )
    .replace(/(\bimport\s+["'])(\.{1,2}\/[^"']+)(["'])/g, (_match, prefix, specifier, suffix) =>
        prefix + addJsExtension(specifier) + suffix
    )
    .replace(/(\bimport\s*\(\s*["'])(\.{1,2}\/[^"']+)(["']\s*\))/g, (_match, prefix, specifier, suffix) =>
        prefix + addJsExtension(specifier) + suffix
    );

const rewriteEmittedModules = async (directory) => {
    for (const entry of await readdir(directory, {withFileTypes: true})) {
        const entryPath = path.join(directory, entry.name);
        if (entry.isDirectory()) {
            await rewriteEmittedModules(entryPath);
        } else if (entry.isFile() && entry.name.endsWith(".js")) {
            const source = await readFile(entryPath, "utf8");
            const rewritten = rewriteRelativeImports(source);
            if (rewritten !== source) await writeFile(entryPath, rewritten, "utf8");
        }
    }
};

export const compileTypeScriptFixtures = async ({sourceNames, outDir, target = "ES2023"}) => {
    await writeFile(path.join(outDir, "package.json"), "{\"type\":\"module\"}\n", "utf8");

    const args = [
        "--ignoreConfig",
        "--target", target,
        "--module", "ESNext",
        "--lib", "ES2023,DOM,DOM.Iterable",
        "--moduleResolution", "bundler",
        "--useDefineForClassFields",
        "--verbatimModuleSyntax",
        "--moduleDetection", "force",
        "--strict",
        "--skipLibCheck",
        "--erasableSyntaxOnly",
        "--noUnusedLocals",
        "--noUnusedParameters",
        "--noFallthroughCasesInSwitch",
        "--noUncheckedSideEffectImports",
        "--rootDir", appRoot,
        "--outDir", outDir,
        ...sourceNames.map((sourceName) => path.join(appRoot, sourceName))
    ];

    const result = spawnSync(tscExecutable, args, {
        cwd: webviewRoot,
        encoding: "utf8",
        shell: process.platform === "win32"
    });
    if (result.error) throw result.error;
    if (result.status !== 0) {
        throw new Error(
            ["TypeScript CLI fixture compilation failed", result.stdout, result.stderr]
                .filter(Boolean)
                .join("\n")
        );
    }

    await rewriteEmittedModules(outDir);
};
