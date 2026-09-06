import {installSourceNativeMarkdownPaste} from "../editor/source-native-paste.ts";
import {installSourceNativeLocalImagePreview} from "../trust/source-native-local-image-preview.ts";
import {installSourceNativeNavigationGuard} from "../trust/source-native-navigation-guard.ts";
import {readSourceNativeCspNonce, SourceNativeCspNonceError} from "../trust/source-native-csp.ts";
import {
    SourceNativeAttachment,
    encodeAttachmentMessage,
    type AttachmentOutboundMessage,
    type AttachmentTransportFailure,
    type SourceNativeStateTransition
} from "../sync/source-native-sync.ts";

const MAX_IDENTITY_LENGTH = 128;

/**
 * Window-level bridge functions the host runtime ({@link JcefSourceNativeRuntimeTransport} on the
 * Kotlin side) installs once its owned browser realm finishes loading. This bootstrap never
 * assumes these are present before it needs them: it defines its own `__markflowSourceNativeInit`
 * entry point and only calls it immediately if the host glue happened to install first, and the
 * host-side glue calls it directly once installed. Neither side polls or retries; whichever side
 * finishes setup second makes the single deterministic call into the other.
 */
export interface SourceNativeHostBridge {
    __markflowSourceNativeSend?: (
        raw: string,
        onSuccess: (response: string) => void,
        onFailure: (errorCode: number, errorMessage: string) => void
    ) => void;
    __markflowSourceNativeReady?: (
        raw: string,
        onSuccess: (response: string) => void,
        onFailure: (errorCode: number, errorMessage: string) => void
    ) => void;
    __markflowSourceNativeOpenExternal?: (
        raw: string,
        onSuccess: (response: string) => void,
        onFailure: (errorCode: number, errorMessage: string) => void
    ) => void;
    /** Host-owned, runtime-lifetime capability state; never sourced from location/search or Markdown. */
    __markflowSourceNativeLocalImageBaseUrl?: string | null;
    __markflowSourceNativeReceive?: (raw: string) => void;
    __markflowSourceNativeInit?: () => void;
    __markflowHostGlueInstalled?: boolean;
}

export type SourceNativeBootstrapWindow = Window & SourceNativeHostBridge;

export class SourceNativeBootstrapError extends Error {
    constructor(message: string) {
        super(message);
        this.name = "SourceNativeBootstrapError";
    }
}

function readIdentityParam(search: string, name: string): string {
    const params = new URLSearchParams(search);
    const value = params.get(name);
    if (value === null || value.trim().length === 0 || value.length > MAX_IDENTITY_LENGTH) {
        throw new SourceNativeBootstrapError(`missing or invalid ${name}`);
    }
    return value;
}

function requireSourceNativeCspNonce(parent: Element): string {
    try {
        return readSourceNativeCspNonce(parent.ownerDocument);
    } catch (error) {
        if (error instanceof SourceNativeCspNonceError) {
            throw new SourceNativeBootstrapError("missing or invalid CSP nonce");
        }
        throw error;
    }
}

/**
 * Installs one [SourceNativeAttachment] bound to the current browser realm's host bridge.
 *
 * This is the minimum production-capable web entry point required by #105: it uses the
 * source-native CodeMirror path exclusively, sends only target mutation/recovery wire messages,
 * strictly consumes ACK/rejection/host messages through [SourceNativeAttachment]'s own strict
 * decode path, and has zero correctness dependency on Crepe/Milkdown or the legacy bridge/session.
 */
