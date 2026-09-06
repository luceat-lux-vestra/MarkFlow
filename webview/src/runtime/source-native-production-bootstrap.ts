import {readSourceNativeCspNonce, SourceNativeCspNonceError} from "../trust/source-native-csp.ts";
import {
    installSourceNativeBootstrap,
    SourceNativeBootstrapError,
    type SourceNativeBootstrapWindow
} from "./source-native-bootstrap.ts";
import type {SourceNativeAttachment, SourceNativeStateTransition} from "../sync/source-native-sync.ts";

/**
 * Production composition boundary for one source-native browser document.
 *
 * The HTTP response nonce is validated before any attachment/editor/bridge ownership is created.
 * The validated value is presentation-only and is forwarded solely to CodeMirror's CSP nonce
 * facet through the lower-level bootstrap.
 */
export function installProductionSourceNativeBootstrap(
    parent: Element,
    hostWindow: SourceNativeBootstrapWindow,
    locationSearch: string,
    onStateTransition?: (transition: SourceNativeStateTransition) => void
): SourceNativeAttachment {
    let cspNonce: string;
    try {
        cspNonce = readSourceNativeCspNonce(parent.ownerDocument);
    } catch (error) {
        if (error instanceof SourceNativeCspNonceError) {
            throw new SourceNativeBootstrapError("missing or invalid CSP nonce");
        }
        throw error;
    }

    return installSourceNativeBootstrap(
        parent,
        hostWindow,
        locationSearch,
        onStateTransition,
        cspNonce
    );
}
