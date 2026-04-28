import mermaid from "mermaid";
import {applyRuntimeUiSettings, createMermaidPreviewConfig, logThemeDiagnostics, resolveMermaidTheme, resolveRuntimeSettings} from "./runtime-settings";
import type {MarkFlowRuntimeSettings} from "./types";
import {logMermaidTrace} from "./editor-telemetry";

type MermaidPreviewRenderer = (html: string) => void;

export type MermaidSettingsApplyResult = {
    previewOnlyByDefaultChanged: boolean;
    nextRevision: number;
    nextTheme: "default" | "dark";
    skippedDuplicate: boolean;
};

export class MarkFlowMermaidRenderer {
    private runtimeSettings = resolveRuntimeSettings(window.intelliJ_markFlowSettings);
    private lastAppliedMermaidTheme: "default" | "dark" = resolveMermaidTheme(this.runtimeSettings);
    private lastAppliedSettingsRevision = -1;
    private hasAppliedRuntimeSettingsOnce = false;
    private lastAppliedPreviewOnlyByDefault = true;
    private activeCrepeSessionId = 0;
    private isCrepeReady = false;
    private mermaidRenderQueues = new WeakMap<MermaidPreviewRenderer, Promise<void>>();
    private mermaidRenderRequestId = 0;
    private mermaidPreviewEpoch = 0;
    private mermaidLoadingWatchdogTimers = new WeakMap<MermaidPreviewRenderer, number>();
    private mermaidPreviewRenderers = new Map<string, () => void>();
    private mermaidPreviewIdByRenderer = new WeakMap<MermaidPreviewRenderer, string>();

    public getRuntimeSettings() {
        return this.runtimeSettings;
    }

    public getLastAppliedSettingsRevision() {
        return this.lastAppliedSettingsRevision;
    }

    public getLastAppliedMermaidTheme() {
        return this.lastAppliedMermaidTheme;
    }

    public setActiveCrepeSessionId(sessionId: number) {
        this.activeCrepeSessionId = sessionId;
    }

    public setCrepeReady(isReady: boolean) {
        this.isCrepeReady = isReady;
    }

    public applyRuntimeSettingsFromHost(raw: MarkFlowRuntimeSettings | undefined): MermaidSettingsApplyResult {
        logMermaidTrace(`settings:raw ${JSON.stringify(raw ?? {})}`, this.emitToIntelliJLog);
        this.runtimeSettings = resolveRuntimeSettings(raw);

        const previewOnlyByDefaultChanged =
            this.hasAppliedRuntimeSettingsOnce && this.lastAppliedPreviewOnlyByDefault !== this.runtimeSettings.previewOnlyByDefault;
        const nextRevision = Number.isFinite(this.runtimeSettings.settingsRevision)
            ? Number(this.runtimeSettings.settingsRevision)
            : -1;
        const nextTheme = resolveMermaidTheme(this.runtimeSettings);

        if (!previewOnlyByDefaultChanged && nextRevision === this.lastAppliedSettingsRevision && nextTheme === this.lastAppliedMermaidTheme) {
            logMermaidTrace(`settings:skipDuplicate revision=${nextRevision} theme=${nextTheme}`, this.emitToIntelliJLog);
            return {
                previewOnlyByDefaultChanged,
                nextRevision,
                nextTheme,
                skippedDuplicate: true
            };
        }

        console.info(
            `MARKFLOW_UI settings:apply revision=${nextRevision} theme=${nextTheme} source=${this.runtimeSettings.themeSource}`
        );
        this.emitToIntelliJLog(
            `MARKFLOW_UI settings:resolved revision=${nextRevision} source=${this.runtimeSettings.themeSource} security=${this.runtimeSettings.diagramSecurityLevel}`
        );
        logThemeDiagnostics(raw, this.runtimeSettings, nextTheme, this.emitToIntelliJLog);
        mermaid.initialize(createMermaidPreviewConfig(this.runtimeSettings));
        this.lastAppliedMermaidTheme = nextTheme;
        this.lastAppliedSettingsRevision = nextRevision;

        const app = document.getElementById("app");
        if (app) {
            app.setAttribute("data-markflow-theme", this.runtimeSettings.themeSource);
            app.setAttribute("data-markflow-settings-revision", String(this.lastAppliedSettingsRevision));
        }

        applyRuntimeUiSettings(this.runtimeSettings);
        this.hasAppliedRuntimeSettingsOnce = true;
        this.lastAppliedPreviewOnlyByDefault = this.runtimeSettings.previewOnlyByDefault;
        this.renderAllRegisteredMermaidPreviews();

        return {
            previewOnlyByDefaultChanged,
            nextRevision,
            nextTheme,
            skippedDuplicate: false
        };
    }

