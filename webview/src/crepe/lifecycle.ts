import { Crepe } from "@milkdown/crepe";
import mermaid from "mermaid";

import { app } from "../state";
import type { EditorUiState, MarkFlowRuntimeSettings } from "../state";
import { emitToIntelliJLog, logMermaidTrace, sendToIntelliJ, safeReadMarkdown, showBootError, withTimeout } from "../bridge";
import {
    registerMermaidPreviewRenderer,
    triggerForceRerender,
    invalidateMermaidPreviewLifecycle,
    clearMermaidLoadingWatchdog,
    scheduleMermaidRender,
    enqueueMermaidRender,
} from "../mermaid";
import { wrapMermaidSvg, renderMermaidError } from "../mermaid/renderer";
import { applyRuntimeSettingsFromHost, rerenderPreviewsAfterSettingsChange } from "../settings/manager";
import { applyEditorUiState, replaceEditorMarkdown, beginExternalUpdateGuard, clearExternalUpdateGuardLater } from "../editor-state/sync";
import { captureEditorUiState, recoverEditorLayout } from "../editor-state/capture";
import { installMarkdownPasteHandler, getRemoveMarkdownPasteHandler, setRemoveMarkdownPasteHandler } from "../paste/handler";
import { requestRecoveryLease, notifyRecoveryOutcome, clearRecoveryState } from "../recovery";

import type { Crepe as CrepeType } from "@milkdown/crepe";

const MERMAID_RENDER_TIMEOUT_MS = 8000;
const MERMAID_RENDER_RETRY_DELAY_MS = 250;
const MERMAID_RENDER_MAX_RETRIES = 1;
const MERMAID_LOADING_WATCHDOG_MS = 12000;
const BOOT_READY_TIMEOUT_MS = 5000;

const uid = () => Math.random().toString(36).substring(7);

const isMermaidLanguage = (language: string) => language.trim().toLowerCase() === "mermaid";

const normalizePreviewSnippet = (value: string, maxLength = 160) =>
    value.replace(/\s+/g, " ").trim().slice(0, maxLength);

const logCrepeCreateFailure = (error: unknown) => {
    console.error("MARKFLOW_UI crepe:create failed", error);
    showBootError("crepe:create", String(error));
};

const requestPreviewResumeRefresh = (reason: string) => {
    const retryToken = ++app.previewResumeRetryToken;
    requestAnimationFrame(() => {
        if (retryToken !== app.previewResumeRetryToken) return;
        if (!app.isEditorActive || document.visibilityState === "hidden") return;
        recoverEditorLayout(reason);
    });
};

