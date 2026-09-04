import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {resolve} from "node:path";
import {JSDOM} from "jsdom";
import * as ts from "typescript";
import {test} from "node:test";

const repositoryRoot = resolve(import.meta.dirname, "../..");
const corePath = resolve(repositoryRoot, "webview/src/editor/source-native-editor.ts");
const syncPath = resolve(repositoryRoot, "webview/src/sync/source-native-sync.ts");

const dom = new JSDOM("<!doctype html><html><body></body></html>", {pretendToBeVisual: true});
for (const [name, value] of [
    ["window", dom.window],
    ["document", dom.window.document],
    ["navigator", dom.window.navigator],
    ["MutationObserver", dom.window.MutationObserver],
    ["DOMParser", dom.window.DOMParser],
    ["Element", dom.window.Element],
    ["HTMLElement", dom.window.HTMLElement],
    ["Node", dom.window.Node],
    ["Range", dom.window.Range],
    ["getComputedStyle", dom.window.getComputedStyle.bind(dom.window)],
    ["requestAnimationFrame", dom.window.requestAnimationFrame.bind(dom.window)],
    ["cancelAnimationFrame", dom.window.cancelAnimationFrame.bind(dom.window)]
]) {
    Object.defineProperty(globalThis, name, {configurable: true, value});
}
if (globalThis.ResizeObserver === undefined) {
    Object.defineProperty(globalThis, "ResizeObserver", {
        configurable: true,
        value: class {
            observe() {}
            unobserve() {}
            disconnect() {}
        }
    });
}

const packageUrls = new Map([
    ["@codemirror/lang-markdown", await import.meta.resolve("@codemirror/lang-markdown")],
    ["@codemirror/language", await import.meta.resolve("@codemirror/language")],
    ["@codemirror/state", await import.meta.resolve("@codemirror/state")],
    ["@codemirror/view", await import.meta.resolve("@codemirror/view")]
]);

function transpile(source, replacements = new Map()) {
    let output = ts.transpileModule(source, {
        compilerOptions: {
            target: ts.ScriptTarget.ES2023,
            module: ts.ModuleKind.ESNext
        }
    }).outputText;
    for (const [from, to] of replacements) {
        output = output.replaceAll(from, to);
    }
    return output;
}

const coreUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(
    transpile(readFileSync(corePath, "utf8"), packageUrls)
)}`;
const syncUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(
    transpile(readFileSync(syncPath, "utf8"), new Map([["../editor/source-native-editor.ts", coreUrl]]))
)}`;
const sync = await import(syncUrl);

function boot(attachment, source = "initial", revision = "0") {
    assert.equal(attachment.receive({
        type: "bootstrapSnapshot",
        attachmentId: attachment.attachmentId,
        documentRevision: sync.parseDocumentRevision(revision),
        source
    }), true);
}

function withAttachment(callback, {attachmentId = "attachment-a", source = "initial", revision = "0"} = {}) {
    const parent = document.createElement("div");
    document.body.append(parent);
    const sent = [];
    const transitions = [];
    const attachment = new sync.SourceNativeAttachment({
        parent,
        attachmentId,
        onSend: (message) => sent.push(message),
        nextRequestId: (() => {
            let counter = 0;
            return () => `request-${++counter}`;
        })(),
        onStateTransition: (transition) => transitions.push(transition)
    });
    boot(attachment, source, revision);
    try {
        return callback({attachment, sent, transitions});
    } finally {
        attachment.dispose();
        parent.remove();
    }
}

test("document revisions remain canonical decimal strings above JS safe integer and at Long max", () => {
    const high = sync.parseDocumentRevision("9007199254740993");
    const maximum = sync.parseDocumentRevision("9223372036854775807");
    assert.equal(high, "9007199254740993");
    assert.equal(sync.nextDocumentRevision(high), "9007199254740994");
    assert.equal(maximum, "9223372036854775807");
    for (const invalid of ["", " ", "+1", "-1", "01", "1.0", "1e3", "abc", "9223372036854775808", 1, null]) {
        assert.throws(() => sync.parseDocumentRevision(invalid));
    }
});

