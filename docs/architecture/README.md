# Architecture

MarkFlow's architecture is intentionally being re-established. Existing implementation structure is not automatically authoritative.

## Current architectural principles

1. **Canonical document/source authority must be established by the fresh-main audit.** Preserve and evaluate IntelliJ `Document`/VFS/undo-redo/persistence semantics, disk/VFS relationships, and web-editor state without assuming that the IntelliJ host document, editor engine, or a separate MarkFlow model is authoritative.
2. **Versioned host↔webview protocol.** Kotlin and TypeScript communicate through one validated contract boundary.
3. **Explicit ownership and lifecycle.** Browsers, handlers, listeners, sessions, timers, tasks, and editor-engine instances have one owner and deterministic disposal.
4. **State-machine synchronization.** Correctness must not depend on debounce timing, arbitrary sleeps, or boolean reentrancy flags.
5. **Replaceable web editor engine.** Milkdown/Crepe is an adapter, not MarkFlow's domain model.
6. **Security at the boundary.** Markdown, HTML, resources, and webview messages are untrusted input.
7. **Optimization follows evidence.** Pooling, prewarming, caches, deltas, retries, and concurrency require measured need and bounded behavior.

Issue #52 defines the architecture-leap process and audit/design gate: repository hardening settles first, then the architecture owner performs a fresh-main product/fidelity audit and target-architecture design. The owner creates the complete initial execution child-issue set after that design. The approved target architecture and accepted ADRs, not historical phase wording or the current implementation, are authoritative.

Repository hardening is a separate concern owned by Epic #54 and Tracks #51, #60, and #61. Completing those tracks does not authorize runtime redesign; the architecture owner must first perform the fresh-`main` audit required by #52.

## When an ADR is required

Write an ADR before implementation when a change affects any of:

- authoritative document/source ownership;
- source normalization or persistence semantics;
- host↔webview protocol/versioning;
- synchronization/revision/session model;
- JCEF/browser ownership or lifecycle model;
- security/trust boundaries;
- supported IntelliJ/JCEF compatibility policy;
- editor-engine replacement or abstraction boundary;
- release/publication semantics.

Routine implementation details do not require an ADR.

## ADR lifecycle

Use `adr-template.md`. Store accepted records in this directory using a stable numeric prefix, for example:

```text
0001-document-authority.md
0002-host-webview-protocol.md
```

Statuses: `Proposed`, `Accepted`, `Superseded`, `Rejected`.

An ADR must record context, decision, alternatives, consequences, migration/compatibility implications, and evidence needed to validate the decision.
