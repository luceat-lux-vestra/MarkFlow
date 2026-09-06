import {installSourceNativeBootstrap, type SourceNativeBootstrapWindow} from "./source-native-bootstrap.ts";
import {readSourceNativeCspNonce} from "../trust/source-native-csp.ts";

const parent = document.getElementById("app");
if (parent === null) {
    console.error("MARKFLOW_UI source-native bootstrap failed: missing #app root");
} else {
    try {
        // Browser-policy gate: production must not construct the attachment/editor unless this
        // exact source-native document response carries one valid per-response nonce.
        const cspNonce = readSourceNativeCspNonce(document);
        installSourceNativeBootstrap(
            parent,
            window as SourceNativeBootstrapWindow,
            window.location.search,
            undefined,
            cspNonce
        );
    } catch (error) {
        console.error("MARKFLOW_UI source-native bootstrap failed", error);
    }
}