test("attachment and request identities reject invalid or oversized values", () => {
    for (const invalid of ["", "  ", "attachment\n", "x".repeat(129), null, 1]) {
        assert.throws(() => sync.parseAttachmentId(invalid));
        assert.throws(() => sync.parseRequestId(invalid));
        assert.throws(() => sync.parseRecoveryId(invalid));
    }
});

test("wire decoder is strict and normal ACK/update shapes contain no source", () => {
    const ack = JSON.stringify({
        type: "mutationAccepted",
        attachmentId: "attachment-a",
        requestId: "request-1",
        finalDocumentRevision: "9007199254740993"
    });
    const update = JSON.stringify({
        type: "hostIncrementalUpdate",
        attachmentId: "attachment-a",
        documentRevision: "9007199254740994",
        edit: {from: 1, to: 1, inserted: "x"}
    });
    const snapshotRequest = JSON.stringify({
        type: "snapshotRequest",
        attachmentId: "attachment-a",
        recoveryId: "recovery-1"
    });
    assert.equal(sync.decodeAttachmentMessage(ack).finalDocumentRevision, "9007199254740993");
    assert.equal(sync.decodeAttachmentMessage(update).edit.inserted, "x");
    assert.equal(sync.decodeAttachmentMessage(snapshotRequest).recoveryId, "recovery-1");
    assert.equal(ack.includes("source"), false);
    assert.equal(update.includes("source"), false);
    for (const raw of [
        "",
        "not-json",
        JSON.stringify({type: "unknown"}),
        ack.replace("}", ',"source":"must-reject"}'),
        ack.replace('"9007199254740993"', "9007199254740993"),
        JSON.stringify({type: "snapshotRequest", attachmentId: "attachment-a"})
    ]) {
        assert.throws(() => sync.decodeAttachmentMessage(raw));
    }
});

test("one local multi-edit transaction creates one request with exact UTF-16 edits and gates the next source edit", () => {
    withAttachment(({attachment, sent, transitions}) => {
        attachment.editor.view.dispatch({
            changes: [
                {from: 1, to: 3, insert: "AB"},
                {from: 7, to: 9, insert: "XY"}
            ],
            userEvent: "input.type"
        });

        assert.equal(attachment.editor.source, "0AB3456XY9");
        assert.equal(attachment.state, "AWAITING_ACK");
        assert.equal(sent.length, 1);
        assert.equal(sent[0].type, "mutationRequest");
        assert.equal(sent[0].edits.length, 2);
        assert.equal(sent[0].baseDocumentRevision, "0");
        assert.equal(sent[0].requestId, "request-1");

        attachment.editor.view.dispatch({changes: {from: 0, insert: "blocked"}, userEvent: "input.type"});
        assert.equal(attachment.editor.source, "0AB3456XY9");
        assert.equal(sent.length, 1);

        attachment.editor.view.dispatch({selection: {anchor: 2}});
        assert.equal(attachment.editor.view.state.selection.main.anchor, 2);
        assert.deepEqual(transitions.map(({from, to}) => [from, to]), [
            ["BOOTSTRAP", "READY"],
            ["READY", "AWAITING_ACK"]
        ]);
    }, {source: "0123456789"});
});

test("surrogate-pair local edit preserves JavaScript UTF-16 coordinates", () => {
    withAttachment(({attachment, sent}) => {
        attachment.editor.view.dispatch({changes: {from: 3, insert: "x"}, userEvent: "input.type"});
        assert.deepEqual(sent[0].edits, [{from: 3, to: 3, inserted: "x"}]);
    }, {source: "A😀BC"});
});

test("exact accepted ACK uses the exact effective event count", () => {
    withAttachment(({attachment, sent}) => {
        attachment.editor.view.dispatch({
            changes: [
                {from: 0, insert: "x"},
                {from: 2, insert: "y"}
            ],
            userEvent: "input.type"
        });
        const request = sent[0];
        assert.equal(request.edits.length, 2);
        assert.equal(attachment.receive({
            type: "mutationAccepted",
            attachmentId: attachment.attachmentId,
            requestId: request.requestId,
            finalDocumentRevision: sync.parseDocumentRevision("2")
        }), true);
        assert.equal(attachment.state, "READY");
        assert.equal(attachment.currentRevision, "2");
    });
});

