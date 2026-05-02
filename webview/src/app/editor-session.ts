import {Crepe} from "@milkdown/crepe";
import type {EditorUiState, MarkFlowRuntimeSettings} from "./types";
import {createEditorBridge, type EditorBridge} from "./bridge";
import {installMarkdownPasteHandler} from "./clipboard";
import {applyEditorUiState, captureEditorUiState, focusEditorView, recoverEditorLayout, replaceEditorMarkdown, safeReadMarkdown} from "./editor-state";
import {emitToIntelliJLog as baseEmitToIntelliJLog, markFlowStage, showBootError} from "./editor-telemetry";
import {createRecoveryController} from "./recovery";
import {MarkFlowMermaidRenderer} from "./mermaid-renderer";

const MARKDOWN_SYNC_DEBOUNCE_MS = 400;

export class MarkFlowEditorSession {
    private readonly emitToIntelliJLog = baseEmitToIntelliJLog;
    private readonly recovery = createRecoveryController(this.emitToIntelliJLog);
    private readonly mermaidRenderer = new MarkFlowMermaidRenderer();
    private readonly bridge: EditorBridge;

    private isUpdatingFromIntelliJ = false;
    private isCrepeReady = false;
    private pendingMarkdownFromIntelliJ: string | null = null;
    private pendingEditorStateFromIntelliJ: EditorUiState | null = null;
    private removeMarkdownPasteHandler: (() => void) | null = null;
    private isEditorActive = true;
    private activeCrepe: Crepe | null = null;
    private pendingSettingsRerenderRevision: number | null = null;
    private externalUpdateGuardToken = 0;
    private isRecreatingCrepe = false;
    private pendingCrepeRecreate = false;
    private previewResumeRetryToken = 0;
    private crepeSessionSequence = 0;
    private hasBootCompleted = false;
    private hasShownBootError = false;
    private pendingMarkdownSync: {
        crepe: Crepe;
        sessionId: number;
        markdown: string;
    } | null = null;
    private markdownSyncTimerId: number | null = null;

    constructor() {
        this.bridge = createEditorBridge({
            onSettings: this.handleRuntimeSettingsFromIntelliJ,
            onEditorActive: this.handleEditorActive,
            onIntelliJMarkdownUpdate: this.handleIntelliJMarkdownUpdate,
            onIntelliJEditorState: this.handleIntelliJEditorState,
            emitToIntelliJLog: this.emitToIntelliJLog
        });
    }

    public readonly initEditor = async () => {
        markFlowStage("init:start", this.emitToIntelliJLog);
        setTimeout(() => {
            if (!window.cefQuery) {
                markFlowStage("bridge:missing", this.emitToIntelliJLog);
                return;
            }
            markFlowStage("bridge:ready", this.emitToIntelliJLog);
        }, 300);

        this.bridge.install();

        // 1) Initialize Mermaid.
        this.handleRuntimeSettingsFromIntelliJ(window.intelliJ_markFlowSettings);
        markFlowStage("mermaid:initialized", this.emitToIntelliJLog);

        window.addEventListener("error", (event) => {
            const detail = event.message || String(event.error ?? "unknown error");
            markFlowStage("window:error", this.emitToIntelliJLog, detail);
            if (isEditorViewContextError(event.error ?? detail)) {
                logEditorViewContextError("window:error", event.error ?? detail, this.emitToIntelliJLog);
                return;
            }
            if (this.hasBootCompleted) {
                this.emitToIntelliJLog(
                    `MARKFLOW_UI window:error postBoot detail=${detail} file=${event.filename ?? "<unknown>"} line=${event.lineno ?? -1} col=${event.colno ?? -1}`
                );
                return;
            }
            if (detail === "Script error." && !event.error) {
                this.emitToIntelliJLog("MARKFLOW_UI window:error ignoredGenericScriptError during boot");
                return;
            }
            if (!this.hasShownBootError) {
                this.hasShownBootError = true;
                showBootError("window:error", detail, this.emitToIntelliJLog);
            }
        });

        window.addEventListener("unhandledrejection", (event) => {
            const reason = event.reason instanceof Error ? event.reason.message : String(event.reason);
            markFlowStage("window:unhandledrejection", this.emitToIntelliJLog, reason);
            if (isEditorViewContextError(event.reason ?? reason)) {
                logEditorViewContextError("window:unhandledrejection", event.reason ?? reason, this.emitToIntelliJLog);
                return;
            }
            if (this.hasBootCompleted) {
                this.emitToIntelliJLog(`MARKFLOW_UI window:unhandledrejection postBoot reason=${reason}`);
                return;
            }
            if (!this.hasShownBootError) {
                this.hasShownBootError = true;
                showBootError("window:unhandledrejection", reason, this.emitToIntelliJLog);
            }
        });

        window.addEventListener("visibilitychange", () => {
            if (document.visibilityState !== "visible" || !this.isEditorActive) {
                return;
            }
            markFlowStage("bridge:visible", this.emitToIntelliJLog, "true");
            this.requestPreviewResumeRefresh("visibilitychange");
        });

        // 2) Load initial markdown injected by Kotlin.
        const initialText = window.intelliJ_initialMarkdown ?? "";
        markFlowStage("initialText:ready", this.emitToIntelliJLog, initialText.slice(0, 48));

        // 3) Create the Crepe editor instance.
        const crepeSessionId = ++this.crepeSessionSequence;
        const crepe = this.createCrepeInstance(initialText, crepeSessionId);
        this.mermaidRenderer.setActiveCrepeSessionId(crepeSessionId);
        this.mermaidRenderer.setCrepeReady(false);
        this.activeCrepe = crepe;
        this.attachCrepeBridge(crepe, crepeSessionId);
        markFlowStage("crepe:constructed", this.emitToIntelliJLog);

        await this.startCrepe(crepe, "create:done");

        if (this.pendingCrepeRecreate) {
            void this.recreateCrepeInstance("settings:queuedAfterCreate");
        }

        setTimeout(() => {
            if (!this.isCrepeReady) {
                markFlowStage("crepe:create:pending", this.emitToIntelliJLog, "still waiting for editor readiness");
            }
        }, BOOT_READY_TIMEOUT_MS);

        this.hasBootCompleted = true;
        markFlowStage("init:done", this.emitToIntelliJLog);
    };

