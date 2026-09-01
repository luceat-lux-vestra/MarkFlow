import {readFileSync} from "node:fs";
import {resolve} from "node:path";
import {test} from "node:test";
import * as ts from "typescript";

const source = readFileSync(resolve(import.meta.dirname, "..", "src", "app", "color.ts"), "utf8");
const code = ts.transpileModule(source, {
    compilerOptions: {module: "ESNext", target: "ES2020"},
    fileName: "color.ts"
}).outputText;
const {
    adjustForContrast,
    contrastRatio,
    parseHex,
    readableTextColor
} = await import(`data:text/javascript;base64,${Buffer.from(code).toString("base64")}`);

const rgb = (value) => parseHex(value);
const ratio = (foreground, background) => contrastRatio(rgb(foreground), rgb(background));

test("parseHex accepts short hex and rgba input", () => {
    if (JSON.stringify(parseHex("#abc")) !== JSON.stringify({r: 170, g: 187, b: 204})) {
        throw new Error("expected #abc to expand to #aabbcc");
    }
    if (JSON.stringify(parseHex("rgba(18, 52, 86, 0.5)")) !== JSON.stringify({r: 18, g: 52, b: 86})) {
        throw new Error("expected rgba input to provide RGB channels");
    }
});

test("readableTextColor chooses the endpoint with the greatest WCAG contrast", () => {
    if (readableTextColor("#808080") !== "#000000") {
        throw new Error("mid-gray should prefer black because it has greater contrast");
    }
    if (readableTextColor("#fefefe") !== "#000000") {
        throw new Error("near-white should prefer black");
    }
    if (readableTextColor("#010101") !== "#ffffff") {
        throw new Error("near-black should prefer white");
    }
});

test("adjustForContrast preserves already-valid colors and satisfies ratio 4.5", () => {
    if (adjustForContrast("#000000", "#ffffff", 4.5) !== "#000000") {
        throw new Error("already-valid foreground should be unchanged");
    }

    const cases = [
        ["#777777", "#ffffff", 4.5],
        ["#888888", "#000000", 4.5],
        ["#7f7f7f", "#808080", 4.5],
        ["#808080", "#808080", 3.0],
        ["#fefefe", "#ffffff", 4.5],
        ["#010101", "#000000", 4.5]
    ];
    for (const [foreground, background, requested] of cases) {
        const result = adjustForContrast(foreground, background, requested);
        if (ratio(result, background) < requested) {
            throw new Error(
                `expected ${result} against ${background} to meet ${requested}: got ${ratio(result, background)}`
            );
        }
    }
});

test("adjustForContrast uses the best endpoint when the requested ratio is unreachable", () => {
    const result = adjustForContrast("#808080", "#808080", 7.0);
    if (result !== "#000000") {
        throw new Error(`expected the higher-contrast endpoint #000000, got ${result}`);
    }
});
