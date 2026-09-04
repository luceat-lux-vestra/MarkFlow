import {
    SOURCE_NATIVE_MAX_EDITS,
    SOURCE_NATIVE_MAX_INSERTED_UTF16,
    SourceNativeEditorCore,
    type SourceChange,
    type SourceChangeTransaction
} from "../editor/source-native-editor.ts";

export const MAX_ATTACHMENT_ID_LENGTH = 128;
export const MAX_REQUEST_ID_LENGTH = 128;
export const MAX_SOURCE_EDIT_COUNT = SOURCE_NATIVE_MAX_EDITS;
export const MAX_INSERTED_UTF16_CODE_UNITS = SOURCE_NATIVE_MAX_INSERTED_UTF16;

const MAX_SIGNED_LONG = "9223372036854775807";

declare const attachmentIdBrand: unique symbol;
declare const requestIdBrand: unique symbol;
declare const recoveryIdBrand: unique symbol;
declare const documentRevisionBrand: unique symbol;

export type AttachmentId = string & {[attachmentIdBrand]: true};
export type RequestId = string & {[requestIdBrand]: true};
export type RecoveryId = string & {[recoveryIdBrand]: true};
export type DocumentRevision = string & {[documentRevisionBrand]: true};

export interface WireSourceEdit {
    readonly from: number;
    readonly to: number;
    readonly inserted: string;
}

export interface BootstrapSnapshotMessage {
    readonly type: "bootstrapSnapshot";
    readonly attachmentId: AttachmentId;
    readonly documentRevision: DocumentRevision;
    readonly source: string;
}

export interface RecoverySnapshotMessage {
    readonly type: "recoverySnapshot";
    readonly attachmentId: AttachmentId;
    readonly recoveryId: RecoveryId;
    readonly documentRevision: DocumentRevision;
    readonly source: string;
}

export interface SnapshotRequestMessage {
    readonly type: "snapshotRequest";
    readonly attachmentId: AttachmentId;
    readonly recoveryId: RecoveryId;
}

export interface MutationRequestMessage {
    readonly type: "mutationRequest";
    readonly attachmentId: AttachmentId;
    readonly requestId: RequestId;
    readonly baseDocumentRevision: DocumentRevision;
    readonly edits: readonly WireSourceEdit[];
}

export interface MutationAcceptedMessage {
    readonly type: "mutationAccepted";
    readonly attachmentId: AttachmentId;
    readonly requestId: RequestId;
    readonly finalDocumentRevision: DocumentRevision;
}

export interface MutationAcceptedUnchangedMessage {
    readonly type: "mutationAcceptedUnchanged";
    readonly attachmentId: AttachmentId;
    readonly requestId: RequestId;
    readonly finalDocumentRevision: DocumentRevision;
}

export type MutationRejectionCategory =
    | "STALE_DOCUMENT_REVISION"
    | "DUPLICATE_REQUEST"
    | "WRONG_ATTACHMENT"
    | "DISPOSED"
    | "INVALID_MUTATION"
    | "INVALID_TRANSACTION"
    | "CONFLICT"
    | "UNSUPPORTED_FIDELITY"
    | "INTERNAL_FAILURE";

export interface MutationRejectedMessage {
    readonly type: "mutationRejected";
    readonly attachmentId: AttachmentId;
    readonly requestId: RequestId;
    readonly category: MutationRejectionCategory;
}

export interface HostIncrementalUpdateMessage {
    readonly type: "hostIncrementalUpdate";
    readonly attachmentId: AttachmentId;
    readonly documentRevision: DocumentRevision;
    readonly edit: WireSourceEdit;
}

export type AttachmentWireMessage =
    | BootstrapSnapshotMessage
    | RecoverySnapshotMessage
    | SnapshotRequestMessage
    | MutationRequestMessage
    | MutationAcceptedMessage
    | MutationAcceptedUnchangedMessage
    | MutationRejectedMessage
    | HostIncrementalUpdateMessage;