    private readonly handleRuntimeSettingsFromIntelliJ = (settings: MarkFlowRuntimeSettings | undefined) => {
        const result = this.mermaidRenderer.applyRuntimeSettingsFromHost(settings);
        markFlowStage("bridge:settingsApplied", this.emitToIntelliJLog);

        if (result.skippedDuplicate) {
            return;
        }

        if (result.previewOnlyByDefaultChanged) {
            if (!this.isCrepeReady || !this.activeCrepe || this.isRecreatingCrepe) {
                this.pendingCrepeRecreate = true;
                this.emitToIntelliJLog("MARKFLOW_UI settings:previewOnlyByDefault recreate queued");
                return;
            }
            void this.recreateCrepeInstance("settings:previewOnlyByDefault");
            return;
        }

        if (!this.isCrepeReady || !this.activeCrepe) {
            this.pendingSettingsRerenderRevision = this.mermaidRenderer.getLastAppliedSettingsRevision();
            console.info(`MARKFLOW_UI rerender:queued revision=${this.pendingSettingsRerenderRevision}`);
            this.emitToIntelliJLog(`MARKFLOW_UI rerender:queued revision=${this.pendingSettingsRerenderRevision}`);
            return;
        }
        this.rerenderPreviewsAfterSettingsChange();
    };

    private readonly handleEditorActive = (active: boolean) => {
        this.isEditorActive = active;
        markFlowStage("bridge:editorActive", this.emitToIntelliJLog, active ? "true" : "false");
        if (active) {
            if (this.activeCrepe && this.isCrepeReady) {
                focusEditorView(this.activeCrepe, this.emitToIntelliJLog);
            }
            this.requestPreviewResumeRefresh("editorActive");
        } else {
            this.flushPendingMarkdownSync();
        }
    };

    private handleIntelliJMarkdownUpdate = (newMarkdown: string) => {
        markFlowStage("bridge:updateFromIntelliJ", this.emitToIntelliJLog, newMarkdown.slice(0, 32));
        if (!this.isCrepeReady || !this.activeCrepe) {
            this.pendingMarkdownFromIntelliJ = newMarkdown;
            return;
        }

        this.beginExternalUpdateGuard();
        try {
            replaceEditorMarkdown(this.activeCrepe, newMarkdown, this.emitToIntelliJLog, true);
            if (this.recovery.state.activeRecoveryRole === "follower" && this.recovery.state.activeRecoveryEpoch !== null) {
                const followerEpoch = this.recovery.state.activeRecoveryEpoch;
                this.recovery.clearRecoveryState("follower:markdownApplied");
                this.recovery.notifyRecoveryOutcome("complete", followerEpoch, "follower:markdownApplied");
            }
        } finally {
            this.clearExternalUpdateGuardLater();
        }
    };