export function createCrepeInstance(initialText: string, crepeSessionId: number): CrepeType {
    return new Crepe({
        root: document.getElementById("app"),
        defaultValue: initialText,
        featureConfigs: {
            [Crepe.Feature.CodeMirror]: {
                previewOnlyByDefault: app.runtimeSettings.previewOnlyByDefault,
                renderPreview: (language, content, applyPreview) => {
                    if (isMermaidLanguage(language) && content.trim()) {
                        const renderEpoch = app.mermaidPreviewEpoch;
                        const requestId = ++app.mermaidRenderRequestId;
                        const isRenderContextActive = () =>
                            renderEpoch === app.mermaidPreviewEpoch && crepeSessionId === app.activeCrepeSessionId;

                        if (!isRenderContextActive()) return;

                        const settlePreview = (html: string) => {
                            clearMermaidLoadingWatchdog(applyPreview);
                            if (isRenderContextActive()) applyPreview(html);
                        };

                        markFlowStage("mermaid:renderPreview", normalizePreviewSnippet(content, 32));
                        logMermaidTrace(
                            `renderPreview id=${requestId} theme=${app.lastAppliedMermaidTheme} len=${content.length}`
                        );

                        const renderNow = (attempt = 0) => {
                            const scheduledRevision = app.lastAppliedSettingsRevision;
                            const scheduledTheme = app.lastAppliedMermaidTheme;
                            const svgId = `mermaid-svg-${uid()}`;

                            clearMermaidLoadingWatchdog(applyPreview);
                            const watchdogId = window.setTimeout(() => {
                                if (!isRenderContextActive()) return;
                                logMermaidTrace(`watchdog id=${requestId} fallback=error`);
                                renderMermaidError(applyPreview, new Error("Mermaid preview watchdog timeout"));
                            }, MERMAID_LOADING_WATCHDOG_MS);
                            app.mermaidLoadingWatchdogTimers.set(applyPreview, watchdogId);

                            logMermaidTrace(
                                `queued id=${requestId} attempt=${attempt} revision=${scheduledRevision} theme=${scheduledTheme}`
                            );

                            enqueueMermaidRender(applyPreview, async () => {
                                if (!isRenderContextActive()) {
                                    clearMermaidLoadingWatchdog(applyPreview);
                                    logMermaidTrace(`staleContext id=${requestId} phase=beforeRender`);
                                    return;
                                }
                                logMermaidTrace(`start id=${requestId} svg=${svgId}`);
                                try {
                                    const output = await withTimeout(
                                        mermaid.render(svgId, content),
                                        MERMAID_RENDER_TIMEOUT_MS
                                    );
                                    if (!isRenderContextActive()) {
                                        clearMermaidLoadingWatchdog(applyPreview);
                                        logMermaidTrace(`staleContext id=${requestId} phase=afterRender`);
                                        return;
                                    }
                                    if (scheduledTheme !== app.lastAppliedMermaidTheme) {
                                        logMermaidTrace(
                                            `stale id=${requestId} scheduledTheme=${scheduledTheme} currentTheme=${app.lastAppliedMermaidTheme}`
                                        );
                                        return;
                                    }
                                    if (scheduledRevision !== app.lastAppliedSettingsRevision) {
                                        logMermaidTrace(
                                            `revisionAdvanced id=${requestId} scheduled=${scheduledRevision} current=${app.lastAppliedSettingsRevision} applying=true`
                                        );
                                    }
                                    logMermaidTrace(`success id=${requestId} theme=${app.lastAppliedMermaidTheme}`);
                                    settlePreview(wrapMermaidSvg(output.svg));
                                } catch (error) {
                                    if (!isRenderContextActive()) {
                                        clearMermaidLoadingWatchdog(applyPreview);
                                        logMermaidTrace(`staleContext id=${requestId} phase=error`);
                                        return;
                                    }
                                    const detail = error instanceof Error ? error.message : String(error);
                                    const timedOut = detail.includes("timed out");
                                    if (timedOut && attempt < MERMAID_RENDER_MAX_RETRIES) {
                                        logMermaidTrace(`retry id=${requestId} nextAttempt=${attempt + 1}`);
                                        window.setTimeout(() => {
                                            if (!isRenderContextActive()) {
                                                clearMermaidLoadingWatchdog(applyPreview);
                                                return;
                                            }
                                            renderNow(attempt + 1);
                                        }, MERMAID_RENDER_RETRY_DELAY_MS);
                                        return;
                                    }
                                    logMermaidTrace(`failed id=${requestId} detail=${detail}`);
                                    if (isRenderContextActive()) renderMermaidError(applyPreview, error);
                                }
                            });
                        };

                        registerMermaidPreviewRenderer(applyPreview, renderNow);

                        if (app.runtimeSettings.renderTriggerMode === "MANUAL_REFRESH") {
                            const previousManualId = app.manualPreviewIdByRenderer.get(applyPreview);
                            if (previousManualId) {
                                app.manualMermaidRenderers.delete(previousManualId);
                            }
                            const manualId = `manual-mermaid-${uid()}`;
                            app.manualPreviewIdByRenderer.set(applyPreview, manualId);
                            app.manualMermaidRenderers.set(manualId, () => {
                                if (!isRenderContextActive()) {
                                    app.manualMermaidRenderers.delete(manualId);
                                    app.manualPreviewIdByRenderer.delete(applyPreview);
                                    return;
                                }
                                app.manualMermaidRenderers.delete(manualId);
                                app.manualPreviewIdByRenderer.delete(applyPreview);
                                renderNow();
                            });
                            if (isRenderContextActive()) {
                                applyPreview(
                                    `<div class="markflow-manual-preview"><button type="button" class="markflow-manual-preview-button" onclick="window.__markflowRenderMermaidPreview && window.__markflowRenderMermaidPreview('${manualId}')">${app.runtimeSettings.manualRenderInlineLabel}</button><div class="markflow-manual-shortcut-hint">${app.runtimeSettings.manualRenderShortcutHint}</div></div>`
                                );
                            }
                            return;
                        }

                        scheduleMermaidRender(renderNow, applyPreview);
                        return;
                    }
                    return null;
                },
            },
            [Crepe.Feature.Latex]: {},
        },
    });
}

function attachCrepeBridge(crepe: Crepe) {
    crepe.on((listener) => {
        listener.markdownUpdated((_ctx, markdown, prevMarkdown) => {
            if (!app.isCrepeReady || app.activeCrepe !== crepe) return;
            if (app.isUpdatingFromIntelliJ) return;
            if (markdown !== prevMarkdown) {
                sendToIntelliJ(markdown, captureEditorUiState(crepe));
            }
        });
    });
}

