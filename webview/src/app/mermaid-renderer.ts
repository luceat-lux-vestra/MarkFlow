import {applyRuntimeAppearance} from "./crepe-theme";
import {hashMermaidPaletteIdentity} from "./mermaid-cache-key";
import {applyRuntimeUiSettings, createMermaidPreviewConfig, logThemeDiagnostics, resolveMermaidTheme, resolveRuntimeSettings, runtimeSettingsIdentity} from "./runtime-settings";
import type {MarkFlowRuntimeSettings} from "./types";
import {emitDiagnosticsLog, logMermaidTrace} from "./editor-telemetry";

type MermaidPreviewRenderer = (html: string) => void;
type MermaidModule = typeof import("mermaid");
type MermaidRenderRequest = {
    requestId: number;
    crepeSessionId: number;
    previewId: string;
    applyPreview: MermaidPreviewRenderer;
    content: string;
    contentHash: string;
    diagramKey: string;
    wrapperKey: string;
    attempt: number;
};

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
    private lastAppliedRuntimeIdentity = "";
    private hasAppliedRuntimeSettingsOnce = false;
    private lastAppliedPreviewOnlyByDefault = true;
    private activeCrepeSessionId = 0;
    private mermaidRenderQueues = new WeakMap<MermaidPreviewRenderer, Promise<void>>();
    private mermaidRenderRequestId = 0;
    private mermaidPreviewEpoch = 0;
    private mermaidLoadingWatchdogTimers = new WeakMap<MermaidPreviewRenderer, number>();
    private mermaidPreviewRenderers = new Map<string, () => void>();
    private mermaidPreviewRendererById = new Map<string, MermaidPreviewRenderer>();
    private mermaidPreviewIdByRenderer = new WeakMap<MermaidPreviewRenderer, string>();
    private mermaidPreviewVisibility = new Map<string, boolean>();
    private mermaidPreviewRenderedOnce = new Set<string>();
    private mermaidPendingPreviewRefreshIds = new Set<string>();
    private mermaidPreviewPendingRequests = new Map<string, MermaidRenderRequest>();
    private mermaidPreviewTaskScheduled = new Set<string>();
    private mermaidPreviewObservedElements = new Map<string, Element>();
    private mermaidPreviewAppliedDiagramKeys = new Map<string, string>();
    private mermaidPreviewAppliedWrapperKeys = new Map<string, string>();
    private mermaidPreviewRenderedSvgById = new Map<string, string>();
    private mermaidModulePromise: Promise<MermaidModule> | null = null;
    private previewVisibilityObserver: IntersectionObserver | null = null;
    private documentCharacterCount = 0;
    private lastInitializedMermaidSettingsRevision = Number.NEGATIVE_INFINITY;

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

    public setDocumentCharacterCount(characterCount: number) {
        this.documentCharacterCount = Math.max(0, characterCount);
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
        const nextRuntimeIdentity = runtimeSettingsIdentity(this.runtimeSettings);

        if (!previewOnlyByDefaultChanged && nextRuntimeIdentity === this.lastAppliedRuntimeIdentity) {
            logMermaidTrace(`settings:skipDuplicate revision=${nextRevision} theme=${nextTheme}`, this.emitToIntelliJLog);
            return {
                previewOnlyByDefaultChanged,
                nextRevision,
                nextTheme,
                skippedDuplicate: true
            };
        }

        emitDiagnosticsLog(`MARKFLOW_UI settings:apply revision=${nextRevision} theme=${nextTheme} source=${this.runtimeSettings.themeSource}`, this.emitToIntelliJLog);
        this.emitToIntelliJLog(
            `MARKFLOW_UI settings:resolved revision=${nextRevision} source=${this.runtimeSettings.themeSource} security=${this.runtimeSettings.diagramSecurityLevel}`
        );
        logThemeDiagnostics(raw, this.runtimeSettings, nextTheme, this.emitToIntelliJLog);
        this.lastAppliedMermaidTheme = nextTheme;
        this.lastAppliedSettingsRevision = nextRevision;
        this.lastAppliedRuntimeIdentity = nextRuntimeIdentity;
        this.applyMermaidRuntimeSettingsIfLoaded();

        const app = document.getElementById("app");
        if (app) {
            app.setAttribute("data-markflow-theme", this.runtimeSettings.themeSource);
            app.setAttribute("data-markflow-settings-revision", String(this.lastAppliedSettingsRevision));
        }

        applyRuntimeUiSettings(this.runtimeSettings);
        applyRuntimeAppearance(this.runtimeSettings);

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
                    const previewId = renderer.getOrCreateMermaidPreviewId(applyPreview);
                    logMermaidTrace(`renderPreview ${normalizePreviewSnippet(content, 32)}`, renderer.emitToIntelliJLog);
                    const renderNow = () => {
                        const request = renderer.createMermaidRenderRequest(crepeSessionId, previewId, applyPreview, content);
                        renderer.requestMermaidPreviewRender(request);
                    };

                    renderer.registerMermaidPreviewRenderer(applyPreview, previewId, renderNow);
                    renderNow();
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
        this.mermaidPreviewRendererById.clear();
        this.mermaidPreviewIdByRenderer = new WeakMap();
        this.mermaidRenderQueues = new WeakMap();
        this.mermaidPreviewVisibility.clear();
        this.mermaidPreviewRenderedOnce.clear();
        this.mermaidPendingPreviewRefreshIds.clear();
        this.mermaidPreviewPendingRequests.clear();
        this.mermaidPreviewTaskScheduled.clear();
        this.mermaidPreviewObservedElements.forEach((element) => {
            this.previewVisibilityObserver?.unobserve(element);
        });
        this.mermaidPreviewObservedElements.clear();
        this.mermaidPreviewAppliedDiagramKeys.clear();
        this.mermaidPreviewAppliedWrapperKeys.clear();
        this.mermaidPreviewRenderedSvgById.clear();
        logMermaidTrace(`lifecycleInvalidated reason=${reason} epoch=${this.mermaidPreviewEpoch}`, this.emitToIntelliJLog);
    }

    public renderAllRegisteredMermaidPreviews() {
        Array.from(this.mermaidPreviewRenderers.entries()).forEach(([previewId, render]) => {
            if (this.shouldDeferPreviewRefresh(previewId) && this.mermaidPreviewRenderedOnce.has(previewId)) {
                this.mermaidPendingPreviewRefreshIds.add(previewId);
                this.observeMermaidPreview(previewId);
                return;
            }
            render();
        });
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

    private async ensureMermaid(): Promise<MermaidModule["default"]> {
        if (!this.mermaidModulePromise) {
            this.mermaidModulePromise = import("mermaid");
        }
        const module = await this.mermaidModulePromise;
        const mermaid = module.default;
        this.initializeMermaid(mermaid);
        return mermaid;
    }

    private applyMermaidRuntimeSettingsIfLoaded() {
        if (!this.mermaidModulePromise) {
            return;
        }

        void this.mermaidModulePromise
            .then((module) => {
                this.initializeMermaid(module.default);
            })
            .catch((err: unknown) => {
                this.emitToIntelliJLog(`MARKFLOW_UI mermaid:settingsApply:importFailed ${String(err)}`);
            });
    }

    private initializeMermaid(mermaid: MermaidModule["default"]) {
        if (this.lastInitializedMermaidSettingsRevision === this.lastAppliedSettingsRevision) {
            return;
        }
        mermaid.initialize(createMermaidPreviewConfig(this.runtimeSettings));
        this.lastInitializedMermaidSettingsRevision = this.lastAppliedSettingsRevision;
    }

    private getOrCreateMermaidPreviewId(applyPreview: MermaidPreviewRenderer) {
        const existingId = this.mermaidPreviewIdByRenderer.get(applyPreview);
        const previewId = existingId ?? `mermaid-preview-${uid()}`;
        this.mermaidPreviewIdByRenderer.set(applyPreview, previewId);
        return previewId;
    }

    private registerMermaidPreviewRenderer(applyPreview: MermaidPreviewRenderer, previewId: string, renderNow: () => void) {
        const existingId = this.mermaidPreviewIdByRenderer.get(applyPreview);
        const resolvedPreviewId = existingId ?? previewId;
        this.mermaidPreviewIdByRenderer.set(applyPreview, resolvedPreviewId);
        this.mermaidPreviewRendererById.set(resolvedPreviewId, applyPreview);
        this.mermaidPreviewRenderers.set(resolvedPreviewId, () => {
            renderNow();
        });
    }

    private createMermaidRenderRequest(
        crepeSessionId: number,
        previewId: string,
        applyPreview: MermaidPreviewRenderer,
        content: string
    ): MermaidRenderRequest {
        const contentHash = hashPreviewContent(content);
        return {
            requestId: ++this.mermaidRenderRequestId,
            crepeSessionId,
            previewId,
            applyPreview,
            content,
            contentHash,
            diagramKey: this.createMermaidDiagramKey(contentHash),
            wrapperKey: this.createMermaidWrapperKey(),
            attempt: 0
        };
    }

    private createMermaidDiagramKey(contentHash: string) {
        // For IDE_SYNC the Mermaid theme is palette-derived, but lastAppliedMermaidTheme
        // is OS-based, so switching IDE themes on a constant OS must still change the key
        // (otherwise a cached SVG from the previous palette would be reused).
        const palette = this.runtimeSettings.themeSource === "IDE_SYNC"
            ? hashMermaidPaletteIdentity(this.runtimeSettings.ideColorScheme)
            : "none";
        return [
            contentHash,
            this.lastAppliedMermaidTheme,
            palette,
            this.runtimeSettings.diagramSecurityLevel,
            this.runtimeSettings.mermaidSizeMode
        ].join(":");
    }

    private createMermaidWrapperKey() {
        return [this.runtimeSettings.mermaidSizeMode, this.runtimeSettings.mermaidZoomPercent].join(":");
    }

    private requestMermaidPreviewRender(request: MermaidRenderRequest) {
        const {previewId, applyPreview, diagramKey, wrapperKey} = request;
        const hasRenderedOnce = this.mermaidPreviewRenderedOnce.has(previewId);
        const currentDiagramKey = this.mermaidPreviewAppliedDiagramKeys.get(previewId);
        const currentWrapperKey = this.mermaidPreviewAppliedWrapperKeys.get(previewId);
        const cachedSvg = this.mermaidPreviewRenderedSvgById.get(previewId);

        if (this.shouldDeferPreviewRefresh(previewId) && hasRenderedOnce) {
            this.mermaidPendingPreviewRefreshIds.add(previewId);
            this.observeMermaidPreview(previewId);
            return;
        }

        if (currentDiagramKey === diagramKey && currentWrapperKey === wrapperKey) {
            return;
        }

        if (currentDiagramKey === diagramKey && currentWrapperKey !== wrapperKey && cachedSvg) {
            logMermaidTrace(`wrapOnly request=${request.requestId} preview=${previewId}`, this.emitToIntelliJLog);
            this.applyCachedMermaidPreview(previewId, cachedSvg, wrapperKey, applyPreview);
            return;
        }

        const pendingRequest = this.mermaidPreviewPendingRequests.get(previewId);
        if (pendingRequest && pendingRequest.diagramKey === diagramKey && pendingRequest.wrapperKey === wrapperKey) {
            return;
        }

        this.mermaidPreviewPendingRequests.set(previewId, request);
        this.scheduleMermaidPreviewRender(previewId);
    }

    private scheduleMermaidPreviewRender(previewId: string) {
        if (this.mermaidPreviewTaskScheduled.has(previewId)) {
            return;
        }

        const applyPreview = this.mermaidPreviewRendererById.get(previewId);
        if (!applyPreview) {
            return;
        }

        this.mermaidPreviewTaskScheduled.add(previewId);
        this.enqueueMermaidRender(applyPreview, async () => {
            await this.processPendingMermaidPreviewRenders(previewId);
        });
    }

    private async processPendingMermaidPreviewRenders(previewId: string) {
        const applyPreview = this.mermaidPreviewRendererById.get(previewId);
        if (!applyPreview) {
            this.mermaidPreviewTaskScheduled.delete(previewId);
            return;
        }

        try {
            while (true) {
                const request = this.mermaidPreviewPendingRequests.get(previewId);
                if (!request) {
                    return;
                }
                this.mermaidPreviewPendingRequests.delete(previewId);
                await this.executeMermaidRenderRequest(request);
            }
        } finally {
            this.mermaidPreviewTaskScheduled.delete(previewId);
            if (this.mermaidPreviewPendingRequests.has(previewId)) {
                this.scheduleMermaidPreviewRender(previewId);
            }
        }
    }

    private async executeMermaidRenderRequest(request: MermaidRenderRequest) {
        const {applyPreview, previewId, requestId, crepeSessionId, content, diagramKey} = request;
        const renderEpoch = this.mermaidPreviewEpoch;
        const isRenderContextActive = () => {
            return renderEpoch === this.mermaidPreviewEpoch && crepeSessionId === this.activeCrepeSessionId;
        };

        if (!isRenderContextActive()) {
            logMermaidTrace(`staleContext id=${requestId} phase=beforeRender`, this.emitToIntelliJLog);
            return;
        }

        const currentDiagramKey = this.createMermaidDiagramKey(request.contentHash);
        const currentWrapperKey = this.createMermaidWrapperKey();
        if (currentDiagramKey !== diagramKey) {
            logMermaidTrace(
                `stale id=${requestId} scheduled=${diagramKey} current=${currentDiagramKey}`,
                this.emitToIntelliJLog
            );
            this.mermaidPreviewPendingRequests.set(previewId, {
                ...request,
                requestId: ++this.mermaidRenderRequestId,
                diagramKey: currentDiagramKey,
                wrapperKey: currentWrapperKey,
                attempt: 0
            });
            return;
        }

        const svgId = `mermaid-svg-${uid()}`;
        this.clearMermaidLoadingWatchdog(applyPreview);
        const watchdogId = window.setTimeout(() => {
            if (!isRenderContextActive()) {
                return;
            }
            logMermaidTrace(`watchdog id=${requestId} fallback=error`, this.emitToIntelliJLog);
            this.renderMermaidError(applyPreview, new Error("Mermaid preview watchdog timeout"));
        }, MERMAID_LOADING_WATCHDOG_MS);
        this.mermaidLoadingWatchdogTimers.set(applyPreview, watchdogId);

        logMermaidTrace(`queued id=${requestId} preview=${previewId} revision=${this.lastAppliedSettingsRevision}`, this.emitToIntelliJLog);
        try {
            logMermaidTrace(`start id=${requestId} svg=${svgId}`, this.emitToIntelliJLog);
            const mermaid = await this.ensureMermaid();
            const output = await this.withTimeout(mermaid.render(svgId, content), MERMAID_RENDER_TIMEOUT_MS);
            if (!isRenderContextActive()) {
                this.clearMermaidLoadingWatchdog(applyPreview);
                logMermaidTrace(`staleContext id=${requestId} phase=afterRender`, this.emitToIntelliJLog);
                return;
            }

            const latestDiagramKey = this.createMermaidDiagramKey(request.contentHash);
            const latestWrapperKey = this.createMermaidWrapperKey();
            if (latestDiagramKey !== diagramKey) {
                logMermaidTrace(
                    `stale id=${requestId} scheduled=${diagramKey} current=${latestDiagramKey} applying=true`,
                    this.emitToIntelliJLog
                );
                this.mermaidPreviewPendingRequests.set(previewId, {
                    ...request,
                    requestId: ++this.mermaidRenderRequestId,
                    diagramKey: latestDiagramKey,
                    wrapperKey: latestWrapperKey,
                    attempt: 0
                });
                return;
            }

            logMermaidTrace(`success id=${requestId} theme=${this.lastAppliedMermaidTheme}`, this.emitToIntelliJLog);
            this.applyRenderedMermaidPreview(previewId, diagramKey, latestWrapperKey, output.svg, applyPreview);
        } catch (error) {
            if (!isRenderContextActive()) {
                this.clearMermaidLoadingWatchdog(applyPreview);
                logMermaidTrace(`staleContext id=${requestId} phase=error`, this.emitToIntelliJLog);
                return;
            }
            const detail = error instanceof Error ? error.message : String(error);
            const timedOut = detail.includes("timed out");
            this.clearMermaidLoadingWatchdog(applyPreview);
            if (timedOut) {
                if (request.attempt >= MERMAID_RENDER_MAX_RETRIES) {
                    logMermaidTrace(`failed id=${requestId} detail=${detail}`, this.emitToIntelliJLog);
                    this.renderMermaidError(applyPreview, error);
                    return;
                }
                const retryRequest = {
                    ...request,
                    requestId: ++this.mermaidRenderRequestId,
                    attempt: request.attempt + 1
                };
                logMermaidTrace(`retry id=${requestId} nextAttempt=${retryRequest.attempt}`, this.emitToIntelliJLog);
                await new Promise<void>((resolve) => {
                    window.setTimeout(() => resolve(), MERMAID_RENDER_RETRY_DELAY_MS);
                });
                if (isRenderContextActive()) {
                    this.mermaidPreviewPendingRequests.set(previewId, retryRequest);
                }
                return;
            }
            logMermaidTrace(`failed id=${requestId} detail=${detail}`, this.emitToIntelliJLog);
            this.renderMermaidError(applyPreview, error);
        }
    }

    private applyRenderedMermaidPreview(
        previewId: string,
        diagramKey: string,
        wrapperKey: string,
        svg: string,
        applyPreview: MermaidPreviewRenderer
    ) {
        this.clearMermaidLoadingWatchdog(applyPreview);
        this.mermaidPreviewRenderedSvgById.set(previewId, svg);
        this.mermaidPreviewAppliedDiagramKeys.set(previewId, diagramKey);
        this.mermaidPreviewAppliedWrapperKeys.set(previewId, wrapperKey);
        applyPreview(this.wrapMermaidSvg(svg, previewId));
        this.observeMermaidPreview(previewId);
        this.mermaidPreviewRenderedOnce.add(previewId);
        this.mermaidPendingPreviewRefreshIds.delete(previewId);
    }

    private applyCachedMermaidPreview(
        previewId: string,
        svg: string,
        wrapperKey: string,
        applyPreview: MermaidPreviewRenderer
    ) {
        this.mermaidPreviewAppliedWrapperKeys.set(previewId, wrapperKey);
        applyPreview(this.wrapMermaidSvg(svg, previewId));
        this.observeMermaidPreview(previewId);
        this.mermaidPreviewRenderedOnce.add(previewId);
        this.mermaidPendingPreviewRefreshIds.delete(previewId);
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
                emitDiagnosticsLog(`MARKFLOW_UI mermaid:queueFailure ${detail}`, this.emitToIntelliJLog);
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

    private shouldPreferVisiblePreviews() {
        return this.documentCharacterCount >= MERMAID_VISIBLE_ONLY_DOCUMENT_THRESHOLD;
    }

    private shouldDeferPreviewRefresh(previewId: string) {
        return this.shouldPreferVisiblePreviews() && this.mermaidPreviewRenderedOnce.has(previewId) && this.mermaidPreviewVisibility.get(previewId) !== true;
    }

    private observeMermaidPreview(previewId: string) {
        if (typeof IntersectionObserver !== "function") {
            this.mermaidPreviewVisibility.set(previewId, true);
            return;
        }

        const selector = `[data-markflow-mermaid-preview-id="${previewId}"]`;
        const element = document.querySelector(selector);
        if (!(element instanceof HTMLElement)) {
            return;
        }

        const previous = this.mermaidPreviewObservedElements.get(previewId);
        if (previous && previous !== element) {
            this.previewVisibilityObserver?.unobserve(previous);
        }
        this.mermaidPreviewObservedElements.set(previewId, element);
        this.ensurePreviewVisibilityObserver().observe(element);
    }

    private ensurePreviewVisibilityObserver() {
        if (this.previewVisibilityObserver) {
            return this.previewVisibilityObserver;
        }

        this.previewVisibilityObserver = new IntersectionObserver((entries) => {
            for (const entry of entries) {
                const previewId = (entry.target as HTMLElement).dataset.markflowMermaidPreviewId;
                if (!previewId) {
                    continue;
                }
                const isVisible = entry.isIntersecting;
                this.mermaidPreviewVisibility.set(previewId, isVisible);
                if (isVisible && this.mermaidPendingPreviewRefreshIds.has(previewId)) {
                    this.mermaidPendingPreviewRefreshIds.delete(previewId);
                    this.mermaidPreviewRenderers.get(previewId)?.();
                }
            }
        }, {root: null, threshold: 0.01});

        return this.previewVisibilityObserver;
    }

    private wrapMermaidSvg(svg: string, previewId: string) {
        const isXyChartSvg = /xychart/i.test(svg);
        const sizeClassByMode: Record<string, string> = {
            FIT_TO_VIEWPORT: "fit-to-viewport",
            SHRINK_TO_FIT: "shrink-to-fit",
            ACTUAL_SIZE_SCROLL: "actual-size-scroll"
        };
        const sizeClass = sizeClassByMode[this.runtimeSettings.mermaidSizeMode] ?? "fit-to-viewport";
        const chartTypeClass = isXyChartSvg ? " markflow-mermaid-chart-xychart" : "";
        const zoomScale = this.runtimeSettings.mermaidZoomPercent / 100;
        return `<div class="markflow-mermaid-preview markflow-mermaid-size-${sizeClass}${chartTypeClass}" data-markflow-mermaid-preview-id="${previewId}" style="transform: scale(${zoomScale}); transform-origin: top left;">${svg}</div>`;
    }
}

const uid = () => Math.random().toString(36).substring(7);

const normalizePreviewSnippet = (value: string, maxLength = 160) => value.replace(/\s+/g, " ").trim().slice(0, maxLength);

const isMermaidLanguage = (language: string) => language.trim().toLowerCase() === "mermaid";

const hashPreviewContent = (value: string) => {
    let hash = 2166136261;
    for (let index = 0; index < value.length; index += 1) {
        hash ^= value.charCodeAt(index);
        hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(36);
};

const MERMAID_RENDER_TIMEOUT_MS = 8000;
const MERMAID_RENDER_RETRY_DELAY_MS = 250;
const MERMAID_RENDER_MAX_RETRIES = 1;
const MERMAID_LOADING_WATCHDOG_MS = 12000;
const MERMAID_VISIBLE_ONLY_DOCUMENT_THRESHOLD = 40_000;
