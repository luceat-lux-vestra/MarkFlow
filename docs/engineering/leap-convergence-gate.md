# Leap Convergence Gate

This document is the repository-visible quality contract for #156. #156, #84, and #52 were reopened under corrective track #309; the compatibility, lifecycle, performance, E2E, and release obligations below remain maintained regression/release guardrails until the corrective proof closes them again. CI green is necessary but not sufficient; missing or unclassified evidence remains a failure.

## Maintained IntelliJ envelope

MarkFlow's public compatibility floor remains build 262 / IntelliJ IDEA 2026.2+.

The authoritative **full-product real-user acceptance runtime is IntelliJ IDEA 2026.2.3**. The Starter/Driver release-blocking path does not require an unreleased EAP IDE. Compatibility with later platform builds remains a separate concern: Plugin Verifier may keep pinned forward targets, and a deliberately scoped EAP smoke probe may be added as informational evidence, but neither substitutes for or expands the full-product acceptance authority.

The Starter/Driver workflow packages the exact candidate plugin once from the exact source SHA, records its archive name and SHA-256, and then runs parallel 2026.2.3 scenario shards against that same immutable ZIP. Shards are forbidden from rebuilding the packaged candidate. The maintained shard set is:

- `canonical` — one coherent production whole-product journey;
- `core-editing` — exact-source editing, reveal, paste, Undo/Redo, save/reopen;
- `ordinary-markdown` — table and task-list interaction;
- `derived-content` — host resources, Mermaid/KaTeX, and raw-HTML production wiring;
- `lifecycle` — split-editor and degraded/source-fallback behavior.

Plugin Verifier remains an independent binary/API compatibility layer. A verifier PASS does not replace launched-IDE evidence, and an EAP verifier target does not make that EAP build a release-blocking full-product runtime.

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

For ordinary product PRs, each required stable shard and the canonical journey execute once on fresh CI runners against the shared exact plugin ZIP. The canonical journey itself traverses production native opening/presentation, source reveal, source-local editing and Markdown-aware paste, Undo/Redo, table and task interaction, host resource/navigation discovery, Mermaid/KaTeX rendering, raw-HTML presentation, save, close, reopen, and exact persisted bytes.

## Deterministic visual acceptance

#339 adds a third product-proof layer without weakening the source/fidelity or semantic/runtime oracles. The visual job consumes the same exact packaged plugin artifact as Starter/Driver, launches **IntelliJ IDEA 2026.2.3 only**, and captures a fixed `1200x760` crop from an editor-only Markdown viewport inside a fixed `1800x1000` IDE window under a pinned `ubuntu-24.04` / Xvfb `1920x1080x24` / 96-DPI / 1x-scale envelope. The visual harness forces IntelliJ's bundled default Light UI theme and default editor color scheme before presentation capture.

The representative visual corpus is split into bounded documents for typography, structural Markdown, code presentation, derived Mermaid/KaTeX content, and host-resource/raw-HTML presentation. MarkFlow-controlled appearance is fixed before capture. The observed IntelliJ LAF, editor scheme/font, screen/viewport geometry, and scale become an exact versioned environment identity; environment drift fails before a new baseline may be accepted.

Goldens are repository-versioned SVG wrappers around the exact expected PNG so they remain directly reviewable while the comparator consumes the embedded raster. CI never writes accepted goldens. A baseline change requires a reviewed user-visible explanation and a new exact HEAD. The comparator permits only a per-channel delta of 8 and at most 0.02% changed pixels (with a 64-pixel floor for caret/raster noise); dimension changes fail unconditionally. A maintained negative control changes a meaningful image region and must fail the comparator. Failures retain actual, expected, diff, metrics, environment, package identity, and IDE logs.

The visual oracle is deliberately separate from the semantic Starter aggregate gate. A visual PASS does not prove source bytes, edit locality, Undo/Redo, renderer state, or lifecycle; those remain owned by their maintained proof layers. The deleted Robot Server infrastructure stays deleted.

For the final #309 convergence/release-candidate gate, an explicit workflow-dispatch control runs the canonical journey a second time in a **separate fresh job/IDE process** against the same exact packaged artifact. That second run is repeatability evidence, never a flaky retry: the first canonical run must already be PASS, and a failed repeat remains deterministic FAIL until classified and fixed.

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
2. the IDEA 2026.2.3 package-once Starter gate passes every maintained shard plus the canonical whole-product journey on one recorded plugin digest;
3. final #309/release-candidate evidence includes the independent fresh-process canonical repeat; no-JCEF editing and retained-JCEF rendering remain separately evidenced within their maintained scope;
4. quantitative tripwires pass and their evidence artifacts/logs are reviewable;
5. legacy Robot/editor/protocol residue searches are clean and the #141 inventory has zero unresolved `TEMPORARY` production rows;
6. released settings migration tests remain green and removed keys are not serialized again;
7. unresolved review threads are zero and the reviewed HEAD/base/main identities are fresh;
8. squash merge uses the exact reviewed HEAD;
9. merged-main identity and the applicable push-triggered post-main workflows pass for the reviewed change or release candidate.

Any UNKNOWN, UNVERIFIED, insufficiently classified compatibility/lifecycle/performance/release behavior remains FAIL.