export type AttachmentOutboundMessage = MutationRequestMessage | SnapshotRequestMessage;

export type AttachmentTransportFailure =
    | {
        readonly type: "mutation";
        readonly attachmentId: AttachmentId;
        readonly requestId: RequestId;
    }
    | {
        readonly type: "recovery";
        readonly attachmentId: AttachmentId;
        readonly recoveryId: RecoveryId;
    };

export class AttachmentWireDecodeError extends Error {
    constructor() {
        super("invalid attachment wire message");
        this.name = "AttachmentWireDecodeError";
    }
}

export function parseAttachmentId(value: unknown): AttachmentId {
    return parseIdentity(value, MAX_ATTACHMENT_ID_LENGTH) as AttachmentId;
}

export function parseRequestId(value: unknown): RequestId {
    return parseIdentity(value, MAX_REQUEST_ID_LENGTH) as RequestId;
}

export function parseRecoveryId(value: unknown): RecoveryId {
    return parseIdentity(value, MAX_REQUEST_ID_LENGTH) as RecoveryId;
}

export function parseDocumentRevision(value: unknown): DocumentRevision {
    if (typeof value !== "string" || !/^(0|[1-9][0-9]*)$/.test(value)) {
        throw new AttachmentWireDecodeError();
    }
    try {
        if (BigInt(value) > BigInt(MAX_SIGNED_LONG)) {
            throw new AttachmentWireDecodeError();
        }
    } catch (_error) {
        throw new AttachmentWireDecodeError();
    }
    return value as DocumentRevision;
}

export function nextDocumentRevision(revision: DocumentRevision): DocumentRevision {
    return advanceDocumentRevision(revision, 1);
}

function advanceDocumentRevision(revision: DocumentRevision, eventCount: number): DocumentRevision {
    try {
        if (!Number.isSafeInteger(eventCount) || eventCount < 1) {
            throw new AttachmentWireDecodeError();
        }
        const next = BigInt(revision) + BigInt(eventCount);
        if (next > BigInt(MAX_SIGNED_LONG)) {
            throw new AttachmentWireDecodeError();
        }
        return parseDocumentRevision(next.toString());
    } catch (_error) {
        throw new AttachmentWireDecodeError();
    }
}

export function compareDocumentRevisions(left: DocumentRevision, right: DocumentRevision): number {
    const leftValue = BigInt(left);
    const rightValue = BigInt(right);
    return leftValue < rightValue ? -1 : leftValue > rightValue ? 1 : 0;
}

export function encodeAttachmentMessage(message: AttachmentWireMessage): string {
    return JSON.stringify(message);
}

export function decodeAttachmentMessage(raw: string): AttachmentWireMessage {
    try {
        const value: unknown = JSON.parse(raw);
        return parseMessage(value);
    } catch (error) {
        if (error instanceof AttachmentWireDecodeError) {
            throw error;
        }
        throw new AttachmentWireDecodeError();
    }
}

export type SourceNativeAttachmentState =
    | "BOOTSTRAP"
    | "READY"
    | "AWAITING_ACK"
    | "RECOVERING"
    | "DISPOSED";

export interface SourceNativeStateTransition {
    readonly from: SourceNativeAttachmentState;
    readonly to: SourceNativeAttachmentState;
    readonly reason: string;
}

export interface SourceNativeAttachmentOptions {
    readonly parent: Element;
    readonly attachmentId: string;
    readonly onSend: (message: AttachmentOutboundMessage) => void;
    readonly nextRequestId?: () => string;
    readonly onStateTransition?: (transition: SourceNativeStateTransition) => void;
}

interface PendingMutation {
    readonly request: MutationRequestMessage;
    readonly expectedFinalRevision: DocumentRevision;
    readonly effectiveEditCount: number;
}

/**
 * One attachment-bound source-native transport state machine.
 *
 * Bootstrap/recovery are the only full-source boundaries. Steady-state proposals, ACKs and host
 * updates carry exact source edits and independent attachment/request/document-revision identities.
 */
