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

**Required order; GitHub Squash Merge is not a substitute for branch-history cleanup:**

1. Inspect the work-branch commit log from current merge-base through HEAD and review the full change set. Consolidate/remove temporary experiments, checkpoint, fixup and superseded commits, without rewriting protected/merged `main`. If the history is already clean, explicitly record the inspection and reason no rewrite is necessary.
2. Verify the final branch HEAD after cleanup. A history rewrite changes the proof identity: rerun affected checks, runtime evidence when relevant, and strict code review on that **exact new SHA**. Review conversations and required checks must be satisfied for that SHA.
3. Fresh-read `main`, merge-base, live ruleset, PR HEAD and unresolved reviews immediately before merge. Do not promote `UNKNOWN`, `UNVERIFIED` or insufficient evidence to PASS.
4. Squash-merge only with `expected_head_sha` pinned to the exact reviewed HEAD; never treat Squash Merge itself as fulfillment of step 1.

Review PASS belongs only to the exact final PR HEAD. Any subsequent SHA change invalidates it.

After merge verify:

1. GitHub reports the PR merged;
2. the squash commit exists on `main`;
3. `main` moved to the expected result;
4. the linked issue exit criteria remain satisfied.

## Release

Release/publication is never an automatic continuation of merge. Follow `../release/process.md`.