test("too-small and too-large accepted revisions fail closed", () => {
    for (const finalDocumentRevision of ["0", "1", "3"]) {
        withAttachment(({attachment, sent}) => {
            attachment.editor.view.dispatch({
                changes: [
                    {from: 0, insert: "x"},
                    {from: 2, insert: "y"}
                ],
                userEvent: "input.type"
            });
            const request = sent[0];
            assert.equal(attachment.receive({
                type: "mutationAccepted",
                attachmentId: attachment.attachmentId,
                requestId: request.requestId,
                finalDocumentRevision: sync.parseDocumentRevision(finalDocumentRevision)
            }), false);
            assert.equal(attachment.state, "RECOVERING");
            assert.equal(sent.at(-1).type, "snapshotRequest");
        });
    }
});

test("accepted-unchanged requires base revision and does not accept an optimistic change", () => {
    withAttachment(({attachment, sent}) => {
        attachment.editor.view.dispatch({changes: {from: 0, insert: "x"}, userEvent: "input.type"});
        assert.equal(attachment.receive({
            type: "mutationAcceptedUnchanged",
            attachmentId: attachment.attachmentId,
            requestId: sent[0].requestId,
            finalDocumentRevision: sync.parseDocumentRevision("0")
        }), false);
        assert.equal(attachment.state, "RECOVERING");
        assert.equal(sent.at(-1).type, "snapshotRequest");
    });
});

test("wrong request ACK terminalizes unresolved mutation instead of racing recovery", () => {
    withAttachment(({attachment, sent}) => {
        attachment.editor.view.dispatch({changes: {from: 0, insert: "x"}, userEvent: "input.type"});
        assert.equal(attachment.receive({
            type: "mutationAccepted",
            attachmentId: attachment.attachmentId,
            requestId: sync.parseRequestId("other-request"),
            finalDocumentRevision: sync.parseDocumentRevision("1")
        }), false);
        assert.equal(attachment.state, "DISPOSED");
        assert.equal(attachment.currentRevision, "0");
        assert.equal(sent.length, 1);
    });
});

test("recoverable current-attachment rejection categories request an explicit snapshot", () => {
    for (const category of [
        "STALE_DOCUMENT_REVISION",
        "DUPLICATE_REQUEST",
        "INVALID_MUTATION",
        "INVALID_TRANSACTION",
        "CONFLICT",
        "UNSUPPORTED_FIDELITY",
        "INTERNAL_FAILURE"
    ]) {
        withAttachment(({attachment, sent}) => {
            attachment.editor.view.dispatch({changes: {from: 0, insert: "x"}, userEvent: "input.type"});
            assert.equal(attachment.receive({
                type: "mutationRejected",
                attachmentId: attachment.attachmentId,
                requestId: sent[0].requestId,
                category
            }), true);
            assert.equal(attachment.state, "RECOVERING");
            assert.equal(sent.at(-1).type, "snapshotRequest");
            assert.equal(attachment.receive({
                type: "recoverySnapshot",
                attachmentId: attachment.attachmentId,
                recoveryId: sent.at(-1).recoveryId,
                documentRevision: sync.parseDocumentRevision("4"),
                source: "authoritative recovery"
            }), true);
            assert.equal(attachment.editor.source, "authoritative recovery");
            assert.equal(attachment.currentRevision, "4");
            assert.equal(attachment.state, "READY");
        });
    }
});

