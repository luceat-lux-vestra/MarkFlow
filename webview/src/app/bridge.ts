import type {EditorUiState, MarkFlowRuntimeSettings} from "./types";

type EditorBridgeCallbacks = {
    onSettings: (settings: MarkFlowRuntimeSettings | undefined) => void;
    onEditorActive: (active: boolean) => void;
    onIntelliJMarkdownUpdate: (markdown: string) => void;
    onIntelliJEditorState: (state: EditorUiState) => void;
    getMarkdown: () => string;
    emitToIntelliJLog: (message: string) => void;
};

export type EditorBridge = {
    install: () => void;
    sendToIntelliJ: (markdownText: string, uiState: EditorUiState) => void;
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
    const sendToIntelliJ = (markdownText: string, uiState: EditorUiState) => {
        console.info(`MARKFLOW_UI SAVE:ENTRY cefQuery=${typeof window.cefQuery} len=${markdownText.length}`);
        if (!window.cefQuery) {
            console.info("MARKFLOW_UI SAVE:BLOCKED cefQuery missing");
            callbacks.emitToIntelliJLog("MARKFLOW_SAVE sendToIntelliJ:BLOCKED cefQuery missing");
            return;
        }

        const safeState = sanitizeUiState(uiState);
        const sessionId = window.__markflowSessionId;
        const request = JSON.stringify({
            action: "update",
            sessionId,
            content: markdownText,
            version: safeState.version,
            scrollTop: safeState.scrollTop,
            cursorOffset: safeState.cursorOffset,
            selectionStart: safeState.selectionStart,
            selectionEnd: safeState.selectionEnd
        });

        if (!request || request === "undefined") {
            console.info("MARKFLOW_UI SAVE:BLOCKED request invalid");
            callbacks.emitToIntelliJLog("MARKFLOW_SAVE sendToIntelliJ:BLOCKED request invalid");
            return;
        }

        console.info(`MARKFLOW_UI SAVE:CEF_QUERY sessionId=${sessionId} contentLen=${markdownText.length}`);
        callbacks.emitToIntelliJLog(`MARKFLOW_SAVE sendToIntelliJ:CEF_QUERY sessionId=${sessionId} contentLen=${markdownText.length}`);
        window.cefQuery({
            request,
            onSuccess: () => {
                console.info("MARKFLOW_UI SAVE:ACK received");
                callbacks.emitToIntelliJLog("MARKFLOW_SAVE sendToIntelliJ:ACK received");
            },
            onFailure: (_errCode, errMsg) => {
                console.info(`MARKFLOW_UI SAVE:FAIL ${errMsg}`);
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

        window.getMarkdown = () => {
            return callbacks.getMarkdown();
        };

        window.sendToIntelliJ = (markdownText: string, uiState: EditorUiState) => {
            sendToIntelliJ(markdownText, uiState);
        };

        window.updateFromIntelliJ = (newMarkdown: string) => {
            callbacks.onIntelliJMarkdownUpdate(newMarkdown);
        };

        window.applyEditorStateFromIntelliJ = (state: EditorUiState) => {
            callbacks.onIntelliJEditorState(state);
        };
    };

    return {
        install,
        sendToIntelliJ
    };
};