function markFlowStage(stage: string, detail = "") {
    const message = detail ? `MARKFLOW_UI ${stage}: ${detail}` : `MARKFLOW_UI ${stage}`;
    console.info(message);
    emitToIntelliJLog(message);
    const domApp = document.getElementById("app");
    if (domApp) {
        domApp.setAttribute("data-markflow-stage", stage);
    }
}

function flushPendingIntelliJState(crepe: Crepe) {
    if (!app.isCrepeReady) return;

    const pendingMarkdown = app.pendingMarkdownFromIntelliJ;
    app.pendingMarkdownFromIntelliJ = null;
    if (pendingMarkdown !== null) {
        markFlowStage("bridge:updateFromIntelliJ:flush", pendingMarkdown.slice(0, 32));
        beginExternalUpdateGuard();
        try {
            replaceEditorMarkdown(crepe, pendingMarkdown);
        } finally {
            clearExternalUpdateGuardLater();
        }
    }

    const pendingState = app.pendingEditorStateFromIntelliJ;
    app.pendingEditorStateFromIntelliJ = null;
    if (pendingState !== null) {
        markFlowStage("bridge:applyEditorState:flush", `${pendingState.scrollTop},${pendingState.cursorOffset}`);
        applyEditorUiState(crepe, pendingState);
    }
}

export async function startCrepe(
    crepe: Crepe,
    layoutReason: string,
    restoreState?: EditorUiState
) {
    markFlowStage("crepe:create:start");
    try {
        await crepe.create();
    } catch (error) {
        logCrepeCreateFailure(error);
        return;
    }

    app.isCrepeReady = true;
    markFlowStage("crepe:create:done");
    installMarkdownPasteHandler(crepe);
    if (restoreState) {
        applyEditorUiState(crepe, restoreState);
    }
    flushPendingIntelliJState(crepe);
    recoverEditorLayout(layoutReason);

    if (app.pendingSettingsRerenderRevision !== null) {
        console.info(`MARKFLOW_UI rerender:flushQueued revision=${app.pendingSettingsRerenderRevision}`);
        emitToIntelliJLog(`MARKFLOW_UI rerender:flushQueued revision=${app.pendingSettingsRerenderRevision}`);
        app.pendingSettingsRerenderRevision = null;
        rerenderPreviewsAfterSettingsChange();
    }
    if (app.pendingLayoutRecovery) {
        app.pendingLayoutRecovery = false;
        recoverEditorLayout("create:flushQueued");
    }
    if (app.pendingHostForceRerender) {
        app.pendingHostForceRerender = false;
        triggerForceRerender();
    }
}

export async function recreateCrepeInstance(reason: string) {
    if (app.isRecreatingCrepe) {
        app.pendingCrepeRecreate = true;
        return;
    }

    const current = app.activeCrepe;
    if (!current || !app.isCrepeReady) {
        app.pendingCrepeRecreate = true;
        return;
    }

    app.isRecreatingCrepe = true;
    app.pendingCrepeRecreate = false;

    const recreateSessionId = (window as any).__markflowSessionId;
    const recoveryEpochAtStart = app.activeRecoveryEpoch;
    const recoveryRoleAtStart = app.activeRecoveryRole;

    try {
        await requestRecoveryLease(`recreate:${reason}`);

        if ((window as any).__markflowSessionId !== recreateSessionId) {
            emitToIntelliJLog(
                `MARKFLOW_UI recreate:sessionChanged during recovery oldSession=${recreateSessionId} newSession=${(window as any).__markflowSessionId}`
            );
            clearRecoveryState("recreate:sessionChanged");
            return;
        }

        const fallbackMarkdown = app.pendingMarkdownFromIntelliJ ?? window.intelliJ_initialMarkdown ?? "";
        const markdown = safeReadMarkdown(current, fallbackMarkdown, `recreate:${reason}`);
        const uiState = captureEditorUiState(current);

        const removeHandler = getRemoveMarkdownPasteHandler();
        removeHandler?.();
        setRemoveMarkdownPasteHandler(null);

        try {
            (current as unknown as { destroy?: () => void }).destroy?.();
        } catch (error) {
            emitToIntelliJLog(`MARKFLOW_UI crepe:destroy failed ${String(error)}`);
        }

        const domApp = document.getElementById("app");
        if (domApp) {
            domApp.innerHTML = "";
        }

        app.isCrepeReady = false;
        invalidateMermaidPreviewLifecycle(`recreate:${reason}`);
        const nextSessionId = ++app.crepeSessionSequence;
        const next = createCrepeInstance(markdown, nextSessionId);
        app.activeCrepeSessionId = nextSessionId;
        app.activeCrepe = next;
        attachCrepeBridge(next);

        markFlowStage("crepe:recreate:start", reason);
        await startCrepe(next, "recreate:done", uiState);
        markFlowStage("crepe:recreate:done", reason);

        if (
            recoveryRoleAtStart === "leader" &&
            recoveryEpochAtStart !== null &&
            (window as any).__markflowSessionId === recreateSessionId
        ) {
            const succeeded = app.isCrepeReady && app.activeCrepe === next;
            notifyRecoveryOutcome(succeeded ? "complete" : "failed", recoveryEpochAtStart, reason);
        } else if ((window as any).__markflowSessionId === recreateSessionId) {
            clearRecoveryState(`recreate:completed role=${recoveryRoleAtStart}`);
        }
    } finally {
        app.isRecreatingCrepe = false;
    }

    if (app.pendingCrepeRecreate) {
        void recreateCrepeInstance("settings:queued");
    }
}

