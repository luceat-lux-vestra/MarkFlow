export type PreviewUrlKind =
    | "fragment"
    | "document-relative"
    | "parent-relative"
    | "absolute-path"
    | "protocol-relative"
    | "remote-http"
    | "remote-https"
    | "file"
    | "data"
    | "blob"
    | "executable"
    | "external-scheme"
    | "malformed";

export type PreviewUrlRequirement =
    | "denied"
    | "local-resource-capability"
    | "remote-resource-opt-in"
    | "navigation-capability";

export interface PreviewUrlClassification {
    readonly kind: PreviewUrlKind;
    readonly requirement: PreviewUrlRequirement;
}

export type PreviewElementCapability = "passive" | "denied-active" | "denied-unknown";
export type PreviewAttributeCapability = "passive" | "url-bearing" | "denied-active" | "denied-unknown";

const PASSIVE_ELEMENT_NAMES = new Set([
    "a", "abbr", "b", "bdi", "bdo", "blockquote", "br", "caption", "cite", "code",
    "col", "colgroup", "dd", "del", "details", "dfn", "div", "dl", "dt", "em",
    "figcaption", "figure", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "i", "img",
    "ins", "kbd", "li", "mark", "ol", "p", "pre", "q", "s", "samp", "small", "span",
    "strong", "sub", "summary", "sup", "table", "tbody", "td", "tfoot", "th", "thead",
    "time", "tr", "u", "ul", "var", "wbr"
]);

const ACTIVE_ELEMENT_NAMES = new Set([
    "base",
    "embed",
    "form",
    "frame",
    "frameset",
    "iframe",
    "link",
    "meta",
    "object",
    "portal",
    "script",
    "style"
]);

const PASSIVE_ATTRIBUTE_NAMES = new Set([
    "align", "alt", "class", "colspan", "datetime", "decoding", "dir", "height", "lang",
    "loading", "open", "referrerpolicy", "rel", "reversed", "role", "rowspan", "scope", "start",
    "title", "type", "width"
]);

const URL_ATTRIBUTE_NAMES = new Set([
    "action",
    "background",
    "cite",
    "data",
    "href",
    "longdesc",
    "poster",
    "src",
    "usemap",
    "xlink:href"
]);

const ACTIVE_ATTRIBUTE_NAMES = new Set([
    "download",
    "form",
    "formaction",
    "formenctype",
    "formmethod",
    "formnovalidate",
    "formtarget",
    "imagesrcset",
    "ping",
    "srcdoc",
    "srcset",
    "style",
    "target"
]);

const LEADING_ASCII_SPACE_OR_CONTROL = /^[\u0000-\u0020\u007f]+/u;
const ASCII_SPACE_OR_CONTROL = /[\u0000-\u0020\u007f]/gu;
const WINDOWS_ABSOLUTE_PATH = /^[A-Za-z]:[\\/]/u;
const PERCENT_BYTE = /%([0-9A-Fa-f]{2})/gu;
const URI_SCHEME = /^[A-Za-z][A-Za-z0-9+.-]*$/u;

function decodedUrlProbe(value: string): string {
    let result = value.replace(LEADING_ASCII_SPACE_OR_CONTROL, "");
    // Decode bounded percent-byte layers only for classification. This does not create a loadable
    // URL: it makes obfuscated executable/protocol prefixes at least as strict as their decoded
    // form while avoiding an unbounded decode loop.
    for (let index = 0; index < 3; index += 1) {
        const decoded = result.replace(PERCENT_BYTE, (_match, hex: string) =>
            String.fromCharCode(Number.parseInt(hex, 16))
        );
        if (decoded === result) {
            break;
        }
        result = decoded;
    }
    return result;
}

function classification(kind: PreviewUrlKind, requirement: PreviewUrlRequirement): PreviewUrlClassification {
    return Object.freeze({kind, requirement});
}