test("wrong-attachment and disposed rejection terminalize the attachment without recovery", () => {
    for (const category of ["WRONG_ATTACHMENT", "DISPOSED"]) {
        withAttachment(({attachment, sent}) => {
            attachment.editor.view.dispatch({changes: {from: 0, insert: "x"}, userEvent: "input.type"});
            const request = sent[0];
            assert.equal(attachment.receive({
                type: "mutationRejected",
                attachmentId: attachment.attachmentId,
                requestId: request.requestId,
                category
            }), true);
            assert.equal(attachment.state, "DISPOSED");
            assert.equal(sent.length, 1);
            const source = attachment.editor.source;
            assert.equal(attachment.receive({
                type: "recoverySnapshot",
                attachmentId: attachment.attachmentId,
                recoveryId: sync.parseRecoveryId("late-recovery"),
                documentRevision: sync.parseDocumentRevision("4"),
                source: "must-not-apply"
            }), false);
            attachment.editor.view.dispatch({changes: {from: 0, insert: "must-not-apply"}, userEvent: "input.type"});
            assert.equal(attachment.editor.source, source);
        });
    }
});

test("delayed recovery snapshot from R1 cannot complete recovery R2", () => {
    withAttachment(({attachment, sent}) => {
        attachment.editor.view.dispatch({changes: {from: 0, insert: "x"}, userEvent: "input.type"});
        assert.equal(attachment.receive({
            type: "mutationRejected",
            attachmentId: attachment.attachmentId,
            requestId: sent[0].requestId,
            category: "CONFLICT"
        }), true);
        const recoveryR1 = sent.at(-1);
        assert.equal(recoveryR1.type, "snapshotRequest");
        assert.equal(attachment.receive({
            type: "recoverySnapshot",
            attachmentId: attachment.attachmentId,
            recoveryId: recoveryR1.recoveryId,
            documentRevision: sync.parseDocumentRevision("1"),
            source: "R1 source"
        }), true);

        attachment.editor.view.dispatch({changes: {from: 0, insert: "y"}, userEvent: "input.type"});
        const secondRequest = sent.at(-1);
        assert.equal(secondRequest.type, "mutationRequest");
        assert.equal(attachment.receive({
            type: "mutationRejected",
            attachmentId: attachment.attachmentId,
            requestId: secondRequest.requestId,
            category: "CONFLICT"
        }), true);
        const recoveryR2 = sent.at(-1);
        assert.equal(recoveryR2.type, "snapshotRequest");
        assert.notEqual(recoveryR1.recoveryId, recoveryR2.recoveryId);

        assert.equal(attachment.receive({
            type: "recoverySnapshot",
            attachmentId: attachment.attachmentId,
            recoveryId: recoveryR1.recoveryId,
            documentRevision: sync.parseDocumentRevision("3"),
            source: "late R1 source"
        }), false);
        assert.equal(attachment.state, "RECOVERING");
        assert.equal(attachment.receive({
            type: "recoverySnapshot",
            attachmentId: attachment.attachmentId,
            recoveryId: recoveryR2.recoveryId,
            documentRevision: sync.parseDocumentRevision("4"),
            source: "R2 source"
        }), true);
        assert.equal(attachment.state, "READY");
        assert.equal(attachment.editor.source, "R2 source");
    });
});

test("recovery rejects a snapshot older than its acknowledged revision", () => {
    withAttachment(({attachment, sent}) => {
        attachment.editor.view.dispatch({changes: {from: 0, insert: "x"}, userEvent: "input.type"});
        assert.equal(attachment.receive({
            type: "mutationRejected",
            attachmentId: attachment.attachmentId,
            requestId: sent[0].requestId,
            category: "CONFLICT"
        }), true);
        const recovery = sent.at(-1);
        assert.equal(attachment.currentRevision, "5");
        assert.equal(attachment.receive({
            type: "recoverySnapshot",
            attachmentId: attachment.attachmentId,
            recoveryId: recovery.recoveryId,
            documentRevision: sync.parseDocumentRevision("4"),
            source: "stale snapshot"
        }), false);
        assert.equal(attachment.state, "RECOVERING");
        assert.equal(attachment.currentRevision, "5");
        assert.equal(attachment.editor.source, "xinitial");
        assert.equal(attachment.receive({
            type: "recoverySnapshot",
            attachmentId: attachment.attachmentId,
            recoveryId: recovery.recoveryId,
            documentRevision: sync.parseDocumentRevision("5"),
            source: "current snapshot"
        }), true);
        assert.equal(attachment.state, "READY");
        assert.equal(attachment.currentRevision, "5");
        assert.equal(attachment.editor.source, "current snapshot");
    }, {revision: "5"});
});