export async function initEditor() {
    markFlowStage("init:start");
    setTimeout(() => {
        if (!(window as any).cefQuery) {
            markFlowStage("bridge:missing");
            return;
        }
        markFlowStage("bridge:ready");
    }, 300);

    applyRuntimeSettingsFromHost((window as any).intelliJ_markFlowSettings);
    markFlowStage("mermaid:initialized");

    (window as any).applyMarkFlowSettingsFromIntelliJ = (settings: MarkFlowRuntimeSettings) => {
        emitToIntelliJLog(`MARKFLOW_UI bridge:settingsReceived ${JSON.stringify(settings)}`);
        applyRuntimeSettingsFromHost(settings);
        markFlowStage("bridge:settingsApplied");
    };

    (window as any).setMarkFlowEditorActive = (active: boolean) => {
        app.isEditorActive = active;
        markFlowStage("bridge:editorActive", active ? "true" : "false");
        if (active) {
            requestPreviewResumeRefresh("editorActive");
        }
    };

    window.addEventListener("visibilitychange", () => {
        if (document.visibilityState !== "visible" || !app.isEditorActive) return;
        markFlowStage("bridge:visible", "true");
        requestPreviewResumeRefresh("visibilitychange");
    });

    (window as any).__markflowRenderMermaidPreview = (manualId: string) => {
        app.manualMermaidRenderers.get(manualId)?.();
    };

    const initialText = (window as any).intelliJ_initialMarkdown ?? "";
    markFlowStage("initialText:ready", initialText.slice(0, 48));

    const crepeSessionId = ++app.crepeSessionSequence;
    const crepe = createCrepeInstance(initialText, crepeSessionId);
    app.activeCrepeSessionId = crepeSessionId;
    app.activeCrepe = crepe;
    attachCrepeBridge(crepe);
    markFlowStage("crepe:constructed");

    (window as any).updateFromIntelliJ = (newMarkdown: string) => {
        markFlowStage("bridge:updateFromIntelliJ", newMarkdown.slice(0, 32));
        if (!app.isCrepeReady || !app.activeCrepe) {
            app.pendingMarkdownFromIntelliJ = newMarkdown;
            return;
        }
        beginExternalUpdateGuard();
        try {
            replaceEditorMarkdown(app.activeCrepe, newMarkdown);
            if (app.activeRecoveryRole === "follower" && app.activeRecoveryEpoch !== null) {
                const followerEpoch = app.activeRecoveryEpoch;
                clearRecoveryState("follower:markdownApplied");
                notifyRecoveryOutcome("complete", followerEpoch, "follower:markdownApplied");
            }
        } finally {
            clearExternalUpdateGuardLater();
        }
    };

    (window as any).applyEditorStateFromIntelliJ = (state: EditorUiState) => {
        markFlowStage("bridge:applyEditorState", `${state.scrollTop},${state.cursorOffset}`);
        if (!app.isCrepeReady || !app.activeCrepe) {
            app.pendingEditorStateFromIntelliJ = state;
            return;
        }
        applyEditorUiState(app.activeCrepe, state);
        if (app.activeRecoveryRole === "follower" && app.activeRecoveryEpoch !== null) {
            const followerEpoch = app.activeRecoveryEpoch;
            clearRecoveryState("follower:stateApplied");
            notifyRecoveryOutcome("complete", followerEpoch, "follower:stateApplied");
        }
    };

    await startCrepe(crepe, "create:done");

    if (app.pendingCrepeRecreate) {
        void recreateCrepeInstance("settings:queuedAfterCreate");
    }

    setTimeout(() => {
        if (!app.isCrepeReady) {
            markFlowStage("crepe:create:pending", "still waiting for editor readiness");
        }
    }, BOOT_READY_TIMEOUT_MS);

    markFlowStage("init:done");
}