export class SourceNativeAttachment {
    readonly editor: SourceNativeEditorCore;
    readonly attachmentId: AttachmentId;
    private readonly onSend: (message: AttachmentOutboundMessage) => void;
    private readonly nextRequestId: () => string;
    private readonly onStateTransition?: (transition: SourceNativeStateTransition) => void;
    private stateValue: SourceNativeAttachmentState = "BOOTSTRAP";
    private currentRevisionValue: DocumentRevision | null = null;
    private pendingMutation: PendingMutation | null = null;
    private recoveryIdValue: RecoveryId | null = null;
    private recoverySequence = 0n;

    constructor(options: SourceNativeAttachmentOptions) {
        this.attachmentId = parseAttachmentId(options.attachmentId);
        this.onSend = options.onSend;
        this.nextRequestId = options.nextRequestId ?? (() => crypto.randomUUID());
        this.onStateTransition = options.onStateTransition;
        this.editor = new SourceNativeEditorCore({
            parent: options.parent,
            initialSource: "",
            onBeforeLocalChange: (change) => this.beforeLocalChange(change),
            onLocalChange: (change) => this.afterLocalChange(change)
        });
    }

    get state(): SourceNativeAttachmentState {
        return this.stateValue;
    }

    get currentRevision(): DocumentRevision | null {
        return this.currentRevisionValue;
    }

    get pendingRequestId(): RequestId | null {
        return this.pendingMutation?.request.requestId ?? null;
    }

    get recoveryId(): RecoveryId | null {
        return this.recoveryIdValue;
    }

    /** Install only the snapshot allowed by the current lifecycle state. */
    receiveSnapshot(message: BootstrapSnapshotMessage | RecoverySnapshotMessage): boolean {
        if (message.attachmentId !== this.attachmentId || this.state === "DISPOSED") {
            return false;
        }
        if (message.type === "bootstrapSnapshot" && this.state !== "BOOTSTRAP") {
            return false;
        }
        if (message.type === "recoverySnapshot") {
            if (this.state !== "RECOVERING" || message.recoveryId !== this.recoveryIdValue) {
                return false;
            }
        }

        this.editor.applyHostSource(message.source);
        this.currentRevisionValue = message.documentRevision;
        this.pendingMutation = null;
        this.recoveryIdValue = null;
        this.transition("READY", message.type);
        return true;
    }

    /** Decode and process one inbound message. Decode failure is recovery, never success. */
    receiveRaw(raw: string): boolean {
        try {
            return this.receive(decodeAttachmentMessage(raw));
        } catch (_error) {
            this.enterRecovery("malformed-or-unknown-message");
            return false;
        }
    }

    receive(message: AttachmentWireMessage): boolean {
        if (this.state === "DISPOSED") {
            return false;
        }
        if (message.type === "bootstrapSnapshot" || message.type === "recoverySnapshot") {
            return this.receiveSnapshot(message);
        }
        if (message.type === "snapshotRequest") {
            return false;
        }
        if (message.attachmentId !== this.attachmentId) {
            return false;
        }

        switch (message.type) {
            case "mutationAccepted":
                return this.receiveAccepted(message, false);
            case "mutationAcceptedUnchanged":
                return this.receiveAccepted(message, true);
            case "mutationRejected":
                return this.receiveRejected(message);
            case "hostIncrementalUpdate":
                return this.receiveHostUpdate(message);
        }
        return false;
    }

    dispose(): void {
        if (this.state === "DISPOSED") {
            return;
        }
        this.pendingMutation = null;
        this.transition("DISPOSED", "dispose");
        this.editor.dispose();
    }