export function installSourceNativeBootstrap(
    parent: Element,
    hostWindow: SourceNativeBootstrapWindow,
    locationSearch: string,
    onStateTransition?: (transition: SourceNativeStateTransition) => void
): SourceNativeAttachment {
    const attachmentId = readIdentityParam(locationSearch, "attachmentId");
    const runtimeToken = readIdentityParam(locationSearch, "runtimeToken");
    // Fail closed before constructing the attachment/editor. The nonce belongs only to this HTML
    // response and is not allowed to enter source, transport identity, URLs, persistence or logs.
    const cspNonce = requireSourceNativeCspNonce(parent);

    const attachment: SourceNativeAttachment = new SourceNativeAttachment({
        parent,
        attachmentId,
        cspNonce,
        onSend: (message: AttachmentOutboundMessage) => sendToHost(hostWindow, attachment, message),
        onStateTransition
    });
    installSourceNativeMarkdownPaste(attachment.editor.view);
    installSourceNativeNavigationGuard(
        attachment.editor.view,
        parent,
        (url) => requestExternalNavigation(hostWindow, attachment, attachmentId, runtimeToken, url)
    );

    hostWindow.__markflowSourceNativeReceive = (raw: string) => {
        attachment.receiveRaw(raw);
    };

    let localImagePreviewInstalled = false;
    hostWindow.__markflowSourceNativeInit = () => {
        if (attachment.state === "DISPOSED") {
            return;
        }
        if (!localImagePreviewInstalled) {
            localImagePreviewInstalled = true;
            try {
                installSourceNativeLocalImagePreview(
                    attachment.editor.view,
                    hostWindow.__markflowSourceNativeLocalImageBaseUrl ?? null
                );
            } catch (_error) {
                // Local-image presentation is optional. Core source readiness remains available.
            }
        }
        signalReady(hostWindow, attachmentId, runtimeToken);
    };

    if (hostWindow.__markflowHostGlueInstalled === true) {
        hostWindow.__markflowSourceNativeInit();
    }

    return attachment;
}

function sendToHost(
    hostWindow: SourceNativeHostBridge,
    attachment: SourceNativeAttachment,
    message: AttachmentOutboundMessage
): void {
    const send = hostWindow.__markflowSourceNativeSend;
    if (typeof send !== "function") {
        attachment.receiveTransportFailure(toTransportFailure(message));
        return;
    }
    send(
        encodeAttachmentMessage(message),
        (raw) => {
            attachment.receiveRaw(raw);
        },
        () => {
            attachment.receiveTransportFailure(toTransportFailure(message));
        }
    );
}

function requestExternalNavigation(
    hostWindow: SourceNativeHostBridge,
    attachment: SourceNativeAttachment,
    attachmentId: string,
    runtimeToken: string,
    url: string
): void {
    if (attachment.state === "DISPOSED") {
        return;
    }
    const openExternal = hostWindow.__markflowSourceNativeOpenExternal;
    if (typeof openExternal !== "function") {
        return;
    }
    openExternal(
        JSON.stringify({type: "openExternal", attachmentId, runtimeToken, url}),
        () => {
            // Host acceptance is intentionally presentation-only and carries no source state.
        },
        () => {
            // Navigation failure is inert. Never retry or fall back to embedded navigation.
        }
    );
}

function toTransportFailure(message: AttachmentOutboundMessage): AttachmentTransportFailure {
    if (message.type === "mutationRequest") {
        return {type: "mutation", attachmentId: message.attachmentId, requestId: message.requestId};
    }
    return {type: "recovery", attachmentId: message.attachmentId, recoveryId: message.recoveryId};
}

function signalReady(hostWindow: SourceNativeHostBridge, attachmentId: string, runtimeToken: string): void {
    const ready = hostWindow.__markflowSourceNativeReady;
    if (typeof ready !== "function") {
        return;
    }
    ready(
        JSON.stringify({type: "runtimeReady", attachmentId, runtimeToken}),
        () => {
            // The host acknowledges the handshake; the host, not this bootstrap, decides when to
            // send the one BootstrapSnapshot for this runtime/attachment lifetime.
        },
        () => {
            // A failed readiness handshake must never be treated as ready. There is no retry:
            // the host runtime that owns this browser realm stays fail-closed at BOOTSTRAP.
        }
    );
}