test("recovery fences snapshots against an authoritative update observed first", () => {
    withAttachment(({attachment, sent}) => {
        assert.equal(attachment.receiveRaw("not-json"), false);
        const recovery = sent.at(-1);
        assert.equal(attachment.state, "RECOVERING");
        assert.equal(attachment.receive({
            type: "hostIncrementalUpdate",
            attachmentId: attachment.attachmentId,
            documentRevision: sync.parseDocumentRevision("7"),
            edit: {from: 0, to: 0, inserted: "authoritative"}
        }), false);
        assert.equal(attachment.state, "RECOVERING");
        assert.equal(attachment.currentRevision, "5");
        assert.equal(attachment.editor.source, "initial");
        assert.equal(sent.length, 1);
        assert.equal(attachment.receive({
            type: "recoverySnapshot",
            attachmentId: attachment.attachmentId,
            recoveryId: recovery.recoveryId,
            documentRevision: sync.parseDocumentRevision("6"),
            source: "snapshot before observed update"
        }), false);
        assert.equal(attachment.state, "RECOVERING");
        assert.equal(attachment.receive({
            type: "recoverySnapshot",
            attachmentId: attachment.attachmentId,
            recoveryId: recovery.recoveryId,
            documentRevision: sync.parseDocumentRevision("7"),
            source: "snapshot after observed update"
        }), true);
        assert.equal(attachment.state, "READY");
        assert.equal(attachment.currentRevision, "7");
        assert.equal(attachment.editor.source, "snapshot after observed update");
    }, {revision: "5"});
});

test("synchronous recovery snapshot send failure terminalizes instead of hanging in recovery", () => {
    const parent = document.createElement("div");
    document.body.append(parent);
    const attachment = new sync.SourceNativeAttachment({
        parent,
        attachmentId: "attachment-a",
        onSend: (message) => {
            if (message.type === "snapshotRequest") {
                throw new Error("snapshot transport unavailable");
            }
        }
    });
    try {
        assert.equal(attachment.receiveRaw("not-json"), false);
        assert.equal(attachment.state, "DISPOSED");
        assert.equal(attachment.recoveryId, null);
        assert.equal(attachment.receive({
            type: "recoverySnapshot",
            attachmentId: attachment.attachmentId,
            recoveryId: sync.parseRecoveryId("late-recovery"),
            documentRevision: sync.parseDocumentRevision("1"),
            source: "must-not-apply"
        }), false);
    } finally {
        attachment.dispose();
        parent.remove();
    }
});

test("malformed or unknown inbound messages fail closed and do not fabricate success", () => {
    for (const raw of ["", "not-json", JSON.stringify({type: "unknown"})]) {
        withAttachment(({attachment, sent}) => {
            assert.equal(attachment.receiveRaw(raw), false);
            assert.equal(attachment.state, "RECOVERING");
            assert.equal(sent.at(-1).type, "snapshotRequest");
        });
    }
});

test("malformed response while mutation is unresolved terminalizes without snapshot recovery", () => {
    withAttachment(({attachment, sent}) => {
        attachment.editor.view.dispatch({changes: {from: 0, insert: "x"}, userEvent: "input.type"});
        assert.equal(attachment.state, "AWAITING_ACK");
        assert.equal(sent.length, 1);
        assert.equal(attachment.receiveRaw("not-json"), false);
        assert.equal(attachment.state, "DISPOSED");
        assert.equal(sent.length, 1);
    });
});

