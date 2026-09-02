# Architecture

MarkFlow's architecture is intentionally being re-established. Existing implementation structure is not automatically authoritative.

## Current architectural principles

1. **One authoritative host document model.** IntelliJ document/VFS semantics and source fidelity must be explicit.
2. **Versioned host↔webview protocol.** Kotlin and TypeScript communicate through one validated contract boundary.
3. **Explicit ownership and lifecycle.** Browsers, handlers, listeners, sessions, timers, tasks, and editor-engine instances have one owner and deterministic disposal.
4. **State-machine synchronization.** Correctness must not depend on debounce timing, arbitrary sleeps, or boolean reentrancy flags.
5. **Replaceable web editor engine.** Milkdown/Crepe is an adapter, not MarkFlow's domain model.
6. **Security at the boundary.** Markdown, HTML, resources, and webview messages are untrusted input.
7. **Optimization follows evidence.** Pooling, prewarming, caches, deltas, retries, and concurrency require measured need and bounded behavior.

GitHub issue #52 is the active architecture-leap plan until its decisions are decomposed into accepted ADRs and implementation issues.

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
