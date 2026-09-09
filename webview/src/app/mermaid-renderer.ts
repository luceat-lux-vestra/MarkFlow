import {applyRuntimeAppearance} from "./crepe-theme";
import {hashMermaidPaletteIdentity} from "./mermaid-cache-key";
import {
    applyRuntimeUiSettings,
    logThemeDiagnostics,
    resolveMermaidTheme,
    resolveRuntimeSettings,
    runtimeSettingsIdentity
} from "./runtime-settings";
import type {MarkFlowRuntimeSettings} from "./types";
import {emitDiagnosticsLog, logMermaidTrace} from "./editor-telemetry";
import {
    createBrowserDerivedRendererService,
    mermaidDerivedRenderConfig
} from "./browser-derived-renderer-backends";
import type {
    DerivedRenderIdentity,
    DerivedRendererService
} from "./derived-renderer-service";

type MermaidPreviewRenderer = (html: string) => void;
type MermaidRenderRequest = {
    requestId: number;
    previewId: string;
    applyPreview: MermaidPreviewRenderer;
    content: string;
    contentHash: string;
    diagramKey: string;
    wrapperKey: string;
    identity: DerivedRenderIdentity;
    controller: AbortController;
};

export type MermaidSettingsApplyResult = {
    previewOnlyByDefaultChanged: boolean;
    nextRevision: number;
    nextTheme: "default" | "dark";
    skippedDuplicate: boolean;
};

/**
 * Temporary CodeMirror presentation adapter for the extracted renderer service.
 *
 * It owns only browser-editor preview registration, visibility and DOM wrapping. Mermaid engine
 * execution, timeout/retry, cancellation and renderer failures are owned by DerivedRendererService.
 * This adapter is deleted by #154 after #148 connects the same service to native presentation.
 */
export class MarkFlowMermaidRenderer {
    private runtimeSettings = resolveRuntimeSettings(window.intelliJ_markFlowSettings);
    private lastAppliedMermaidTheme: "default" | "dark" = resolveMermaidTheme(this.runtimeSettings);
    private lastAppliedSettingsRevision = -1;
    private lastAppliedRuntimeIdentity = "";
    private hasAppliedRuntimeSettingsOnce = false;
    private lastAppliedPreviewOnlyByDefault = true;
    private readonly rendererService: DerivedRendererService;
    private mermaidRenderRequestId = 0;
    private mermaidPreviewEpoch = 0;
    private mermaidPreviewRenderers = new Map<string, () => void>();
    private mermaidPreviewRendererById = new Map<string, MermaidPreviewRenderer>();
    private mermaidPreviewIdByRenderer = new WeakMap<MermaidPreviewRenderer, string>();
    private mermaidPreviewVisibility = new Map<string, boolean>();
    private mermaidPreviewRenderedOnce = new Set<string>();
    private mermaidPendingPreviewRefreshIds = new Set<string>();
    private mermaidPreviewObservedElements = new Map<string, Element>();
    private mermaidPreviewAppliedDiagramKeys = new Map<string, string>();
    private mermaidPreviewAppliedWrapperKeys = new Map<string, string>();
    private mermaidPreviewRenderedSvgById = new Map<string, string>();
    private mermaidPreviewPendingRequests = new Map<string, MermaidRenderRequest>();
    private previewVisibilityObserver: IntersectionObserver | null = null;
    private documentCharacterCount = 0;
    private disposed = false;

    constructor(rendererService: DerivedRendererService = createBrowserDerivedRendererService()) {
        this.rendererService = rendererService;
    }

    public getRendererService(): DerivedRendererService {
        return this.rendererService;
    }

    public getRuntimeSettings() {
        return this.runtimeSettings;
    }

    public getLastAppliedSettingsRevision() {
        return this.lastAppliedSettingsRevision;
    }