    private handleIntelliJEditorState = (state: EditorUiState) => {
        markFlowStage("bridge:applyEditorState", this.emitToIntelliJLog, `${state.scrollTop},${state.cursorOffset}`);
        if (!this.isCrepeReady || !this.activeCrepe) {
            this.pendingEditorStateFromIntelliJ = state;
            return;
        }

        applyEditorUiState(this.activeCrepe, state, this.emitToIntelliJLog);
        if (this.recovery.state.activeRecoveryRole === "follower" && this.recovery.state.activeRecoveryEpoch !== null) {
            const followerEpoch = this.recovery.state.activeRecoveryEpoch;
            this.recovery.clearRecoveryState("follower:stateApplied");
            this.recovery.notifyRecoveryOutcome("complete", followerEpoch, "follower:stateApplied");
        }
    };

    private attachCrepeBridge(crepe: Crepe, sessionId: number) {
        markFlowStage("bridge:attachCrepeBridge:start", this.emitToIntelliJLog, `crepeSession=${sessionId}`);
        crepe.on((listener) => {
            listener.markdownUpdated((_ctx, markdown, prevMarkdown) => {
                if (!this.isCrepeReady || this.activeCrepe !== crepe) {
                    console.info(`MARKFLOW_UI SAVE:BLOCKED listener isCrepeReady=${this.isCrepeReady} activeCrepeMatch=${this.activeCrepe === crepe}`);
                    this.emitToIntelliJLog(`MARKFLOW_SAVE markdownUpdated:BLOCKED isCrepeReady=${this.isCrepeReady} activeCrepeMatch=${this.activeCrepe === crepe}`);
                    return;
                }
                if (this.isUpdatingFromIntelliJ) {
                    console.info("MARKFLOW_UI SAVE:BLOCKED isUpdatingFromIntelliJ=true");
                    this.emitToIntelliJLog("MARKFLOW_SAVE markdownUpdated:BLOCKED isUpdatingFromIntelliJ=true");
                    return;
                }
                if (markdown !== prevMarkdown) {
                    this.scheduleMarkdownSync(crepe, sessionId, markdown);
                }
            });
        });
        markFlowStage("bridge:attachCrepeBridge:done", this.emitToIntelliJLog);
    }

    private async startCrepe(crepe: Crepe, layoutReason: string, restoreState?: EditorUiState) {
        markFlowStage("crepe:create:start", this.emitToIntelliJLog);
        try {
            await crepe.create();
        } catch (error) {
            console.error("MARKFLOW_UI crepe:create failed", error);
            showBootError("crepe:create", String(error), this.emitToIntelliJLog);
            return;
        }

        this.isCrepeReady = true;
        this.mermaidRenderer.setCrepeReady(true);
        markFlowStage("crepe:create:done", this.emitToIntelliJLog);
        this.removeMarkdownPasteHandler = installMarkdownPasteHandler(crepe, this.emitToIntelliJLog);
        if (restoreState) {
            applyEditorUiState(crepe, restoreState, this.emitToIntelliJLog);
        }
        this.flushPendingIntelliJState(crepe);
        recoverEditorLayout(layoutReason, this.isCrepeReady, this.activeCrepe, this.emitToIntelliJLog);

        if (this.pendingSettingsRerenderRevision !== null) {
            console.info(`MARKFLOW_UI rerender:flushQueued revision=${this.pendingSettingsRerenderRevision}`);
            this.emitToIntelliJLog(`MARKFLOW_UI rerender:flushQueued revision=${this.pendingSettingsRerenderRevision}`);
            this.pendingSettingsRerenderRevision = null;
            this.rerenderPreviewsAfterSettingsChange();
        }
    }

