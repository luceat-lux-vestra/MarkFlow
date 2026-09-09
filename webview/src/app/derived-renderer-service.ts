export type DerivedRendererKind = "mermaid" | "katex";

export type DerivedRenderIdentity = Readonly<{
    sourceGeneration: string;
    configGeneration: string;
}>;

export type DerivedRenderRequest = Readonly<{
    kind: DerivedRendererKind;
    source: string;
    config: Readonly<Record<string, unknown>>;
    identity: DerivedRenderIdentity;
    signal?: AbortSignal;
}>;

export type DerivedRenderArtifact = Readonly<{
    status: "success";
    kind: DerivedRendererKind;
    identity: DerivedRenderIdentity;
    mediaType: "image/svg+xml" | "text/html";
    content: string;
}>;

export type DerivedRenderFailureCode =
    | "INVALID_REQUEST"
    | "CANCELLED"
    | "TIMEOUT"
    | "BACKEND_UNAVAILABLE"
    | "RENDER_FAILED"
    | "DISPOSED";

export type DerivedRenderFailure = Readonly<{
    status: "failure";
    kind: DerivedRendererKind;
    identity: DerivedRenderIdentity;
    code: DerivedRenderFailureCode;
    retryable: boolean;
    message: string;
}>;

export type DerivedRenderResult = DerivedRenderArtifact | DerivedRenderFailure;

export type DerivedRendererBackendRequest = Readonly<{
    source: string;
    config: Readonly<Record<string, unknown>>;
    identity: DerivedRenderIdentity;
    signal: AbortSignal;
}>;

export type DerivedRendererBackendResult = Readonly<{
    mediaType: "image/svg+xml" | "text/html";
    content: string;
}>;

export type DerivedRendererBackend = Readonly<{
    render(request: DerivedRendererBackendRequest): Promise<DerivedRendererBackendResult>;
}>;

type DerivedRendererServiceOptions = Readonly<{
    backends: Readonly<Record<DerivedRendererKind, DerivedRendererBackend>>;
    timeoutMs?: Partial<Record<DerivedRendererKind, number>>;
    maxRetries?: Partial<Record<DerivedRendererKind, number>>;
    maxCacheEntries?: number;
}>;

export class DerivedRendererBackendUnavailableError extends Error {
    constructor(message = "renderer backend unavailable") {
        super(message);
        this.name = "DerivedRendererBackendUnavailableError";
    }
}

export class DerivedRendererService {
    private readonly backends: Readonly<Record<DerivedRendererKind, DerivedRendererBackend>>;
    private readonly timeoutMs: Readonly<Record<DerivedRendererKind, number>>;
    private readonly maxRetries: Readonly<Record<DerivedRendererKind, number>>;
    private readonly maxCacheEntries: number;
    private readonly activeControllers = new Set<AbortController>();
    private readonly cache = new Map<string, DerivedRenderArtifact>();
    private disposed = false;

    constructor(options: DerivedRendererServiceOptions) {
        this.backends = options.backends;
        this.timeoutMs = {
            mermaid: normalizePositiveInteger(options.timeoutMs?.mermaid, 8_000),
            katex: normalizePositiveInteger(options.timeoutMs?.katex, 3_000)
        };
        this.maxRetries = {
            mermaid: normalizeNonNegativeInteger(options.maxRetries?.mermaid, 1),
            katex: normalizeNonNegativeInteger(options.maxRetries?.katex, 0)
        };
        this.maxCacheEntries = normalizePositiveInteger(options.maxCacheEntries, 64);
    }

    public async render(request: DerivedRenderRequest): Promise<DerivedRenderResult> {
        const invalid = validateRequest(request);
        if (invalid) {
            return failure(request.kind, request.identity, "INVALID_REQUEST", false, invalid);
        }
        if (this.disposed) {
            return failure(request.kind, request.identity, "DISPOSED", false, "renderer service is disposed");
        }
        if (request.signal?.aborted) {
            return failure(request.kind, request.identity, "CANCELLED", false, "renderer request was cancelled");
        }

        const cacheKey = createCacheKey(request);
        const cached = this.cache.get(cacheKey);
        if (cached) {
            this.cache.delete(cacheKey);
            this.cache.set(cacheKey, cached);
            return cached;
        }

        const backend = this.backends[request.kind];
        for (let attempt = 0; attempt <= this.maxRetries[request.kind]; attempt += 1) {
            const controller = new AbortController();
            const unlink = linkAbortSignal(request.signal, controller);
            this.activeControllers.add(controller);
            try {
                const rendered = await withTimeout(
                    backend.render({
                        source: request.source,
                        config: request.config,
                        identity: request.identity,
                        signal: controller.signal
                    }),
                    this.timeoutMs[request.kind],
                    controller
                );
                if (controller.signal.aborted || request.signal?.aborted || this.disposed) {
                    return failure(
                        request.kind,
                        request.identity,
                        this.disposed ? "DISPOSED" : "CANCELLED",
                        false,
                        this.disposed ? "renderer service is disposed" : "renderer request was cancelled"
                    );
                }

                const artifact: DerivedRenderArtifact = {
                    status: "success",
                    kind: request.kind,
                    identity: request.identity,
                    mediaType: rendered.mediaType,
                    content: rendered.content
                };
                this.putCache(cacheKey, artifact);
                return artifact;
            } catch (error) {
                if (this.disposed) {
                    return failure(request.kind, request.identity, "DISPOSED", false, "renderer service is disposed");
                }
                if (request.signal?.aborted || isAbortError(error)) {
                    return failure(request.kind, request.identity, "CANCELLED", false, "renderer request was cancelled");
                }
                if (error instanceof DerivedRendererBackendUnavailableError) {
                    return failure(request.kind, request.identity, "BACKEND_UNAVAILABLE", true, "renderer backend unavailable");
                }
                if (error instanceof DerivedRendererTimeoutError) {
                    if (attempt < this.maxRetries[request.kind]) continue;
                    return failure(request.kind, request.identity, "TIMEOUT", true, "renderer request timed out");
                }
                return failure(request.kind, request.identity, "RENDER_FAILED", true, "renderer failed");
            } finally {
                unlink();
                this.activeControllers.delete(controller);
            }
        }

        return failure(request.kind, request.identity, "RENDER_FAILED", true, "renderer failed");
    }

