# Testing Strategy

MarkFlow uses risk-based proof obligations. Helper-level tests are useful, but the changed contract must be exercised at the narrowest level that can actually falsify it. CI green is necessary, never sufficient.

## Baseline automated checks

For ordinary code changes, maintain coverage across applicable layers:

- Kotlin/Gradle compilation and unit/platform tests;
- retained renderer/web TypeScript build and source-level tests while consumers remain;
- plugin packaging;
- IntelliJ Plugin Verifier against the declared compatibility target;
- static analysis with a version-compatible trustworthy scanner configuration.

## Contract-focused testing

### Source authority / fidelity

Cover:

- exact no-edit byte/text stability;
- supported lexically-local edits and preservation of unrelated whitespace/delimiters/fences/list markers/line endings/final newline;
- native dirty/save/undo/redo;
- external `Document`/VFS changes;
- malformed/ambiguous/unsupported source fallback rather than guessed reconstruction.

The shared `fixtures/markdown-fidelity/` corpus is fixture-integrity evidence only unless a runtime test actually exercises the target path.

### Native editor and projection

Cover real IntelliJ behavior for:

- one authoritative `Document` shared by native editor splits;
- attach/refresh/reveal/remove/dispose of projection without source mutation or source undo entries;
- caret/selection exact-source reveal around projected constructs;
- stale projection-plan rejection after source/config change;
- IME/composition, keymaps, multicaret and clipboard baseline;
- caret/selection/scroll/FileEditor state;
- repeated create/recreate/dispose and project close;
- JCEF unavailable with source editing still usable.

### Derived renderers

Mermaid/KaTeX migration evidence must prove the **same extracted renderer service**, not a duplicate engine:

- Mermaid representative success/error corpus, theme/palette/size/zoom/error settings;
- KaTeX inline/display success/error and density;
- stale render result rejection;
- renderer unavailable/crash/restart/failure while source editing remains usable;
- source byte stability while rendering;
- split-editor independent presentation;
- deterministic artifact/runtime disposal and bounded caches/resources.

If JCEF is retained as a renderer backend, use real production renderer bundle/realm evidence for lifecycle/request/network/trust claims. API availability or a synthetic browser page is insufficient.

### Trust / resources / raw HTML

Use hostile fixtures for:

- raw HTML scripts/events/styles/active content and malformed input;
- local image `..`, encoded traversal, mixed separators, symlink escape, missing/unsupported/oversize media and decoded-size limits;
- navigation scheme allow/deny and explicit user action;
- renderer ambient network/filesystem/navigation denial;
- clipboard/file-import authority, collisions, partial failure/rollback/undo and read-only/non-local contexts when that feature is implemented;
- diagnostics redaction and bounded payloads.

### Migration-period legacy browser editor

Before #153/#154 remove it, a PR that changes the current browser editor or its host↔web/session/recovery/trust code must still test the current production contract: duplication/reordering/delay/stale sessions, flush/dispose/reload/recovery, request/navigation containment, and failure paths as applicable.

This is **migration safety evidence only**. Do not report it as proof that host↔web synchronization is target architecture. Mechanism-only tests are deleted/replaced when their responsibility is purged.

### Compatibility

Verify maintained IntelliJ APIs/runtime behavior rather than assuming compilation proves compatibility. Experimental/private APIs require explicit evidence. JCEF compatibility is tested only for responsibilities that actually retain JCEF; ordinary Markdown editing must be proven independently of JCEF availability.

### Performance / resources

Do not make performance claims without measurements. Identify workload, document size, environment, warm/cold state, metric and threshold.

Measure target bottlenecks before adding incremental parsing complexity, retained artifacts, renderer reuse, pooling, prewarm, caches or concurrency. Resource-retention claims require repeated lifecycle and bounded-lifetime evidence, not throughput alone.

## Manual/runtime evidence

Record IDE build, OS, scenario, expected behavior and observed result when helper tests cannot prove native editor, projection, inlay, renderer, accessibility or lifecycle semantics. Screenshots/log snippets may supplement but must not expose private document content.

## CI integrity

A job that reports success while its underlying scanner/test reports unacceptable findings is not a valid gate. Tool versions must be compatible, scanner/runtime exceptions investigated, and failure thresholds configured so required checks represent the actual policy.