    private async recreateCrepeInstance(reason: string) {
        if (this.isRecreatingCrepe) {
            this.pendingCrepeRecreate = true;
            return;
        }

        const current = this.activeCrepe;
        if (!current || !this.isCrepeReady) {
            this.pendingCrepeRecreate = true;
            return;
        }

        this.isRecreatingCrepe = true;
        this.pendingCrepeRecreate = false;

        const recreateSessionId = window.__markflowSessionId;
        const recoveryEpochAtStart = this.recovery.state.activeRecoveryEpoch;
        const recoveryRoleAtStart = this.recovery.state.activeRecoveryRole;

        try {
            await this.recovery.requestRecoveryLease(`recreate:${reason}`);

            if (window.__markflowSessionId !== recreateSessionId) {
                this.emitToIntelliJLog(`MARKFLOW_UI recreate:sessionChanged during recovery oldSession=${recreateSessionId} newSession=${window.__markflowSessionId}`);
                this.recovery.clearRecoveryState("recreate:sessionChanged");
                return;
            }

            const fallbackMarkdown = this.pendingMarkdownFromIntelliJ ?? window.intelliJ_initialMarkdown ?? "";
            const markdown = safeReadMarkdown(current, fallbackMarkdown, `recreate:${reason}`, this.emitToIntelliJLog);
            const uiState = captureEditorUiState(current, this.emitToIntelliJLog);

            this.removeMarkdownPasteHandler?.();
            this.removeMarkdownPasteHandler = null;

            try {
                (current as unknown as { destroy?: () => void }).destroy?.();
            } catch (error) {
                this.emitToIntelliJLog(`MARKFLOW_UI crepe:destroy failed ${String(error)}`);
            }

            const app = document.getElementById("app");
            if (app) {
                app.innerHTML = "";
            }

            this.isCrepeReady = false;
            this.mermaidRenderer.setCrepeReady(false);
            this.mermaidRenderer.invalidateMermaidPreviewLifecycle(`recreate:${reason}`);
            const nextSessionId = ++this.crepeSessionSequence;
            const next = this.createCrepeInstance(markdown, nextSessionId);
            this.mermaidRenderer.setActiveCrepeSessionId(nextSessionId);
            this.activeCrepe = next;
            this.attachCrepeBridge(next, nextSessionId);

            markFlowStage("crepe:recreate:start", this.emitToIntelliJLog, reason);
            await this.startCrepe(next, "recreate:done", uiState);
            markFlowStage("crepe:recreate:done", this.emitToIntelliJLog, reason);

            if (recoveryRoleAtStart === "leader" && recoveryEpochAtStart !== null && window.__markflowSessionId === recreateSessionId) {
                const succeeded = this.isCrepeReady && this.activeCrepe === next;
                this.recovery.notifyRecoveryOutcome(succeeded ? "complete" : "failed", recoveryEpochAtStart, reason);
            } else if (window.__markflowSessionId === recreateSessionId) {
                this.recovery.clearRecoveryState(`recreate:completed role=${recoveryRoleAtStart}`);
            }
        } finally {
            this.isRecreatingCrepe = false;
        }

        if (this.pendingCrepeRecreate) {
            void this.recreateCrepeInstance("settings:queued");
        }
    }

    private flushPendingIntelliJState(crepe: Crepe) {
        if (!this.isCrepeReady) return;

        const pendingMarkdown = this.pendingMarkdownFromIntelliJ;
        this.pendingMarkdownFromIntelliJ = null;
        if (pendingMarkdown !== null) {
            markFlowStage("bridge:updateFromIntelliJ:flush", this.emitToIntelliJLog, pendingMarkdown.slice(0, 32));
            this.beginExternalUpdateGuard();
            try {
                replaceEditorMarkdown(crepe, pendingMarkdown, this.emitToIntelliJLog, true);
            } finally {
                this.clearExternalUpdateGuardLater();
            }
        }

        const pendingState = this.pendingEditorStateFromIntelliJ;
        this.pendingEditorStateFromIntelliJ = null;
        if (pendingState !== null) {
            markFlowStage("bridge:applyEditorState:flush", this.emitToIntelliJLog, `${pendingState.scrollTop},${pendingState.cursorOffset}`);
            applyEditorUiState(crepe, pendingState, this.emitToIntelliJLog);
        }
    }

    private scheduleMarkdownSync(crepe: Crepe, sessionId: number, markdown: string) {
        this.pendingMarkdownSync = {
            crepe,
            sessionId,
            markdown
        };

        if (this.markdownSyncTimerId !== null) {
            window.clearTimeout(this.markdownSyncTimerId);
        }

        this.markdownSyncTimerId = window.setTimeout(() => {
            this.markdownSyncTimerId = null;
            this.flushPendingMarkdownSync();
        }, MARKDOWN_SYNC_DEBOUNCE_MS);
    }

