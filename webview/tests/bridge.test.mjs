import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import * as ts from "typescript";
import {test} from "node:test";

const sourcePath = new URL("../src/app/bridge.ts", import.meta.url);
const sourceText = await readFile(sourcePath, "utf8");
const transpiled = ts.transpileModule(sourceText, {
    compilerOptions: {
        target: ts.ScriptTarget.ES2023,
        module: ts.ModuleKind.ESNext
    }
}).outputText;

const moduleUrl = `data:text/javascript;charset=utf-8,${encodeURIComponent(transpiled)}`;
const {createEditorBridge} = await import(moduleUrl);

const createCallbacks = () => {
    const logs = [];
    const markdownUpdates = [];
    const editorStates = [];
    const settings = [];
    const activeStates = [];
    const flushes = [];

    return {
        logs,
        markdownUpdates,
        editorStates,
        settings,
        activeStates,
        flushes,
        callbacks: {
            onSettings: (value) => settings.push(value),
            onEditorActive: (value) => activeStates.push(value),
            onIntelliJMarkdownUpdate: (value) => markdownUpdates.push(value),
            onIntelliJEditorState: (value) => editorStates.push(value),
            emitToIntelliJLog: (message) => logs.push(message),
            onFlushNow: () => flushes.push(true)
        }
    };
};

test("blocks sendToIntelliJ when cefQuery is missing", () => {
    const {callbacks, logs, markdownUpdates} = createCallbacks();
    globalThis.window = {
        __markflowSessionId: "lease-1-session-1",
        __markflowSourceRevision: 1,
        intelliJ_sourceRevision: 1
    };

    const bridge = createEditorBridge(callbacks);
    bridge.sendToIntelliJ("raw", 2, {
        version: Number.NaN,
        scrollTop: Number.POSITIVE_INFINITY,
        cursorOffset: 11,
        selectionStart: 3,
        selectionEnd: 9
    });

    assert.equal(markdownUpdates.length, 0);
    assert.ok(logs.some((message) => message.includes("BLOCKED cefQuery missing")));
});

test("serializes update requests with session, revision, and sanitized ui state", () => {
    const {callbacks, logs} = createCallbacks();
    const requests = [];
    let ack = null;

    globalThis.window = {
        __markflowSessionId: "lease-2-session-7",
        __markflowSourceRevision: 12,
        intelliJ_sourceRevision: 12,
        cefQuery: ({request, onSuccess}) => {
            requests.push(JSON.parse(request));
            onSuccess("{\"ok\":false,\"sourceRevision\":18,\"reason\":\"stale\"}");
        }
    };

    const bridge = createEditorBridge(callbacks);
    bridge.sendToIntelliJ(
        "raw markdown",
        15,
        {
            version: Number.NaN,
            scrollTop: Number.POSITIVE_INFINITY,
            cursorOffset: -4,
            selectionStart: 8,
            selectionEnd: 12
        },
        (value) => {
            ack = value;
        }
    );

    assert.equal(requests.length, 1);
    assert.deepEqual(requests[0], {
        action: "update",
        sessionId: "lease-2-session-7",
        rawMarkdown: "raw markdown",
        sourceRevision: 15,
        version: 1,
        scrollTop: 0,
        cursorOffset: -4,
        selectionStart: 8,
        selectionEnd: 12
    });
    assert.deepEqual(ack, {ok: false, sourceRevision: 18, reason: "stale"});
    assert.equal(logs.length, 0);
});

test("falls back to optimistic ack when the response payload is invalid JSON", () => {
    const {callbacks} = createCallbacks();
    let ack = null;

    globalThis.window = {
        __markflowSessionId: "lease-3-session-8",
        __markflowSourceRevision: 4,
        intelliJ_sourceRevision: 4,
        cefQuery: ({onSuccess}) => {
            onSuccess("not-json");
        }
    };

    const bridge = createEditorBridge(callbacks);
    bridge.sendToIntelliJ("body", 5, {
        version: 1,
        scrollTop: 0,
        cursorOffset: 2,
        selectionStart: -1,
        selectionEnd: -1
    }, (value) => {
        ack = value;
    });

    assert.deepEqual(ack, {ok: true, sourceRevision: 5});
});

test("installs host callbacks and preserves lease session ids for markdown updates", () => {
    const {callbacks, markdownUpdates, editorStates, settings, activeStates, flushes} = createCallbacks();

    globalThis.window = {
        __markflowSessionId: "lease-4-session-2",
        __markflowSourceRevision: 9,
        intelliJ_sourceRevision: 9,
        cefQuery: () => {
            throw new Error("cefQuery should not be called in this test");
        }
    };

    const bridge = createEditorBridge(callbacks);
    bridge.install();

    assert.equal(typeof globalThis.window.updateFromIntelliJ, "function");
    assert.equal(typeof globalThis.window.applyMarkFlowSettingsFromIntelliJ, "function");
    assert.equal(typeof globalThis.window.setMarkFlowEditorActive, "function");
    assert.equal(typeof globalThis.window.markflowFlushNow, "function");

    globalThis.window.applyMarkFlowSettingsFromIntelliJ({theme: "dark"});
    globalThis.window.setMarkFlowEditorActive(true);
    globalThis.window.applyEditorStateFromIntelliJ({
        version: 1,
        scrollTop: 24,
        cursorOffset: 8,
        selectionStart: 3,
        selectionEnd: 9
    });
    globalThis.window.markflowFlushNow();

    globalThis.window.updateFromIntelliJ("first raw");
    globalThis.window.updateFromIntelliJ({
        rawMarkdown: "second raw",
        sourceRevision: 11,
        leaseSessionId: "lease-4-session-2"
    });

    assert.deepEqual(settings, [{theme: "dark"}]);
    assert.deepEqual(activeStates, [true]);
    assert.equal(editorStates.length, 1);
    assert.equal(flushes.length, 1);
    assert.equal(markdownUpdates.length, 2);
    assert.deepEqual(markdownUpdates[0], {
        rawMarkdown: "first raw",
        sourceRevision: 9,
        leaseSessionId: "lease-4-session-2"
    });
    assert.deepEqual(markdownUpdates[1], {
        rawMarkdown: "second raw",
        sourceRevision: 11,
        leaseSessionId: "lease-4-session-2"
    });
});
