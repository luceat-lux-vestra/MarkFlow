import type {IdeColors} from "./types";

/**
 * Returns the stable identity of the IDE palette values that affect Mermaid output.
 * Selection colors affect the editor selection only, not Mermaid's SVG, so they intentionally do
 * not invalidate an otherwise reusable diagram.
 */
const MERMAID_PALETTE_KEYS = ["background", "foreground", "border"] as const;

const hashString = (value: string): string => {
    let hash = 2166136261;
    for (let index = 0; index < value.length; index += 1) {
        hash ^= value.charCodeAt(index);
        hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(36);
};

export const hashMermaidPaletteIdentity = (ideColorScheme: IdeColors): string => {
    const entries = MERMAID_PALETTE_KEYS.map((key) => `${key}=${ideColorScheme[key] ?? ""}`);
    return hashString(entries.join("|"));
};
