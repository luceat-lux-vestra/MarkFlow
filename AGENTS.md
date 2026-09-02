# MarkFlow Engineering Instructions

## Mission

MarkFlow is an IntelliJ-platform WYSIWYG Markdown editor. The product goal is not to preserve the current implementation; it is to provide a reliable, source-safe, responsive Markdown editing experience inside supported IntelliJ IDEs.

The repository began with local-model-driven bootstrap code. Existing classes, modules, bridge formats, browser pooling, synchronization flags, timers, and editor-engine choices are not architectural authority. Preserve proven user-visible behavior and evidence, not accidental implementation structure.

## Required reading

For non-trivial work, read:

1. `README.md` for current product behavior;
2. GitHub issue #52 for the architecture-leap process, anti-goals, and fresh-main audit/design gate;
3. the focused issue/ADR for the subsystem being changed;
4. relevant tests and compatibility evidence.

The historical `plans/*` documents are context only. They do not define the target architecture or override #52's process/anti-goals or the later approved target architecture and accepted ADRs.

## Repository governance

Repository and delivery hardening is owned by Epic #54. Track #51 owns repository policy and live settings, Track #60 owns the canonical CI/workflow and drift controls, and Track #61 owns release/publication provenance. Issue #52 is a separate runtime architecture Epic; do not create its execution issue hierarchy or begin that architecture leap as part of repository hardening.

The current repository merge contract is enforced by the live configuration: `main` is protected and pull-request-only, history is linear, deletion and non-fast-forward updates are blocked, review conversations must be resolved, required checks use strict status freshness, and the solo-maintainer-safe approval count is zero. The repository permits squash merges only; merge commits and rebase merges are disabled, branch-update support is enabled, auto-merge is disabled, and there are no routine bypass actors. `CODEOWNERS` routes review ownership but is not itself a code-owner approval requirement under the current ruleset.

CI green never replaces exact-final-HEAD review. A review PASS belongs only to the reviewed SHA; any new commit requires review again. The reviewer, not the implementing agent, decides PASS and authorizes a squash merge with `expected_head_sha=<reviewed SHA>`. Release/publication remains a separate irreversible gate. The exact required-context classification and producer reconciliation belong to #60, and release provenance/recovery belongs to #61; do not duplicate either implementation contract here.

## Core contracts

Every change must respect or explicitly revise these contracts:

- IntelliJ `Document`, VFS, `FileEditor`, project, and disposal lifecycle correctness;
- Markdown source fidelity: visually equivalent output is not sufficient if persisted source is unexpectedly rewritten;
- deterministic two-way host↔webview synchronization without echo loops, lost final edits, stale callbacks, or cross-session contamination;
- explicit ownership and bounded lifetime for JCEF browsers, handlers, queries, listeners, tasks, timers, caches, and editor-engine instances;
- untrusted Markdown/HTML/resource handling with explicit security policy;
- supported IntelliJ/JCEF compatibility;
- release/publication as a separate irreversible gate.

## Architecture rules

Do not treat Kotlin as merely a file I/O layer or TypeScript as merely a renderer. Responsibilities must be assigned by authoritative ownership, lifecycle, and data contracts.

Prefer these boundaries:

- IntelliJ host integration owns IDE lifecycle, authoritative host document interaction, supported platform APIs, and resource ownership.
- The webview owns presentation/editor-engine concerns behind explicit adapters.
- Host↔webview communication goes through one versioned, validated protocol boundary.
- Synchronization correctness comes from an explicit revision/session/state model, never from debounce timing or boolean reentrancy flags alone.
- Pooling, pre-warming, caching, delta transport, retries, and extra concurrency are optimizations that require evidence; they are not default architecture.

Do not add abstractions, extension points, compatibility shims, or feature flags solely to reserve a hypothetical future design.

## Kotlin / IntelliJ discipline

- Use supported IntelliJ Platform APIs; do not rely on private implementation details without an explicit compatibility decision.
- Make EDT/write-action/command requirements explicit. Never hide blocking or cross-thread assumptions.
- Every registered listener, JCEF handler/query, scheduled task, coroutine/job, browser, and disposable object must have one clear owner and deterministic cleanup.
- Avoid detached background work and process-global mutable state.
- Treat editor/project disposal, rapid reopen, split editors, external document changes, and browser reload/crash as ordinary lifecycle scenarios.
- Preserve IntelliJ undo/redo semantics deliberately; do not make web-originated edits silently bypass user expectations without an accepted contract.

## TypeScript / webview discipline

- Keep TypeScript strict.
- Do not expose feature logic directly through arbitrary `window.*` globals or raw `cefQuery` calls. Keep transport behind the protocol adapter.
- Treat editor engines such as Milkdown/Crepe as replaceable adapters, not as the domain model.
- Feature capabilities such as Mermaid, KaTeX, raw HTML, clipboard handling, theme/settings, and UI-state restore must have explicit ownership and tests.
- Avoid hidden unbounded queues, caches, timers, observers, DOM listeners, or retries.
- Do not log full document content or sensitive source text by default.

## Security

Markdown and webview-originated messages are untrusted input.

Review changes for:

- raw HTML sanitization;
- script/event/style injection;
- link/navigation and local/remote resource access;
- Mermaid configuration;
- JCEF origin/resource serving;
- protocol input validation and size limits;
- diagnostics redaction.

Do not weaken sanitization or origin boundaries for convenience.

## Testing and evidence

Test the changed contract, not only helper functions. Depending on risk, cover:

- exact/allowed Markdown round-trip behavior;
- host↔webview message ordering, duplication, delay, rejection, and stale sessions;
- undo/redo and external document edits;
- open/close/reopen, multiple tabs, split editors, hide/show, project close, browser reload/failure;
- final-edit flush behavior on deactivate/dispose;
- large documents and bounded-resource behavior;
- adversarial HTML/resource fixtures;
- supported IntelliJ versions and plugin verification.

A green CI run is necessary evidence, never sufficient proof of correctness.

## Strict merge gate

Review the exact final PR HEAD for:

- functional correctness and regressions;
- architecture and ownership boundaries;
- lifecycle, concurrency, ordering, and synchronization semantics;
- error handling, diagnostics, and provenance;
- source fidelity and IntelliJ behavior;
- security boundaries;
- API/platform compatibility;
- performance and resource retention;
- abstractions, duplication, complexity, dead code, and hacks;
- edge cases and test coverage;
- diff scope and documentation/evidence consistency.

A PASS is valid only for the exact reviewed HEAD SHA. Any HEAD change invalidates the PASS. Merge only with squash and the reviewed `expected_head_sha`. Verify the resulting squash commit and `main` movement before closing the linked issue.

Release/publication requires a separate explicit review and must never be implied by implementation completion.

## Work discipline

- Keep each PR focused on one independently reviewable purpose.
- Preserve unrelated user changes.
- Do not perform a giant rewrite PR even when the architecture is being aggressively replaced; establish contracts and migrate in gated slices.
- Do not claim a benchmark, compatibility result, leak fix, security property, or test result unless it was actually demonstrated.
- When an accepted contract is missing, stop implementation at analysis/spike/ADR rather than encoding another accidental architecture.
