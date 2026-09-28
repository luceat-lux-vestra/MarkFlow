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
4. completed target implementation/convergence Tasks #143–#156 as acceptance evidence under those contracts;
5. current implementation/tests/historical docs as evidence only.

#139/#141 architecture reset and migration reconciliation, #153 production native-editor cutover, #154 browser-editor/protocol/trust purge, #155 dependency/toolchain/JCEF/settings convergence, and #156 final compatibility/lifecycle/performance/release convergence are completed. #84 and the parent Leap Epic #52 are closed. These issues remain repository-visible acceptance/provenance records, not active migration work.

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

Because repository squash commits use PR metadata, PR titles and bodies must not contain GitHub Actions skip directives. Required `PR Metadata Safety` validation re-runs on metadata edits and fails closed on recognized skip markers and `skip-checks: true` trailers. Automated/API squash merges must also provide an explicitly sanitized commit message instead of inheriting arbitrary PR body text. If a default-branch workflow is ever suppressed before a run exists, use the applicable exact-SHA recovery path and treat the original missing run as an audit anomaly rather than as PASS.

Documentation-only pull requests may skip heavy product validation only when the required `Build` job classifies every changed path against the trusted base-revision allowlist. The required `Build`, `Test`, `Inspect code`, and `Verify plugin` contexts still materialize and succeed; unknown paths, non-documentation changes, rename/copy provenance outside the allowlist, changed-file metadata failures, or classifier uncertainty fall back to full validation.

Repository hardening is owned separately from runtime Leap work. Runtime architecture acceptance never follows from repository CI green alone.

## Dependency update governance

Dependabot is a discovery and maintenance mechanism, not authority to merge a dependency change. Dependency pull requests never auto-merge and remain subject to the same exact-final-HEAD proof obligation as maintainer-authored changes.

- Security updates remain independent from routine version-update grouping unless a separately reviewed security-grouping policy explicitly says otherwise.
- Major updates remain individually visible as migration signals; do not blanket-ignore them merely to reduce notification volume.
- Gradle libraries/plugins/toolchain updates remain individually reviewable rather than being hidden in one generic batch.
- In `/webview`, only explicitly listed low-risk development tools may share a minor/patch version-update group. TypeScript, Vite, production dependencies, editor dependencies, and renderer dependencies remain individual update signals.
- GitHub Actions minor/patch version updates may be grouped as one workflow-maintenance unit; major updates remain individual.
- A bump that requires code, configuration, behavior, compatibility, or architecture migration must move to a dedicated owned issue/work item instead of being merged as a routine bot bump.
- Dependency grouping must never weaken Dependabot alert/security-update behavior, the merge gate, or release provenance.

## Release governance

Release/publication is a separate irreversible decision. Merging a pull request, passing CI, creating a draft release, or closing a runtime/hardening issue does not authorize Marketplace publication. Detailed tag, version, artifact, signing and recovery provenance is governed by the maintained release process and the completed Track #61 record.

## Evolution

If additional maintainers are added, this document must define nomination, approval, removal, inactivity, conflict-of-interest, security-response and release-authority rules before granting elevated repository permissions.
