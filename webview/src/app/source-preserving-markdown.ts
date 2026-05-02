import type {Crepe} from "@milkdown/crepe";
import {remarkPluginsCtx, remarkStringifyOptionsCtx} from "@milkdown/core";
import type {MarkdownNode, RemarkPlugin} from "@milkdown/transformer";
import type {Options} from "remark-stringify";
import {visit} from "unist-util-visit";
import {deriveMarkdownSourceDefaults} from "./source-preserving-markdown-data";

type SourceStyleData = {
    markflowBullet?: "-" | "+" | "*";
    markflowOrderedDelimiter?: "." | ")";
    markflowHeadingStyle?: "atx" | "setext";
    markflowHeadingCloseAtx?: boolean;
    markflowCodeStyle?: "fenced" | "indented";
    markflowFenceMarker?: "`" | "~";
    markflowFenceLength?: number;
    markflowRuleMarker?: "-" | "*" | "_";
    markflowRuleSpaces?: boolean;
    markflowRuleRepetition?: number;
};

type SourceStyleNode = MarkdownNode & {data?: SourceStyleData};

const sourceStyleRemarkPlugin: RemarkPlugin<{}> = {
    plugin: () => (tree, file) => {
        const source = typeof file.value === "string" ? file.value : "";
        if (!source) {
            return;
        }

        const lines = source.split(/\r\n|\r|\n/);
        visit(tree as any, (node: any) => Boolean(node && node.position), (node: any) => {
            const data = ensureNodeData(node);
            const startLine = node.position?.start?.line;
            if (!startLine || startLine < 1) {
                return;
            }

            const line = lines[startLine - 1] ?? "";
            const nextLine = lines[startLine] ?? "";

            if (node.type === "list") {
                if (node.ordered) {
                    const orderedMatch = line.match(/^\s{0,3}\d+([.)])\s+/);
                    if (orderedMatch) {
                        data.markflowOrderedDelimiter = orderedMatch[1] as "." | ")";
                    }
                } else {
                    const bulletMatch = line.match(/^\s{0,3}([*+\-])\s+/);
                    if (bulletMatch) {
                        data.markflowBullet = bulletMatch[1] as "-" | "+" | "*";
                    }
                }
                return;
            }

            if (node.type === "heading") {
                const atxMatch = line.match(/^\s{0,3}(#{1,6})(?:\s+|$)/);
                if (atxMatch) {
                    data.markflowHeadingStyle = "atx";
                    data.markflowHeadingCloseAtx = /\s#+\s*$/.test(line);
                    return;
                }

                const setextMatch = nextLine.match(/^\s{0,3}(=+|-+)\s*$/);
                if (setextMatch) {
                    data.markflowHeadingStyle = "setext";
                }
                return;
            }

            if (node.type === "code") {
                const fencedMatch = line.match(/^\s{0,3}([`~]{3,})(.*)$/);
                if (fencedMatch) {
                    data.markflowCodeStyle = "fenced";
                    data.markflowFenceMarker = fencedMatch[1].charAt(0) as "`" | "~";
                    data.markflowFenceLength = fencedMatch[1].length;
                    return;
                }

                if (/^\s{4,}/.test(line) || /^\t/.test(line)) {
                    data.markflowCodeStyle = "indented";
                }
                return;
            }

            if (node.type === "thematicBreak") {
                const breakMatch = line.match(/^\s{0,3}([*\-_])(?:\s*\1){2,}\s*$/);
                if (breakMatch) {
                    data.markflowRuleMarker = breakMatch[1] as "-" | "*" | "_";
                    data.markflowRuleSpaces = /\s/.test(line.trim().slice(1));
                    data.markflowRuleRepetition = line.trim().replace(/\s+/g, "").length;
                }
            }
        });
    },
    options: {}
};

const customHandlers: Partial<NonNullable<Options["handlers"]>> = {
    list: (node: any, parent: any, state: any, info: any) => {
        void parent;
        const exit = state.enter("list");
        const bulletCurrent = state.bulletCurrent;
        const data = getSourceStyleData(node);
        const bullet = node.ordered
            ? data.markflowOrderedDelimiter ?? state.options.bulletOrdered ?? "."
            : data.markflowBullet ?? state.options.bullet ?? "-";

        state.bulletCurrent = bullet;
        const value = state.containerFlow(node, info);
        state.bulletLastUsed = bullet;
        state.bulletCurrent = bulletCurrent;
        exit();
        return value;
    },
    heading: (node: any, parent: any, state: any, info: any) => {
        void parent;
        const rank = Math.max(Math.min(6, node.depth || 1), 1);
        const data = getSourceStyleData(node);
        const style = data.markflowHeadingStyle ?? (state.options.setext ? "setext" : "atx");
        const tracker = state.createTracker(info);

        if (style === "setext" && rank <= 2) {
            const exit = state.enter("headingSetext");
            const subexit = state.enter("phrasing");
            const value = state.containerPhrasing(node, {
                ...tracker.current(),
                before: "\n",
                after: "\n"
            });
            subexit();
            exit();

            return (
                value +
                "\n" +
                (rank === 1 ? "=" : "-").repeat(
                    value.length - (Math.max(value.lastIndexOf("\r"), value.lastIndexOf("\n")) + 1)
                )
            );
        }

        const sequence = "#".repeat(rank);
        const exit = state.enter("headingAtx");
        const subexit = state.enter("phrasing");

        tracker.move(sequence + " ");

        let value = state.containerPhrasing(node, {
            before: "# ",
            after: "\n",
            ...tracker.current()
        });

        if (/^[\t ]/.test(value)) {
            value = encodeCharacterReference(value.charCodeAt(0)) + value.slice(1);
        }

        value = value ? `${sequence} ${value}` : sequence;

        if (data.markflowHeadingCloseAtx ?? state.options.closeAtx) {
            value += ` ${sequence}`;
        }

        subexit();
        exit();

        return value;
    },
    code: (node: any, parent: any, state: any, info: any) => {
        void parent;
        const data = getSourceStyleData(node);
        const raw = node.value || "";

        if (data.markflowCodeStyle === "indented" || formatCodeAsIndented(node, state)) {
            const exit = state.enter("codeIndented");
            const value = state.indentLines(raw, codeIndentMap);
            exit();
            return value;
        }

        const marker = data.markflowFenceMarker ?? state.options.fence ?? "`";
        const longest = longestStreak(raw, marker);
        const fenceLength = Math.max(3, data.markflowFenceLength ?? 0, longest + 1);
        const sequence = marker.repeat(fenceLength);
        const suffix = marker === "`" ? "GraveAccent" : "Tilde";
        const exit = state.enter("codeFenced");
        const tracker = state.createTracker(info);
        let value = tracker.move(sequence);

        if (node.lang) {
            const subexit = state.enter(`codeFencedLang${suffix}`);
            value += tracker.move(
                state.safe(node.lang, {
                    before: value,
                    after: " ",
                    encode: ["`"],
                    ...tracker.current()
                })
            );
            subexit();
        }

        if (node.lang && node.meta) {
            const subexit = state.enter(`codeFencedMeta${suffix}`);
            value += tracker.move(" ");
            value += tracker.move(
                state.safe(node.meta, {
                    before: value,
                    after: "\n",
                    encode: ["`"],
                    ...tracker.current()
                })
            );
            subexit();
        }

        value += tracker.move("\n");

        if (raw) {
            value += tracker.move(`${raw}\n`);
        }

        value += tracker.move(sequence);
        exit();
        return value;
    },
    thematicBreak: (_: any, __: any, state: any) => {
        void __;
        const data = getSourceStyleData(_);
        const marker = data.markflowRuleMarker ?? state.options.rule ?? "*";
        const spaces = data.markflowRuleSpaces ?? state.options.ruleSpaces ?? false;
        const repetition = Math.max(3, data.markflowRuleRepetition ?? state.options.ruleRepetition ?? 3);
        const value = (marker + (spaces ? " " : "")).repeat(repetition);
        return spaces ? value.slice(0, -1) : value;
    }
};

export const configureSourcePreservingMarkdown = (crepe: Crepe, sourceMarkdown: string) => {
    const defaults = deriveMarkdownSourceDefaults(sourceMarkdown);

    crepe.editor.config((ctx) => {
        ctx.update(remarkPluginsCtx, (plugins) => [...plugins, sourceStyleRemarkPlugin]);
        ctx.update(remarkStringifyOptionsCtx, (options) => ({
            ...options,
            ...defaults,
            handlers: {
                ...(options.handlers ?? {}),
                ...customHandlers
            }
        }));
    });
};

const ensureNodeData = (node: MarkdownNode): SourceStyleData => {
    const typedNode = node as SourceStyleNode;
    const data = typedNode.data ?? {};
    typedNode.data = data;
    return data;
};

const getSourceStyleData = (node: any): SourceStyleData => {
    return (node?.data ?? {}) as SourceStyleData;
};

const longestStreak = (value: string, marker: string): number => {
    let longest = 0;
    let current = 0;
    for (const char of value) {
        if (char === marker) {
            current += 1;
            longest = Math.max(longest, current);
        } else {
            current = 0;
        }
    }
    return longest;
};

const formatCodeAsIndented = (node: any, state: any): boolean => {
    const value = node?.value ?? "";
    return state.options.fences === false || (!value && !node.lang && !node.meta);
};

const codeIndentMap = (line: string, _: unknown, blank: boolean) => {
    void _;
    return (blank ? "" : "    ") + line;
};

const encodeCharacterReference = (value: number): string => {
    return `&#${value};`;
};