function explicitScheme(value: string): string | null {
    const colon = value.indexOf(":");
    if (colon <= 0) {
        return null;
    }
    const compact = value.slice(0, colon).replace(ASCII_SPACE_OR_CONTROL, "");
    return URI_SCHEME.test(compact) ? compact.toLowerCase() : null;
}

function validRemoteUrl(value: string, expectedProtocol: "http:" | "https:"): boolean {
    try {
        const parsed = new URL(value);
        return parsed.protocol === expectedProtocol && parsed.hostname.length > 0;
    } catch (_error) {
        return false;
    }
}

/**
 * Classify a URL-bearing preview value without granting any browser/filesystem capability.
 *
 * Every result is non-authorizing. Callers must obtain the named future capability explicitly;
 * `denied` means there is no capability path for this value in the current target contract.
 */
export function classifyPreviewUrl(value: string): PreviewUrlClassification {
    const probe = decodedUrlProbe(value);
    if (probe.length === 0) {
        return classification("malformed", "denied");
    }

    if (WINDOWS_ABSOLUTE_PATH.test(probe) || probe.startsWith("\\\\")) {
        return classification("absolute-path", "denied");
    }
    if (probe.startsWith("//") || probe.startsWith("/\\") || probe.startsWith("\\/")) {
        return classification("protocol-relative", "denied");
    }
    if (probe.startsWith("/")) {
        return classification("absolute-path", "denied");
    }
    if (probe === ".." || probe.startsWith("../") || probe.startsWith("..\\")) {
        return classification("parent-relative", "denied");
    }
    if (probe.startsWith("#")) {
        return classification("fragment", "navigation-capability");
    }

    const scheme = explicitScheme(probe);
    if (scheme !== null) {
        switch (scheme) {
            case "javascript":
            case "vbscript":
                return classification("executable", "denied");
            case "file":
                return classification("file", "denied");
            case "data":
                return classification("data", "denied");
            case "blob":
                return classification("blob", "denied");
            case "http":
                return validRemoteUrl(probe, "http:")
                    ? classification("remote-http", "remote-resource-opt-in")
                    : classification("malformed", "denied");
            case "https":
                return validRemoteUrl(probe, "https:")
                    ? classification("remote-https", "remote-resource-opt-in")
                    : classification("malformed", "denied");
            default:
                return classification("external-scheme", "denied");
        }
    }

    // A colon before a path/query/fragment separator is URL-scheme-like but not a valid URI scheme.
    // Do not reinterpret it as a local path candidate.
    const firstSeparator = probe.search(/[\\/?#]/u);
    const colon = probe.indexOf(":");
    if (colon >= 0 && (firstSeparator < 0 || colon < firstSeparator)) {
        return classification("malformed", "denied");
    }

    return classification("document-relative", "local-resource-capability");
}

/** Active/embedded and unknown elements fail closed before generated DOM can be admitted. */
export function classifyPreviewElement(tagName: string): PreviewElementCapability {
    const normalized = tagName.trim().toLowerCase();
    if (ACTIVE_ELEMENT_NAMES.has(normalized)) {
        return "denied-active";
    }
    return PASSIVE_ELEMENT_NAMES.has(normalized) ? "passive" : "denied-unknown";
}

/**
 * Classify one generated-DOM attribute. `url-bearing` is not authorization: the attribute value
 * must still pass `classifyPreviewUrl`, and even then the named future capability is required.
 * Unknown attributes fail closed rather than silently widening a later renderer's trust surface.
 */
export function classifyPreviewAttribute(attributeName: string): PreviewAttributeCapability {
    const normalized = attributeName.trim().toLowerCase();
    if (normalized.startsWith("on") || ACTIVE_ATTRIBUTE_NAMES.has(normalized)) {
        return "denied-active";
    }
    if (URL_ATTRIBUTE_NAMES.has(normalized)) {
        return "url-bearing";
    }
    if (PASSIVE_ATTRIBUTE_NAMES.has(normalized) || normalized.startsWith("aria-") || normalized.startsWith("data-")) {
        return "passive";
    }
    return "denied-unknown";
}
