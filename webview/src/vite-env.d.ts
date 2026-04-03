/// <reference types="vite/client" />

type IntelliJEditorState = {
    version: number;
    scrollTop: number;
    cursorOffset: number;
    selectionStart: number;
    selectionEnd: number;
};

interface Window {
    intelliJ_initialMarkdown?: string;
    cefQuery?: (options: {
        request: string;
        onSuccess?: (response?: string) => void;
        onFailure?: (errorCode: number, errorMessage: string) => void;
    }) => void;
    markflowLog?: (message: string) => void;
    updateFromIntelliJ?: (newMarkdown: string) => void;
    applyEditorStateFromIntelliJ?: (state: IntelliJEditorState) => void;
}
