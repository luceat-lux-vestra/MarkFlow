#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

die() {
  echo "dependency-submission: $*" >&2
  exit 1
}

assert_contract() {
  local workflow="$1"
  [ -f "$workflow" ] || die "dependency submission workflow is missing"
  grep -Fxq '  push:' "$workflow" || die "publisher must run from push"
  grep -Fxq '    branches: [main]' "$workflow" || die "publisher must be scoped to main"
  grep -Fxq '  workflow_dispatch:' "$workflow" || die "manual cutover/recovery dispatch is missing"
  if grep -Eq '^  (pull_request|pull_request_target|workflow_run|repository_dispatch):' "$workflow"; then
    die "publisher must never run from PR or untrusted webhook events"
  fi
  local actual_paths expected_paths
  actual_paths="$(awk '
    /^    paths:[[:space:]]*$/ { inside=1; next }
    inside && /^  [^ ]/ { exit }
    inside && /^      - / { print }
  ' "$workflow" | LC_ALL=C sort)"
  expected_paths="$(printf '%s\n' \
    "      - '.github/workflows/dependency-submission.yml'" \
    "      - 'build.gradle.kts'" \
    "      - 'settings.gradle.kts'" \
    "      - 'gradle.properties'" \
    "      - 'gradle/**'" | LC_ALL=C sort)"
  [ "$actual_paths" = "$expected_paths" ] || die "publisher dependency-input paths drifted"
  grep -Fxq 'permissions: {}' "$workflow" || die "workflow default permissions must remain empty"
  grep -Fq "github.ref == 'refs/heads/main'" "$workflow" || die "main-only job guard is missing"
  grep -Fxq '    runs-on: ubuntu-24.04-arm' "$workflow" || die "publisher must use ARM64"
  grep -Fxq '      contents: write' "$workflow" || die "submission job lacks contents: write"
  grep -Fxq '          cache-read-only: true' "$workflow" || die "publisher must never write Gradle caches"
  grep -Fxq '          dependency-graph: generate-and-submit' "$workflow" || die "publisher is not in submit mode"
  grep -Fxq "          dependency-graph-include-configurations: '^intellijPlatformSandboxRuntimeClasspath$'" "$workflow" ||
    die "publisher must submit only the packaged plugin runtime configuration"
  grep -Fxq "          DEPENDENCY_GRAPH_RUNTIME_INCLUDE_CONFIGURATIONS: '^intellijPlatformSandboxRuntimeClasspath$'" "$workflow" ||
    die "publisher must classify the packaged plugin configuration as runtime scope"
  grep -Fxq '          dependency-graph-continue-on-failure: false' "$workflow" || die "publisher must fail closed"
  grep -Fq 'gradle/actions/dependency-submission@8da46b539cd8fe6ab27ca75128919a18d757604c' "$workflow" || die "dependency-submission action pin drifted"
  grep -Fq '          persist-credentials: false' "$workflow" || die "checkout credentials must not persist"
  grep -Fq '          ref: ${{ github.sha }}' "$workflow" || die "publisher must checkout exact trigger SHA"
  grep -Fq 'python3 .github/scripts/trusted-dependency-submission-validation.py verify-main' "$workflow" ||
    die "publisher must delegate packaged runtime graph verification to the trusted validator"
  grep -Fq -- '--dir dependency-graph-reports' "$workflow" ||
    die "publisher graph verification report directory drifted"
  grep -Fq -- '--head "$GITHUB_SHA"' "$workflow" ||
    die "publisher graph verification must bind exact main SHA"
}

assert_validator_contract() {
  local validator="$1"
  [ -f "$validator" ] || die "dependency submission validator is missing"
  grep -Fq 'RUNTIME_CONFIGURATION = "intellijPlatformSandboxRuntimeClasspath"' "$validator" ||
    die "validator lost the packaged runtime configuration contract"
  grep -Fq 'FILTER_LINE = f"dependency-graph-include-configurations:' "$validator" ||
    die "validator lost dependency graph configuration filtering"
  grep -Fq 'RUNTIME_SCOPE_ENV_LINE = (' "$validator" ||
    die "validator lost runtime scope classification"
  grep -Fq 'require(resolved, "packaged runtime dependency graph is empty")' "$validator" ||
    die "runtime graph must fail closed when empty"
  grep -Fq 'def validate_packaged_runtime_scope(resolved: dict[str, dict]) -> tuple[int, int]:' "$validator" ||
    die "validator lost packaged runtime scope validation"
  grep -Fq 'pkg:maven/org.gradle/gradle-core@' "$validator" ||
    die "validator lost the single permitted development-scope build-tool identity"
  if grep -Fq 'require(len(resolved) >= 450,' "$validator" || grep -Fq 'require(direct >= 40,' "$validator"; then
    die "obsolete broad-graph cardinality contract returned"
  fi
}

workflow=".github/workflows/dependency-submission.yml"
validator=".github/scripts/trusted-dependency-submission-validation.py"
assert_contract "$workflow"
assert_validator_contract "$validator"
fixtures="$(mktemp -d)"
trap 'rm -rf "$fixtures"' EXIT

sed 's/cache-read-only: true/cache-read-only: false/' "$workflow" > "$fixtures/cache-writer.yml"
if ( assert_contract "$fixtures/cache-writer.yml" ) 2>/dev/null; then die "cache-writer fixture accepted"; fi
sed 's/generate-and-submit/generate-and-upload/' "$workflow" > "$fixtures/no-submit.yml"
if ( assert_contract "$fixtures/no-submit.yml" ) 2>/dev/null; then die "no-submit fixture accepted"; fi
sed '/gradle\/\*\*/d' "$workflow" > "$fixtures/missing-gradle-input.yml"
if ( assert_contract "$fixtures/missing-gradle-input.yml" ) 2>/dev/null; then die "missing-input fixture accepted"; fi
sed "s/\\^intellijPlatformSandboxRuntimeClasspath\\$/\.\*/" "$workflow" > "$fixtures/broad-runtime-scope.yml"
if ( assert_contract "$fixtures/broad-runtime-scope.yml" ) 2>/dev/null; then die "broad-runtime-scope fixture accepted"; fi

python3 .github/scripts/trusted-dependency-submission-validation.py self-test
python3 .github/scripts/trusted-dependency-submission-validation.py check-contract \
  --production "$workflow" \
  --pr-workflow .github/workflows/build.yml

echo "custom Gradle dependency submission contract passed (4 shell negative controls + packaged-runtime validator self-tests)"
