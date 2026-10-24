# Dependabot dependency fast-lane policy

Tracked by R2A #114.

## Current policy

The production pilot is intentionally narrow:

> **Dependabot identity + trusted-base proof of a pure ordinary dependency bump**

Dependabot identity alone is insufficient. The decision is produced by
`.github/scripts/dependency-pr-fast-lane.py` loaded from the exact PR base
revision. Unsupported, malformed, mixed, or ambiguous changes fail closed to
the normal full CI path.

## Eligible dependency updates

### npm

A PR may use the fast lane when all changed files are confined to
`webview/package.json` / `webview/package-lock.json` and the manifest change
is limited to versions of existing dependencies.

This covers direct, development, and transitive lock-resolution updates.

Dependency add/remove, package scripts/metadata changes, lockfile schema/root
identity changes, rename/copy, or mixed files fall back to full CI.

### Gradle

A PR may use the fast lane when the only changed file is
`gradle/libs.versions.toml`, catalog structure is unchanged, and every changed
version key belongs exclusively to `[libraries]`.

Any `[plugins]` version change remains full CI. This includes Kotlin,
IntelliJ Platform, Kover, Qodana, changelog, and other Gradle plugins.

Gradle wrapper, `build.gradle.kts`, `settings.gradle.kts`, workflow/policy,
product source/test/config, or unknown changes remain full CI.

## Fast-lane pre-merge evidence

A positive trusted fast-lane decision does **not** mean functional equivalence.

The required evidence is deliberately bounded:

- **Build** remains mandatory and executes `buildPlugin`, preserving
  dependency resolution, compilation, renderer build, and plugin packaging.
- **Test** runs ecosystem-specific low-cost validation:
  - npm: `./gradlew testWebviewDependencyContract`
  - Gradle libraries: `./gradlew testClasses integrationTestClasses`
- **Dependency Review** remains mandatory with its existing severity gate.
- **PR Metadata Safety** remains mandatory.
- **Merge Gate** remains the single stable required context.

The following expensive full-product evidence becomes non-applicable only for a
trusted positive fast-lane decision:

- ordinary full JVM/runtime test execution and Kover artifact;
- Chromium Mermaid acceptance;
- Qodana full-product inspection;
- Plugin Verifier;
- Native Runtime Evidence;
- Starter Driver E2E / visual evidence.

The normal path is unchanged for every non-fast-lane PR.

## Cheap deterministic contract

The static Mermaid dependency/detector contract is separated from Chromium
acceptance by `testWebviewDependencyContract`.

That preserves the historical #454 behavior: an unaudited Mermaid version bump
can still fail before merge without paying for the full browser acceptance
suite.

## Trust model

The workflow must:

1. enumerate changed files from GitHub with rename/copy provenance;
2. load the classifier from the exact trusted PR base SHA;
3. bind classification to GitHub's PR author, base SHA, head SHA, and changed
   file count;
4. fetch base/head dependency metadata directly from GitHub;
5. validate classifier self-tests and output shape;
6. fail closed to full CI on any unavailable/malformed input.

A PR that edits the workflow, classifier, build scripts, or other policy
surfaces cannot classify itself into the fast lane.

## Risk policy

This is an explicit velocity/correctness tradeoff. Routine dependency updates
need to demonstrate buildability, compilation, packaging, cheap deterministic
contracts, and dependency-security admission before merge. Expensive
full-product functional equivalence is not a blocking requirement for this
narrow class.

A functional regression that survives these checks is handled by fix or revert
after merge. Repeated or high-impact regressions are grounds to tighten or
disable the policy.

## Rollback

Rollback is intentionally simple: stop consuming
`dependency_fast_lane=true` and the existing full validation path resumes.
