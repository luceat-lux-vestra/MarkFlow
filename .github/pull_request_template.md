## Summary

<!-- What changes, and why is this the smallest coherent change? -->

## Related issue / decision

<!-- Link the issue. Link an ADR/RFC when an accepted contract is affected. -->

## Scope

- [ ] Implementation
- [ ] Refactor / architecture
- [ ] Migration / deletion
- [ ] Tests / evidence
- [ ] Documentation
- [ ] Build / CI / dependencies
- [ ] Release-only change

## Contract impact

Describe impact on each applicable area. Write `N/A` with a reason when truly not applicable.

- **Markdown source authority / fidelity:**
- **Native IntelliJ Document / Editor / VFS / undo-redo:**
- **Projection / exact-source reveal / stale-work rejection:**
- **Derived renderer / Mermaid / KaTeX / raw HTML:**
- **Lifecycle / disposal / bounded resources:**
- **Security / local resources / navigation / untrusted content:**
- **Optional JCEF / renderer-runtime isolation:**
- **IDE/API compatibility:**
- **Performance:**
- **User-visible behavior:**

## Architecture / migration ownership

<!-- Which component owns the changed responsibility after this PR? If old code remains TEMPORARY, name its owner and exact deletion criterion. -->

- `RETAIN` / `EXTRACT` / `REPLACE` / `DELETE` / `TEMPORARY` / `UNRESOLVED` impact:
- Superseded responsibility removed:
- Temporary owner + deletion criterion:

## Failure and edge cases

<!-- Include stale projection/render work, renderer unavailable/failure, malformed/unsupported source, external edits, splits, recreate/dispose, JCEF unavailable, hostile resources, and legacy migration failure when relevant. -->

## Validation performed

List commands/scenarios actually run. Do not check a box for work that was not performed.

- [ ] Relevant Kotlin/platform tests
- [ ] Relevant renderer/web tests
- [ ] `./gradlew check`
- [ ] `./gradlew buildPlugin`
- [ ] Plugin verification where compatibility is affected
- [ ] Manual IntelliJ runtime evidence where editor/UI behavior is affected
- [ ] Real renderer/JCEF evidence where a retained renderer runtime is affected
- [ ] Security/adversarial fixtures where trust boundaries are affected
- [ ] Performance/resource evidence where performance claims are made
- [ ] Migration/deletion search proving no unintended consumer remains

Details / results:

```text
<commands, scenarios, versions, results>
```

## Risk / rollback

<!-- What can regress? Prefer reverting an unmerged/merged slice over preserving two permanent editor architectures. -->

## Review checklist

- [ ] The diff has one coherent independently reviewable purpose.
- [ ] IntelliJ `Document` remains the sole mutable Markdown authority.
- [ ] Presentation/renderer work cannot mutate source merely by rendering.
- [ ] No browser/JCEF dependency was introduced into ordinary source-editing correctness.
- [ ] No host↔web source protocol/custom revision/ACK/retry was added without a separate proven requirement.
- [ ] Stale derived work has deterministic rejection.
- [ ] Mermaid/KaTeX continuity does not create duplicate renderer engines.
- [ ] Already-merged Leap code is not preserved solely because it exists.
- [ ] Every temporary mechanism has an owner and deletion criterion.
- [ ] New listeners/tasks/timers/browsers/caches/artifacts have explicit bounds and disposal.
- [ ] Diagnostics are actionable/redacted and do not expose full document content by default.
- [ ] Tests/evidence cover changed success, failure, recovery, lifecycle, compatibility, edge and adversarial contracts.
- [ ] Documentation/issues match the accepted target and actual implementation state.
- [ ] No unrelated dead code, workaround, compatibility shim or generated artifact is included.

## Final merge gate

A green CI result is necessary but **not sufficient**. `UNKNOWN`, `UNVERIFIED`, and insufficient evidence are FAIL.

Before merge, review the exact final PR HEAD plus fresh `main`, merge-base, live ruleset, required checks and unresolved threads. Any HEAD movement invalidates a prior PASS.

Squash merge only with the reviewed `expected_head_sha`, then verify merged `main` SHA/tree/signature and post-main checks before closing the linked Task. Release/publication is a separate explicit gate.
