import katex from "katex";
import {createMermaidPreviewConfig} from "./runtime-settings";
import type {MarkFlowRuntimeSettings} from "./types";
import {
    DerivedRendererBackendUnavailableError,
    DerivedRendererService,
    type DerivedRendererBackend,
    type DerivedRendererBackendRequest
} from "./derived-renderer-service";

type MermaidModule = typeof import("mermaid");
type ResolvedRuntimeSettings = Required<MarkFlowRuntimeSettings>;

export const mermaidDerivedRenderConfig = (
    runtimeSettings: ResolvedRuntimeSettings
): Readonly<Record<string, unknown>> => ({runtimeSettings});

export const katexDerivedRenderConfig = (
    displayMode: boolean
): Readonly<Record<string, unknown>> => ({displayMode});

class BrowserMermaidBackend implements DerivedRendererBackend {
    private modulePromise: Promise<MermaidModule> | null = null;
    private queue: Promise<void> = Promise.resolve();
    private renderSequence = 0;

    public render(request: DerivedRendererBackendRequest) {
        const task = this.queue
            .catch(() => undefined)
            .then(() => this.renderNow(request));
        this.queue = task.then(() => undefined, () => undefined);
        return task;
    }

    private async renderNow(request: DerivedRendererBackendRequest) {
        throwIfAborted(request.signal);
        const runtimeSettings = requireRuntimeSettings(request.config);
        let module: MermaidModule;
        try {
            this.modulePromise ??= import("mermaid");
            module = await this.modulePromise;
        } catch {
            this.modulePromise = null;
            throw new DerivedRendererBackendUnavailableError();
        }
        throwIfAborted(request.signal);

        const mermaid = module.default;
        mermaid.initialize(createMermaidPreviewConfig(runtimeSettings));
        const output = await mermaid.render(`markflow-derived-mermaid-${++this.renderSequence}`, request.source);
        throwIfAborted(request.signal);
        return {mediaType: "image/svg+xml" as const, content: output.svg};
    }
}

class BrowserKatexBackend implements DerivedRendererBackend {
    public async render(request: DerivedRendererBackendRequest) {
        throwIfAborted(request.signal);
        const displayMode = request.config.displayMode === true;
        const content = katex.renderToString(request.source, {
            throwOnError: false,
            trust: false,
            displayMode
        });
        throwIfAborted(request.signal);
        return {mediaType: "text/html" as const, content};
    }
}

export const createBrowserDerivedRendererService = (): DerivedRendererService =>
    new DerivedRendererService({
        backends: {
            mermaid: new BrowserMermaidBackend(),
            katex: new BrowserKatexBackend()
        },
        timeoutMs: {mermaid: 8_000, katex: 3_000},
        maxRetries: {mermaid: 1, katex: 0},
        maxCacheEntries: 64
    });

const requireRuntimeSettings = (config: Readonly<Record<string, unknown>>): ResolvedRuntimeSettings => {
    const runtimeSettings = config.runtimeSettings;
    if (!runtimeSettings || typeof runtimeSettings !== "object") {
        throw new Error("missing Mermaid runtime settings");
    }
    return runtimeSettings as ResolvedRuntimeSettings;
};

const throwIfAborted = (signal: AbortSignal): void => {
    if (!signal.aborted) return;
    const error = new Error("renderer request aborted");
    error.name = "AbortError";
    throw error;
};
