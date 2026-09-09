import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import test from "node:test";
import ts from "typescript";

const sourceUrl = new URL("../src/app/derived-renderer-service.ts", import.meta.url);
const source = await readFile(sourceUrl, "utf8");
const transpiled = ts.transpileModule(source, {
    compilerOptions: {
        target: ts.ScriptTarget.ES2023,
        module: ts.ModuleKind.ESNext,
        strict: true
    },
    fileName: "derived-renderer-service.ts"
}).outputText;
const moduleUrl = `data:text/javascript;base64,${Buffer.from(transpiled).toString("base64")}`;
const {
    DerivedRendererBackendUnavailableError,
    DerivedRendererService
} = await import(moduleUrl);

const identity = (sourceGeneration = "s1", configGeneration = "c1") => ({sourceGeneration, configGeneration});
const success = (content = "<svg/>", mediaType = "image/svg+xml") => async () => ({content, mediaType});
const service = (overrides = {}) => new DerivedRendererService({
    backends: {
        mermaid: {render: success()},
        katex: {render: success("<span>math</span>", "text/html")}
    },
    timeoutMs: {mermaid: 30, katex: 30},
    maxRetries: {mermaid: 1, katex: 0},
    maxCacheEntries: 4,
    ...overrides
});

const render = (instance, overrides = {}) => instance.render({
    kind: "mermaid",
    source: "graph TD; A-->B",
    config: {theme: "default"},
    identity: identity(),
    ...overrides
});

test("returns inert artifact with explicit identity", async () => {
    const instance = service();
    const result = await render(instance);
    assert.equal(result.status, "success");
    assert.equal(result.mediaType, "image/svg+xml");
    assert.deepEqual(result.identity, identity());
    instance.dispose();
});

test("supports KaTeX artifact through the same contract", async () => {
    const instance = service();
    const result = await render(instance, {
        kind: "katex",
        source: "x^2",
        config: {displayMode: false},
        identity: identity("math-1", "inline-1")
    });
    assert.equal(result.status, "success");
    assert.equal(result.mediaType, "text/html");
    assert.equal(result.content, "<span>math</span>");
    instance.dispose();
});

test("rejects oversized Mermaid source before backend execution", async () => {
    let calls = 0;
    const instance = service({backends: {
        mermaid: {render: async () => { calls += 1; return {content: "x", mediaType: "image/svg+xml"}; }},
        katex: {render: success("x", "text/html")}
    }});
    const result = await render(instance, {source: "m".repeat(64 * 1024 + 1)});
    assert.equal(result.status, "failure");
    assert.equal(result.code, "INVALID_REQUEST");
    assert.equal(calls, 0);
    instance.dispose();
});

test("rejects oversized KaTeX source before backend execution", async () => {
    const instance = service();
    const result = await render(instance, {
        kind: "katex",
        source: "x".repeat(32 * 1024 + 1),
        config: {displayMode: true},
        identity: identity("math-big", "display")
    });
    assert.equal(result.status, "failure");
    assert.equal(result.code, "INVALID_REQUEST");
    instance.dispose();
});

test("rejects secret-like renderer config", async () => {
    const instance = service();
    const result = await render(instance, {config: {nested: {authorizationToken: "do-not-pass"}}});
    assert.equal(result.status, "failure");
    assert.equal(result.code, "INVALID_REQUEST");
    assert.ok(!result.message.includes("do-not-pass"));
    instance.dispose();
});

test("Mermaid timeout retries exactly once and can recover", async () => {
    let calls = 0;
    const instance = service({
        backends: {
            mermaid: {render: async ({signal}) => {
                calls += 1;
                if (calls === 1) {
                    await new Promise((resolve, reject) => {
                        signal.addEventListener("abort", () => {
                            const error = new Error("aborted");
                            error.name = "AbortError";
                            reject(error);
                        }, {once: true});
                    });
                }
                return {content: "<svg>recovered</svg>", mediaType: "image/svg+xml"};
            }},
            katex: {render: success("x", "text/html")}
        },
        timeoutMs: {mermaid: 5},
        maxRetries: {mermaid: 1}
    });
    const result = await render(instance);
    assert.equal(result.status, "success");
    assert.equal(calls, 2);
    instance.dispose();
});

