# Dependency and toolchain lifecycle (EG-DEP-001)

Owner: MarkFlow #652. Use native Dependabot for Gradle, npm, GitHub Actions and Go actionlint. No additional update service, write credential or custom update-PR workflow.

The renderer follows the standard nvm newest-LTS selector (lts/*). The existing Gradle build resolves the official Node release index once per invocation, then provisions and checks the exact versioned distribution without requiring PATH Node or npm. The resolved version is logged and included in renderer task cache identity. Explicit offline reproduction requires the previously resolved version. The npm manifest retains the consumer engine minimum, not a moving upper cap; Dependabot owns @types/node and renderer packages. The scheduled read-only check flags a new LTS/types major mismatch for coordinated review.

Go follows the supported stable channel; actionlint is a Go module dependency. zizmor follows the immutable official action's bundled hash-locked CLI via native Actions Dependabot. Existing negative controls remain mandatory.

Java uses the current LTS major with setup-java check-latest for patch updates, and Ubuntu uses the current GA ARM64 image. Neither provides a supported newest-LTS/GA ARM64 major alias that preserves these requirements. Existing scheduled drift audit flags changes for ordinary reviewed migration; no automatic PR generator or credential is required. Bounded provider-gap registry entries follow the prior portfolio pattern; do not treat a stale target as compliant.

The visual acceptance input is distinct from the runner: the verified Noble Liberation Sans package is hash-checked before extraction, the existing exact font hash and raster evidence remain unchanged. Review the pinned test input on the recorded trigger. Central audit results are authoritative only for merged source; no claim of new CI success until exact-head gates pass.
