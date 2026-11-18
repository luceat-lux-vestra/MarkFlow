# Gradle dependency submission

Status: **ACTIVE repository-owned publisher**.

The repository-owned workflow replaces GitHub-managed Automatic Dependency
Submission. Managed ADS should remain disabled while this workflow is active.

The publisher:
- runs automatically only on protected `main` pushes that change Gradle dependency/build-definition inputs;
- supports manual dispatch on `main` for recovery or explicit refresh;
- uses JDK25 on `ubuntu-24.04-arm`;
- keeps Gradle cache access read-only;
- submits with `dependency-graph: generate-and-submit`;
- restricts dependency extraction to `intellijPlatformSandboxRuntimeClasspath`;
- marks that included configuration as runtime scope;
- verifies exact-main provenance and a non-empty, direct runtime graph after submission.

## Why the graph is runtime-scoped

`gradle/actions/dependency-submission` resolves every build dependency by
default. For an IntelliJ plugin that includes IDE host, bundled-plugin, build
tooling, and test configurations that are not owned by the shipped MarkFlow
plugin.

The IntelliJ Platform Gradle Plugin defines
`intellijPlatformSandboxRuntimeClasspath` as the regular sandbox runtime
classpath. `prepareSandbox` copies dependencies from this classpath into the
developed plugin's `lib/` directory. This makes it the repository-owned
security boundary for dependencies shipped with MarkFlow.

The dependency graph therefore excludes IntelliJ host/build/test-only
configurations instead of maintaining package-specific Dependabot ignore lists.
A vulnerable transitive dependency should appear only when it is reachable from
the packaged runtime configuration.

The GitHub Dependency Graph Gradle Plugin may still report Gradle itself as a
direct development dependency independently of configuration filtering. That is
upstream build-tool metadata and is not treated as a MarkFlow runtime library.

## Validation contract

Validation is semantic rather than cardinality-based. The old
`>= 450 resolved / >= 40 direct` guard intentionally no longer applies because
a broad graph is not evidence of a correct product security boundary.

Production and PR validation must instead agree on the exact
`^intellijPlatformSandboxRuntimeClasspath# Gradle dependency submission

Status: **ACTIVE repository-owned publisher**.

The repository-owned workflow replaces GitHub-managed Automatic Dependency
Submission. Managed ADS should remain disabled while this workflow is active.

The publisher:
- runs automatically only on protected `main` pushes that change Gradle dependency/build-definition inputs;
- supports manual dispatch on `main` for recovery or explicit refresh;
- uses JDK25 on `ubuntu-24.04-arm`;
- keeps Gradle cache access read-only;
- submits with `dependency-graph: generate-and-submit`;
- restricts dependency extraction to `intellijPlatformSandboxRuntimeClasspath`;
- marks that included configuration as runtime scope;
- verifies exact-main provenance and a non-empty, direct runtime graph after submission.

## Why the graph is runtime-scoped

`gradle/actions/dependency-submission` resolves every build dependency by
default. For an IntelliJ plugin that includes IDE host, bundled-plugin, build
tooling, and test configurations that are not owned by the shipped MarkFlow
plugin.

The IntelliJ Platform Gradle Plugin defines
`intellijPlatformSandboxRuntimeClasspath` as the regular sandbox runtime
classpath. `prepareSandbox` copies dependencies from this classpath into the
developed plugin's `lib/` directory. This makes it the repository-owned
security boundary for dependencies shipped with MarkFlow.

The dependency graph therefore excludes IntelliJ host/build/test-only
configurations instead of maintaining package-specific Dependabot ignore lists.
A vulnerable transitive dependency should appear only when it is reachable from
the packaged runtime configuration.

The GitHub Dependency Graph Gradle Plugin may still report Gradle itself as a
direct development dependency independently of configuration filtering. That is
upstream build-tool metadata and is not treated as a MarkFlow runtime library.

## Validation contract

Validation is semantic rather than cardinality-based. The old
`>= 450 resolved / >= 40 direct` guard intentionally no longer applies because
a broad graph is not evidence of a correct product security boundary.

 filter, remain fail-closed, and
bind the exact source SHA/ref. The dependency-graph plugin's unavoidable
`org.gradle:gradle-core` entry is the only permitted `development`-scoped
node; every repository-owned dependency admitted by the filtered graph must be
`runtime` scoped.

The acceptance run for this cutover produced one resolved/direct dependency:
the upstream Gradle build-tool node. That means MarkFlow currently ships no
external Gradle runtime dependency through its plugin `lib/`. Future packaged
runtime dependencies will enter the graph automatically through the same
configuration rather than through package-name allow/ignore lists.

Rollback:
1. disable or remove the repository-owned publisher;
2. re-enable GitHub-managed Automatic Dependency Submission;
3. require a successful fresh managed main snapshot before considering the rollback complete.

Do not intentionally keep both publishers active.
