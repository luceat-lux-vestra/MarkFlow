# Architecture

MarkFlow's architecture is governed by **product/correctness invariants first, accepted ADRs second, and implementation last**. Existing code is never authoritative merely because it already exists or was previously merged during Leap.

## Authority hierarchy

When architecture sources disagree, use this order:

1. #78 and `docs/product/leap-capability-fidelity-contract.md` — product and source-fidelity authority;
2. accepted ADRs and architecture decisions produced through #139;
3. responsibility Tracks #79–#84 as reconciled against the accepted architecture;
4. PR-sized implementation Task contracts;
5. current classes, packages, libraries, tests and historical design text as evidence only.

Issue #52 defines the replacement-first Leap policy. Issue #139 is the architecture reset gate. Issue #140 records the first-principles editor/runtime comparison, and #141 owns the repository-wide `RETAIN / REPLACE / DELETE / TEMPORARY` migration map after target selection.

## Current target direction

ADR 0001 establishes **IntelliJ-native authoritative editing with an in-place, source-neutral Markdown projection layer** as the accepted Leap target when PR #142 merges.

ADR 0002 establishes **Mermaid/KaTeX renderer continuity across that editor migration**. Native editor migration removes the browser from source-editing correctness; it does not authorize reimplementing or dropping supported Mermaid/KaTeX rendering. Existing maintained renderer engines are extracted from editor-specific integration and reused behind the derived-render boundary.

The target principles are:

1. **IntelliJ `Document` is the sole mutable live Markdown authority.** A native IntelliJ `Editor` edits that same `Document` directly.
2. **Native editing is the invariant; the integration shell is evidence-selected.** Prefer augmenting the platform text editor when it can coexist cleanly with bundled Markdown/editor behavior. A MarkFlow-owned shell is acceptable only if it still wraps a native editor on the same `Document` and independently proves equivalent platform semantics. Neither option may introduce a second editable source model.
3. **Presentation is derived and disposable.** Parsing, styling, folding, inlays, images and rich previews are projections over source and never become source authority.
4. **Reveal exact source in active edit context.** Presentation may visually reduce Markdown syntax only when the exact source remains recoverable and caret/selection interaction cannot become ambiguous.
5. **Stale derived work is inert.** Asynchronous parse/render results apply only to the exact current source/config generation they were produced from.
6. **Mermaid and KaTeX remain supported renderer capabilities.** The editor migration reuses/extracts the maintained Mermaid and KaTeX renderer stack; it does not create parallel renderer engines. Crepe/CodeMirror integration is replaceable, the renderer capability is not collateral deletion.
7. **Derived renderer execution is isolated from editing.** TypeScript/Vite and an isolated JCEF off-screen runtime may remain when they are the best execution adapter for Mermaid/KaTeX. JCEF below this boundary never owns source editing.
8. **Trust surfaces are eliminated before they are hardened.** Local resources, navigation and renderer inputs use explicit host-owned capabilities. Browser origin/network/CSP machinery is not retained for deleted editor surfaces; any retained renderer runtime receives its own narrower containment policy.
9. **Lifecycle ownership is explicit.** Per-editor presentation controllers own their listeners, folds/inlays, tasks and artifacts; renderer runtime ownership is separate and deterministic.
10. **Optimization follows evidence.** Incremental parsing, caches, retained artifacts, concurrency and renderer reuse require measured benefit and bounded lifetime.
11. **Replacement includes Leap code, but classification is responsibility-level.** CodeMirror/JCEF/source-native/bridge/loopback/CSP implementation already merged during Leap is retained only where #141 independently justifies the responsibility. Whole directories/toolchains are not deleted merely because one consumer is superseded.

See `leap-target-architecture-comparison.md`, `0001-native-authority-projection-architecture.md`, and `0002-mermaid-katex-renderer-continuity.md`.

## Architecture anti-goals

Do not:

- preserve a component because removing it creates a larger diff;
- delete a working supported renderer merely because its current editor adapter is being replaced;
- maintain two independent Mermaid or KaTeX engines during steady-state production;
- keep a host↔web editing protocol when the responsibility can remain inside the IntelliJ editor/Document model;
- add protocol versions, epochs, retries, caches or adapters unless they represent a real independent requirement;
- make source durability depend on debounce, arbitrary sleeps, browser readiness or retry luck;
- make optional renderer/JCEF availability a prerequisite for editing Markdown;
- reconstruct authoritative Markdown from a lossy rich document model;
- persist ephemeral resource/security capabilities;
- keep two editor architectures indefinitely for rollback comfort.

## When an ADR is required

Write or revise an ADR before implementation when a change affects any of:

- authoritative document/source ownership;
- primary editor-surface architecture;
- source normalization or persistence semantics;
- synchronization/transport model where more than one correctness authority exists;
- lifecycle/ownership of editors, JCEF or derived renderer processes;
- security/trust/resource/navigation boundaries;
- supported IntelliJ/JCEF compatibility policy;
- parser/editor/renderer replacement boundaries;
- Mermaid/KaTeX renderer-engine replacement or capability semantics;
- settings/state identity or migration semantics;
- release/publication semantics.

Routine implementation details under an accepted boundary do not require a new ADR.

## ADR lifecycle

Use `adr-template.md`. Store records in this directory using a stable numeric prefix, for example:

```text
0001-native-authority-projection-architecture.md
0002-mermaid-katex-renderer-continuity.md
```

Statuses: `Proposed`, `Accepted`, `Superseded`, `Rejected`.

An ADR must record context, decision, materially different alternatives, consequences, compatibility/migration implications, security/lifecycle implications and the evidence required to validate implementation.

## Repository hardening

Repository hardening remains a separate concern owned by its governance/hardening issues. Passing repository CI does not authorize an architecture; architecture approval and implementation both remain subject to #52's proof-obligation discipline.
