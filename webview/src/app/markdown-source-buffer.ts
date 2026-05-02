import remarkParse from "remark-parse";
import {unified} from "unified";

type MarkdownNode = {
    type: string;
    position?: {
        start?: {offset?: number};
        end?: {offset?: number};
    };
    children?: MarkdownNode[];
    [key: string]: unknown;
};

const parser = unified().use(remarkParse);

const stripTransientFields = (value: unknown): unknown => {
    if (Array.isArray(value)) {
        return value.map((item) => stripTransientFields(item));
    }

    if (!value || typeof value !== "object") {
        return value;
    }

    const result: Record<string, unknown> = {};
    for (const [key, entry] of Object.entries(value as Record<string, unknown>)) {
        if (key === "position" || key === "data") {
            continue;
        }
        result[key] = stripTransientFields(entry);
    }

    return result;
};

const nodeSignature = (node: MarkdownNode): string => JSON.stringify(stripTransientFields(node));

const parseMarkdown = (markdown: string): MarkdownNode => parser.parse(markdown) as MarkdownNode;

const nodeSlice = (markdown: string, node: MarkdownNode): string => {
    const start = node.position?.start?.offset;
    const end = node.position?.end?.offset;
    if (typeof start !== "number" || typeof end !== "number" || start < 0 || end < start) {
        return markdown;
    }
    return markdown.slice(start, end);
};

type MarkdownNodeRange = {
    index: number;
    node: MarkdownNode;
    start: number;
    end: number;
    signature: string;
};

const collectNodeRanges = (nodes: MarkdownNode[]): MarkdownNodeRange[] => {
    const ranges: MarkdownNodeRange[] = [];
    for (let index = 0; index < nodes.length; index += 1) {
        const node = nodes[index];
        const start = node.position?.start?.offset;
        const end = node.position?.end?.offset;
        if (typeof start !== "number" || typeof end !== "number" || start < 0 || end < start) {
            return [];
        }
        ranges.push({
            index,
            node,
            start,
            end,
            signature: nodeSignature(node)
        });
    }
    return ranges;
};

const getNodeRange = (node: MarkdownNode): {start: number; end: number} | null => {
    const start = node.position?.start?.offset;
    const end = node.position?.end?.offset;
    if (typeof start !== "number" || typeof end !== "number" || start < 0 || end < start) {
        return null;
    }
    return {start, end};
};

const patchContainerNode = (
    rawMarkdown: string,
    rawNode: MarkdownNode,
    serializedMarkdown: string,
    nextNode: MarkdownNode
): string | null => {
    const rawRange = getNodeRange(rawNode);
    const rawChildren = rawNode.children ?? [];
    const nextChildren = nextNode.children ?? [];

    if (!rawRange || rawChildren.length === 0 || rawChildren.length !== nextChildren.length) {
        return null;
    }

    let output = rawMarkdown.slice(rawRange.start, rawRange.start);
    let cursor = rawRange.start;

    for (let i = 0; i < rawChildren.length; i += 1) {
        const rawChild = rawChildren[i];
        const nextChild = nextChildren[i];
        const rawChildRange = getNodeRange(rawChild);
        if (!rawChildRange) {
            return null;
        }

        output += rawMarkdown.slice(cursor, rawChildRange.start);
        output += patchNodeSlice(rawMarkdown, rawChild, serializedMarkdown, nextChild);
        cursor = rawChildRange.end;
    }

    output += rawMarkdown.slice(cursor, rawRange.end);
    return output;
};

const patchNodeSlice = (
    rawMarkdown: string,
    rawNode: MarkdownNode,
    serializedMarkdown: string,
    nextNode: MarkdownNode
): string => {
    if (nodeSignature(rawNode) === nodeSignature(nextNode)) {
        return nodeSlice(rawMarkdown, rawNode);
    }

    const recursivePatch = rawNode.type === nextNode.type && (rawNode.children ?? []).length > 0
        ? patchContainerNode(rawMarkdown, rawNode, serializedMarkdown, nextNode)
        : null;
    if (recursivePatch !== null) {
        return recursivePatch;
    }

    const nextSlice = nodeSlice(serializedMarkdown, nextNode);
    return nextSlice;
};