test("transport uncertainty after an optimistic commit terminalizes without racing a snapshot", () => {
    const parent = document.createElement("div");
    document.body.append(parent);
    const attachment = new sync.SourceNativeAttachment({
        parent,
        attachmentId: "attachment-a",
        onSend: (message) => {
            if (message.type === "mutationRequest") {
                throw new Error("transport unavailable");
            }
        },
        nextRequestId: () => "request-1"
    });
    try {
        boot(attachment, "source");
        attachment.editor.view.dispatch({changes: {from: 0, insert: "local"}, userEvent: "input.type"});
        assert.equal(attachment.editor.source, "localsource");
        assert.equal(attachment.state, "DISPOSED");
        assert.equal(attachment.pendingRequestId, null);
        assert.equal(attachment.recoveryId, null);
    } finally {
        attachment.dispose();
        parent.remove();
    }
});

test("asynchronous transport failures require the current attachment and operation", () => {
    withAttachment(({attachment, sent}) => {
        attachment.editor.view.dispatch({changes: {from: 0, insert: "x"}, userEvent: "input.type"});
        const request = sent[0];
        assert.equal(attachment.receiveTransportFailure({
            type: "mutation",
            attachmentId: attachment.attachmentId,
            requestId: sync.parseRequestId("stale-request")
        }), false);
        assert.equal(attachment.state, "AWAITING_ACK");
        assert.equal(attachment.receiveTransportFailure({
            type: "mutation",
            attachmentId: attachment.attachmentId,
            requestId: request.requestId
        }), true);
        assert.equal(attachment.state, "DISPOSED");
        assert.equal(sent.length, 1);
    });

    withAttachment(({attachment, sent}) => {
        assert.equal(attachment.receiveRaw("not-json"), false);
        const recovery = sent.at(-1);
        assert.equal(attachment.receiveTransportFailure({
            type: "recovery",
            attachmentId: attachment.attachmentId,
            recoveryId: recovery.recoveryId
        }), true);
        assert.equal(attachment.state, "DISPOSED");
        assert.equal(attachment.receive({
            type: "recoverySnapshot",
            attachmentId: attachment.attachmentId,
            recoveryId: recovery.recoveryId,
            documentRevision: sync.parseDocumentRevision("1"),
            source: "must-not-apply"
        }), false);
    });
});

test("contiguous host edit applies exactly, emits no local proposal, and advances one revision", () => {
    withAttachment(({attachment, sent}) => {
        assert.equal(attachment.receive({
            type: "hostIncrementalUpdate",
            attachmentId: attachment.attachmentId,
            documentRevision: sync.parseDocumentRevision("1"),
            edit: {from: 1, to: 3, inserted: "b"}
        }), true);
        assert.equal(attachment.editor.source, "abc");
        assert.equal(attachment.currentRevision, "1");
        assert.equal(sent.length, 0);
    }, {source: "a😀c"});
});

test("stale, duplicate, gap, out-of-order, and invalid host updates recover without speculative apply", () => {
    const invalidUpdates = [
        {documentRevision: "0", edit: {from: 0, to: 0, inserted: "x"}},
        {documentRevision: "2", edit: {from: 0, to: 0, inserted: "x"}},
        {documentRevision: "1", edit: {from: 100, to: 101, inserted: "x"}}
    ];
    for (const invalid of invalidUpdates) {
        withAttachment(({attachment, sent}) => {
            const before = attachment.editor.source;
            assert.equal(attachment.receive({
                type: "hostIncrementalUpdate",
                attachmentId: attachment.attachmentId,
                documentRevision: sync.parseDocumentRevision(invalid.documentRevision),
                edit: invalid.edit
            }), false);
            assert.equal(attachment.state, "RECOVERING");
            assert.equal(attachment.editor.source, before);
            assert.equal(sent.at(-1).type, "snapshotRequest");
        });
    }
});

test("host update during AWAITING_ACK terminalizes instead of racing speculative recovery", () => {
    withAttachment(({attachment, sent}) => {
        attachment.editor.view.dispatch({changes: {from: 0, insert: "local"}, userEvent: "input.type"});
        const beforeHostUpdate = attachment.editor.source;
        assert.equal(attachment.receive({
            type: "hostIncrementalUpdate",
            attachmentId: attachment.attachmentId,
            documentRevision: sync.parseDocumentRevision("1"),
            edit: {from: 0, to: 0, inserted: "host"}
        }), false);
        assert.equal(attachment.state, "DISPOSED");
        assert.equal(attachment.editor.source, beforeHostUpdate);
        assert.equal(sent.length, 1);
    });
});

