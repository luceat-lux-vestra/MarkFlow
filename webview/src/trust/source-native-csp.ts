export const SOURCE_NATIVE_CSP_NONCE_PLACEHOLDER = "__MARKFLOW_CSP_NONCE__";

const SOURCE_NATIVE_CSP_NONCE_PATTERN = /^[A-Za-z0-9_-]{43}$/;

export class SourceNativeCspNonceError extends Error {
    constructor() {
        super("missing or invalid source-native CSP nonce");
        this.name = "SourceNativeCspNonceError";
    }
}

/**
 * Read the one Vite-generated CSP nonce marker from the current source-native document.
 *
 * Use HTMLElement.nonce rather than getAttribute("nonce"): browsers deliberately hide nonce
 * attribute values from ordinary attribute reads to reduce CSS/DOM exfiltration surface.
 */
export function readSourceNativeCspNonce(document: Document): string {
    const metas = document.querySelectorAll<HTMLMetaElement>('meta[property="csp-nonce"]');
    if (metas.length !== 1) {
        throw new SourceNativeCspNonceError();
    }

    const nonce = metas[0]?.nonce;
    if (typeof nonce !== "string"
        || nonce === SOURCE_NATIVE_CSP_NONCE_PLACEHOLDER
        || !SOURCE_NATIVE_CSP_NONCE_PATTERN.test(nonce)) {
        throw new SourceNativeCspNonceError();
    }
    return nonce;
}
