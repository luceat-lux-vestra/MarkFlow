/**
 * Color utilities for IDE palette sync.
 *
 * The webview owns the design system: it reads the IDE's solid colors (an opaque provider) and
 * derives a coherent light/dark palette with contrast guards. All hex parsing here assumes the
 * `#RRGGBB` form produced by the Kotlin backend (see MarkFlowIdeThemeService.toHex).
 */

export type Rgb = {
    r: number;
    g: number;
    b: number;
};

/** Parse a hex or rgba color string into RGB components. Returns null for unparseable input. */
export const parseHex = (hex: string): Rgb | null => {
    const value = String(hex).trim().replace(/^#/, "");
    if (/^[0-9a-fA-F]{3}$/.test(value) || /^[0-9a-fA-F]{6}$/.test(value)) {
        const expanded = value.length === 3
            ? value.split("").map((channel) => channel + channel).join("")
            : value;
        return {
            r: parseInt(expanded.slice(0, 2), 16),
            g: parseInt(expanded.slice(2, 4), 16),
            b: parseInt(expanded.slice(4, 6), 16)
        };
    }
    const rgba = String(hex).trim().match(
        /^rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})(?:\s*,\s*(?:0|1|0?\.\d+))?\s*\)$/i
    );
    if (rgba) {
        return {
            r: Math.min(255, Number(rgba[1])),
            g: Math.min(255, Number(rgba[2])),
            b: Math.min(255, Number(rgba[3]))
        };
    }
    return null;
};

/** Render RGB components back to a lowercase `#RRGGBB` string. */
export const toHex = ({r, g, b}: Rgb): string => {
    const clamp = (v: number) => Math.max(0, Math.min(255, Math.round(v)));
    const component = (v: number) => clamp(v).toString(16).padStart(2, "0");
    return `#${component(r)}${component(g)}${component(b)}`;
};

/** Linearized relative luminance (WCAG 2.1) for an RGB color in the 0-255 range. */
export const relativeLuminance = ({r, g, b}: Rgb): number => {
    const channel = (c: number) => {
        const s = c / 255;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    };
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
};

/** WCAG contrast ratio between two RGB colors (>= 1, higher = more contrast). */
export const contrastRatio = (a: Rgb, b: Rgb): number => {
    const la = relativeLuminance(a);
    const lb = relativeLuminance(b);
    const lighter = Math.max(la, lb);
    const darker = Math.min(la, lb);
    return (lighter + 0.05) / (darker + 0.05);
};

/** Whether a contrast ratio meets WCAG AA for the given text size (large text = 18pt+ / 14.67bold). */
export const meetsAA = (ratio: number, largeText: boolean): boolean =>
    largeText ? ratio >= 3.0 : ratio >= 4.5;

/** Black or white — whichever contrasts more with the background. */
export const readableTextColor = (bgHex: string): string => {
    const bg = parseHex(bgHex);
    if (!bg) return "#000000";
    const black: Rgb = {r: 0, g: 0, b: 0};
    const white: Rgb = {r: 255, g: 255, b: 255};
    return contrastRatio(black, bg) >= contrastRatio(white, bg) ? "#000000" : "#ffffff";
};

/** Linearly blend two colors. weight=0 returns c1, weight=1 returns c2. */
export const mix = (c1Hex: string, c2Hex: string, weight: number): string => {
    const c1 = parseHex(c1Hex);
    const c2 = parseHex(c2Hex);
    if (!c1 || !c2) return c1Hex;
    const w = Math.max(0, Math.min(1, weight));
    return toHex({
        r: c1.r + (c2.r - c1.r) * w,
        g: c1.g + (c2.g - c1.g) * w,
        b: c1.b + (c2.b - c1.b) * w
    });
};

/** Lighten a color toward white by `amount` (0-1). */
export const lighten = (hex: string, amount: number): string => mix(hex, "#ffffff", amount);

/** Darken a color toward black by `amount` (0-1). */
export const darken = (hex: string, amount: number): string => mix(hex, "#000000", amount);

/**
 * Adjust `fg` so it meets the WCAG AA ratio against `bg`. If it already passes, `fg` is returned
 * unchanged; otherwise it is moved toward black/white (whichever increases contrast) until it
 * passes. Falls back to pure black/white if no intermediate step suffices.
 */
export const adjustForContrast = (fgHex: string, bgHex: string, minRatio = 4.5): string => {
    const fg = parseHex(fgHex);
    const bg = parseHex(bgHex);
    if (!fg || !bg) return fgHex;
    const requestedRatio = Number.isFinite(minRatio) ? Math.max(1, minRatio) : 4.5;
    if (contrastRatio(fg, bg) >= requestedRatio) return fgHex;

    const black: Rgb = {r: 0, g: 0, b: 0};
    const white: Rgb = {r: 255, g: 255, b: 255};
    // Choose the endpoint that actually maximizes WCAG contrast. A luminance midpoint is not
    // sufficient: for mid-gray backgrounds, black can beat white even when luminance is below 0.5.
    const target = contrastRatio(black, bg) >= contrastRatio(white, bg) ? black : white;
    if (contrastRatio(target, bg) < requestedRatio) {
        // The requested ratio is not reachable against this background. Return the best possible
        // endpoint rather than claiming an invariant that the color space cannot satisfy.
        return toHex(target);
    }

    let lo = 0;
    let hi = 1;
    for (let i = 0; i < 24; i += 1) {
        const mid = (lo + hi) / 2;
        const candidate = {
            r: fg.r + (target.r - fg.r) * mid,
            g: fg.g + (target.g - fg.g) * mid,
            b: fg.b + (target.b - fg.b) * mid
        };
        if (contrastRatio(candidate, bg) >= requestedRatio) {
            hi = mid;
        } else {
            lo = mid;
        }
    }
    const result = toHex({
        r: fg.r + (target.r - fg.r) * hi,
        g: fg.g + (target.g - fg.g) * hi,
        b: fg.b + (target.b - fg.b) * hi
    });
    // Rounding to CSS's integer RGB representation can move the result just below the threshold.
    // Verify the emitted value, and use the already-verified endpoint if necessary.
    return parseHex(result) && contrastRatio(parseHex(result)!, bg) >= requestedRatio
        ? result
        : toHex(target);
};

/** Build a hex string from raw RGB components (used by tests and callers). */
export const rgb = (r: number, g: number, b: number): string => toHex({r, g, b});