    /**
     * Correlate an asynchronous transport failure with the exact live operation. A stale
     * callback is inert; a mutation failure opens a correlated recovery operation, while a
     * recovery-operation failure terminalizes the attachment without fabricating success.
     */
    receiveTransportFailure(failure: AttachmentTransportFailure): boolean {
        if (this.state === "DISPOSED" || failure.attachmentId !== this.attachmentId) {
            return false;
        }
        if (failure.type === "mutation") {
            if (this.state !== "AWAITING_ACK"
                || this.pendingMutation === null
                || failure.requestId !== this.pendingMutation.request.requestId) {
                return false;
            }
            this.enterRecovery("transport-failure");
            return true;
        }
        if (failure.type !== "recovery"
            || this.state !== "RECOVERING"
            || failure.recoveryId !== this.recoveryIdValue) {
            return false;
        }
        this.terminalize("recovery-transport-failure");
        return true;
    }

    private beforeLocalChange(change: SourceChangeTransaction): boolean {
        if (this.state !== "READY" || this.currentRevisionValue === null || this.pendingMutation !== null) {
            return false;
        }

        let requestId: RequestId;
        try {
            requestId = parseRequestId(this.nextRequestId());
        } catch (_error) {
            return false;
        }
        const effectiveEditCount = this.editor.effectiveSourceChangeCount(change);
        if (effectiveEditCount === 0) {
            return false;
        }
        let expectedFinalRevision: DocumentRevision;
        try {
            expectedFinalRevision = advanceDocumentRevision(this.currentRevisionValue, effectiveEditCount);
        } catch (_error) {
            return false;
        }
        const request: MutationRequestMessage = Object.freeze({
            type: "mutationRequest",
            attachmentId: this.attachmentId,
            requestId,
            baseDocumentRevision: this.currentRevisionValue,
            edits: Object.freeze(change.changes.map((item) => Object.freeze({
                from: item.from,
                to: item.to,
                inserted: item.inserted
            })))
        });
        this.pendingMutation = {request, expectedFinalRevision, effectiveEditCount};
        this.transition("AWAITING_ACK", "local-source-transaction-accepted");
        return true;
    }

    private afterLocalChange(change: SourceChangeTransaction): void {
        const pending = this.pendingMutation;
        if (this.state !== "AWAITING_ACK" || pending === null || !sameChanges(pending.request.edits, change)) {
            this.enterRecovery("local-transaction-provenance-mismatch");
            return;
        }
        try {
            this.onSend(pending.request);
        } catch (_error) {
            this.enterRecovery("transport-uncertainty");
        }
    }

    private receiveAccepted(
        message: MutationAcceptedMessage | MutationAcceptedUnchangedMessage,
        unchanged: boolean,
    ): boolean {
        const pending = this.pendingMutation;
        if (this.state !== "AWAITING_ACK" || pending === null) {
            return false;
        }
        if (message.requestId !== pending.request.requestId) {
            this.enterRecovery("ack-correlation-mismatch");
            return false;
        }
        const relation = compareDocumentRevisions(message.finalDocumentRevision, pending.request.baseDocumentRevision);
        if (unchanged && (relation !== 0 || pending.effectiveEditCount !== 0)) {
            this.enterRecovery("ack-revision-invariant-failure");
            return false;
        }
        if (!unchanged && message.finalDocumentRevision !== pending.expectedFinalRevision) {
            this.enterRecovery("ack-revision-invariant-failure");
            return false;
        }

        this.pendingMutation = null;
        this.currentRevisionValue = message.finalDocumentRevision;
        this.transition("READY", unchanged ? "accepted-unchanged" : "accepted");
        return true;
    }

    private receiveRejected(message: MutationRejectedMessage): boolean {
        const pending = this.pendingMutation;
        if (this.state !== "AWAITING_ACK" || pending === null) {
            return false;
        }
        if (message.requestId !== pending.request.requestId) {
            this.enterRecovery("rejection-correlation-mismatch");
            return false;
        }

        this.pendingMutation = null;
        if (message.category === "WRONG_ATTACHMENT" || message.category === "DISPOSED") {
            this.terminalize(`rejected-${message.category}`);
            return true;
        }
        this.enterRecovery(`rejected-${message.category}`);
        return true;
    }

