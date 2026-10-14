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
- verifies exact-main provenance and fail-closed graph coverage after submission.

Current reference graph: 509 resolved / 52 direct / 1,436 edges / 509 purls.
The workflow fails closed below 450 resolved or 40 direct dependencies.

Rollback:
1. disable or remove the repository-owned publisher;
2. re-enable GitHub-managed Automatic Dependency Submission;
3. require a successful fresh managed main snapshot before considering the rollback complete.

Do not intentionally keep both publishers active.
