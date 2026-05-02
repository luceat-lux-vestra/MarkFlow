import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import * as ts from "typescript";
import {test} from "node:test";

const dataSourcePath = new URL("../src/app/source-preserving-markdown-data.ts", import.meta.url);
const dataSourceText = await readFile(dataSourcePath, "utf8");
const dataTranspiled = ts.transpileModule(dataSourceText, {
    compilerOptions: {
        target: ts.ScriptTarget.ES2023,
        module: ts.ModuleKind.ESNext
    }
}).outputText;
const dataModuleUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(dataTranspiled)}`;

const sourcePath = new URL("../src/app/source-preserving-markdown.ts", import.meta.url);
const sourceText = await readFile(sourcePath, "utf8");
const coreUrl = await import.meta.resolve("@milkdown/core");
const transformerUrl = await import.meta.resolve("@milkdown/transformer");
const stringifyUrl = await import.meta.resolve("remark-stringify");
const visitUrl = await import.meta.resolve("unist-util-visit");
const transpiled = ts.transpileModule(sourceText, {
    compilerOptions: {
        target: ts.ScriptTarget.ES2023,
        module: ts.ModuleKind.ESNext
    }
}).outputText
    .replace(/from "@milkdown\/core"/g, `from "${coreUrl}"`)
    .replace(/from "@milkdown\/transformer"/g, `from "${transformerUrl}"`)
    .replace(/from "remark-stringify"/g, `from "${stringifyUrl}"`)
    .replace(/from "unist-util-visit"/g, `from "${visitUrl}"`)
    .replace(/from "\.\/source-preserving-markdown-data"/g, `from "${dataModuleUrl}"`);

const moduleUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(transpiled)}`;
const {configureSourcePreservingMarkdown} = await import(moduleUrl);

const collectConfigUpdates = (sourceMarkdown) => {
    const updates = [];
    const crepe = {
        editor: {
            config(callback) {
                callback({
                    update(_key, updater) {
                        const arraySeed = [];
                        const objectSeed = {
                            bullet: "*",
                            bulletOther: "-",
                            bulletOrdered: ".",
                            emphasis: "*",
                            strong: "*",
                            fence: "`",
                            rule: "*",
                            setext: false,
                            closeAtx: false,
                            listItemIndent: "one",
                            handlers: {
                                existing: () => "existing"
                            }
                        };

                        let arrayResult = null;
                        let objectResult = null;
                        try {
                            arrayResult = updater(arraySeed);
                        } catch (_error) {
                            arrayResult = null;
                        }
                        try {
                            objectResult = updater(objectSeed);
                        } catch (_error) {
                            objectResult = null;
                        }

                        updates.push({arrayResult, objectResult});
                    }
                });
            }
        }
    };

    configureSourcePreservingMarkdown(crepe, sourceMarkdown);
    return updates;
};

const makeTracker = () => ({
    current: () => ({before: "", after: ""}),
    move: (value) => value
});

const makeState = (overrides = {}) => {
    const state = {
        bulletCurrent: "?",
        bulletLastUsed: null,
        enter: () => () => {},
        createTracker: () => makeTracker(),
        containerFlow: () => state.bulletCurrent,
        containerPhrasing: () => "Heading text",
        safe: (value) => String(value),
        indentLines: (value) => `INDENT:${value}`,
        ...overrides
    };

    state.options = {
        bullet: "-",
        bulletOrdered: ".",
        setext: false,
        closeAtx: false,
        fence: "`",
        rule: "*",
        ruleSpaces: false,
        ruleRepetition: 3,
        ...(overrides.options ?? {})
    };

    return state;
};

