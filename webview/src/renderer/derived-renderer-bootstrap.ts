import {createBrowserDerivedRendererService} from "../app/browser-derived-renderer-backends";
import type {DerivedRenderResult, DerivedRendererKind} from "../app/derived-renderer-service";

const rendererService = createBrowserDerivedRendererService();
const activeControllers = new Map<string, AbortController>();

type HostWindow = Window & {
    __markflowDerivedRendererHostReady?: () => void;
    __markflowDerivedRendererReady?: () => void;
    __markflowDerivedRendererResult?: (raw: string) => void;
    __markflowDerivedRendererRenderBase64?: (encoded: string) => void;
    __markflowDerivedRendererCancel?: (requestId: string) => void;
};

type WireRequest = {
    requestId: string;
    kind: DerivedRendererKind;
    source: string;
    configJson: string;
    sourceGeneration: string;
    configGeneration: string;
};

const host = window as HostWindow;

const send = (payload: Record<string, unknown>) => {
    host.__markflowDerivedRendererResult?.(JSON.stringify(payload));
};

const failure = (request: Partial<WireRequest>, code: string, retryable: boolean, message: string) => {
    send({
        type: "failure",
        requestId: request.requestId ?? "",
        status: "failure",
        kind: request.kind ?? null,
        sourceGeneration: request.sourceGeneration ?? "",
        configGeneration: request.configGeneration ?? "",
        code,
        retryable,
        message
    });
};

const emitResult = (request: WireRequest, result: DerivedRenderResult) => {
    if (result.status === "success") {
        send({
            type: "result",
            requestId: request.requestId,
            status: "success",
            kind: result.kind,
            sourceGeneration: result.identity.sourceGeneration,
            configGeneration: result.identity.configGeneration,
            mediaType: result.mediaType,
            content: result.content
        });
        return;
    }

    send({
        type: "failure",
        requestId: request.requestId,
        status: "failure",
        kind: result.kind,
        sourceGeneration: result.identity.sourceGeneration,
        configGeneration: result.identity.configGeneration,
        code: result.code,
        retryable: result.retryable,
        message: result.message
    });
};

const decodeWireRequest = (encoded: string): WireRequest => {
    const bytes = Uint8Array.from(atob(encoded), (value) => value.charCodeAt(0));
    const raw = new TextDecoder().decode(bytes);
    const parsed = JSON.parse(raw) as Partial<WireRequest>;
    if (!parsed.requestId || (parsed.kind !== "mermaid" && parsed.kind !== "katex")) {
        throw new Error("invalid renderer request envelope");
    }
    if (typeof parsed.source !== "string" || typeof parsed.configJson !== "string") {
        throw new Error("invalid renderer request payload");
    }
    if (!parsed.sourceGeneration || !parsed.configGeneration) {
        throw new Error("invalid renderer request identity");
    }
    return parsed as WireRequest;
};

host.__markflowDerivedRendererRenderBase64 = (encoded: string) => {
    let request: WireRequest;
    try {
        request = decodeWireRequest(encoded);
    } catch {
        failure({}, "INVALID_REQUEST", false, "renderer request envelope was rejected");
        return;
    }

    let config: Readonly<Record<string, unknown>>;
    try {
        const parsed = JSON.parse(request.configJson) as unknown;
        if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
            throw new Error("renderer config must be an object");
        }
        config = parsed as Readonly<Record<string, unknown>>;
    } catch {
        failure(request, "INVALID_REQUEST", false, "renderer config was rejected");
        return;
    }

    const previous = activeControllers.get(request.requestId);
    previous?.abort();
    const controller = new AbortController();
    activeControllers.set(request.requestId, controller);

    void rendererService.render({
        kind: request.kind,
        source: request.source,
        config,
        identity: {
            sourceGeneration: request.sourceGeneration,
            configGeneration: request.configGeneration
        },
        signal: controller.signal
    }).then((result) => {
        if (activeControllers.get(request.requestId) !== controller) return;
        activeControllers.delete(request.requestId);
        emitResult(request, result);
    }).catch(() => {
        if (activeControllers.get(request.requestId) !== controller) return;
        activeControllers.delete(request.requestId);
        failure(request, "RENDER_FAILED", true, "renderer request failed");
    });
};

host.__markflowDerivedRendererCancel = (requestId: string) => {
    activeControllers.get(requestId)?.abort();
    activeControllers.delete(requestId);
};

host.__markflowDerivedRendererHostReady = () => {
    host.__markflowDerivedRendererReady?.();
};

window.addEventListener("beforeunload", () => {
    for (const controller of activeControllers.values()) controller.abort();
    activeControllers.clear();
    rendererService.dispose();
});
