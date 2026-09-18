# Leap Convergence Gate

This document is the repository-visible quality contract for #156. It converts the final native Leap compatibility, lifecycle, performance, and release obligations into reproducible evidence. CI green is necessary but not sufficient; missing or unclassified evidence remains a failure.

## Maintained IntelliJ envelope

MarkFlow's public compatibility floor remains build 262 / IntelliJ IDEA 2026.2+.

The #156 runtime matrix is pinned rather than floating:

| Role | IntelliJ target | Required runtime evidence |
| --- | --- | --- |
| stable baseline | 2026.2.3 | Starter/Driver acceptance, repeated twice in one job; native editing with JCEF plugin absent; retained isolated JCEF renderer |
| forward compatibility | 2026.3 EAP2 build 263.4732.28 | Starter/Driver acceptance; native editing with JCEF plugin absent; retained isolated JCEF renderer |

Plugin Verifier remains an additional binary/API compatibility layer. A verifier PASS does not replace launched-IDE evidence.

The EAP target is a forward-compatibility probe, not a promise that unreleased IDE behavior is stable. Updating either pinned runtime target is a reviewed maintenance change because it changes the evidence environment.

## Layered product proof

The broad #78 fidelity corpus stays in lower-level native/platform evidence. Starter/Driver proves representative user paths rather than duplicating every lexical fixture through UI automation.

Required user-path coverage remains:

- open/edit/save/reopen through production native editing;
- exact-source reveal and source-local rich edit with undo/redo;
- real Markdown-aware paste;
- native table interaction;
- safe raw-HTML sanitization/rasterization and hostile raw-HTML fail-closed exact-source fallback in real-IDE projection evidence;
- split-editor behavior where the Driver path is stable;
- renderer-unavailable degraded editing.

The stable Starter/Driver matrix entry executes the complete suite twice in independent Gradle test executions. A second pass is not a retry: the first pass must succeed, and both runs are retained as evidence. This is the state-leak/repeatability proof.

## Quantitative regression tripwires

These are CI regression budgets on GitHub-hosted Linux runners, not user-facing latency SLAs and not optimization targets. They intentionally leave headroom around the accepted native architecture while making catastrophic performance regressions fail closed.

| Path | Workload | Budget |
| --- | --- | ---: |
| native rapid edit/caret/projection | 20 source edits, caret moves, and synchronous current-generation projection refreshes | <= 5000 ms total |
| large native projection planning | 750 repeated representative Markdown sections plus a table | <= 3000 ms |
| isolated renderer runtime creation | one retained JCEF runtime | <= 15000 ms |
| representative Mermaid render | first representative Mermaid success render on the runtime | <= 15000 ms |
| representative KaTeX render | inline and display success cases | <= 15000 ms each |
| renderer create/dispose lifecycle | three create/dispose cycles returning live-instance count to baseline | <= 10000 ms |
| renderer JavaScript bundle | largest emitted JS asset | <= 700 KiB |
| renderer JavaScript bundle | sum of emitted JS assets | <= 4000 KiB |

A budget breach is a deterministic failure until explained and fixed or the budget is explicitly re-baselined with measured evidence. Do not rerun a red job to manufacture PASS.

No pooling, prewarm, cache, incremental parser, worker, or concurrency mechanism is justified by these budgets alone. Added complexity still requires a measured bottleneck, bounded ownership, and a separate correctness/lifecycle proof.

## Lifecycle and diagnostic classification

Resource ownership is proved with explicit counters and repeated lifecycle cases, not heap-size folklore:

- native editor/presentation probes must end with no retained owned editors/controllers/inlays beyond their baseline;
- the isolated JCEF renderer must return its MarkFlow runtime live-instance count to the exact baseline after disposal, and its retained loopback resource owner/server/extracted-root state must return to zero/absent;
- renderer resource-server startup failure and JCEF browser-construction failure after resource acquisition must both roll back owner/server/extracted-root state to the same baseline; a renderer temp-root deletion failure must remain observable, block stale-root reuse, and recover through cleanup before the next fresh acquisition;
- no-JCEF evidence must prove the renderer factory is absent while source edit/save/undo/redo/settings remain usable;
- repeated Starter execution must not depend on state from the prior launched IDE; each pass preserves `idea.log`, and MarkFlow-owned `AlreadyDisposedException`, `ConcurrentModificationException`, plugin-load, or class-load failures fail the gate.

IntelliJ/JCEF may emit an `AppShutdownProxySelector` warning while the platform remote CEF server is stopping. The renderer workflow classifies every such stack: a warning is tolerated only when no `com.algorist.markflow` frame is in that warning stack and MarkFlow's renderer live-instance count has already returned to baseline. A MarkFlow-owned shutdown stack is a failure.

The historical Robot Server workflow/task/plugin has no retained product consumer after #193 Starter/Driver convergence and is deleted. Hardening rejects its reintroduction.

## Renderer bundle warning disposition

Vite's historical >500 kB chunk warning is not hidden or treated as an optimization mandate. Build CI records actual emitted JS sizes and enforces the explicit budgets above. Current renderer code splitting may be changed only when measurement shows a useful improvement without weakening Mermaid/KaTeX behavior, lazy loading, or lifecycle ownership.

## Release gate

Before #156 can close:

1. exact-final-HEAD Build/Test/Qodana/Plugin Verifier and all native/renderer/Starter evidence workflows pass;
2. both maintained runtime matrix entries pass without rerun-based reclassification;
3. no-JCEF editing and retained-JCEF rendering pass on both entries;
4. quantitative tripwires pass and their evidence artifacts/logs are reviewable;
5. legacy Robot/editor/protocol residue searches are clean and the #141 inventory has zero unresolved `TEMPORARY` production rows;
6. released settings migration tests remain green and removed keys are not serialized again;
7. unresolved review threads are zero and the reviewed HEAD/base/main identities are fresh;
8. squash merge uses the exact reviewed HEAD;
9. merged-main identity and push-triggered post-main workflows pass before #156 or #84 is closed.

Any UNKNOWN, UNVERIFIED, insufficiently classified compatibility/lifecycle/performance/release behavior remains FAIL.
