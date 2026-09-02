## Summary

<!-- What changes, and why is this the smallest coherent change? -->

## Related issue / decision

<!-- Link the issue. Link an ADR/RFC when an accepted contract is affected. -->

## Scope

- [ ] Implementation
- [ ] Refactor / architecture
- [ ] Bug fix
- [ ] Tests / evidence
- [ ] Documentation
- [ ] Build / CI / dependencies
- [ ] Release-only change

## Contract impact

Describe impact on each applicable area. Write `N/A` with a reason when truly not applicable.

- **Markdown/source fidelity:**
- **IntelliJ Document / VFS / undo-redo:**
- **Host↔webview protocol / revisions / ordering:**
- **JCEF/editor/project lifecycle and disposal:**
- **Security / untrusted content / resource access:**
- **IDE/API compatibility:**
- **Performance / bounded resources:**
- **User-visible behavior:**

## Architecture / ownership

<!-- Which component owns the changed state/resource after this PR? What old responsibility was removed? -->

## Failure and edge cases

<!-- Include stale sessions, delayed/duplicated messages, load/reload failure, external edits, split editors, close/dispose, etc. as relevant. -->

## Validation performed

List commands/scenarios actually run. Do not check a box for work that was not performed.

- [ ] Relevant Kotlin tests
- [ ] Relevant webview tests
- [ ] `./gradlew check`
- [ ] `./gradlew buildPlugin`
- [ ] Plugin verification where compatibility is affected
- [ ] Manual IDE/JCEF scenario testing where lifecycle/UI behavior is affected
- [ ] Security/adversarial fixtures where trust boundaries are affected
- [ ] Performance/resource evidence where performance claims are made

Details / results:

```text
<commands, scenarios, versions, results>
```

## Risk / rollback

<!-- What can regress? How can the change be reverted or disabled safely? -->

## Review checklist

- [ ] The diff has one independently reviewable purpose.
- [ ] No current implementation detail is preserved solely because it already exists.
- [ ] Ownership/lifecycle boundaries are explicit.
- [ ] Timing/debounce/retry behavior is not being used as a substitute for correctness.
- [ ] New listeners/tasks/timers/queries/browsers/caches have explicit bounds and disposal.
- [ ] Diagnostics are actionable and do not expose full document content by default.
- [ ] Tests/evidence cover the changed contract and important failure paths.
- [ ] Documentation and claims match the implementation.
- [ ] No unrelated dead code, workaround, compatibility shim, or generated artifact is included.

## Final merge gate

A green CI result is necessary but **not sufficient**.

Before merge, the reviewer must inspect the exact final PR HEAD for correctness, architecture/ownership, lifecycle/concurrency, source fidelity, error handling/diagnostics, security, compatibility, performance/resource retention, complexity/dead code, edge cases, tests/evidence, scope, and docs consistency.

Any HEAD change invalidates a prior PASS. Squash merge only with the reviewed `expected_head_sha`. Release/publication is a separate explicit gate.
