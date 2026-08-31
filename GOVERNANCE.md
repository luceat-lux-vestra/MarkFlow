# Governance

MarkFlow is currently maintained by `@luceat-lux-vestra`.

## Decision making

- Routine changes are decided through pull request review.
- Significant architecture changes require an issue and an ADR before implementation.
- The following are always significant: authoritative document ownership, persistence/source-fidelity rules, host↔webview protocol, synchronization/revision semantics, JCEF ownership/lifecycle, security/trust boundaries, supported IDE compatibility, and release/publication policy.
- Accepted decisions and their rationale must be recorded in the repository; historical bootstrap plans are not architectural authority.

## Maintainer responsibilities

The maintainer is responsible for:

- enforcing the exact-final-HEAD merge gate;
- protecting repository/release credentials and branch rules;
- maintaining CI and static-analysis integrity rather than merely green status;
- triaging security reports;
- keeping architecture, compatibility, testing, and release documentation synchronized with implementation;
- separating implementation completion from publication authorization.

## Merge governance

`main` should be changed through pull requests only. Squash merge is the intended merge method. Force-push and deletion of `main` are forbidden. Required checks and review conversations must be satisfied before merge.

A review PASS belongs to one exact PR HEAD SHA. If HEAD changes, review again. Merge only the reviewed SHA and verify resulting `main` movement after merge.

## Evolution

If additional maintainers are added, this document must define nomination, approval, removal, inactivity, conflict-of-interest, security-response, and release-authority rules before granting elevated repository permissions.