    public dispose(): void {
        if (this.disposed) return;
        this.disposed = true;
        for (const controller of this.activeControllers) controller.abort();
        this.activeControllers.clear();
        this.cache.clear();
    }

    private putCache(key: string, artifact: DerivedRenderArtifact): void {
        this.cache.delete(key);
        this.cache.set(key, artifact);
        while (this.cache.size > this.maxCacheEntries) {
            const oldest = this.cache.keys().next().value as string | undefined;
            if (oldest === undefined) break;
            this.cache.delete(oldest);
        }
    }
}

class DerivedRendererTimeoutError extends Error {
    constructor() {
        super("renderer timeout");
        this.name = "DerivedRendererTimeoutError";
    }
}

const failure = (
    kind: DerivedRendererKind,
    identity: DerivedRenderIdentity,
    code: DerivedRenderFailureCode,
    retryable: boolean,
    message: string
): DerivedRenderFailure => ({status: "failure", kind, identity, code, retryable, message});

const validateRequest = (request: DerivedRenderRequest): string | null => {
    if (!request.identity.sourceGeneration.trim() || !request.identity.configGeneration.trim()) {
        return "renderer identity is required";
    }
    const sourceLimit = request.kind === "mermaid" ? MAX_MERMAID_SOURCE_CHARS : MAX_KATEX_SOURCE_CHARS;
    if (request.source.length > sourceLimit) return "renderer source exceeds configured bound";
    if (stableStringify(request.config).length > MAX_CONFIG_CHARS) return "renderer config exceeds configured bound";
    if (containsSecretLikeKey(request.config)) return "renderer config contains a forbidden secret-like key";
    return null;
};

const containsSecretLikeKey = (value: unknown): boolean => {
    if (!value || typeof value !== "object") return false;
    if (Array.isArray(value)) return value.some(containsSecretLikeKey);
    return Object.entries(value as Record<string, unknown>).some(([key, nested]) =>
        SECRET_LIKE_KEY.test(key) || containsSecretLikeKey(nested)
    );
};

const createCacheKey = (request: DerivedRenderRequest): string => [
    request.kind,
    request.identity.sourceGeneration,
    request.identity.configGeneration,
    hashString(request.source),
    hashString(stableStringify(request.config))
].join(":");

const stableStringify = (value: unknown): string => {
    if (Array.isArray(value)) return `[${value.map(stableStringify).join(",")}]`;
    if (value && typeof value === "object") {
        const entries = Object.entries(value as Record<string, unknown>)
            .sort(([left], [right]) => left.localeCompare(right))
            .map(([key, nested]) => `${JSON.stringify(key)}:${stableStringify(nested)}`);
        return `{${entries.join(",")}}`;
    }
    return JSON.stringify(value) ?? "null";
};

const hashString = (value: string): string => {
    let hash = 2166136261;
    for (let index = 0; index < value.length; index += 1) {
        hash ^= value.charCodeAt(index);
        hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(36);
};

const withTimeout = async <T>(promise: Promise<T>, timeoutMs: number, controller: AbortController): Promise<T> => {
    let timeoutId: ReturnType<typeof setTimeout> | undefined;
    const timeout = new Promise<never>((_, reject) => {
        timeoutId = setTimeout(() => {
            reject(new DerivedRendererTimeoutError());
            controller.abort();
        }, timeoutMs);
    });
    try {
        return await Promise.race([promise, timeout]);
    } finally {
        if (timeoutId !== undefined) clearTimeout(timeoutId);
    }
};

const linkAbortSignal = (signal: AbortSignal | undefined, controller: AbortController): (() => void) => {
    if (!signal) return () => {};
    const abort = () => controller.abort();
    signal.addEventListener("abort", abort, {once: true});
    return () => signal.removeEventListener("abort", abort);
};

const isAbortError = (error: unknown): boolean =>
    (typeof DOMException !== "undefined" && error instanceof DOMException)
        ? error.name === "AbortError"
        : error instanceof Error && error.name === "AbortError";

const normalizePositiveInteger = (value: number | undefined, fallback: number): number =>
    Number.isFinite(value) && Number(value) > 0 ? Math.floor(Number(value)) : fallback;

const normalizeNonNegativeInteger = (value: number | undefined, fallback: number): number =>
    Number.isFinite(value) && Number(value) >= 0 ? Math.floor(Number(value)) : fallback;

const SECRET_LIKE_KEY = /(authorization|credential|password|secret|token|cookie)/i;
const MAX_MERMAID_SOURCE_CHARS = 64 * 1024;
const MAX_KATEX_SOURCE_CHARS = 32 * 1024;
const MAX_CONFIG_CHARS = 16 * 1024;