const buildLcsMatches = (left: string[], right: string[]): Array<[number, number]> => {
    const rows = left.length;
    const cols = right.length;
    const dp: number[][] = Array.from({length: rows + 1}, () => Array(cols + 1).fill(0));

    for (let i = rows - 1; i >= 0; i -= 1) {
        for (let j = cols - 1; j >= 0; j -= 1) {
            dp[i][j] = left[i] === right[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
        }
    }

    const matches: Array<[number, number]> = [];
    let i = 0;
    let j = 0;
    while (i < rows && j < cols) {
        if (left[i] === right[j]) {
            matches.push([i, j]);
            i += 1;
            j += 1;
            continue;
        }

        if (dp[i + 1][j] >= dp[i][j + 1]) {
            i += 1;
        } else {
            j += 1;
        }
    }

    return matches;
};

export const updateRawMarkdownFromSerialized = (rawMarkdown: string, serializedMarkdown: string): string => {
    if (rawMarkdown === serializedMarkdown) {
        return rawMarkdown;
    }

    const rawAst = parseMarkdown(rawMarkdown);
    const nextAst = parseMarkdown(serializedMarkdown);

    if (JSON.stringify(stripTransientFields(rawAst)) === JSON.stringify(stripTransientFields(nextAst))) {
        return rawMarkdown;
    }

    const rawChildren = rawAst.children ?? [];
    const nextChildren = nextAst.children ?? [];
    if (!rawChildren.length || !nextChildren.length) {
        return serializedMarkdown;
    }

    const rawRanges = collectNodeRanges(rawChildren);
    const nextRanges = collectNodeRanges(nextChildren);
    if (!rawRanges.length || !nextRanges.length) {
        return serializedMarkdown;
    }

    const rawSignatures = rawRanges.map((range) => range.signature);
    const nextSignatures = nextRanges.map((range) => range.signature);
    const matches = buildLcsMatches(rawSignatures, nextSignatures);
    const rawIndexByNext = new Map<number, number>();
    const nextIndexByRaw = new Map<number, number>();
    for (const [rawIndex, nextIndex] of matches) {
        rawIndexByNext.set(nextIndex, rawIndex);
        nextIndexByRaw.set(rawIndex, nextIndex);
    }

    const unmatchedRawRanges = rawRanges.filter((range) => !nextIndexByRaw.has(range.index));
    const unmatchedNextRanges = nextRanges.filter((range) => !rawIndexByNext.has(range.index));
    const unmatchedRangePairs = new Map<number, number>();
    const unmatchedPairCount = Math.min(unmatchedRawRanges.length, unmatchedNextRanges.length);
    for (let i = 0; i < unmatchedPairCount; i += 1) {
        unmatchedRangePairs.set(unmatchedRawRanges[i].index, unmatchedNextRanges[i].index);
    }

    let output = "";
    let cursor = 0;
    for (const rawRange of rawRanges) {
        if (rawRange.start >= cursor) {
            output += rawMarkdown.slice(cursor, rawRange.start);
        }

        const matchedNextIndex = nextIndexByRaw.get(rawRange.index);
        const pairedNextIndex = matchedNextIndex ?? unmatchedRangePairs.get(rawRange.index);
        if (pairedNextIndex === undefined) {
            cursor = rawRange.end;
            continue;
        }

        if (matchedNextIndex !== undefined) {
            const nextRange = nextRanges[matchedNextIndex];
            output += patchNodeSlice(rawMarkdown, rawRange.node, serializedMarkdown, nextRange.node);
        } else {
            const nextRange = nextRanges[pairedNextIndex];
            output += patchNodeSlice(rawMarkdown, rawRange.node, serializedMarkdown, nextRange.node);
        }

        cursor = rawRange.end;
    }

    if (cursor < rawMarkdown.length) {
        output += rawMarkdown.slice(cursor);
    }

    const extraNextRanges = unmatchedNextRanges.slice(unmatchedPairCount);
    if (extraNextRanges.length) {
        output += extraNextRanges.map((range) => serializedMarkdown.slice(range.start, range.end)).join("");
    }

    return output;
};
