import type {EditorUiState, MarkdownSourceSnapshot, MarkdownUpdateAck, MarkFlowRuntimeSettings} from "./types";

type EditorBridgeCallbacks = {
    onSettings: (settings: MarkFlowRuntimeSettings | undefined) => void;
    onEditorActive: (active: boolean) => void;
    onIntelliJMarkdownUpdate: (snapshot: MarkdownSourceSnapshot) => void;
    onIntelliJEditorState: (state: EditorUiState) => void;
    emitToIntelliJLog: (message: string) => void;
    onFlushNow: () => void;
};

export type EditorBridge = {
    install: () => void;
    sendToIntelliJ: (
        rawMarkdown: string,
        sourceRevision: number,
        uiState: EditorUiState,
        onSuccess?: (ack: MarkdownUpdateAck) => void
    ) => void;
};

const sanitizeUiState = (uiState: EditorUiState): EditorUiState => {
    return {
        version: Number.isFinite(uiState.version) ? uiState.version : 1,
        scrollTop: Number.isFinite(uiState.scrollTop) ? uiState.scrollTop : 0,
        cursorOffset: Number.isFinite(uiState.cursorOffset) ? uiState.cursorOffset : -1,
        selectionStart: Number.isFinite(uiState.selectionStart) ? uiState.selectionStart : -1,
        selectionEnd: Number.isFinite(uiState.selectionEnd) ? uiState.selectionEnd : -1
    };
};

export const createEditorBridge = (callbacks: EditorBridgeCallbacks): EditorBridge => {
    const sendToIntelliJ = (
        rawMarkdown: string,
        sourceRevision: number,
        uiState: EditorUiState,
        onSuccess?: (ack: MarkdownUpdateAck) => void
    ) => {
        if (!window.cefQuery) {
            callbacks.emitToIntelliJLog("MARKFLOW_SAVE sendToIntelliJ:BLOCKED cefQuery missing");
            return;
        }

        const safeState = sanitizeUiState(uiState);
        const sessionId = window.__markflowSessionId;
        const request = JSON.stringify({
            action: "update",
            sessionId,
            rawMarkdown,
            sourceRevision,
            version: safeState.version,
            scrollTop: safeState.scrollTop,
            cursorOffset: safeState.cursorOffset,
            selectionStart: safeState.selectionStart,
            selectionEnd: safeState.selectionEnd
        });

        if (!request || request === "undefined") {
            callbacks.emitToIntelliJLog("MARKFLOW_SAVE sendToIntelliJ:BLOCKED request invalid");
            return;
        }

        window.cefQuery({
            request,
            onSuccess: (response) => {
                if (!onSuccess) {
                    return;
                }

                if (!response) {
                    onSuccess({ok: true, sourceRevision});
                    return;
                }

                try {
                    onSuccess(JSON.parse(response) as MarkdownUpdateAck);
                } catch (_error) {
                    onSuccess({ok: true, sourceRevision});
                }
            },
            onFailure: (_errCode, errMsg) => {
                callbacks.emitToIntelliJLog(`MARKFLOW_SAVE sendToIntelliJ:FAIL ${errMsg}`);
            }
        });
    };

    const install = () => {
        window.applyMarkFlowSettingsFromIntelliJ = (settings: MarkFlowRuntimeSettings) => {
            callbacks.emitToIntelliJLog(`MARKFLOW_UI bridge:settingsReceived ${JSON.stringify(settings)}`);
            callbacks.onSettings(settings);
        };

        window.setMarkFlowEditorActive = (active: boolean) => {
            callbacks.onEditorActive(active);
        };

        window.sendToIntelliJ = (rawMarkdown: string, sourceRevision: number, uiState: EditorUiState) => {
            sendToIntelliJ(rawMarkdown, sourceRevision, uiState);
        };

        window.updateFromIntelliJ = (snapshot: MarkdownSourceSnapshot | string) => {
            if (typeof snapshot === "string") {
                callbacks.onIntelliJMarkdownUpdate({
                    rawMarkdown: snapshot,
                    sourceRevision: Number(window.__markflowSourceRevision ?? window.intelliJ_sourceRevision ?? 1),
                    leaseSessionId: String(window.__markflowSessionId ?? "")
                });
                return;
            }
            callbacks.onIntelliJMarkdownUpdate(snapshot);
        };

        window.applyEditorStateFromIntelliJ = (state: EditorUiState) => {
            callbacks.onIntelliJEditorState(state);
        };

        window.markflowFlushNow = () => {
            callbacks.onFlushNow();
        };
    };

    return {
        install,
        sendToIntelliJ
    };
};