test("captures source-style metadata from markdown nodes", () => {
    const sourceMarkdown = [
        "- bullet",
        "1) ordered",
        "# Heading #",
        "Setext heading",
        "------",
        "~~~ts",
        "console.log(1)",
        "~~~",
        "    indented code",
        "***"
    ].join("\n");

    const updates = collectConfigUpdates(sourceMarkdown);
    const pluginUpdate = updates.find((entry) => Array.isArray(entry.arrayResult));
    assert.ok(pluginUpdate);

    const sourceStylePlugin = pluginUpdate.arrayResult.at(-1);
    assert.equal(typeof sourceStylePlugin?.plugin, "function");

    const transformer = sourceStylePlugin.plugin();
    const tree = {
        type: "root",
        children: [
            {type: "list", ordered: false, position: {start: {line: 1}}},
            {type: "list", ordered: true, position: {start: {line: 2}}},
            {type: "heading", depth: 1, position: {start: {line: 3}}},
            {type: "heading", depth: 2, position: {start: {line: 4}}},
            {type: "code", position: {start: {line: 6}}},
            {type: "code", position: {start: {line: 9}}},
            {type: "thematicBreak", position: {start: {line: 10}}}
        ]
    };

    transformer(tree, {value: sourceMarkdown});

    assert.equal(tree.children[0].data.markflowBullet, "-");
    assert.equal(tree.children[1].data.markflowOrderedDelimiter, ")");
    assert.equal(tree.children[2].data.markflowHeadingStyle, "atx");
    assert.equal(tree.children[2].data.markflowHeadingCloseAtx, true);
    assert.equal(tree.children[3].data.markflowHeadingStyle, "setext");
    assert.equal(tree.children[4].data.markflowCodeStyle, "fenced");
    assert.equal(tree.children[4].data.markflowFenceMarker, "~");
    assert.equal(tree.children[4].data.markflowFenceLength, 3);
    assert.equal(tree.children[5].data.markflowCodeStyle, "indented");
    assert.equal(tree.children[6].data.markflowRuleMarker, "*");
    assert.equal(tree.children[6].data.markflowRuleSpaces, false);
    assert.equal(tree.children[6].data.markflowRuleRepetition, 3);
});

test("configures source-preserving stringify defaults and handlers", () => {
    const updates = collectConfigUpdates([
        "+ plus bullet",
        "7) ordered",
        "~~~js",
        "___",
        "# Heading #",
        "Setext heading",
        "========",
        "\t- nested",
        "This has _emphasis_ and __strong__"
    ].join("\n"));

    const stringifyUpdate = updates.find((entry) => entry.objectResult && entry.objectResult.handlers);
    assert.ok(stringifyUpdate);

    const options = stringifyUpdate.objectResult;
    assert.equal(options.bullet, "+");
    assert.equal(options.bulletOther, "*");
    assert.equal(options.bulletOrdered, ")");
    assert.equal(options.fence, "~");
    assert.equal(options.rule, "_");
    assert.equal(options.setext, true);
    assert.equal(options.closeAtx, true);
    assert.equal(options.emphasis, "_");
    assert.equal(options.strong, "_");
    assert.equal(options.listItemIndent, "tab");
    assert.equal(typeof options.handlers.list, "function");
    assert.equal(typeof options.handlers.heading, "function");
    assert.equal(typeof options.handlers.code, "function");
    assert.equal(typeof options.handlers.thematicBreak, "function");
    assert.equal(typeof options.handlers.existing, "function");

    const listState = makeState();
    assert.equal(options.handlers.list({ordered: false, data: {markflowBullet: "+"}}, null, listState, {}), "+");
    assert.equal(listState.bulletCurrent, "?");
    assert.equal(options.handlers.list({ordered: true, data: {markflowOrderedDelimiter: ")"}}, null, listState, {}), ")");
    assert.equal(listState.bulletCurrent, "?");

    const setextHeadingState = makeState({options: {setext: true}});
    assert.equal(
        options.handlers.heading({depth: 1, data: {markflowHeadingStyle: "setext"}}, null, setextHeadingState, {}),
        "Heading text\n============"
    );

    const closeAtxState = makeState();
    assert.equal(
        options.handlers.heading(
            {depth: 2, data: {markflowHeadingStyle: "atx", markflowHeadingCloseAtx: true}},
            null,
            closeAtxState,
            {}
        ),
        "## Heading text ##"
    );

    const codeState = makeState();
    assert.equal(
        options.handlers.code(
            {
                value: "console.log(1);",
                lang: "ts",
                meta: "meta",
                data: {markflowCodeStyle: "fenced", markflowFenceMarker: "~", markflowFenceLength: 4}
            },
            null,
            codeState,
            {}
        ),
        "~~~~ts meta\nconsole.log(1);\n~~~~"
    );

    assert.equal(
        options.handlers.code(
            {value: "indented code", data: {markflowCodeStyle: "indented"}},
            null,
            codeState,
            {}
        ),
        "INDENT:indented code"
    );

    assert.equal(
        options.handlers.thematicBreak(
            {data: {markflowRuleMarker: "_", markflowRuleSpaces: true, markflowRuleRepetition: 4}},
            null,
            makeState(),
            {}
        ),
        "_ _ _ _"
    );
});
