import type {Editor} from "@milkdown/core";
import type {NodeSchema, MarkdownNode} from "@milkdown/transformer";
import {type NodeViewConstructor} from "@milkdown/prose/view";
import { $node, $view } from "@milkdown/utils";
import {visit} from "unist-util-visit";

export type RawHtmlKind = "inline" | "block";

type RawHtmlNodeData = MarkdownNode & {
    data?: {
        markflowHtmlKind?: RawHtmlKind;
    };
};

type RawHtmlNodeAttrs = {
    html: string;
};

const INLINE_NODE_ID = "markflow-html-inline";
const BLOCK_NODE_ID = "markflow-html-block";
const RAW_HTML_KIND_ATTR = "data-markflow-raw-html-kind";
const RAW_HTML_DATA_ATTR = "data-markflow-raw-html";
const SELECTED_CLASS = "markflow-raw-html--selected";

const INLINE_PARENT_TYPES = new Set([
    "paragraph",
    "heading",
    "emphasis",
    "strong",
    "delete",
    "link",
    "linkReference",
    "tableCell"
]);

const BLOCKED_TAGS = new Set([
    "base",
    "embed",
    "iframe",
    "link",
    "meta",
    "object",
    "script",
    "style",
    "template"
]);

const BLOCK_HTML_TAGS = new Set([
    "address",
    "article",
    "aside",
    "blockquote",
    "caption",
    "colgroup",
    "dd",
    "details",
    "dialog",
    "div",
    "dl",
    "dt",
    "fieldset",
    "figcaption",
    "figure",
    "footer",
    "form",
    "h1",
    "h2",
    "h3",
    "h4",
    "h5",
    "h6",
    "header",
    "hr",
    "html",
    "li",
    "main",
    "nav",
    "ol",
    "p",
    "pre",
    "section",
    "table",
    "tbody",
    "td",
    "tfoot",
    "th",
    "thead",
    "tr",
    "ul"
]);

const SAFE_URL_SCHEMES = /^(?:https?|mailto|tel|sms|ftp|blob):/i;
const SAFE_DATA_URL = /^data:image\/[a-z0-9.+-]+;base64,/i;

const HTML_NODE_ID = "html";
const HTML_NODE_DATA_ATTR = "data-markflow-html-value";
const HTML_NODE_KIND_ATTR = "data-markflow-html-kind";
const HTML_NODE_EDIT_ATTR = "data-markflow-html-editing";
const HTML_EDITING_CLASS = "markflow-html--editing";
const HTML_BLOCK_CLASS = "markflow-html--block";
const HTML_INLINE_CLASS = "markflow-html--inline";
const HTML_PREVIEW_CLASS = "markflow-html__preview";
const HTML_EDITOR_CLASS = "markflow-html__editor";
const HTML_TOOLBAR_CLASS = "markflow-html__toolbar";
const HTML_BUTTON_CLASS = "markflow-html__button";

type HtmlNodeAttrs = {
    value: string;
};

const htmlNodeSchema = $node(HTML_NODE_ID, () => {
    return createHtmlNodeSchema();
});

const htmlNodeView = $view(htmlNodeSchema, () => createHtmlNodeView());

export const createRawHtmlNodeSchema = (kind: RawHtmlKind): NodeSchema => {
    const id = kind === "inline" ? INLINE_NODE_ID : BLOCK_NODE_ID;

    return {
        group: kind === "inline" ? "inline" : "block",
        inline: kind === "inline",
        atom: true,
        selectable: true,
        isolating: kind === "block",
        attrs: {
            html: {
                default: ""
            }
        },
        parseDOM: [
            {
                tag: `${kind === "inline" ? "span" : "div"}[${RAW_HTML_KIND_ATTR}="${kind}"]`,
                getAttrs: (element) => {
                    const dom = element as HTMLElement;
                    return {
                        html: decodeRawHtmlData(dom.getAttribute(RAW_HTML_DATA_ATTR) ?? "")
                    } satisfies RawHtmlNodeAttrs;
                }
            }
        ],
        toDOM: (node) => {
            const html = String((node.attrs as RawHtmlNodeAttrs).html ?? "");
            return [
                kind === "inline" ? "span" : "div",
                {
                    class: `markflow-raw-html markflow-raw-html--${kind}`,
                    [RAW_HTML_KIND_ATTR]: kind,
                    [RAW_HTML_DATA_ATTR]: encodeRawHtmlData(html)
                }
            ];
        },
        parseMarkdown: {
            match: (node) => node.type === "html" && getRawHtmlKind(node) === kind,
            runner: (state, node, proseType) => {
                state.addNode(proseType, {
                    html: String(node.value ?? "")
                } satisfies RawHtmlNodeAttrs);
            }
        },
        toMarkdown: {
            match: (node) => node.type.name === id,
            runner: (state, node) => {
                state.addNode("html", undefined, String((node.attrs as RawHtmlNodeAttrs).html ?? ""));
            }
        }
    };
};

