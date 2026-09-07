# Architecture

MarkFlow's architecture is governed by **product/correctness invariants first, accepted ADRs second, and implementation last**. Existing code is never authoritative merely because it exists or was previously merged during Leap.

## Authority hierarchy

When architecture sources disagree, use this order:

1. #78 and `docs/product/leap-capability-fidelity-contract.md` — product and source-fidelity authority;
2. accepted ADRs and architecture decisions produced through #139;
3. responsibility Tracks #79–#84 as reconciled against the accepted architecture;
4. PR-sized implementation Task contracts;
5. current classes, packages, libraries, tests and historical design text as evidence only.

Issue #52 defines replacement-first Leap policy. Issue #139 is the architecture reset gate. #140 records the first-principles comparison. PR #142 merged ADR 0001 and ADR 0002. #141 owns repository-wide migration classification/stale-guidance/backlog reconciliation before #139 may close.

## Accepted target

ADR 0001 establishes **IntelliJ-native authoritative editing with an in-place, source-neutral Markdown projection layer**.

ADR 0002 establishes **Mermaid/KaTeX renderer continuity across that editor migration**. Native editor migration removes the browser from source-editing correctness; it does not authorize reimplementing or dropping supported Mermaid/KaTeX rendering. Existing maintained renderer engines are extracted from editor-specific integration and reused behind the derived-render boundary.

#143 resolves ADR 0001's lower-level shell choice: the target **augments the normal IntelliJ platform text editor** and attaches per-editor MarkFlow presentation through maintained native editor lifecycle APIs. A MarkFlow-owned replacement `FileEditor` shell is rejected because it adds provider/state/input/focus/lifecycle ownership without an independent product requirement. See `native-editor-shell-selection.md`. This selection does not itself cut production over; #153 remains the cutover owner.

Target principles:

1. **IntelliJ `Document` is the sole mutable live Markdown authority.** A native IntelliJ `Editor` edits that same `Document` directly.
2. **Native editing uses the platform text-editor shell.** MarkFlow augments the normal native editor; it does not introduce a target replacement `FileEditorProvider`.
3. **Presentation is derived and disposable.** Parsing, styling, folding, inlays, images and rich previews never become source authority.
4. **Reveal exact source in active edit context.** Presentation may visually reduce syntax only when exact source remains recoverable and interaction is unambiguous.
5. **Stale derived work is inert.** Parse/render results apply only to the exact current source/config identity they were produced from.
6. **Mermaid and KaTeX remain supported renderer capabilities.** Mermaid `11.17.2` and KaTeX `^0.18.4` are extracted/reused; editor adapters are replaceable.
7. **Derived renderer execution is isolated from editing.** TypeScript/Vite/Node/JCEF may remain for real renderer consumers, but JCEF never owns source editing or gates it.
8. **Trust surfaces are minimized first.** Local resources/navigation are host-owned. Browser origin/network/CSP machinery is deleted with superseded editor surfaces; retained renderer runtimes receive narrower renderer-specific containment.
9. **Lifecycle ownership is explicit.** Per-editor presentation controllers and renderer runtimes have separate deterministic owners and bounded resources.
10. **Optimization follows evidence.** Incremental parsing, caches, retained artifacts, renderer reuse, pooling/prewarm/concurrency require measured benefit and explicit bounds.
11. **Replacement includes already-merged Leap code.** Current CodeMirror/JCEF/source-native/bridge/loopback/CSP implementation survives only where #141 independently justifies a responsibility.
12. **Classification is responsibility-level.** Do not label an entire directory/toolchain `DELETE` when retained renderer consumers still need part of it.

See `0001-native-authority-projection-architecture.md`, `0002-mermaid-katex-renderer-continuity.md`, `native-editor-shell-selection.md`, `leap-target-architecture-comparison.md`, and `leap-migration-inventory.md`.

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

#141 defines the canonical execution graph:

- early parallel candidates: #143 native shell proof, #144 renderer extraction, #150 image-import product decision;
- #143 -> #145 projection foundation;
- #143 + #145 -> #146 native paste/actions/state;
- #145 -> #147 host local-image/navigation;
- #144 + #145 -> #148 Mermaid/KaTeX native inlays;
- #145 -> #149 sanitized source-preserved raw-HTML rendering;
- #150 + #147 -> #151 image import;
- #145 + #146 -> #152 ordinary Markdown/table parity;
- required #143–#152 evidence -> #153 production native cutover;
- #153 -> #154 mandatory old-editor/protocol/trust purge;
- #154 -> #155 dependency/toolchain/JCEF/settings convergence;
- #155 -> #156 final compatibility/lifecycle/performance/release convergence;
- #156 -> #84 eligible to close.

#139 may close after #141 repository-reset evidence is merged/post-main verified. #52 remains open until implementation/convergence completes.

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
