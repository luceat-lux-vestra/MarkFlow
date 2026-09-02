# Development Process

## Definition of ready

A non-trivial implementation issue is ready when it has:

- a concrete problem statement and user/engineering impact;
- explicit scope and non-goals;
- acceptance/exit criteria;
- the authoritative contract or an ADR requirement identified;
- known lifecycle, source-fidelity, security, compatibility, and migration risks called out where relevant;
- expected validation/evidence described.

If the architecture contract is unknown, create a spike or ADR issue instead of encoding another accidental design.

## Implementation

- Branch from current `main`.
- One independently reviewable purpose per PR.
- Do not preserve bootstrap implementation solely to reduce diff size.
- Do not combine cleanup, architecture migration, feature behavior, and release work unless they are inseparable for correctness.
- Add diagnostics at ownership/failure boundaries, not arbitrary verbose logging.
- Preserve unrelated user changes.

## Definition of done

A change is done only when:

- exit criteria are demonstrably satisfied;
- changed contracts and failure paths have appropriate tests/evidence;
- resource ownership/disposal is explicit;
- error handling and diagnostics are actionable;
- security/compatibility implications have been addressed;
- stale code, flags, shims, and comments introduced or obsoleted by the change are removed;
- documentation/evidence matches implementation;
- required CI checks pass on the exact final HEAD;
- strict final-HEAD review passes.

Agent completion, a green CI icon, or a successful happy-path demo is not definition-of-done evidence by itself.

## Review and merge

Review the exact final PR HEAD. A PASS is valid only for that SHA. Any new commit invalidates it.

Before merge, re-read PR HEAD, then squash merge with the expected reviewed SHA pinned. After merge verify:

1. GitHub reports the PR merged;
2. the squash commit exists on `main`;
3. `main` moved to the expected result;
4. the linked issue exit criteria remain satisfied.

## Release

Release/publication is never an automatic continuation of merge. Follow `../release/process.md`.
