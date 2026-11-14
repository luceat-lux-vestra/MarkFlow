# Architecture

MarkFlow's architecture is governed by **product/correctness invariants first, accepted ADRs second, and implementation last**. Existing code is never authoritative merely because it exists or was previously merged during Leap.

## Authority hierarchy

When architecture sources disagree, use this order:

1. #78 and `docs/product/leap-capability-fidelity-contract.md` — product and source-fidelity authority;
2. accepted ADRs and architecture decisions, including Leap 2 ADR 0003;
3. responsibility Tracks #79–#84 as reconciled against the accepted architecture;
4. PR-sized implementation Task contracts;
5. current classes, packages, libraries, tests and historical design text as evidence only.

Issue #52 established the replacement-first Leap policy. #139 and #141 are completed architecture-reset and repository-wide migration-reconciliation gates, PR #142 accepted ADR 0001/0002, #143–#156 completed the first native Leap migration, and #84/#52 then closed. Leap 2 #545 reopened only the editor-shell/product-fidelity question after fresh pre-1.0 manual evidence; #556/PR #558 accepted a MarkFlow-owned native FileEditor shell while retaining the original native source-authority and renderer boundaries.

## Current architecture

ADR 0001 establishes **IntelliJ-native authoritative editing with an in-place, source-neutral Markdown projection layer**.

ADR 0002 establishes **Mermaid/KaTeX renderer continuity across that editor migration**. Native editor migration removes the browser from source-editing correctness; it does not authorize reimplementing or dropping supported Mermaid/KaTeX rendering. Existing maintained renderer engines are extracted from editor-specific integration and reused behind the derived-render boundary.

ADR 0003 records the Leap 2 shell decision from #556: the target is now a **distinct MarkFlow-owned native `FileEditor` over the same authoritative IntelliJ `Document`**, while the bundled IntelliJ Markdown editor remains independently selectable. The #143 platform-augmentation decision remains historical evidence for the first Leap and is superseded only at this lower shell layer; ADR 0001 source authority/projection and ADR 0002 renderer continuity remain accepted. See `0003-leap2-owned-native-file-editor-shell.md`, `native-editor-shell-selection.md`, and `leap2-owned-shell-cutover-inventory.md`.

#145 establishes the next target foundation over that selected shell: **an immutable exact `Document` snapshot and source/config identity are parsed into an immutable `NativeProjectionPlan`, then applied by one source-neutral `NativePresentationController` per native editor**. The initial mechanism and evidence contract are recorded in `native-markdown-projection-foundation.md`. #145 is a projection foundation, not ordinary-Markdown parity and not production cutover.

#146 resolves native edit-ownership details above that foundation without creating a second editor authority: **IntelliJ's normal paste action, command/undo stack and opaque `FileEditorState` remain authoritative, while MarkFlow may preprocess only an inserted Markdown payload and apply narrowly source-local rich edit commands to explicit native selections**. The implementation/evidence contract is recorded in `native-edit-semantics.md`.

Current architecture principles:

1. **IntelliJ `Document` is the sole mutable live Markdown authority.** A native IntelliJ `Editor` edits that same `Document` directly.
2. **MarkFlow owns a distinct native FileEditor surface over that same Document.** The bundled IntelliJ Markdown surface remains independently selectable and receives no MarkFlow presentation after cutover.
3. **Presentation is derived and disposable.** Parsing, styling, folding, inlays, images and rich previews never become source authority.
4. **Reveal exact source in active edit context.** Presentation may visually reduce syntax only when exact source remains recoverable and interaction is unambiguous.
5. **Stale derived work is inert.** Parse/render results apply only to the exact current source/config identity they were produced from.
6. **Mermaid and KaTeX remain supported renderer capabilities.** The retained production engines are Mermaid `12.3.0` and KaTeX `^0.19.0`; editor adapters are replaceable and renderer upgrades remain separately gated. Mermaid 12 compatibility is intentional: MarkFlow explicitly preserves classic look and Dagre layout instead of accepting the new upstream defaults implicitly.
7. **Derived renderer execution is isolated from editing.** TypeScript/Vite/Node/JCEF may remain for real renderer consumers, but JCEF never owns source editing or gates it.
8. **Trust surfaces are minimized first.** Local resources/navigation are host-owned. Browser origin/network/CSP machinery is deleted with superseded editor surfaces; retained renderer runtimes receive narrower renderer-specific containment.
9. **Lifecycle ownership is explicit.** Per-editor presentation controllers and renderer runtimes have separate deterministic owners and bounded resources.
10. **Optimization follows evidence.** Incremental parsing, caches, retained artifacts, renderer reuse, pooling/prewarm/concurrency require measured benefit and explicit bounds.
11. **Replacement includes already-merged Leap code.** Superseded CodeMirror/JCEF source-editing/bridge/loopback/CSP mechanisms are not compatibility APIs; #154 removes them once their retained responsibilities have been extracted.
12. **Classification is responsibility-level.** Do not label an entire directory/toolchain `DELETE` when retained renderer consumers still need part of it.

