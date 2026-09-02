# Release and Publication Process

Release/publication is a separate irreversible gate from implementation merge.

This document defines the repository-level release boundary and authorization discipline. Track #61 owns the active publication path's immutable-tag, version/artifact identity, signing, provenance, and recovery implementation. Until that Track's exit criteria are proven against merged `main` and live settings, this document must not be read as evidence that publication is safe.

## Principles

- A merged PR is not release authorization.
- Agent completion or CI success is not publication authorization. A release must be created explicitly by an authorized maintainer.
- Marketplace/signing credentials must only be exercised after an explicit release decision.
- Release evidence must refer to the exact commit/artifact being published.

## Release candidate checklist

Before publication, record and verify:

1. exact `main` commit SHA selected for release;
2. changelog/release notes accurately describing user-visible changes and known limitations;
3. clean build/test/static-analysis results for that commit;
4. plugin packaging and IntelliJ Plugin Verifier results for the supported compatibility matrix;
5. manual smoke scenarios for editor open/edit/save/reopen, theme/settings, Mermaid/KaTeX, and lifecycle behavior relevant to the release;
6. security-sensitive changes and dependency updates reviewed;
7. generated release artifact identity/checksum retained where practical;
8. rollback/withdrawal plan understood.

The release workflow accepts only the repository's timestamp version format
`yy.MM.dd.HHmmss` as an immutable tag/version identity. It resolves the tag to a
commit, requires that commit to be reachable from reviewed `main`, builds with
that exact version, and verifies the archive's `META-INF/plugin.xml` version and
SHA-256 before any Marketplace credential is used.

The workflow records a pending release-identity asset before publication. A
rerun with a pending identity stops for manual recovery instead of guessing
whether Marketplace publication completed. A completed identity may be replayed
only when the tag, source commit, artifact name, and artifact digest all match;
an existing tag/version is never rewritten or repointed automatically. The
preflight helper and its fixtures are non-publishing and do not require
Marketplace credentials.

## Publication authorization

Publication requires an explicit maintainer decision after reviewing the release candidate evidence. Do not infer authorization from issue/PR closure.

## Post-publication verification

After publishing:

- verify the expected version/artifact is visible at the intended distribution channel;
- verify release metadata and notes;
- perform a clean-install smoke test when practical;
- record any incident or unexpected incompatibility as a new issue rather than silently patching release history.

## Hotfixes

Hotfix urgency does not waive the exact-final-HEAD merge gate or release gate. Scope may be minimized, but correctness, compatibility, security, and artifact verification remain required.