    private flushPendingMarkdownSync() {
        if (this.markdownSyncTimerId !== null) {
            window.clearTimeout(this.markdownSyncTimerId);
            this.markdownSyncTimerId = null;
        }

        const pending = this.pendingMarkdownSync;
        if (!pending) {
            return;
        }

        this.pendingMarkdownSync = null;

        if (!this.isCrepeReady || this.activeCrepe !== pending.crepe || this.crepeSessionSequence !== pending.sessionId) {
            return;
        }

        this.bridge.sendToIntelliJ(
            pending.markdown,
            captureEditorUiState(pending.crepe, this.emitToIntelliJLog)
        );
    }

    private rerenderPreviewsAfterSettingsChange() {
        console.info(
            `MARKFLOW_UI rerender:start ready=${this.isCrepeReady} hasCrepe=${this.activeCrepe !== null} revision=${this.mermaidRenderer.getLastAppliedSettingsRevision()}`
        );
        this.emitToIntelliJLog(
            `MARKFLOW_UI rerender:start ready=${this.isCrepeReady} hasCrepe=${this.activeCrepe !== null} revision=${this.mermaidRenderer.getLastAppliedSettingsRevision()}`
        );
        if (!this.activeCrepe || !this.isCrepeReady) return;

        const fallbackMarkdown = this.pendingMarkdownFromIntelliJ ?? window.intelliJ_initialMarkdown ?? "";
        const currentMarkdown = safeReadMarkdown(this.activeCrepe, fallbackMarkdown, "rerender", this.emitToIntelliJLog);

        this.beginExternalUpdateGuard();
        try {
            replaceEditorMarkdown(this.activeCrepe, currentMarkdown, this.emitToIntelliJLog, true);
        } finally {
            this.clearExternalUpdateGuardLater();
        }

        requestAnimationFrame(() => {
            if (!this.activeCrepe || !this.isCrepeReady) return;
            this.beginExternalUpdateGuard();
            try {
                replaceEditorMarkdown(this.activeCrepe, currentMarkdown, this.emitToIntelliJLog, true);
            } finally {
                this.clearExternalUpdateGuardLater();
                console.info(`MARKFLOW_UI rerender:done revision=${this.mermaidRenderer.getLastAppliedSettingsRevision()}`);
                this.emitToIntelliJLog(`MARKFLOW_UI rerender:done revision=${this.mermaidRenderer.getLastAppliedSettingsRevision()}`);
            }
        });
    }

    private requestPreviewResumeRefresh(reason: string) {
        const retryToken = ++this.previewResumeRetryToken;
        requestAnimationFrame(() => {
            if (retryToken !== this.previewResumeRetryToken) {
                return;
            }
            if (!this.isEditorActive || document.visibilityState === "hidden") {
                return;
            }
            recoverEditorLayout(reason, this.isCrepeReady, this.activeCrepe, this.emitToIntelliJLog);
        });
    }

    private beginExternalUpdateGuard() {
        this.externalUpdateGuardToken += 1;
        this.isUpdatingFromIntelliJ = true;
    }

    private clearExternalUpdateGuardLater() {
        const token = this.externalUpdateGuardToken;
        setTimeout(() => {
            if (token !== this.externalUpdateGuardToken) {
                return;
            }
            this.isUpdatingFromIntelliJ = false;
        }, EXTERNAL_UPDATE_GUARD_MS);
    }

    private createCrepeInstance(initialText: string, crepeSessionId: number): Crepe {
        this.mermaidRenderer.setActiveCrepeSessionId(crepeSessionId);
        return new Crepe({
            root: document.getElementById("app"),
            defaultValue: initialText,
            featureConfigs: {
                [Crepe.Feature.CodeMirror]: this.mermaidRenderer.createCodeMirrorFeatureConfig(crepeSessionId),
                [Crepe.Feature.Latex]: {}
            }
        });
    }
}

const isEditorViewContextError = (error: unknown): boolean => {
    const message = error instanceof Error ? error.message : String(error);
    return message.includes('Context "editorView" not found');
};

const logEditorViewContextError = (reason: string, error: unknown, emitToIntelliJLog: (message: string) => void) => {
    emitToIntelliJLog(`MARKFLOW_UI ${reason} editorView context missing: ${String(error)}`);
};

const EXTERNAL_UPDATE_GUARD_MS = 50;
const BOOT_READY_TIMEOUT_MS = 5000;
