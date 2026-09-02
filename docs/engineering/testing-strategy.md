# Testing Strategy

MarkFlow uses risk-based evidence. Helper-level tests are useful, but the changed contract must be exercised at the narrowest level that can actually falsify it.

## Baseline automated checks

For ordinary code changes, maintain coverage across:

- Kotlin/Gradle compilation and unit/platform tests;
- webview TypeScript build and source-level tests;
- plugin packaging;
- IntelliJ Plugin Verifier against the declared compatibility target;
- static analysis with a version-compatible, trustworthy scanner configuration.

## Contract-focused testing

### Document/source fidelity

Cover exact or explicitly allowed Markdown round trips, external document edits, undo/redo, line endings, and source constructs that visual equivalence could silently rewrite.

The shared baseline corpus is `fixtures/markdown-fidelity/`. Its manifest
validator checks declared files, IDs, metadata enums, line endings, and
trailing-newline bytes. This is fixture-integrity evidence only; it must not be
reported as proof that the current runtime conforms to the Leap product/fidelity
contract.

### Host↔webview synchronization

Cover message duplication, reordering, delay, stale sessions/revisions, rejected messages, echo suppression, final-edit flush, reload, and reconnect behavior.

### Lifecycle/resource ownership

Cover open/close/reopen, split editors, multiple tabs/projects, hide/show, project disposal, browser reload/failure, listener/query cleanup, and bounded caches/tasks/timers.

### Security

Use adversarial Markdown/HTML/resource/protocol fixtures for changed trust boundaries. Validate sanitization, navigation/resource restrictions, message validation, size limits, and log redaction.

### Compatibility

When platform APIs or JCEF behavior are affected, verify the declared IDE compatibility matrix rather than assuming source compilation proves runtime compatibility.

### Performance

Do not make performance claims without measurements. Tests/benchmarks should identify workload, document size, environment, warm/cold state, metric, and threshold. Resource-retention claims require bounded-lifetime evidence, not only throughput numbers.

## Manual evidence

UI/JCEF behavior often requires manual IDE scenarios. Record IDE build, OS, scenario, expected behavior, and observed result in the PR. Screenshots/log snippets may supplement, but must not expose private document content.

## CI integrity

A job that reports success while its underlying scanner/test reports unacceptable findings is not a valid gate. Tool versions must be compatible, scanner/runtime exceptions investigated, and failure thresholds configured so required checks represent the actual policy.