    public getLastAppliedMermaidTheme() {
        return this.lastAppliedMermaidTheme;
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

    public createCodeMirrorFeatureConfig() {
        return {
            previewOnlyByDefault: this.runtimeSettings.previewOnlyByDefault,
            renderPreview: (language: string, content: string, applyPreview: MermaidPreviewRenderer) => {
                if (!isMermaidLanguage(language) || !content.trim()) return null;

                const previewId = this.getOrCreateMermaidPreviewId(applyPreview);
                logMermaidTrace(`renderPreview ${normalizePreviewSnippet(content, 32)}`, this.emitToIntelliJLog);
                const renderNow = () => {
                    const request = this.createMermaidRenderRequest(previewId, applyPreview, content);
                    this.requestMermaidPreviewRender(request);
                };
                this.registerMermaidPreviewRenderer(applyPreview, previewId, renderNow);
                renderNow();
                return null;
            }
        };
    }

    public invalidateMermaidPreviewLifecycle(reason: string) {
        this.mermaidPreviewEpoch += 1;
        this.mermaidRenderRequestId += 1;
        for (const request of this.mermaidPreviewPendingRequests.values()) request.controller.abort();
        this.mermaidPreviewRenderers.clear();
        this.mermaidPreviewRendererById.clear();
        this.mermaidPreviewIdByRenderer = new WeakMap();
        this.mermaidPreviewVisibility.clear();
        this.mermaidPreviewRenderedOnce.clear();
        this.mermaidPendingPreviewRefreshIds.clear();
        this.mermaidPreviewPendingRequests.clear();
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

    public dispose(): void {
        if (this.disposed) return;
        this.disposed = true;
        this.invalidateMermaidPreviewLifecycle("dispose");
        this.previewVisibilityObserver?.disconnect();
        this.previewVisibilityObserver = null;
        this.rendererService.dispose();
    }

    private readonly emitToIntelliJLog = (message: string) => {
        const logger = window.markflowLog;
        if (typeof logger !== "function") return;
        try {
            logger(message);
        } catch {
            // Diagnostics must never affect editor or renderer availability.
        }
    };

    private getOrCreateMermaidPreviewId(applyPreview: MermaidPreviewRenderer) {
        const existingId = this.mermaidPreviewIdByRenderer.get(applyPreview);
        const previewId = existingId ?? `mermaid-preview-${uid()}`;
        this.mermaidPreviewIdByRenderer.set(applyPreview, previewId);
        return previewId;
    }

    private registerMermaidPreviewRenderer(applyPreview: MermaidPreviewRenderer, previewId: string, renderNow: () => void) {
        this.mermaidPreviewIdByRenderer.set(applyPreview, previewId);
        this.mermaidPreviewRendererById.set(previewId, applyPreview);
        this.mermaidPreviewRenderers.set(previewId, renderNow);
    }

    private createMermaidRenderRequest(
        previewId: string,
        applyPreview: MermaidPreviewRenderer,
        content: string
    ): MermaidRenderRequest {
        const contentHash = hashPreviewContent(content);
        const configGeneration = this.createMermaidConfigGeneration();
        const diagramKey = [contentHash, configGeneration].join(":");
        const wrapperKey = this.createMermaidWrapperKey();
        return {
            requestId: ++this.mermaidRenderRequestId,
            previewId,
            applyPreview,
            content,
            contentHash,
            diagramKey,
            wrapperKey,
            identity: {
                sourceGeneration: contentHash,
                configGeneration
            },
            controller: new AbortController()
        };
    }

    private createMermaidDiagramKey(contentHash: string) {
        return [contentHash, this.createMermaidConfigGeneration()].join(":");
    }

    private createMermaidConfigGeneration() {
        const palette = this.runtimeSettings.themeSource === "IDE_SYNC"
            ? hashMermaidPaletteIdentity(this.runtimeSettings.ideColorScheme)
            : "none";
        return [
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
            request.controller.abort();
            this.mermaidPendingPreviewRefreshIds.add(previewId);
            this.observeMermaidPreview(previewId);
            return;
        }
        if (currentDiagramKey === diagramKey && currentWrapperKey === wrapperKey) {
            request.controller.abort();
            return;
        }
        if (currentDiagramKey === diagramKey && currentWrapperKey !== wrapperKey && cachedSvg) {
            request.controller.abort();
            logMermaidTrace(`wrapOnly request=${request.requestId} preview=${previewId}`, this.emitToIntelliJLog);
            this.applyCachedMermaidPreview(previewId, cachedSvg, wrapperKey, applyPreview);
            return;
        }

        const previous = this.mermaidPreviewPendingRequests.get(previewId);
        previous?.controller.abort();
        this.mermaidPreviewPendingRequests.set(previewId, request);
        void this.executeMermaidRenderRequest(request);
    }

    private async executeMermaidRenderRequest(request: MermaidRenderRequest) {
        const {previewId, requestId, content, contentHash, diagramKey, wrapperKey, identity, controller} = request;
        const renderEpoch = this.mermaidPreviewEpoch;
        logMermaidTrace(`queued id=${requestId} preview=${previewId} revision=${this.lastAppliedSettingsRevision}`, this.emitToIntelliJLog);
        try {
            const result = await this.rendererService.render({
                kind: "mermaid",
                source: content,
                config: mermaidDerivedRenderConfig(this.runtimeSettings),
                identity,
                signal: controller.signal
            });
            if (!this.isCurrentRequest(request, renderEpoch)) {
                logMermaidTrace(`stale id=${requestId} phase=afterRender`, this.emitToIntelliJLog);
                return;
            }

            const latestDiagramKey = this.createMermaidDiagramKey(contentHash);
            const latestWrapperKey = this.createMermaidWrapperKey();
            if (latestDiagramKey !== diagramKey) {
                logMermaidTrace(`stale id=${requestId} scheduled=${diagramKey} current=${latestDiagramKey}`, this.emitToIntelliJLog);
                const next = this.createMermaidRenderRequest(previewId, request.applyPreview, content);
                this.mermaidPreviewPendingRequests.set(previewId, next);
                void this.executeMermaidRenderRequest(next);
                return;
            }

            if (result.status === "failure") {
                logMermaidTrace(`failed id=${requestId} code=${result.code}`, this.emitToIntelliJLog);
                this.renderMermaidError(request.applyPreview, result.code);
                return;
            }
            if (!sameIdentity(result.identity, identity)) {
                logMermaidTrace(`stale id=${requestId} phase=identityMismatch`, this.emitToIntelliJLog);
                return;
            }

            logMermaidTrace(`success id=${requestId} theme=${this.lastAppliedMermaidTheme}`, this.emitToIntelliJLog);
            this.applyRenderedMermaidPreview(previewId, diagramKey, latestWrapperKey || wrapperKey, result.content, request.applyPreview);
        } finally {
            if (this.mermaidPreviewPendingRequests.get(previewId) === request) {
                this.mermaidPreviewPendingRequests.delete(previewId);
            }
        }
    }

    private isCurrentRequest(request: MermaidRenderRequest, renderEpoch: number): boolean {
        if (this.disposed || request.controller.signal.aborted || renderEpoch !== this.mermaidPreviewEpoch) return false;
        return this.mermaidPreviewPendingRequests.get(request.previewId) === request;
    }

    private applyRenderedMermaidPreview(
        previewId: string,
        diagramKey: string,
        wrapperKey: string,
        svg: string,
        applyPreview: MermaidPreviewRenderer
    ) {
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

    private renderMermaidError(applyPreview: MermaidPreviewRenderer, code: string) {
        this.emitToIntelliJLog(`MARKFLOW_UI mermaid:renderError code=${code}`);
        if (this.runtimeSettings.mermaidErrorDisplay === "INLINE_ERROR_BOX") {
            applyPreview(`<div class="mermaid-error">${escapeHtml(this.runtimeSettings.mermaidSyntaxErrorMessage)}</div>`);
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
        if (!(element instanceof HTMLElement)) return;

        const previous = this.mermaidPreviewObservedElements.get(previewId);
        if (previous && previous !== element) this.previewVisibilityObserver?.unobserve(previous);
        this.mermaidPreviewObservedElements.set(previewId, element);
        this.ensurePreviewVisibilityObserver().observe(element);
    }

    private ensurePreviewVisibilityObserver() {
        if (this.previewVisibilityObserver) return this.previewVisibilityObserver;

        this.previewVisibilityObserver = new IntersectionObserver((entries) => {
            for (const entry of entries) {
                const previewId = (entry.target as HTMLElement).dataset.markflowMermaidPreviewId;
                if (!previewId) continue;
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
const sameIdentity = (left: DerivedRenderIdentity, right: DerivedRenderIdentity): boolean =>
    left.sourceGeneration === right.sourceGeneration && left.configGeneration === right.configGeneration;
const escapeHtml = (value: string): string => value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");

const hashPreviewContent = (value: string) => {
    let hash = 2166136261;
    for (let index = 0; index < value.length; index += 1) {
        hash ^= value.charCodeAt(index);
        hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(36);
};

const MERMAID_VISIBLE_ONLY_DOCUMENT_THRESHOLD = 40_000;
