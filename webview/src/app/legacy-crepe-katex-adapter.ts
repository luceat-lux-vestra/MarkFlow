import type {Crepe} from "@milkdown/crepe";
import type {DerivedRenderIdentity, DerivedRendererService} from "./derived-renderer-service";
import {katexDerivedRenderConfig} from "./browser-derived-renderer-backends";

type ApplyPreview = (value: null | string | HTMLElement) => void;
type CodeBlockConfigLike = {
    renderPreview: (
        language: string,
        content: string,
        applyPreview: ApplyPreview
    ) => void | null | string | HTMLElement;
};
type MathNodeLike = {
    type: {name: string};
    attrs: Record<string, unknown>;
};
type NodeViewLike = {
    dom: HTMLElement;
    update?: (node: MathNodeLike) => boolean;
    destroy?: () => void;
};
type NodeViewConstructorLike = (node: MathNodeLike) => NodeViewLike;
type NodeViewEntry = [string, NodeViewConstructorLike];
type PendingRender = {controller: AbortController; identity: DerivedRenderIdentity};

export const installLegacyCrepeKatexAdapter = (
    crepe: Crepe,
    rendererService: DerivedRendererService
): (() => void) => {
    const pendingBlockRenders = new Set<AbortController>();
    const pendingInlineRenders = new Set<AbortController>();
    const blockGenerationByPreview = new WeakMap<ApplyPreview, number>();

    crepe.editor.config((ctx) => {
        ctx.update<CodeBlockConfigLike, "codeBlockConfigCtx">("codeBlockConfigCtx", (previous) => {
            const previousRenderPreview = previous.renderPreview;
            return {
                ...previous,
                renderPreview: (language, content, applyPreview) => {
                    if (language.trim().toLowerCase() !== "latex" || content.length === 0) {
                        return previousRenderPreview(language, content, applyPreview);
                    }

                    const nextGeneration = (blockGenerationByPreview.get(applyPreview) ?? 0) + 1;
                    blockGenerationByPreview.set(applyPreview, nextGeneration);
                    const controller = new AbortController();
                    pendingBlockRenders.add(controller);
                    const identity: DerivedRenderIdentity = {
                        sourceGeneration: `legacy-block:${nextGeneration}`,
                        configGeneration: "katex:block:v1"
                    };

                    void rendererService.render({
                        kind: "katex",
                        source: content,
                        config: katexDerivedRenderConfig(true),
                        identity,
                        signal: controller.signal
                    }).then((result) => {
                        if (controller.signal.aborted) return;
                        if (blockGenerationByPreview.get(applyPreview) !== nextGeneration) return;
                        if (!sameIdentity(result.identity, identity)) return;
                        applyPreview(result.status === "success" ? result.content : "");
                    }).finally(() => {
                        pendingBlockRenders.delete(controller);
                    });
                    return null;
                }
            };
        });

        ctx.update<NodeViewEntry[], "nodeView">("nodeView", (previous) => [
            ...previous.filter(([nodeId]) => nodeId !== MATH_INLINE_ID),
            [MATH_INLINE_ID, createInlineMathNodeView(rendererService, pendingInlineRenders)]
        ]);
    });

    return () => {
        for (const controller of pendingBlockRenders) controller.abort();
        for (const controller of pendingInlineRenders) controller.abort();
        pendingBlockRenders.clear();
        pendingInlineRenders.clear();
    };
};

const createInlineMathNodeView = (
    rendererService: DerivedRendererService,
    pendingInlineRenders: Set<AbortController>
): NodeViewConstructorLike => (initialNode) => {
    const dom = document.createElement("span");
    dom.dataset.type = MATH_INLINE_ID;

    let destroyed = false;
    let generation = 0;
    let pending: PendingRender | null = null;

    const render = (node: MathNodeLike) => {
        pending?.controller.abort();
        if (pending) pendingInlineRenders.delete(pending.controller);

        const source = String(node.attrs.value ?? "");
        dom.dataset.value = source;
        const controller = new AbortController();
        pendingInlineRenders.add(controller);
        const identity: DerivedRenderIdentity = {
            sourceGeneration: `legacy-inline:${++generation}`,
            configGeneration: "katex:inline:v1"
        };
        pending = {controller, identity};

        void rendererService.render({
            kind: "katex",
            source,
            config: katexDerivedRenderConfig(false),
            identity,
            signal: controller.signal
        }).then((result) => {
            if (destroyed || controller.signal.aborted || pending?.controller !== controller) return;
            if (!sameIdentity(result.identity, identity)) return;
            dom.innerHTML = result.status === "success" ? result.content : "";
        }).finally(() => {
            pendingInlineRenders.delete(controller);
            if (pending?.controller === controller) pending = null;
        });
    };

    render(initialNode);
    return {
        dom,
        update: (node) => {
            if (node.type.name !== MATH_INLINE_ID) return false;
            render(node);
            return true;
        },
        destroy: () => {
            destroyed = true;
            pending?.controller.abort();
            if (pending) pendingInlineRenders.delete(pending.controller);
            pending = null;
        }
    };
};

const sameIdentity = (left: DerivedRenderIdentity, right: DerivedRenderIdentity): boolean =>
    left.sourceGeneration === right.sourceGeneration && left.configGeneration === right.configGeneration;

const MATH_INLINE_ID = "math_inline";