test("old or disposed attachment callbacks cannot mutate a replacement attachment", () => {
    const oldParent = document.createElement("div");
    const newParent = document.createElement("div");
    document.body.append(oldParent, newParent);
    const oldSent = [];
    const newSent = [];
    const oldAttachment = new sync.SourceNativeAttachment({
        parent: oldParent,
        attachmentId: "old",
        onSend: (message) => oldSent.push(message),
        nextRequestId: () => "old-request"
    });
    const replacement = new sync.SourceNativeAttachment({
        parent: newParent,
        attachmentId: "replacement",
        onSend: (message) => newSent.push(message),
        nextRequestId: () => "new-request"
    });
    try {
        boot(oldAttachment, "old source");
        boot(replacement, "replacement source");
        oldAttachment.dispose();
        assert.equal(oldAttachment.receive({
            type: "recoverySnapshot",
            attachmentId: oldAttachment.attachmentId,
            recoveryId: sync.parseRecoveryId("stale-recovery"),
            documentRevision: sync.parseDocumentRevision("2"),
            source: "stale source"
        }), false);
        assert.equal(replacement.receive({
            type: "hostIncrementalUpdate",
            attachmentId: oldAttachment.attachmentId,
            documentRevision: sync.parseDocumentRevision("1"),
            edit: {from: 0, to: 0, inserted: "stale"}
        }), false);
        assert.equal(replacement.editor.source, "replacement source");
        assert.equal(newSent.length, 0);
        assert.equal(oldSent.length, 0);
    } finally {
        oldAttachment.dispose();
        replacement.dispose();
        oldParent.remove();
        newParent.remove();
    }
});

test("oversized local transactions are blocked before projection mutation", () => {
    withAttachment(({attachment, sent}) => {
        const source = "x".repeat(2050);
        attachment.editor.applyHostSource(source);
        const before = attachment.editor.source;
        attachment.editor.view.dispatch({
            changes: Array.from({length: sync.MAX_SOURCE_EDIT_COUNT + 1}, (_, index) => ({
                from: index * 2,
                to: index * 2,
                insert: "y"
            })),
            userEvent: "input.type"
        });
        assert.equal(attachment.editor.source, before);
        assert.equal(attachment.state, "READY");
        assert.equal(sent.length, 0);
    });

    withAttachment(({attachment, sent}) => {
        const before = attachment.editor.source;
        attachment.editor.view.dispatch({
            changes: {from: 0, to: 0, insert: "x".repeat(sync.MAX_INSERTED_UTF16_CODE_UNITS + 1)},
            userEvent: "input.type"
        });
        assert.equal(attachment.editor.source, before);
        assert.equal(attachment.state, "READY");
        assert.equal(sent.length, 0);
    });
});

test("disposal makes later source and callback work inert", () => {
    const parent = document.createElement("div");
    document.body.append(parent);
    const sent = [];
    const attachment = new sync.SourceNativeAttachment({
        parent,
        attachmentId: "attachment-a",
        onSend: (message) => sent.push(message),
        nextRequestId: () => "request-1"
    });
    boot(attachment, "source");
    attachment.dispose();
    attachment.dispose();
    assert.equal(attachment.receive({
        type: "recoverySnapshot",
        attachmentId: attachment.attachmentId,
        recoveryId: sync.parseRecoveryId("late-recovery"),
        documentRevision: sync.parseDocumentRevision("1"),
        source: "must-not-apply"
    }), false);
    attachment.editor.view.dispatch({changes: {from: 0, insert: "must-not-apply"}, userEvent: "input.type"});
    assert.equal(attachment.editor.source, "source");
    assert.equal(sent.length, 0);
    parent.remove();
});