    public createCodeMirrorFeatureConfig(crepeSessionId: number) {
        const renderer = this;
        return {
            previewOnlyByDefault: renderer.runtimeSettings.previewOnlyByDefault,
            renderPreview: (language: string, content: string, applyPreview: MermaidPreviewRenderer) => {
                if (isMermaidLanguage(language) && content.trim()) {
                    const renderEpoch = renderer.mermaidPreviewEpoch;
                    const requestId = ++renderer.mermaidRenderRequestId;
                    const isRenderContextActive = () => {
                        return renderer.isCrepeReady && renderEpoch === renderer.mermaidPreviewEpoch && crepeSessionId === renderer.activeCrepeSessionId;
                    };

                    if (!isRenderContextActive()) {
                        return;
                    }

                    const settlePreview = (html: string) => {
                        renderer.clearMermaidLoadingWatchdog(applyPreview);
                        if (isRenderContextActive()) {
                            applyPreview(html);
                        }
                    };
                    logMermaidTrace(`renderPreview ${normalizePreviewSnippet(content, 32)}`, renderer.emitToIntelliJLog);
                    const renderNow = (attempt = 0) => {
                        const scheduledRevision = renderer.lastAppliedSettingsRevision;
                        const scheduledTheme = renderer.lastAppliedMermaidTheme;
                        const svgId = `mermaid-svg-${uid()}`;

                        renderer.clearMermaidLoadingWatchdog(applyPreview);
                        const watchdogId = window.setTimeout(() => {
                            if (!isRenderContextActive()) {
                                return;
                            }
                            logMermaidTrace(`watchdog id=${requestId} fallback=error`, renderer.emitToIntelliJLog);
                            renderer.renderMermaidError(applyPreview, new Error("Mermaid preview watchdog timeout"));
                        }, MERMAID_LOADING_WATCHDOG_MS);
                        renderer.mermaidLoadingWatchdogTimers.set(applyPreview, watchdogId);

                        logMermaidTrace(
                            `queued id=${requestId} attempt=${attempt} revision=${scheduledRevision} theme=${scheduledTheme}`,
                            renderer.emitToIntelliJLog
                        );

                        renderer.enqueueMermaidRender(applyPreview, async () => {
                            if (!isRenderContextActive()) {
                                renderer.clearMermaidLoadingWatchdog(applyPreview);
                                logMermaidTrace(`staleContext id=${requestId} phase=beforeRender`, renderer.emitToIntelliJLog);
                                return;
                            }
                            logMermaidTrace(`start id=${requestId} svg=${svgId}`, renderer.emitToIntelliJLog);
                            try {
                                const output = await renderer.withTimeout(mermaid.render(svgId, content), MERMAID_RENDER_TIMEOUT_MS);
                                if (!isRenderContextActive()) {
                                    renderer.clearMermaidLoadingWatchdog(applyPreview);
                                    logMermaidTrace(`staleContext id=${requestId} phase=afterRender`, renderer.emitToIntelliJLog);
                                    return;
                                }
                                if (scheduledTheme !== renderer.lastAppliedMermaidTheme) {
                                    logMermaidTrace(
                                        `stale id=${requestId} scheduledTheme=${scheduledTheme} currentTheme=${renderer.lastAppliedMermaidTheme}`,
                                        renderer.emitToIntelliJLog
                                    );
                                    return;
                                }
                                if (scheduledRevision !== renderer.lastAppliedSettingsRevision) {
                                    logMermaidTrace(
                                        `revisionAdvanced id=${requestId} scheduled=${scheduledRevision} current=${renderer.lastAppliedSettingsRevision} applying=true`,
                                        renderer.emitToIntelliJLog
                                    );
                                }

                                logMermaidTrace(`success id=${requestId} theme=${renderer.lastAppliedMermaidTheme}`, renderer.emitToIntelliJLog);
                                settlePreview(renderer.wrapMermaidSvg(output.svg));
                            } catch (error) {
                                if (!isRenderContextActive()) {
                                    renderer.clearMermaidLoadingWatchdog(applyPreview);
                                    logMermaidTrace(`staleContext id=${requestId} phase=error`, renderer.emitToIntelliJLog);
                                    return;
                                }
                                const detail = error instanceof Error ? error.message : String(error);
                                const timedOut = detail.includes("timed out");
                                if (timedOut && attempt < MERMAID_RENDER_MAX_RETRIES) {
                                    logMermaidTrace(`retry id=${requestId} nextAttempt=${attempt + 1}`, renderer.emitToIntelliJLog);
                                    window.setTimeout(() => {
                                        if (!isRenderContextActive()) {
                                            renderer.clearMermaidLoadingWatchdog(applyPreview);
                                            return;
                                        }
                                        renderNow(attempt + 1);
                                    }, MERMAID_RENDER_RETRY_DELAY_MS);
                                    return;
                                }
                                logMermaidTrace(`failed id=${requestId} detail=${detail}`, renderer.emitToIntelliJLog);
                                if (isRenderContextActive()) {
                                    renderer.renderMermaidError(applyPreview, error);
                                }
                            }
                        });
                    };

                    renderer.registerMermaidPreviewRenderer(applyPreview, renderNow);
                    renderer.scheduleMermaidRender(renderNow);
                    return;
                }

                return null;
            }
        };
    }

