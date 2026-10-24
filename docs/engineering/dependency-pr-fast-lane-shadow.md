# Dependabot dependency fast-lane shadow policy

Stage 0 for R2A #114 is **advisory only**. It does not change CI applicability.

## Rule

A fast-lane candidate requires both:

1. PR author is `dependabot[bot]`; and
2. trusted diff proves a pure ordinary dependency bump.

Dependabot identity alone is insufficient. Any mixed, unsupported, or ambiguous
change falls back to the existing full CI path.

## FAST candidates

### npm

Changes confined to `webview/package.json` / `webview/package-lock.json`
where:

- existing dependency names are unchanged;
- only dependency version specifications change in `package.json`;
- non-dependency package metadata is unchanged;
- lockfile schema/root identity is unchanged.

This includes direct, dev, and transitive-resolution Dependabot updates.

The classifier deliberately does not reimplement npm's resolver. The eventual
fast lane must run `npm ci`, which owns manifest/lock consistency.

### Gradle

A change confined to `gradle/libs.versions.toml` is FAST only when:

- catalog structure is unchanged;
- only existing `[versions]` values change;
- every changed version is referenced by `[libraries]`;
- none is referenced by `[plugins]`.

## FULL CI

Always full in the initial pilot:

- Gradle `[plugins]` updates;
- Gradle wrapper;
- `build.gradle.kts` / `settings.gradle.kts`;
- GitHub Actions / workflow / policy changes;
- dependency add/remove or structural catalog changes;
- dependency + source/test/config/workflow mixed changes;
- human-authored dependency PRs;
- rename/copy, malformed metadata, unknown paths, parse errors.

There is no package-name allowlist and no generic dependency-role framework.

## Intended production boundary

Fast-lane eligibility is not functional-equivalence proof.

A later production pilot must retain:

- dependency resolution/install;
- compile/build/package;
- cheap deterministic dependency contracts;
- Dependency Review;
- stable Merge Gate.

Expensive functional evidence may be non-applicable for this narrow class, with
surviving regressions handled by fix/revert after merge.

## Cheap-test prerequisite

Current `testWebviewSource` is not a suitable cheap primitive because the same
Mermaid test file also launches real Chromium acceptance.

Before production routing changes, separate the static Mermaid
version/detector contract (which correctly blocked historical #454) from the
expensive Chromium/full-product acceptance.

## Historical/adversarial intent

- #460 DOMPurify transitive lock update -> FAST candidate
- #426 `@types/node` -> FAST candidate
- #424 Vite -> FAST candidate
- #454 Mermaid -> FAST candidate scope; cheap contract can still fail
- ordinary Gradle `[libraries]` version-only bump -> FAST candidate
- #423 Kotlin JVM plugin -> FULL
- Kover/Qodana/other Gradle plugin update -> FULL
- #422 Gradle wrapper -> FULL
- #455 GitHub Actions/workflow -> FULL
- #209-style dependency bump plus `build.gradle.kts` repair -> FULL

The built-in self-test also covers non-Dependabot actor, mixed/high-risk paths,
package metadata changes, and malformed changed-file counts.

## Trust / rollback

A future production caller must load this classifier from the exact trusted PR
base SHA. PR-head policy cannot declare its own checks unnecessary.

Any unsupported/error state returns `fast_lane=false`.

Rollback is simply to stop consuming the fast-lane result and use the existing
full CI path.
