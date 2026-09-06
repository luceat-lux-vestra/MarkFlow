import {syntaxTree} from "@codemirror/language";
import {StateEffect} from "@codemirror/state";
import type {Range} from "@codemirror/state";
import {Decoration, EditorView, ViewPlugin, WidgetType} from "@codemirror/view";
import type {ViewUpdate} from "@codemirror/view";
import {classifyPreviewUrl} from "./preview-trust-policy.ts";

export interface SourceNativeLocalImageHostBridge {
    __markflowSourceNativeSetLocalImageCapability?: (baseUrl: string | null) => void;
}

const refreshLocalImages = StateEffect.define<void>();
const CAPABILITY_PATH = /^\/__markflow_source_native_image__\/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\/$/iu;

type DecorationSet = ReturnType<typeof Decoration.set>;

class LocalImageWidget extends WidgetType {
    private readonly sourceUrl: string;

    constructor(sourceUrl: string) {
        super();
        this.sourceUrl = sourceUrl;
    }

    eq(other: LocalImageWidget): boolean {
        return this.sourceUrl === other.sourceUrl;
    }

    toDOM(): HTMLElement {
        const image = document.createElement("img");
        image.className = "cm-source-native-local-image";
        image.src = this.sourceUrl;
        image.alt = "Local Markdown image preview";
        image.loading = "lazy";
        image.decoding = "async";
        image.referrerPolicy = "no-referrer";
        image.draggable = false;
        return image;
    }

    ignoreEvent(): boolean {
        return true;
    }
}

function validatedCapabilityBase(raw: string | null): URL | null {
    if (raw === null || raw.length > 512) {
        return null;
    }
    try {
        const parsed = new URL(raw);
        if (parsed.protocol !== "http:"
            || parsed.hostname !== "127.0.0.1"
            || parsed.port.length === 0
            || parsed.username.length > 0
            || parsed.password.length > 0
            || parsed.search.length > 0
            || parsed.hash.length > 0
            || !CAPABILITY_PATH.test(parsed.pathname)) {
            return null;
        }
        return parsed;
    } catch (_error) {
        return null;
    }
}

export function resolveSourceNativeLocalImageUrl(destination: string, capabilityBase: string | null): string | null {
    const classification = classifyPreviewUrl(destination);
    if (classification.kind !== "document-relative"
        || classification.requirement !== "local-resource-capability") {
        return null;
    }

    const base = validatedCapabilityBase(capabilityBase);
    if (base === null) {
        return null;
    }

    try {
        const resolved = new URL(destination, base);
        if (resolved.protocol !== base.protocol
            || resolved.host !== base.host
            || !resolved.pathname.startsWith(base.pathname)) {
            return null;
        }
        return resolved.href;
    } catch (_error) {
        return null;
    }
}

function selectionTouches(state: EditorView["state"], from: number, to: number): boolean {
    return state.selection.ranges.some((selection) => {
        if (selection.empty) {
            return selection.from >= from && selection.from <= to;
        }
        return selection.from < to && selection.to > from;
    });
}

function buildLocalImageDecorations(view: EditorView, capabilityBase: string | null): DecorationSet {
    const ranges: Range<Decoration>[] = [];
    const seen = new Set<string>();
    const tree = syntaxTree(view.state);

    for (const visible of view.visibleRanges) {
        tree.iterate({
            from: visible.from,
            to: visible.to,
            enter: (node) => {
                if (node.name !== "Image" || selectionTouches(view.state, node.from, node.to)) {
                    return;
                }

                const key = `${node.from}:${node.to}`;
                if (seen.has(key)) {
                    return;
                }
                seen.add(key);

                const urlNode = node.node.getChild("URL");
                if (urlNode === null || urlNode.from < node.from || urlNode.to > node.to) {
                    return;
                }
                const destination = view.state.sliceDoc(urlNode.from, urlNode.to);
                const resolved = resolveSourceNativeLocalImageUrl(destination, capabilityBase);
                if (resolved === null) {
                    return;
                }

                ranges.push(
                    Decoration.widget({
                        widget: new LocalImageWidget(resolved),
                        side: 1
                    }).range(node.to)
                );
            }
        });
    }

    return Decoration.set(ranges, true);
}

class SourceNativeLocalImagePreview {
    decorations: DecorationSet;
    private readonly view: EditorView;
    private readonly hostWindow: SourceNativeLocalImageHostBridge;
    private capabilityBase: string | null = null;
    private disposed = false;
    private readonly setter: (baseUrl: string | null) => void;

    constructor(view: EditorView, hostWindow: SourceNativeLocalImageHostBridge) {
        this.view = view;
        this.hostWindow = hostWindow;
        this.setter = (baseUrl) => {
            if (this.disposed) {
                return;
            }
            const validated = validatedCapabilityBase(baseUrl);
            this.capabilityBase = validated?.href ?? null;
            this.view.dispatch({effects: refreshLocalImages.of()});
        };
        this.hostWindow.__markflowSourceNativeSetLocalImageCapability = this.setter;
        this.decorations = buildLocalImageDecorations(view, this.capabilityBase);
    }

    update(update: ViewUpdate): void {
        const refreshRequested = update.transactions.some((transaction) =>
            transaction.effects.some((effect) => effect.is(refreshLocalImages))
        );
        if (update.docChanged || update.selectionSet || update.viewportChanged || refreshRequested) {
            this.decorations = buildLocalImageDecorations(update.view, this.capabilityBase);
        }
    }

    destroy(): void {
        if (this.disposed) {
            return;
        }
        this.disposed = true;
        this.capabilityBase = null;
        if (this.hostWindow.__markflowSourceNativeSetLocalImageCapability === this.setter) {
            this.hostWindow.__markflowSourceNativeSetLocalImageCapability = undefined;
        }
    }
}

const sourceNativeLocalImageTheme = EditorView.baseTheme({
    ".cm-source-native-local-image": {
        display: "block",
        maxWidth: "100%",
        maxHeight: "70vh",
        objectFit: "contain",
        margin: "0.35em 0"
    }
});

export function installSourceNativeLocalImagePreview(
    view: EditorView,
    hostWindow: SourceNativeLocalImageHostBridge
): void {
    const plugin = ViewPlugin.define(
        (currentView) => new SourceNativeLocalImagePreview(currentView, hostWindow),
        {decorations: (instance) => instance.decorations}
    );
    view.dispatch({
        effects: StateEffect.appendConfig.of([plugin, sourceNativeLocalImageTheme])
    });
}