    public invalidateMermaidPreviewLifecycle(reason: string) {
        this.mermaidPreviewEpoch += 1;
        this.mermaidRenderRequestId += 1;
        this.mermaidPreviewRenderers.clear();
        this.mermaidPreviewIdByRenderer = new WeakMap();
        this.mermaidRenderQueues = new WeakMap();
        logMermaidTrace(`lifecycleInvalidated reason=${reason} epoch=${this.mermaidPreviewEpoch}`, this.emitToIntelliJLog);
    }

    public renderAllRegisteredMermaidPreviews() {
        Array.from(this.mermaidPreviewRenderers.values()).forEach((render) => render());
    }

    private readonly emitToIntelliJLog = (message: string) => {
        const logger = window.markflowLog;
        if (typeof logger !== "function") return;
        try {
            logger(message);
        } catch {
            // Ignore diagnostics bridge failures so editor boot is unaffected.
        }
    };

    private registerMermaidPreviewRenderer(applyPreview: MermaidPreviewRenderer, renderNow: () => void) {
        const existingId = this.mermaidPreviewIdByRenderer.get(applyPreview);
        const previewId = existingId ?? `mermaid-preview-${uid()}`;
        this.mermaidPreviewIdByRenderer.set(applyPreview, previewId);
        this.mermaidPreviewRenderers.set(previewId, () => {
            if (!this.isCrepeReady || this.activeCrepeSessionId === 0) {
                return;
            }
            renderNow();
        });
    }

    private clearMermaidLoadingWatchdog(applyPreview: MermaidPreviewRenderer) {
        const timerId = this.mermaidLoadingWatchdogTimers.get(applyPreview);
        if (timerId !== undefined) {
            window.clearTimeout(timerId);
            this.mermaidLoadingWatchdogTimers.delete(applyPreview);
        }
    }

    private enqueueMermaidRender(applyPreview: MermaidPreviewRenderer, task: () => Promise<void>) {
        const previousQueue = this.mermaidRenderQueues.get(applyPreview) ?? Promise.resolve();
        const nextQueue = previousQueue
            .catch(() => {
                // Keep this preview's queue progressing even after a failed render.
            })
            .then(task)
            .catch((error) => {
                const detail = error instanceof Error ? error.message : String(error);
                console.warn(`MARKFLOW_UI mermaid:queueFailure ${detail}`);
                this.emitToIntelliJLog(`MARKFLOW_UI mermaid:queueFailure ${detail}`);
            });

        this.mermaidRenderQueues.set(applyPreview, nextQueue);
    }

    private async withTimeout<T>(promise: Promise<T>, timeoutMs: number): Promise<T> {
        let timeoutId: number | null = null;
        const timeoutPromise = new Promise<never>((_, reject) => {
            timeoutId = window.setTimeout(() => {
                reject(new Error(`Mermaid render timed out after ${timeoutMs}ms`));
            }, timeoutMs);
        });

        try {
            return await Promise.race([promise, timeoutPromise]);
        } finally {
            if (timeoutId !== null) {
                window.clearTimeout(timeoutId);
            }
        }
    }

    private renderMermaidError(applyPreview: MermaidPreviewRenderer, error: unknown) {
        this.clearMermaidLoadingWatchdog(applyPreview);
        console.error("MARKFLOW_UI mermaid:renderError", error);
        this.emitToIntelliJLog(`MARKFLOW_UI mermaid:renderError ${String(error)}`);
        if (this.runtimeSettings.mermaidErrorDisplay === "INLINE_ERROR_BOX") {
            applyPreview(`<div class="mermaid-error">${this.runtimeSettings.mermaidSyntaxErrorMessage}</div>`);
            return;
        }
        applyPreview("");
    }

    private scheduleMermaidRender(renderNow: () => void) {
        logMermaidTrace("trigger live", this.emitToIntelliJLog);
        renderNow();
    }

    private wrapMermaidSvg(svg: string) {
        const isXyChartSvg = /xychart/i.test(svg);
        const sizeClassByMode: Record<string, string> = {
            FIT_TO_VIEWPORT: "fit-to-viewport",
            SHRINK_TO_FIT: "shrink-to-fit",
            ACTUAL_SIZE_SCROLL: "actual-size-scroll"
        };
        const sizeClass = sizeClassByMode[this.runtimeSettings.mermaidSizeMode] ?? "fit-to-viewport";
        const chartTypeClass = isXyChartSvg ? " markflow-mermaid-chart-xychart" : "";
        const zoomScale = this.runtimeSettings.mermaidZoomPercent / 100;
        return `<div class="markflow-mermaid-preview markflow-mermaid-size-${sizeClass}${chartTypeClass}" style="transform: scale(${zoomScale}); transform-origin: top left;">${svg}</div>`;
    }
}

const uid = () => Math.random().toString(36).substring(7);

const normalizePreviewSnippet = (value: string, maxLength = 160) => value.replace(/\s+/g, " ").trim().slice(0, maxLength);

const isMermaidLanguage = (language: string) => language.trim().toLowerCase() === "mermaid";

const MERMAID_RENDER_TIMEOUT_MS = 8000;
const MERMAID_RENDER_RETRY_DELAY_MS = 250;
const MERMAID_RENDER_MAX_RETRIES = 1;
const MERMAID_LOADING_WATCHDOG_MS = 12000;
