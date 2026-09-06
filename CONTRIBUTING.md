# Contributing to MarkFlow

MarkFlow is maintained under a proof-obligation review discipline. Public visibility does not reduce the merge standard.

## Before changing code

- Search existing issues and pull requests first.
- Read `AGENTS.md`, `docs/architecture/README.md`, and the focused subsystem/Task documentation.
- Treat #78, accepted ADR 0001/0002, reconciled #79–#84 Tracks, and #141's migration map as authoritative over current implementation and historical phase wording.
- The target editor is native IntelliJ `Document`/`Editor` authority with source-neutral derived presentation. Do not assume the current browser editor, host↔web sync, source revisions, browser pool, loopback realm, CSP/request policy, or editor JCEF lifecycle survive.
- Mermaid/KaTeX are retained product renderer capabilities. Do not delete/rewrite them merely because their current editor adapter is superseded.
- Significant changes to source authority, native editor shell, projection semantics, renderer engine/runtime boundary, trust/resource/navigation policy, settings migration, compatibility, or release behavior require an explicit issue and architecture decision when not already covered by an accepted Task/ADR.
- Report suspected vulnerabilities privately as described in `SECURITY.md`.

## Development workflow

1. Start from a focused issue with explicit exit criteria and architecture dependency.
2. Branch from fresh current `main`.
3. Keep one coherent independently reviewable purpose per pull request.
4. Classify affected migration responsibilities (`RETAIN`, `EXTRACT`, `REPLACE`, `DELETE`, `TEMPORARY`, `UNRESOLVED`) where Leap migration is involved.
5. Give every temporary mechanism an owner and deletion criterion.
6. Add tests/evidence appropriate to success, failure, recovery, regression, compatibility, lifecycle, edge, ownership and adversarial risk.
7. Open a pull request using the repository template.
8. Resolve every review conversation and required check.
9. Re-review the exact final PR HEAD against fresh `main`, merge-base and live ruleset.
10. Squash-merge only the reviewed `expected_head_sha`.
11. Verify resulting `main` SHA/tree/signature and post-main checks before closing the Task.

Any HEAD change invalidates a prior PASS/evidence.

Recommended branch names:

```text
feat/123-short-description
fix/123-short-description
refactor/123-short-description
test/123-short-description
docs/123-short-description
chore/123-short-description
spike/123-short-description
task/123-short-description
```

Use Conventional Commit-style PR titles, for example:

```text
feat(editor): add native projection controller
refactor(renderer): extract Mermaid and KaTeX service
fix(trust): reject renderer resource traversal
docs(leap): reconcile migration ownership
```

The pull request title becomes the squash commit subject on `main`.

## Local validation

Run the checks relevant to the change. For a normal code change, the expected baseline is:

```shell
./gradlew check
./gradlew buildPlugin
./gradlew verifyPlugin

cd webview
npm ci --no-audit --no-fund
npm run test:source
npm run build
```

The web commands remain required while retained renderer/current-runtime consumers still exist. #155 will converge the toolchain after browser-editor purge.

Do not claim a check was run if it was not. Native editor/projection changes require real IntelliJ runtime scenarios where helper tests cannot prove behavior. Retained JCEF renderer changes require real renderer-runtime evidence where applicable.

## Review standard

CI green is necessary but not sufficient. `UNKNOWN`, `UNVERIFIED`, and insufficient evidence are FAIL.

Review the exact final HEAD for functional correctness, source fidelity/native IntelliJ semantics, architecture/ownership, migration deletion criteria, lifecycle/stale-work/resource bounds, renderer continuity, error handling/diagnostics, trust boundaries, compatibility, performance evidence, complexity/dead code, failure/recovery/edge/adversarial coverage, scope, and documentation consistency.

Temporary old browser-editor code may still require regression testing before cutover, but fixing it does not make it target architecture.

## Release boundary

Merging implementation does not authorize publication. Marketplace/release publication is a separate irreversible gate governed by `docs/release/process.md` and Track #61.