    private receiveHostUpdate(message: HostIncrementalUpdateMessage): boolean {
        if (this.state === "AWAITING_ACK") {
            this.enterRecovery("authoritative-update-during-local-proposal");
            return false;
        }
        if (this.state !== "READY" || this.currentRevisionValue === null) {
            return false;
        }
        let expectedRevision: DocumentRevision;
        try {
            expectedRevision = nextDocumentRevision(this.currentRevisionValue);
        } catch (_error) {
            this.enterRecovery("revision-overflow");
            return false;
        }
        if (message.documentRevision !== expectedRevision || !this.editor.applyHostEdit(message.edit)) {
            this.enterRecovery("non-contiguous-or-invalid-host-update");
            return false;
        }
        this.currentRevisionValue = message.documentRevision;
        return true;
    }

    private enterRecovery(reason: string): void {
        if (this.state === "DISPOSED" || this.state === "RECOVERING") {
            return;
        }
        this.pendingMutation = null;
        const recoveryId = this.nextRecoveryOperationId();
        this.recoveryIdValue = recoveryId;
        this.transition("RECOVERING", reason);
        try {
            this.onSend({type: "snapshotRequest", attachmentId: this.attachmentId, recoveryId});
        } catch (_error) {
            // Recovery remains fail-closed if the transport cannot carry the request.
        }
    }

    private terminalize(reason: string): void {
        if (this.state === "DISPOSED") {
            return;
        }
        this.pendingMutation = null;
        this.recoveryIdValue = null;
        this.transition("DISPOSED", reason);
        this.editor.dispose();
    }

    private nextRecoveryOperationId(): RecoveryId {
        this.recoverySequence += 1n;
        return parseRecoveryId(`recovery-${this.recoverySequence.toString()}`);
    }

    private transition(to: SourceNativeAttachmentState, reason: string): void {
        const from = this.stateValue;
        if (from === to) {
            return;
        }
        this.stateValue = to;
        this.onStateTransition?.({from, to, reason});
    }
}