export const createHtmlNodeSchema = (): NodeSchema => {
    return {
        atom: true,
        group: "inline",
        inline: true,
        attrs: {
            value: {
                default: ""
            }
        },
        parseDOM: [
            {
                tag: `span[data-type="${HTML_NODE_ID}"]`,
                getAttrs: (element) => {
                    const dom = element as HTMLElement;
                    return {
                        value: decodeRawHtmlData(dom.getAttribute(HTML_NODE_DATA_ATTR) ?? dom.getAttribute("data-value") ?? "")
                    } satisfies HtmlNodeAttrs;
                }
            }
        ],
        toDOM: (node) => {
            const value = String((node.attrs as HtmlNodeAttrs).value ?? "");
            return [
                "span",
                {
                    "data-type": HTML_NODE_ID,
                    [HTML_NODE_DATA_ATTR]: encodeRawHtmlData(value)
                },
                value
            ];
        },
        parseMarkdown: {
            match: (node) => node.type === HTML_NODE_ID,
            runner: (state, node, proseType) => {
                state.addNode(proseType, {
                    value: String(node.value ?? "")
                } satisfies HtmlNodeAttrs);
            }
        },
        toMarkdown: {
            match: (node) => node.type.name === HTML_NODE_ID,
            runner: (state, node) => {
                state.addNode(HTML_NODE_ID, undefined, String((node.attrs as HtmlNodeAttrs).value ?? ""));
            }
        }
    };
};

export const createHtmlNodeView = (): NodeViewConstructor => {
    return (node, view, getPos) => {
        const dom = document.createElement("span");
        const toolbar = document.createElement("span");
        const preview = document.createElement("span");
        const editor = document.createElement("textarea");
        const toggleButton = document.createElement("button");

        dom.className = `markflow-html ${HTML_INLINE_CLASS}`;
        dom.setAttribute("contenteditable", "false");
        dom.setAttribute(HTML_NODE_KIND_ATTR, inferHtmlDisplayKind(readHtmlNodeValue(node)));

        toolbar.className = HTML_TOOLBAR_CLASS;
        toolbar.setAttribute("contenteditable", "false");

        toggleButton.type = "button";
        toggleButton.className = HTML_BUTTON_CLASS;

        preview.className = HTML_PREVIEW_CLASS;
        preview.setAttribute("contenteditable", "false");

        editor.className = HTML_EDITOR_CLASS;
        editor.spellcheck = false;
        editor.setAttribute("aria-label", "Raw HTML source");

        let currentValue = readHtmlNodeValue(node);
        let editing = false;
        let suppressBlurCommit = false;

        const syncDisplayMode = (value: string) => {
            const displayKind = inferHtmlDisplayKind(value);
            dom.classList.toggle(HTML_BLOCK_CLASS, displayKind === "block");
            dom.classList.toggle(HTML_INLINE_CLASS, displayKind === "inline");
            dom.setAttribute(HTML_NODE_KIND_ATTR, displayKind);
            dom.style.display = displayKind === "block" ? "block" : "inline-block";
        };

        const renderPreview = (value: string) => {
            preview.replaceChildren(sanitizeHtmlFragment(value));
        };

        const showPreview = () => {
            editing = false;
            dom.classList.remove(HTML_EDITING_CLASS);
            dom.setAttribute(HTML_NODE_EDIT_ATTR, "false");
            toggleButton.textContent = "Edit HTML";
            dom.replaceChildren(toolbar, preview);
            syncDisplayMode(currentValue);
            renderPreview(currentValue);
            updateToolbar();
        };

        const showEditor = () => {
            editing = true;
            dom.classList.add(HTML_EDITING_CLASS);
            dom.setAttribute(HTML_NODE_EDIT_ATTR, "true");
            toggleButton.textContent = "Preview HTML";
            editor.value = currentValue;
            dom.replaceChildren(toolbar, editor);
            syncDisplayMode(currentValue);
            queueMicrotask(() => {
                editor.focus();
                editor.select();
            });
            updateToolbar();
        };

        const commit = () => {
            const nextValue = editor.value;
            if (nextValue !== currentValue) {
                const pos = getPos();
                if (typeof pos === "number") {
                    const transaction = view.state.tr.setNodeMarkup(pos, undefined, {
                        ...(node.attrs as HtmlNodeAttrs),
                        value: nextValue
                    });
                    view.dispatch(transaction.scrollIntoView());
                    currentValue = nextValue;
                } else {
                    currentValue = nextValue;
                }
            }
            showPreview();
        };

        toggleButton.addEventListener("click", (event) => {
            event.preventDefault();
            event.stopPropagation();
            if (editing) {
                suppressBlurCommit = true;
                commit();
                suppressBlurCommit = false;
                return;
            }
            showEditor();
        });

        editor.addEventListener("keydown", (event) => {
            if (event.key === "Escape") {
                event.preventDefault();
                suppressBlurCommit = true;
                showPreview();
                suppressBlurCommit = false;
                return;
            }

            if ((event.metaKey || event.ctrlKey) && event.key === "Enter") {
                event.preventDefault();
                commit();
            }
        });

        editor.addEventListener("blur", () => {
            if (!editing || suppressBlurCommit) {
                return;
            }
            commit();
        });

        const updateToolbar = () => {
            toggleButton.setAttribute("aria-pressed", editing ? "true" : "false");
        };

        toggleButton.textContent = "Edit HTML";
        toolbar.append(toggleButton);
        dom.append(toolbar, preview);
        syncDisplayMode(currentValue);
        renderPreview(currentValue);
        updateToolbar();

        dom.addEventListener("dblclick", (event) => {
            event.preventDefault();
            if (!editing) {
                showEditor();
            }
        });

        return {
            dom,
            update: (updatedNode) => {
                if (updatedNode.type !== node.type) {
                    return false;
                }

                node = updatedNode;
                const nextValue = readHtmlNodeValue(updatedNode);
                currentValue = nextValue;
                syncDisplayMode(nextValue);
                if (editing) {
                    editor.value = nextValue;
                } else {
                    renderPreview(nextValue);
                }
                updateToolbar();
                return true;
            },
            selectNode: () => {
                dom.classList.add(SELECTED_CLASS);
            },
            deselectNode: () => {
                dom.classList.remove(SELECTED_CLASS);
            },
            stopEvent: (event) => {
                return event.target instanceof Node ? dom.contains(event.target) : true;
            },
            ignoreMutation: () => true,
            destroy: () => {
                dom.replaceChildren();
            }
        };
    };
};

