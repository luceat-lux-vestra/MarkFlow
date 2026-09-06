# Governance

MarkFlow is currently maintained by `@luceat-lux-vestra`.

## Decision making

- Routine changes are decided through pull request review.
- Significant architecture changes require an issue and an ADR unless they are already execution details under an accepted ADR/Task boundary.
- The following are always architecture-significant: authoritative Markdown ownership; primary native editor/integration shell; source-fidelity or persistence semantics; projection/reveal/stale-work model; renderer-engine or renderer-runtime boundary; JCEF lifecycle where retained as renderer infrastructure; local resource/navigation/raw-HTML trust policy; settings/state migration identity; supported IDE compatibility; and release/publication policy.
- A host↔web source-edit protocol, custom revision/ACK/recovery scheme, browser editor lifecycle, or source-native browser realm is **not** a standing governance requirement. Reintroducing one after ADR 0001 requires a new independent architecture proof.
- Accepted decisions and rationale must be repository-visible. Historical bootstrap plans/current implementation are evidence only.

## Current architecture authority

Authority order is:

1. #78 product/source-fidelity contract;
2. accepted ADRs, currently ADR 0001 and ADR 0002;
3. reconciled #79–#84 responsibility Tracks;
4. focused target Tasks, including #143–#156;
5. current implementation/tests/historical docs as evidence only.

#139 is the architecture reset gate. It remains open until #141 migration classification/guidance/backlog reconciliation is merged and post-main verified. Closing #139 does not close #52.

## Maintainer responsibilities

The maintainer is responsible for:

- enforcing the exact-final-HEAD merge gate;
- protecting repository/release credentials and branch rules;
- maintaining CI/static-analysis integrity rather than merely green status;
- triaging security reports;
- keeping architecture, migration ownership, compatibility, testing and release documentation synchronized with implementation;
- preventing already-merged Leap code from receiving sunk-cost protection;
- ensuring every temporary migration mechanism has a real deletion owner/criterion;
- separating implementation completion from publication authorization.

## Merge governance

The intended merge contract for `main` is pull-request-only, squash-only, linear history, no force-push/deletion, fresh required checks, resolved review conversations, and no routine bypass. Live repository/ruleset readback remains authoritative when configuration changes.

A review PASS belongs to one exact PR HEAD SHA. If HEAD changes, review again. `UNKNOWN`, `UNVERIFIED`, or insufficient evidence are FAIL. Merge only the reviewed `expected_head_sha`, then verify resulting `main` SHA/tree/signature and post-main checks before closing the linked Task.

Repository hardening is owned separately from runtime Leap work. Runtime architecture acceptance never follows from repository CI green alone.

## Release governance

Release/publication is a separate irreversible decision. Merging a pull request, passing CI, creating a draft release, or closing a runtime/hardening issue does not authorize Marketplace publication. Detailed tag, version, artifact, signing and recovery provenance belongs to the release Track/process.

## Evolution

If additional maintainers are added, this document must define nomination, approval, removal, inactivity, conflict-of-interest, security-response and release-authority rules before granting elevated repository permissions.
