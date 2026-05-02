export type MarkdownSourceDefaults = {
    bullet: "-" | "+" | "*";
    bulletOther: "-" | "+" | "*";
    bulletOrdered: "." | ")";
    emphasis: "*" | "_";
    strong: "*" | "_";
    fence: "`" | "~";
    rule: "-" | "*" | "_";
    setext: boolean;
    closeAtx: boolean;
    listItemIndent: "mixed" | "one" | "tab";
};

export const DEFAULT_SOURCE_DEFAULTS: MarkdownSourceDefaults = {
    bullet: "-",
    bulletOther: "*",
    bulletOrdered: ".",
    emphasis: "*",
    strong: "*",
    fence: "`",
    rule: "*",
    setext: false,
    closeAtx: false,
    listItemIndent: "one"
};

export const deriveMarkdownSourceDefaults = (sourceMarkdown: string): MarkdownSourceDefaults => {
    const defaults = {...DEFAULT_SOURCE_DEFAULTS};
    const lines = sourceMarkdown.split(/\r\n|\r|\n/);

    for (const line of lines) {
        if (defaults.bullet === DEFAULT_SOURCE_DEFAULTS.bullet) {
            const bulletMatch = line.match(/^\s{0,3}([*+\-])\s+/);
            if (bulletMatch) {
                defaults.bullet = bulletMatch[1] as "-" | "+" | "*";
                defaults.bulletOther = defaults.bullet === "*" ? "-" : "*";
            }
        }

        if (defaults.bulletOrdered === DEFAULT_SOURCE_DEFAULTS.bulletOrdered) {
            const orderedMatch = line.match(/^\s{0,3}\d+([.)])\s+/);
            if (orderedMatch) {
                defaults.bulletOrdered = orderedMatch[1] as "." | ")";
            }
        }

        if (defaults.fence === DEFAULT_SOURCE_DEFAULTS.fence) {
            const fenceMatch = line.match(/^\s{0,3}([`~]{3,})/);
            if (fenceMatch) {
                defaults.fence = fenceMatch[1].charAt(0) as "`" | "~";
            }
        }

        if (defaults.rule === DEFAULT_SOURCE_DEFAULTS.rule) {
            const ruleMatch = line.match(/^\s{0,3}([*\-_])(?:\s*\1){2,}\s*$/);
            if (ruleMatch) {
                defaults.rule = ruleMatch[1] as "-" | "*" | "_";
            }
        }

        if (!defaults.setext) {
            const setextMatch = line.match(/^\s{0,3}(=+|-+)\s*$/);
            if (setextMatch) {
                defaults.setext = true;
            }
        }

        if (!defaults.closeAtx) {
            const atxMatch = line.match(/^\s{0,3}(#{1,6})(?:\s+|$)/);
            if (atxMatch && /\s#+\s*$/.test(line)) {
                defaults.closeAtx = true;
            }
        }

        if (defaults.emphasis === DEFAULT_SOURCE_DEFAULTS.emphasis) {
            const emphasisMatch = line.match(/(^|[^*])(\*[^*\s][^*\n]*[^*\s]\*|_[^_\s][^_\n]*[^_\s]_)/);
            if (emphasisMatch) {
                defaults.emphasis = emphasisMatch[2].includes("*") ? "*" : "_";
            }
        }

        if (defaults.strong === DEFAULT_SOURCE_DEFAULTS.strong) {
            const strongMatch = line.match(/(\*\*[^*\n]+?\*\*|__[^_\n]+?__)/);
            if (strongMatch) {
                defaults.strong = strongMatch[1].startsWith("*") ? "*" : "_";
            }
        }

        if (defaults.listItemIndent === DEFAULT_SOURCE_DEFAULTS.listItemIndent) {
            if (/^\t{1,}\S/.test(line)) {
                defaults.listItemIndent = "tab";
            } else if (/^\s{4,}(?:[*+\-]|\d+[.)])\s+/.test(line)) {
                defaults.listItemIndent = "mixed";
            }
        }
    }

    return defaults;
};