export const createRawHtmlNodeView = (kind: RawHtmlKind): NodeViewConstructor => {
    return (node) => {
        const dom = document.createElement(kind === "inline" ? "span" : "div");
        dom.className = `markflow-raw-html markflow-raw-html--${kind}`;
        dom.setAttribute("contenteditable", "false");
        dom.setAttribute(RAW_HTML_KIND_ATTR, kind);

        let currentHtml = "";

        const render = (html: string) => {
            currentHtml = html;
            dom.setAttribute(RAW_HTML_DATA_ATTR, encodeRawHtmlData(html));
            dom.replaceChildren(sanitizeHtmlFragment(html));
        };

        render(readRawHtmlNode(node));

        return {
            dom,
            update: (updatedNode) => {
                if (updatedNode.type !== node.type) {
                    return false;
                }

                const nextHtml = readRawHtmlNode(updatedNode);
                if (nextHtml !== currentHtml) {
                    render(nextHtml);
                }

                return true;
            },
            selectNode: () => {
                dom.classList.add(SELECTED_CLASS);
            },
            deselectNode: () => {
                dom.classList.remove(SELECTED_CLASS);
            },
            stopEvent: () => true,
            ignoreMutation: () => true,
            destroy: () => {
                dom.replaceChildren();
            }
        };
    };
};

export const rawHtmlMarkdownFeature = (editor: Editor) => {
    editor.use([
        htmlNodeSchema,
        htmlNodeView
    ]);
};

export const rawHtmlMarkdownPlugins = [
    htmlNodeSchema,
    htmlNodeView
];

export const annotateRawHtmlKinds = (tree: MarkdownNode): MarkdownNode => {
    visit(tree as MarkdownNode, (node: MarkdownNode, _index: number | null | undefined, parent: MarkdownNode | null | undefined) => {
        if (node.type !== "html" || typeof (node as {value?: unknown}).value !== "string") {
            return;
        }

        ensureNodeData(node as RawHtmlNodeData).markflowHtmlKind = inferRawHtmlKind(parent, String((node as {value?: unknown}).value ?? ""));
    });

    return tree;
};

export const inferRawHtmlKind = (parent: MarkdownNode | null | undefined, rawHtml: string): RawHtmlKind => {
    const parentType = parent?.type;
    if (parentType && INLINE_PARENT_TYPES.has(parentType)) {
        if (containsBlockHtmlMarkup(rawHtml)) {
            return "block";
        }
        return "inline";
    }

    return containsBlockHtmlMarkup(rawHtml) ? "block" : "inline";
};

export const encodeRawHtmlData = (rawHtml: string): string => {
    return encodeURIComponent(rawHtml);
};