test("retry exhaustion returns typed timeout", async () => {
    let calls = 0;
    const instance = service({
        backends: {
            mermaid: {render: async () => {
                calls += 1;
                await new Promise(() => {});
            }},
            katex: {render: success("x", "text/html")}
        },
        timeoutMs: {mermaid: 5},
        maxRetries: {mermaid: 1}
    });
    const result = await render(instance);
    assert.equal(result.status, "failure");
    assert.equal(result.code, "TIMEOUT");
    assert.equal(result.retryable, true);
    assert.equal(calls, 2);
    instance.dispose();
});

test("backend unavailable is typed and redacted", async () => {
    const secretSource = "graph TD; userSecret-->B";
    const instance = service({backends: {
        mermaid: {render: async () => { throw new DerivedRendererBackendUnavailableError(secretSource); }},
        katex: {render: success("x", "text/html")}
    }});
    const result = await render(instance, {source: secretSource});
    assert.equal(result.status, "failure");
    assert.equal(result.code, "BACKEND_UNAVAILABLE");
    assert.equal(result.retryable, true);
    assert.ok(!result.message.includes("userSecret"));
    instance.dispose();
});

test("later render recovers after a backend failure", async () => {
    let fail = true;
    const instance = service({backends: {
        mermaid: {render: async () => {
            if (fail) throw new Error("first failure includes source");
            return {content: "<svg>later</svg>", mediaType: "image/svg+xml"};
        }},
        katex: {render: success("x", "text/html")}
    }});
    const first = await render(instance);
    assert.equal(first.status, "failure");
    assert.equal(first.code, "RENDER_FAILED");
    fail = false;
    const second = await render(instance, {identity: identity("s2", "c1")});
    assert.equal(second.status, "success");
    instance.dispose();
});

test("external cancellation makes pending work inert", async () => {
    const instance = service({backends: {
        mermaid: {render: async ({signal}) => new Promise((resolve, reject) => {
            const timer = setTimeout(() => resolve({content: "<svg>late</svg>", mediaType: "image/svg+xml"}), 30);
            signal.addEventListener("abort", () => {
                clearTimeout(timer);
                const error = new Error("abort");
                error.name = "AbortError";
                reject(error);
            }, {once: true});
        })},
        katex: {render: success("x", "text/html")}
    }});
    const controller = new AbortController();
    const pending = render(instance, {signal: controller.signal});
    controller.abort();
    const result = await pending;
    assert.equal(result.status, "failure");
    assert.equal(result.code, "CANCELLED");
    instance.dispose();
});

test("dispose cancels active work and rejects future work", async () => {
    const instance = service({backends: {
        mermaid: {render: async ({signal}) => new Promise((_, reject) => {
            signal.addEventListener("abort", () => {
                const error = new Error("abort");
                error.name = "AbortError";
                reject(error);
            }, {once: true});
        })},
        katex: {render: success("x", "text/html")}
    }});
    const pending = render(instance);
    instance.dispose();
    const active = await pending;
    assert.equal(active.status, "failure");
    assert.equal(active.code, "DISPOSED");
    const future = await render(instance, {identity: identity("future", "future")});
    assert.equal(future.status, "failure");
    assert.equal(future.code, "DISPOSED");
});

test("same source/config identity reuses bounded cache", async () => {
    let calls = 0;
    const instance = service({backends: {
        mermaid: {render: async () => {
            calls += 1;
            return {content: `<svg>${calls}</svg>`, mediaType: "image/svg+xml"};
        }},
        katex: {render: success("x", "text/html")}
    }});
    const first = await render(instance);
    const second = await render(instance);
    assert.equal(first.status, "success");
    assert.equal(second.status, "success");
    assert.equal(calls, 1);
    instance.dispose();
});

test("caller generation collision cannot reuse changed source", async () => {
    let calls = 0;
    const instance = service({backends: {
        mermaid: {render: async ({source}) => {
            calls += 1;
            return {content: `<svg>${source}</svg>`, mediaType: "image/svg+xml"};
        }},
        katex: {render: success("x", "text/html")}
    }});
    const first = await render(instance, {source: "graph TD; A-->B"});
    const second = await render(instance, {source: "graph TD; A-->C"});
    assert.equal(first.status, "success");
    assert.equal(second.status, "success");
    assert.equal(calls, 2);
    instance.dispose();
});
