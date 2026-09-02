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

The live repository configuration currently enforces the following contract for `main`:

- changes require a pull request;
- squash is the only allowed merge method; merge commits and rebase merges are disabled;
- branch-update support is enabled, while auto-merge is disabled;
- history is linear, force-push/non-fast-forward updates and deletion are blocked;
- strict required checks must be fresh and review conversations must be resolved;
- zero approvals are required for the current solo-maintainer model, stale approvals are dismissed on push, and no bypass actors are configured;
- `CODEOWNERS` routes ownership but does not impose a separate code-owner approval requirement.

The exact required-check classification, workflow producers, and live-context reconciliation are owned by Track #60. This document does not claim staged CI controls are already required.

A review PASS belongs to one exact PR HEAD SHA. If HEAD changes, review again. Merge only the reviewed SHA and verify resulting `main` movement after merge.

Repository hardening is owned by Epic #54: #51 owns governance/live settings, #60 owns CI/workflow/drift controls, and #61 owns release/publication provenance and recovery. Issue #52 is a separate runtime architecture Epic and must not be pulled into this hardening work.

Release/publication is a separate irreversible decision. Merging a pull request, passing CI, creating a draft release, or closing a hardening issue does not authorize Marketplace publication. Detailed tag, version, artifact, signing, and recovery provenance belongs to Track #61.

## Evolution

If additional maintainers are added, this document must define nomination, approval, removal, inactivity, conflict-of-interest, security-response, and release-authority rules before granting elevated repository permissions.