export const decodeRawHtmlData = (encodedHtml: string): string => {
    if (!encodedHtml) {
        return "";
    }

    try {
        return decodeURIComponent(encodedHtml);
    } catch {
        return encodedHtml;
    }
};

export const readRawHtmlNode = (node: {attrs?: Partial<RawHtmlNodeAttrs>}): string => {
    return String(node.attrs?.html ?? "");
};

export const readHtmlNodeValue = (node: {attrs?: Partial<HtmlNodeAttrs> | Partial<RawHtmlNodeAttrs>}): string => {
    const attrs = node.attrs ?? {};
    if ("value" in attrs) {
        return String((attrs as Partial<HtmlNodeAttrs>).value ?? "");
    }

    if ("html" in attrs) {
        return String((attrs as Partial<RawHtmlNodeAttrs>).html ?? "");
    }

    return "";
};

export const inferHtmlDisplayKind = (rawHtml: string): RawHtmlKind => {
    return containsBlockHtmlMarkup(rawHtml) ? "block" : "inline";
};

export const sanitizeHtmlFragment = (html: string): DocumentFragment => {
    const template = document.createElement("template");
    template.innerHTML = html;

    const fragment = document.createDocumentFragment();
    for (const child of Array.from(template.content.childNodes)) {
        const sanitized = sanitizeDomNode(child);
        if (sanitized) {
            fragment.append(sanitized);
        }
    }

    return fragment;
};

export const sanitizeRawHtmlUrl = (value: string): string | null => {
    const normalized = value.trim();
    if (!normalized) {
        return null;
    }

    if (
        normalized.startsWith("#") ||
        normalized.startsWith("/") ||
        normalized.startsWith("./") ||
        normalized.startsWith("../") ||
        normalized.startsWith("//")
    ) {
        return normalized;
    }

    if (SAFE_URL_SCHEMES.test(normalized) || SAFE_DATA_URL.test(normalized)) {
        return normalized;
    }

    return null;
};

const sanitizeDomNode = (node: ChildNode): Node | null => {
    if (node.nodeType === Node.TEXT_NODE) {
        return document.createTextNode(node.textContent ?? "");
    }

    if (node.nodeType !== Node.ELEMENT_NODE) {
        return null;
    }

    const element = node as HTMLElement;
    const tagName = element.tagName.toLowerCase();

    if (BLOCKED_TAGS.has(tagName)) {
        return null;
    }

    const copy = document.createElement(tagName);

    for (const {name, value} of Array.from(element.attributes)) {
        const normalizedName = name.toLowerCase();

        if (normalizedName.startsWith("on") || normalizedName === "style" || normalizedName === "srcdoc") {
            continue;
        }

        if (normalizedName === "srcset") {
            continue;
        }

        if (
            normalizedName === "href" ||
            normalizedName === "src" ||
            normalizedName === "xlink:href" ||
            normalizedName === "action" ||
            normalizedName === "formaction" ||
            normalizedName === "poster" ||
            normalizedName === "cite" ||
            normalizedName === "background" ||
            normalizedName === "data" ||
            normalizedName === "ping"
        ) {
            const safeUrl = sanitizeRawHtmlUrl(value);
            if (!safeUrl) {
                continue;
            }

            copy.setAttribute(name, safeUrl);
            continue;
        }

        copy.setAttribute(name, value);
    }

    if (tagName === "a" && copy.getAttribute("target") === "_blank" && !copy.getAttribute("rel")) {
        copy.setAttribute("rel", "noreferrer noopener");
    }

    for (const child of Array.from(element.childNodes)) {
        const sanitizedChild = sanitizeDomNode(child);
        if (sanitizedChild) {
            copy.append(sanitizedChild);
        }
    }

    return copy;
};

const ensureNodeData = (node: RawHtmlNodeData): NonNullable<RawHtmlNodeData["data"]> => {
    const data = node.data ?? {};
    node.data = data;
    return data;
};

const getRawHtmlKind = (node: MarkdownNode): RawHtmlKind => {
    const typedNode = node as RawHtmlNodeData;
    const rawHtml = String((typedNode as {value?: unknown}).value ?? "");
    const explicitKind = typedNode.data?.markflowHtmlKind;
    if (explicitKind) {
        return explicitKind;
    }

    return inferRawHtmlKind(undefined, rawHtml);
};

const containsBlockHtmlMarkup = (rawHtml: string): boolean => {
    if (/\n/.test(rawHtml)) {
        return true;
    }

    const normalized = rawHtml.trim().toLowerCase();
    if (!normalized) {
        return false;
    }

    return Array.from(BLOCK_HTML_TAGS).some((tag) => {
        return normalized.startsWith(`<${tag}`) || normalized.includes(`</${tag}>`) || normalized.includes(`<${tag} `);
    });
};
