# Leap Convergence Gate

This document was established as the repository-visible quality contract for #156. #156, #84, and #52 are closed after the initial convergence proof, but the compatibility, lifecycle, performance, and release obligations below remain maintained regression/release guardrails until deliberately revised. CI green is necessary but not sufficient; missing or unclassified evidence remains a failure.

## Maintained IntelliJ envelope

MarkFlow's public compatibility floor remains build 262 / IntelliJ IDEA 2026.2+.

The required launched-IDE runtime target established by #322 is pinned rather than floating:

| Role | IntelliJ target | Required runtime evidence |
| --- | --- | --- |
| stable baseline | 2026.2.3 | Starter/Driver acceptance, repeated twice where the gate requires repeatability; native editing with JCEF plugin absent; retained isolated JCEF renderer |

Plugin Verifier remains the binary/API compatibility layer and may include forward-looking EAP builds. A verifier PASS does not replace launched-IDE evidence on the pinned stable baseline.

Unreleased EAP launched-IDE smoke is optional forward-compatibility signal only. If retained, it must be non-blocking or scheduled and is not part of the required PR/release runtime gate. Updating the pinned stable runtime target or the verifier compatibility envelope is a reviewed maintenance change because it changes the evidence environment.

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
| renderer JavaScript core bundle | largest non-ELK emitted JS asset | <= 700 KiB |
| renderer JavaScript core bundle | sum of non-ELK emitted JS assets | <= 4000 KiB |
| Mermaid optional ELK lazy chunk | exactly one emitted `elk-*.js` asset | <= 1536 KiB |

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

Vite's historical >500 kB chunk warning is not hidden or treated as an optimization mandate. Build CI records actual emitted JS sizes and enforces the explicit budgets above.

Mermaid 12's full ESM distribution emits ELK as a separate lazy chunk. MarkFlow keeps its historical non-ELK core tripwires unchanged and accounts for that upstream optional payload separately rather than relaxing the core limits. The gate requires exactly one `elk-*.js` chunk, records both core and total emitted bytes, and fails if the ELK payload exceeds its measured regression tripwire. This partition does not authorize eager ELK loading or an additional renderer engine.

Current renderer code splitting may be changed only when measurement shows a useful improvement without weakening Mermaid/KaTeX behavior, lazy loading, or lifecycle ownership.

## Ongoing regression / release gate

The #156 closure proved this gate for the initial native convergence. Future changes or releases that touch this envelope must continue to require:

1. exact-final-HEAD Build/Test/Qodana/Plugin Verifier and all native/renderer/Starter evidence workflows pass;
2. both maintained runtime matrix entries pass without rerun-based reclassification;
3. no-JCEF editing and retained-JCEF rendering pass on both entries;
4. quantitative tripwires pass and their evidence artifacts/logs are reviewable;
5. legacy Robot/editor/protocol residue searches are clean and the #141 inventory has zero unresolved `TEMPORARY` production rows;
6. released settings migration tests remain green and removed keys are not serialized again;
7. unresolved review threads are zero and the reviewed HEAD/base/main identities are fresh;
8. squash merge uses the exact reviewed HEAD;
9. merged-main identity and the applicable push-triggered post-main workflows pass for the reviewed change or release candidate.

Any UNKNOWN, UNVERIFIED, insufficiently classified compatibility/lifecycle/performance/release behavior remains FAIL.
