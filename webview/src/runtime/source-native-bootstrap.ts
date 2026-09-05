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

    const attachment: SourceNativeAttachment = new SourceNativeAttachment({
        parent,
        attachmentId,
        onSend: (message: AttachmentOutboundMessage) => sendToHost(hostWindow, attachment, message),
        onStateTransition
    });

    hostWindow.__markflowSourceNativeReceive = (raw: string) => {
        attachment.receiveRaw(raw);
    };

    hostWindow.__markflowSourceNativeInit = () => {
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