See `0001-native-authority-projection-architecture.md`, `0002-mermaid-katex-renderer-continuity.md`, `0003-leap2-owned-native-file-editor-shell.md`, `native-editor-shell-selection.md`, `native-markdown-projection-foundation.md`, `native-edit-semantics.md`, `leap-target-architecture-comparison.md`, `leap-migration-inventory.md`, and `leap2-owned-shell-cutover-inventory.md`.

## Architecture anti-goals

Do not:

- preserve a component because removing it creates a larger diff or discards recent work;
- delete a working supported renderer merely because its current editor adapter is replaced;
- maintain two independent Mermaid or KaTeX engines in steady-state production;
- keep/revive a host↔web editing protocol, custom revisions, ACK/recovery, browser flush or whole-content reconstruction without a new independent proof;
- make source durability depend on debounce, arbitrary sleeps, browser readiness or retry luck;
- make optional renderer/JCEF availability a prerequisite for Markdown editing;
- reconstruct authoritative Markdown from a lossy rich document model;
- persist ephemeral resource/security capabilities;
- keep two editor architectures indefinitely for rollback comfort;
- preserve Node/TypeScript/Vite/JCEF merely because `webview/` exists, or delete them before actual renderer consumers are known.

## Migration execution

#141 defines the canonical dependency graph. Durable resolutions and ownership are:

- **#143 completed** — platform text-editor augmentation selected/proven;
- **#145 completed** — immutable snapshot/projection-plan/per-editor controller foundation proven over #143;
- #146 resolved native paste/state/representative local-edit ownership through platform paste/command/undo/FileEditorState semantics plus bounded MarkFlow-specific payload/local-edit behavior;
- renderer extraction, native derived presentation, host resource/navigation, ordinary Markdown/table parity and the other #143–#152 prerequisites supplied the evidence required for cutover;
- **#153 completed** — production file opening/editing now uses the platform-native editor and post-main verification closed the cutover gate;
- **#154 completed** — obsolete browser editor, custom document/session/revision protocols, browser editing trust/resource realm and editor-only frontend/tooling are purged while renderer infrastructure is retained;
- **#155 completed** — dependency/toolchain/JCEF packaging and settings are converged over the retained renderer-only stack;
- **#156 completed** — final compatibility/lifecycle/performance/release convergence;
- **#84 and #52 completed** — the quality Track and parent Leap Epic are closed after convergence.

The #143–#156 Tasks and #52/#84 remain useful provenance for why the first native architecture exists; they are not an active migration backlog.

Leap 2 shell migration authority is now:

- **#545 active Epic** — architecture/product reset after owner-visible pre-1.0 fidelity failure;
- **#556 completed / PR #558 merged** — Candidate B MarkFlow-owned native `FileEditor` proof accepted;
- **ADR 0003** — current lower-level shell authority;
- **#559 / `leap2-owned-shell-cutover-inventory.md`** — bounded ADR formalization, inventory and ordered production-cutover plan.

Production remains on the historical platform-augmentation attachment until the ordered Leap 2 cutover slices replace it with exact-candidate evidence. Fidelity repair resumes only after that shell cutover.

## When an ADR is required

Write or revise an ADR before implementation when a change affects any of:

- authoritative document/source ownership;
- primary editor-surface architecture;
- source normalization/persistence semantics;
- synchronization/transport model where more than one correctness authority would exist;
- lifecycle/ownership of editors, JCEF or derived renderer processes;
- security/trust/resource/navigation boundaries;
- supported IntelliJ/JCEF compatibility policy;
- parser/editor/renderer replacement boundaries;
- Mermaid/KaTeX renderer-engine replacement or capability semantics;
- settings/state identity or migration semantics;
- release/publication semantics.

Routine implementation details under an accepted boundary do not require a new ADR.

## ADR lifecycle

Use `adr-template.md`. Statuses: `Proposed`, `Accepted`, `Superseded`, `Rejected`.

An ADR must record context, decision, materially different alternatives, consequences, compatibility/migration implications, security/lifecycle implications and evidence required to validate implementation.

## Repository hardening

Repository hardening remains separate. Passing repository CI does not authorize an architecture; architecture approval and every migration slice remain subject to proof-obligation review.
