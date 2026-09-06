import {classifyPreviewUrl} from "./preview-trust-policy.ts";

const CAPABILITY_PATH = /^\/__markflow_source_image__\/([A-Za-z0-9_-]{43,128})\/$/u;
const PERCENT_BYTE = /%([0-9A-Fa-f]{2})/gu;
const ASCII_CONTROL = /[\u0000-\u001f\u007f]/u;

function capabilityBaseUrl(rawBaseUrl: string | null | undefined): URL | null {
    if (typeof rawBaseUrl !== "string" || rawBaseUrl.length > 512) {
        return null;
    }

    try {
        const base = new URL(rawBaseUrl);
        if (base.protocol !== "http:"
            || base.hostname !== "127.0.0.1"
            || base.port.length === 0
            || base.username.length !== 0
            || base.password.length !== 0
            || base.search.length !== 0
            || base.hash.length !== 0
            || !CAPABILITY_PATH.test(base.pathname)) {
            return null;
        }
        return base;
    } catch (_error) {
        return null;
    }
}

function decodedPathProbe(value: string): string {
    let result = value;
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

function containsTraversalSyntax(value: string): boolean {
    const decoded = decodedPathProbe(value);
    if (ASCII_CONTROL.test(decoded) || decoded.includes("\\")) {
        return true;
    }
    const path = decoded.split(/[?#]/u, 1)[0];
    return path.split("/").some((segment) => segment === "..");
}

/**
 * Resolve one source URL through an already-issued document-local image capability.
 *
 * This function is deliberately pure and non-fetching. It consumes #120's non-authorizing URL
 * classification and returns a loadable loopback URL only when both the source value and the host
 * capability base satisfy the source-native trust boundary. Parent traversal syntax is rejected
 * even when URL normalization would happen to keep the normalized result beneath the root; the
 * capability is not permission to reinterpret traversal as a safe alias.
 */
export function resolveSourceNativeLocalImageUrl(
    sourceUrl: string,
    localImageBaseUrl: string | null | undefined
): string | null {
    const classification = classifyPreviewUrl(sourceUrl);
    if (classification.kind !== "document-relative"
        || classification.requirement !== "local-resource-capability"
        || containsTraversalSyntax(sourceUrl)) {
        return null;
    }

    const base = capabilityBaseUrl(localImageBaseUrl);
    if (base === null) {
        return null;
    }

    try {
        const resolved = new URL(sourceUrl, base);
        if (resolved.protocol !== "http:"
            || resolved.origin !== base.origin
            || !resolved.pathname.startsWith(base.pathname)
            || resolved.pathname === base.pathname) {
            return null;
        }
        return resolved.href;
    } catch (_error) {
        return null;
    }
}
