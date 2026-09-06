import {syntaxTree} from "@codemirror/language";
import {StateEffect} from "@codemirror/state";
import type {Range} from "@codemirror/state";
import {Decoration, EditorView, ViewPlugin, WidgetType} from "@codemirror/view";
import type {ViewUpdate} from "@codemirror/view";
import {resolveSourceNativeLocalImageUrl} from "./source-native-local-image-capability.ts";

type DecorationSet = ReturnType<typeof Decoration.set>;

class SourceNativeLocalImageWidget extends WidgetType {
    private readonly sourceUrl: string;

    constructor(sourceUrl: string) {
        super();
        this.sourceUrl = sourceUrl;
    }

    eq(other: SourceNativeLocalImageWidget): boolean {
        return this.sourceUrl === other.sourceUrl;
    }

    toDOM(): HTMLElement {
        const image = document.createElement("img");
        image.className = "cm-source-native-local-image";
        image.src = this.sourceUrl;
        image.alt = "";
        image.setAttribute("aria-hidden", "true");
        image.loading = "lazy";
        image.decoding = "async";
        image.referrerPolicy = "no-referrer";
        image.draggable = false;
        image.addEventListener("error", () => {
            // The Markdown source remains visible. Hide only the failed presentation projection.
            image.hidden = true;
        }, {once: true});
        return image;
    }

    ignoreEvent(): boolean {
        return true;
    }
}

function selectionTouchesImage(view: EditorView, from: number, to: number): boolean {
    return view.state.selection.ranges.some((selection) => {
        if (selection.empty) {
            // Both source boundaries are editing positions and therefore reveal exact Markdown.
            return selection.from >= from && selection.from <= to;
        }
        return selection.from < to && selection.to > from;
    });
}

function buildLocalImageDecorations(view: EditorView, capabilityBaseUrl: string | null): DecorationSet {
    const ranges: Range<Decoration>[] = [];
    const seen = new Set<string>();
    const tree = syntaxTree(view.state);

    for (const visible of view.visibleRanges) {
        tree.iterate({
            from: visible.from,
            to: visible.to,
            enter: (node) => {
                if (node.name !== "Image" || selectionTouchesImage(view, node.from, node.to)) {
                    return;
                }

                const identity = `${node.from}:${node.to}`;
                if (seen.has(identity)) {
                    return;
                }
                seen.add(identity);

                const urlNode = node.node.getChild("URL");
                if (urlNode === null || urlNode.from < node.from || urlNode.to > node.to) {
                    return;
                }

                const destination = view.state.sliceDoc(urlNode.from, urlNode.to);
                const resolved = resolveSourceNativeLocalImageUrl(destination, capabilityBaseUrl);
                if (resolved === null) {
                    return;
                }

                ranges.push(
                    Decoration.widget({
                        widget: new SourceNativeLocalImageWidget(resolved),
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
    private readonly capabilityBaseUrl: string | null;

    constructor(view: EditorView, capabilityBaseUrl: string | null) {
        this.capabilityBaseUrl = capabilityBaseUrl;
        this.decorations = buildLocalImageDecorations(view, capabilityBaseUrl);
    }

    update(update: ViewUpdate): void {
        if (update.docChanged || update.selectionSet || update.viewportChanged) {
            this.decorations = buildLocalImageDecorations(update.view, this.capabilityBaseUrl);
        }
    }
}

const sourceNativeLocalImageTheme = EditorView.baseTheme({
    ".cm-source-native-local-image": {
        display: "inline-block",
        maxWidth: "100%",
        maxHeight: "70vh",
        objectFit: "contain",
        verticalAlign: "middle",
        marginInlineStart: "0.4em"
    }
});

/**
 * Install the presentation-only consumer for one already-configured source-native realm.
 *
 * The host capability value is immutable for that runtime lifetime (#123/#124), so the extension
 * captures it once. A null/invalid capability simply produces source-only degraded behavior.
 */
export function installSourceNativeLocalImagePreview(
    view: EditorView,
    capabilityBaseUrl: string | null
): void {
    const plugin = ViewPlugin.define(
        (currentView) => new SourceNativeLocalImagePreview(currentView, capabilityBaseUrl),
        {decorations: (instance) => instance.decorations}
    );
    view.dispatch({
        effects: StateEffect.appendConfig.of([plugin, sourceNativeLocalImageTheme])
    });
}
