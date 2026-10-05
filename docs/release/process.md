# Release and Publication Process

Release/publication is a separate irreversible gate from implementation merge.

This document defines the repository-level release boundary and authorization discipline. Track #61 completed the publication-path design and implementation for immutable tags, version/artifact identity, signing, provenance, and recovery. This document is the maintained operational contract after that Track's closure; closure of #61 does not authorize any particular release or weaken the per-release evidence and maintainer-authorization requirements below.

The operational recovery procedure for an ambiguous or partial Marketplace publication is maintained in [`recovery.md`](./recovery.md).

## Principles

- A merged PR is not release authorization.
- Agent completion or CI success is not publication authorization. A release must be created explicitly by an authorized maintainer.
- Marketplace/signing credentials must only be exercised after an explicit release decision and only through the `jetbrains-marketplace` GitHub Environment.
- Release evidence must refer to the exact commit/artifact being published.
- A pending publication identity is a fail-closed lock, not evidence that Marketplace publication failed.
- An existing release version/tag must never be rewritten or reused for different source during recovery.

## Release candidate checklist

Before publication, record and verify:

1. exact `main` commit SHA selected for release;
2. changelog/release notes accurately describing user-visible changes and known limitations;
3. clean build/test/static-analysis results for that commit;
4. plugin packaging and IntelliJ Plugin Verifier results for the supported compatibility envelope, including any maintained forward-compatibility probes;
5. Starter/Driver acceptance on the authoritative IntelliJ IDEA 2026.2.3 full-product runtime: one exact candidate ZIP packaged once, one recorded SHA-256, all required parallel shards, and the canonical whole-product journey consuming that same artifact;
6. deterministic #339 visual acceptance on IDEA 2026.2.3 must pass against reviewed versioned goldens and the exact recorded visual-environment identity, using the same exact packaged plugin artifact; baseline changes are never auto-accepted by CI;
7. for a final #309/release candidate, the explicit independent canonical-repeat job on a fresh runner/IDE process must also pass against the same exact packaged artifact; this repeat is evidence, not a retry path. Real no-JCEF native-editing and retained-JCEF renderer evidence remain required within their maintained scope;
8. the quantitative projection/render/bundle regression tripwires in `docs/engineering/leap-convergence-gate.md` passing on the exact release candidate;
9. manual smoke scenarios only for behavior that cannot be deterministically automated, with the unautomated boundary recorded rather than substituting manual observation for available CI evidence;
10. security-sensitive changes and dependency updates reviewed;
11. generated release artifact identity/checksum retained where practical;
12. rollback/withdrawal plan understood.

The release-blocking full-product Starter/Driver runtime is IntelliJ IDEA 2026.2.3. Forward compatibility remains independently covered by Plugin Verifier and any explicitly scoped informational probes; an unreleased EAP full-product run is not a prerequisite for Marketplace publication. A workflow rerun never converts a deterministic red Starter shard or canonical journey into acceptable evidence.

Automatic Marketplace publication is triggered only by a stable GitHub Release (`released`). Publication tags use stable `vMAJOR.MINOR.PATCH` identity and the JetBrains plugin version is the same SemVer with the leading `v` removed. The production release job is bound to the `jetbrains-marketplace` GitHub Environment; environment protection/ref policy and the four publication/signing secret names are live administrative prerequisites rather than repository-source claims.

The release workflow accepts only stable `vMAJOR.MINOR.PATCH` tags with major version 1 or greater as immutable release identity. It resolves the tag to a commit, requires that commit to be reachable from reviewed `main`, derives the effective plugin version by removing exactly the leading `v`, builds with that exact version, and verifies the archive's `META-INF/plugin.xml` version and SHA-256 before any Marketplace credential is used.

For a new publication, the workflow first builds the exact candidate, then explicitly signs it and verifies that signature. The signature-verified `*-signed.zip` is the repository publication identity: its digest is recorded by the preflight, it is covered by GitHub build-provenance attestation, and the same signed archive is used for Marketplace publication and the GitHub Release asset. `publishPlugin` is invoked with `signPlugin` excluded so no post-attestation re-signing can change the publication subject.

The workflow records a pending release-identity asset only after signed-artifact validation and attestation. A rerun with a pending identity stops for manual recovery instead of guessing whether Marketplace publication completed. A completed identity may be replayed only when the tag, source commit, signed artifact name, and signed artifact digest all match; an existing tag/version is never rewritten or repointed automatically. The preflight helper and its fixtures are non-publishing and do not require Marketplace credentials.

## Publication authorization

Publication requires an explicit maintainer decision after reviewing the release candidate evidence. Do not infer authorization from issue/PR closure.

Recovery of an already-started release also requires explicit maintainer authorization before any Marketplace retry, release-asset mutation, published-marker upload, or Marketplace withdrawal. See [`recovery.md`](./recovery.md).

## Post-publication verification

After publishing:

- verify the expected version is present at the intended Marketplace distribution path/state;
- verify the GitHub Release signed artifact and recorded release identity agree;
- verify the signed artifact's GitHub attestation (for example with `gh attestation verify <artifact> -R luceat-lux-vestra/MarkFlow`);
- verify release metadata and notes;
- perform a clean-install smoke test when practical;
- record any incident or unexpected incompatibility as a new issue rather than silently patching release history.

If workflow execution fails after the pending identity is uploaded, do not infer publication failure from workflow failure or from delayed public visibility. Follow [`recovery.md`](./recovery.md) and classify the Marketplace state as `Exists`, `Absent`, or `Unknown`; `Unknown` fails closed.

## Hotfixes

Hotfix urgency does not waive the exact-final-HEAD merge gate or release gate. Scope may be minimized, but correctness, compatibility, security, and artifact verification remain required.
