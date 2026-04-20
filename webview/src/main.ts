import "./style.css";

// State - namespace import for mutable state access across modules
import { app } from './state';

// Re-exports needed in this file (read-only destructuring)
import { emitToIntelliJLog, markFlowStage } from "./bridge";

// Types (read-only)
import type { EditorUiState, MarkFlowRuntimeSettings } from './state';

// Crepe lifecycle
import { createCrepeInstance, startCrepe } from "./mermaid/crepe";

// Editor state
import { applyEditorUiState, replaceEditorMarkdown } from "./editor-state/sync";

// Force rerender
import { installForceRerenderShortcut } from "./force-rerender";
import { renderAllMermaidAndLatexPreviews } from "./mermaid";

// External update guard
import { beginExternalUpdateGuard, clearExternalUpdateGuardLater } from "./bridge";

// Settings
import { applyRuntimeSettingsFromHost, resolveRuntimeSettings as _resolve } from "./settings/manager";

initEditor();

async function initEditor() {
    markFlowStage("init:start");
    
    applyRuntimeSettingsFromHost((window as any).intelliJ_markFlowSettings);
    markFlowStage("mermaid:initialized");

    (window as any).applyMarkFlowSettingsFromIntelliJ = (settings: MarkFlowRuntimeSettings) => {
        emitToIntelliJLog(`MARKFLOW_UI bridge:settingsReceived ${JSON.stringify(settings)}`);
        applyRuntimeSettingsFromHost(settings);
        markFlowStage("bridge:settingsApplied");
    };

    (window as any).setMarkFlowEditorActive = (active: boolean) => {
        app.isCrepeReady && active ? undefined : undefined; // requestPreviewResumeRefresh moved to queue module
        markFlowStage("bridge:editorActive", active ? "true" : "false");
    };

    (window as any).updateFromIntelliJ = (newMarkdown: string) => {
        markFlowStage("bridge:updateFromIntelliJ", newMarkdown.slice(0, 32));
        if (!app.isCrepeReady || !app.activeCrepe) { app.pendingMarkdownFromIntelliJ = newMarkdown; return; }
        beginExternalUpdateGuard();
        try { replaceEditorMarkdown(app.activeCrepe, newMarkdown); } finally { clearExternalUpdateGuardLater(); }
    };

    (window as any).applyEditorStateFromIntelliJ = (editorState: EditorUiState) => {
        markFlowStage("bridge:applyEditorState", `${editorState.scrollTop},${editorState.cursorOffset}`);
        if (!app.isCrepeReady || !app.activeCrepe) { app.pendingEditorStateFromIntelliJ = editorState; return; }
        applyEditorUiState(app.activeCrepe, editorState);
    };

    const initialText = (window as any).intelliJ_initialMarkdown ?? "";
    markFlowStage("initialText:ready", initialText.slice(0, 48));

    const crepeSessionId = ++app.crepeSessionSequence;
    const crepe = createCrepeInstance(initialText, crepeSessionId);

    installForceRerenderShortcut(renderAllMermaidAndLatexPreviews);

    await startCrepe(crepe, "create:done");
    
    markFlowStage("init:done");
}
