import "katex/dist/katex.min.css";
import {createBrowserDerivedRendererService} from "../app/browser-derived-renderer-backends";
import type {DerivedRenderResult, DerivedRendererKind} from "../app/derived-renderer-service";

const rendererService = createBrowserDerivedRendererService();
const activeControllers = new Map<string, AbortController>();

const MAX_CAPTURE_DIMENSION = 4096;
const MAX_CAPTURE_PIXELS = 8 * 1024 * 1024;

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

type PresentationCapture = {
    mediaType: "image/png";
    contentBase64: string;
    width: number;
    height: number;
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

const emitResult = async (
    request: WireRequest,
    config: Readonly<Record<string, unknown>>,
    result: DerivedRenderResult
) => {
    if (result.status === "success") {
        const presentation = await captureForNativePresentation(request.kind, config, result).catch(() => null);
        send({
            type: "result",
            requestId: request.requestId,
            status: "success",
            kind: result.kind,
            sourceGeneration: result.identity.sourceGeneration,
            configGeneration: result.identity.configGeneration,
            mediaType: result.mediaType,
            content: result.content,
            presentationMediaType: presentation?.mediaType ?? null,
            presentationContentBase64: presentation?.contentBase64 ?? null,
            presentationWidth: presentation?.width ?? null,
            presentationHeight: presentation?.height ?? null
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

const captureForNativePresentation = async (
    kind: DerivedRendererKind,
    config: Readonly<Record<string, unknown>>,
    result: Extract<DerivedRenderResult, {status: "success"}>
): Promise<PresentationCapture> => {
    if (kind === "mermaid" && result.mediaType === "image/svg+xml") {
        return captureSvg(result.content);
    }
    if (kind === "katex" && result.mediaType === "text/html") {
        return captureKatex(result.content, config);
    }
    throw new Error("unsupported native presentation artifact");
};

const captureSvg = async (source: string): Promise<PresentationCapture> => {
    const parsed = new DOMParser().parseFromString(source, "image/svg+xml");
    const svg = parsed.documentElement;
    if (svg.localName !== "svg" || parsed.querySelector("parsererror")) {
        throw new Error("renderer SVG could not be parsed");
    }

    const viewBox = parseViewBox(svg.getAttribute("viewBox"));
    const rawWidth = parsePositiveDimension(svg.getAttribute("width")) ?? viewBox?.width ?? 1;
    const rawHeight = parsePositiveDimension(svg.getAttribute("height")) ?? viewBox?.height ?? 1;
    const dimensions = boundedDimensions(rawWidth, rawHeight);
    svg.setAttribute("width", String(dimensions.width));
    svg.setAttribute("height", String(dimensions.height));

    const serialized = new XMLSerializer().serializeToString(svg);
    return rasterizeSvg(serialized, dimensions.width, dimensions.height);
};

const captureKatex = async (
    source: string,
    config: Readonly<Record<string, unknown>>
): Promise<PresentationCapture> => {
    const wrapper = document.createElement("div");
    wrapper.style.position = "fixed";
    wrapper.style.left = "-100000px";
    wrapper.style.top = "0";
    wrapper.style.display = "inline-block";
    wrapper.style.width = "max-content";
    wrapper.style.maxWidth = "none";
    wrapper.style.background = "transparent";
    wrapper.style.color = "currentColor";
    const baseFontSizePx = finiteNumber(config.baseFontSizePx);
    if (baseFontSizePx != null) wrapper.style.fontSize = `${Math.min(Math.max(baseFontSizePx, 8), 96)}px`;
    wrapper.innerHTML = source;

    if (config.displayDensity === "COMPACT") {
        wrapper.querySelector<HTMLElement>(".katex-display")?.style.setProperty("margin", "0.6em 0");
        wrapper.querySelector<HTMLElement>(".katex")?.style.setProperty("line-height", "1.1");
    }

    document.body.appendChild(wrapper);
    try {
        await document.fonts.ready;
        await nextAnimationFrame();
        const rect = wrapper.getBoundingClientRect();
        if (!(rect.width > 0) || !(rect.height > 0)) {
            throw new Error("KaTeX capture has no layout bounds");
        }
        const dimensions = boundedDimensions(rect.width, rect.height);
        const styledClone = cloneWithComputedStyles(wrapper);
        styledClone.style.position = "static";
        styledClone.style.left = "auto";
        styledClone.style.top = "auto";
        styledClone.style.margin = "0";
        styledClone.style.width = `${rect.width}px`;
        styledClone.style.height = `${rect.height}px`;
        const xhtml = new XMLSerializer().serializeToString(styledClone);
        const foreignObjectSvg = [
            `<svg xmlns="http://www.w3.org/2000/svg" width="${dimensions.width}" height="${dimensions.height}" viewBox="0 0 ${rect.width} ${rect.height}">`,
            `<foreignObject x="0" y="0" width="${rect.width}" height="${rect.height}">`,
            xhtml,
            "</foreignObject></svg>"
        ].join("");
        return rasterizeSvg(foreignObjectSvg, dimensions.width, dimensions.height);
    } finally {
        wrapper.remove();
    }
};

const cloneWithComputedStyles = (source: HTMLElement): HTMLElement => {
    const clone = source.cloneNode(false) as HTMLElement;
    copyComputedStyle(source, clone);
    for (const child of source.childNodes) {
        if (child instanceof HTMLElement) {
            clone.appendChild(cloneWithComputedStyles(child));
        } else if (child instanceof SVGElement) {
            clone.appendChild(child.cloneNode(true));
        } else {
            clone.appendChild(child.cloneNode(true));
        }
    }
    return clone;
};

const copyComputedStyle = (source: Element, target: HTMLElement) => {
    const style = getComputedStyle(source);
    for (const property of style) {
        target.style.setProperty(property, style.getPropertyValue(property), style.getPropertyPriority(property));
    }
};

const rasterizeSvg = async (source: string, width: number, height: number): Promise<PresentationCapture> => {
    const image = new Image();
    image.decoding = "sync";
    image.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(source)}`;
    await loadImage(image);

    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext("2d", {alpha: true});
    if (!context) throw new Error("2D capture context unavailable");
    context.clearRect(0, 0, width, height);
    context.drawImage(image, 0, 0, width, height);
    const dataUrl = canvas.toDataURL("image/png");
    const comma = dataUrl.indexOf(",");
    if (comma < 0) throw new Error("PNG capture encoding failed");
    return {
        mediaType: "image/png",
        contentBase64: dataUrl.slice(comma + 1),
        width,
        height
    };
};

const loadImage = (image: HTMLImageElement): Promise<void> =>
    new Promise((resolve, reject) => {
        image.onload = () => resolve();
        image.onerror = () => reject(new Error("native presentation image load failed"));
    });

const nextAnimationFrame = (): Promise<void> =>
    new Promise((resolve) => requestAnimationFrame(() => resolve()));

const parsePositiveDimension = (raw: string | null): number | null => {
    if (!raw || raw.trim().endsWith("%")) return null;
    const parsed = Number.parseFloat(raw);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
};

const parseViewBox = (raw: string | null): {width: number; height: number} | null => {
    if (!raw) return null;
    const values = raw.trim().split(/[\s,]+/).map(Number);
    if (values.length !== 4 || values.some((value) => !Number.isFinite(value))) return null;
    const width = values[2];
    const height = values[3];
    return width > 0 && height > 0 ? {width, height} : null;
};

const finiteNumber = (value: unknown): number | null =>
    typeof value === "number" && Number.isFinite(value) ? value : null;

const boundedDimensions = (rawWidth: number, rawHeight: number): {width: number; height: number} => {
    let width = Math.max(1, rawWidth);
    let height = Math.max(1, rawHeight);
    const dimensionScale = Math.min(1, MAX_CAPTURE_DIMENSION / width, MAX_CAPTURE_DIMENSION / height);
    width *= dimensionScale;
    height *= dimensionScale;
    const pixelScale = Math.min(1, Math.sqrt(MAX_CAPTURE_PIXELS / (width * height)));
    width *= pixelScale;
    height *= pixelScale;
    return {
        width: Math.max(1, Math.ceil(width)),
        height: Math.max(1, Math.ceil(height))
    };
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
    }).then(async (result) => {
        if (activeControllers.get(request.requestId) !== controller) return;
        await emitResult(request, config, result);
        if (activeControllers.get(request.requestId) !== controller) return;
        activeControllers.delete(request.requestId);
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
