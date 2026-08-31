import {readFileSync} from "node:fs";
import {resolve} from "node:path";
import {test} from "node:test";
import * as ts from "typescript";

const source = readFileSync(resolve(import.meta.dirname, "..", "src", "app", "mermaid-cache-key.ts"), "utf8");
const code = ts.transpileModule(source, {
    compilerOptions: {module: "ESNext", target: "ES2020"},
    fileName: "mermaid-cache-key.ts"
}).outputText;
const {hashMermaidPaletteIdentity} = await import(
    `data:text/javascript;base64,${Buffer.from(code).toString("base64")}`
);

const palette = {
    background: "#123456",
    foreground: "#abcdef",
    selectionBackground: "#654321",
    selectionForeground: "#fedcba",
    border: "#112233"
};

test("Mermaid cache identity changes only for palette values used by Mermaid", () => {
    const same = {...palette};
    const selectionOnly = {...palette, selectionBackground: "#ffffff", selectionForeground: "#000000"};
    const backgroundChanged = {...palette, background: "#654321"};
    const foregroundChanged = {...palette, foreground: "#ffffff"};
    const borderChanged = {...palette, border: "#ffffff"};
    const unknownKey = {...palette, legacyBackground: "#ffffff"};

    if (hashMermaidPaletteIdentity(palette) !== hashMermaidPaletteIdentity(same)) {
        throw new Error("same Mermaid palette must have the same identity");
    }
    if (hashMermaidPaletteIdentity(palette) !== hashMermaidPaletteIdentity(selectionOnly)) {
        throw new Error("editor-only selection colors must not invalidate Mermaid SVGs");
    }
    if (hashMermaidPaletteIdentity(palette) === hashMermaidPaletteIdentity(backgroundChanged)) {
        throw new Error("Mermaid background changes must invalidate cached SVGs");
    }
    if (hashMermaidPaletteIdentity(palette) === hashMermaidPaletteIdentity(foregroundChanged)) {
        throw new Error("Mermaid foreground changes must invalidate cached SVGs");
    }
    if (hashMermaidPaletteIdentity(palette) === hashMermaidPaletteIdentity(borderChanged)) {
        throw new Error("Mermaid border changes must invalidate cached SVGs");
    }
    if (hashMermaidPaletteIdentity(palette) !== hashMermaidPaletteIdentity(unknownKey)) {
        throw new Error("unknown contract keys must not affect Mermaid cache identity");
    }
});