function parseIdentity(value: unknown, maxLength: number): string {
    if (typeof value !== "string"
        || value.trim().length === 0
        || value.length > maxLength
        || /[\u0000-\u001F\u007F-\u009F]/.test(value)) {
        throw new AttachmentWireDecodeError();
    }
    return value;
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function requireKeys(value: Record<string, unknown>, expected: readonly string[]): void {
    const actual = Object.keys(value).sort();
    const sortedExpected = [...expected].sort();
    if (actual.length !== sortedExpected.length || actual.some((key, index) => key !== sortedExpected[index])) {
        throw new AttachmentWireDecodeError();
    }
}

function requiredString(value: Record<string, unknown>, name: string): string {
    const candidate = value[name];
    if (typeof candidate !== "string") {
        throw new AttachmentWireDecodeError();
    }
    return candidate;
}

function parseEdit(value: unknown): WireSourceEdit {
    if (!isRecord(value)) {
        throw new AttachmentWireDecodeError();
    }
    requireKeys(value, ["from", "to", "inserted"]);
    const from = value.from;
    const to = value.to;
    const inserted = value.inserted;
    if (typeof from !== "number"
        || typeof to !== "number"
        || !Number.isSafeInteger(from)
        || !Number.isSafeInteger(to)
        || from < -2147483648
        || from > 2147483647
        || to < -2147483648
        || to > 2147483647
        || typeof inserted !== "string") {
        throw new AttachmentWireDecodeError();
    }
    return {from, to, inserted};
}

function parseEdits(value: unknown): readonly WireSourceEdit[] {
    if (!Array.isArray(value)
        || value.length === 0
        || value.length > MAX_SOURCE_EDIT_COUNT) {
        throw new AttachmentWireDecodeError();
    }
    const edits = value.map(parseEdit);
    const insertedLength = edits.reduce((total, edit) => total + edit.inserted.length, 0);
    if (insertedLength > MAX_INSERTED_UTF16_CODE_UNITS) {
        throw new AttachmentWireDecodeError();
    }
    return edits;
}

function parseMessage(value: unknown): AttachmentWireMessage {
    if (!isRecord(value) || typeof value.type !== "string") {
        throw new AttachmentWireDecodeError();
    }
    switch (value.type) {
        case "bootstrapSnapshot":
            requireKeys(value, ["type", "attachmentId", "documentRevision", "source"]);
            return {
                type: value.type,
                attachmentId: parseAttachmentId(value.attachmentId),
                documentRevision: parseDocumentRevision(value.documentRevision),
                source: requiredString(value, "source")
            };
        case "recoverySnapshot":
            requireKeys(value, ["type", "attachmentId", "recoveryId", "documentRevision", "source"]);
            return {
                type: value.type,
                attachmentId: parseAttachmentId(value.attachmentId),
                recoveryId: parseRecoveryId(value.recoveryId),
                documentRevision: parseDocumentRevision(value.documentRevision),
                source: requiredString(value, "source")
            };
        case "snapshotRequest":
            requireKeys(value, ["type", "attachmentId", "recoveryId"]);
            return {
                type: value.type,
                attachmentId: parseAttachmentId(value.attachmentId),
                recoveryId: parseRecoveryId(value.recoveryId)
            };
        case "mutationRequest":
            requireKeys(value, ["type", "attachmentId", "requestId", "baseDocumentRevision", "edits"]);
            return {
                type: value.type,
                attachmentId: parseAttachmentId(value.attachmentId),
                requestId: parseRequestId(value.requestId),
                baseDocumentRevision: parseDocumentRevision(value.baseDocumentRevision),
                edits: parseEdits(value.edits)
            };
        case "mutationAccepted":
            requireKeys(value, ["type", "attachmentId", "requestId", "finalDocumentRevision"]);
            return {
                type: value.type,
                attachmentId: parseAttachmentId(value.attachmentId),
                requestId: parseRequestId(value.requestId),
                finalDocumentRevision: parseDocumentRevision(value.finalDocumentRevision)
            };
        case "mutationAcceptedUnchanged":
            requireKeys(value, ["type", "attachmentId", "requestId", "finalDocumentRevision"]);
            return {
                type: value.type,
                attachmentId: parseAttachmentId(value.attachmentId),
                requestId: parseRequestId(value.requestId),
                finalDocumentRevision: parseDocumentRevision(value.finalDocumentRevision)
            };
        case "mutationRejected":
            requireKeys(value, ["type", "attachmentId", "requestId", "category"]);
            if (!isRejectionCategory(value.category)) {
                throw new AttachmentWireDecodeError();
            }
            return {
                type: value.type,
                attachmentId: parseAttachmentId(value.attachmentId),
                requestId: parseRequestId(value.requestId),
                category: value.category
            };
        case "hostIncrementalUpdate":
            requireKeys(value, ["type", "attachmentId", "documentRevision", "edit"]);
            return {
                type: value.type,
                attachmentId: parseAttachmentId(value.attachmentId),
                documentRevision: parseDocumentRevision(value.documentRevision),
                edit: parseEdit(value.edit)
            };
        default:
            throw new AttachmentWireDecodeError();
    }
}

function isRejectionCategory(value: unknown): value is MutationRejectionCategory {
    return typeof value === "string" && [
        "STALE_DOCUMENT_REVISION",
        "DUPLICATE_REQUEST",
        "WRONG_ATTACHMENT",
        "DISPOSED",
        "INVALID_MUTATION",
        "INVALID_TRANSACTION",
        "CONFLICT",
        "UNSUPPORTED_FIDELITY",
        "INTERNAL_FAILURE"
    ].includes(value);
}

function sameChanges(left: readonly WireSourceEdit[], right: SourceChangeTransaction): boolean {
    return left.length === right.changes.length && left.every((change, index) => {
        const candidate: SourceChange | undefined = right.changes[index];
        return candidate !== undefined
            && change.from === candidate.from
            && change.to === candidate.to
            && change.inserted === candidate.inserted;
    });
}
