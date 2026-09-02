# Contributing to MarkFlow

MarkFlow is currently maintained as a private repository, but changes should follow the same review discipline expected of a public project.

## Before changing code

- Search existing issues and pull requests first.
- Read `AGENTS.md` and the relevant subsystem documentation under `docs/`.
- Treat issue #52 and later accepted architecture decisions as authoritative over bootstrap-era implementation details.
- Significant changes to document ownership, host↔webview protocol, synchronization semantics, JCEF lifecycle, security policy, supported IDE compatibility, or release behavior require an explicit issue plus an ADR before implementation.
- Report suspected vulnerabilities privately as described in `SECURITY.md`.

## Development workflow

1. Start from a focused issue with explicit exit criteria.
2. Branch from current `main`.
3. Keep one independently reviewable purpose per pull request.
4. Add tests/evidence appropriate to the risk being changed.
5. Open a pull request using the repository template.
6. Resolve every review conversation and required check.
7. Re-review the exact final PR HEAD before merge.
8. Squash-merge only the reviewed HEAD SHA.

The live `main` policy is squash-only with merge commits and rebase merges disabled, branch-update support enabled, auto-merge disabled, strict required checks, resolved review conversations, linear history, and no routine bypass. The current solo-maintainer ruleset requires zero approvals; `CODEOWNERS` routes review ownership without adding a code-owner approval gate. Any new commit invalidates the previous review PASS.

Recommended branch names:

```text
feat/123-short-description
fix/123-short-description
refactor/123-short-description
test/123-short-description
docs/123-short-description
chore/123-short-description
spike/123-short-description
```

Use Conventional Commit-style PR titles, for example:

```text
feat(protocol): version host-webview messages
fix(sync): reject stale webview revisions
refactor(browser): make JCEF ownership explicit
docs(architecture): record document authority decision
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

Do not claim a check was run if it was not. UI/JCEF lifecycle changes usually require manual IDE scenarios in addition to automated tests.

## Review standard

CI green is necessary but not sufficient. Review the exact final HEAD for functional correctness, source fidelity, architecture/ownership, lifecycle, synchronization/concurrency, error handling, diagnostics, security, compatibility, performance/resource retention, complexity/dead code, edge cases, tests/evidence, scope, and documentation consistency.

Any HEAD change invalidates a prior PASS.

## Release boundary

Merging implementation does not authorize publication. Marketplace/release publication is a separate irreversible gate governed by `docs/release/process.md` and Track #61. Do not infer release authorization from CI, a draft release, or issue/PR closure.
