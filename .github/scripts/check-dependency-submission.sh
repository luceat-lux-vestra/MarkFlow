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
  grep -Fq "vars.MARKFLOW_ADS_CUTOVER_DONE == 'true'" "$workflow" || die "cutover activation guard is missing"
  grep -Fq "github.ref == 'refs/heads/main'" "$workflow" || die "main-only job guard is missing"
  grep -Fxq '    runs-on: ubuntu-24.04-arm' "$workflow" || die "publisher must use ARM64"
  grep -Fxq '      contents: write' "$workflow" || die "submission job lacks contents: write"
  grep -Fxq '          cache-read-only: true' "$workflow" || die "publisher must never write Gradle caches"
  grep -Fxq '          dependency-graph: generate-and-submit' "$workflow" || die "publisher is not in submit mode"
  grep -Fxq '          dependency-graph-continue-on-failure: false' "$workflow" || die "publisher must fail closed"
  grep -Fq 'gradle/actions/dependency-submission@3f5f9adaf7d9fecd50b5935e54106014257a94e6' "$workflow" || die "dependency-submission action pin drifted"
  grep -Fq '          persist-credentials: false' "$workflow" || die "checkout credentials must not persist"
  grep -Fq '          ref: ${{ github.sha }}' "$workflow" || die "publisher must checkout exact trigger SHA"
  grep -Fq 'len(resolved) < 450' "$workflow" || die "minimum graph coverage assertion drifted"
}

workflow=".github/workflows/dependency-submission.yml"
assert_contract "$workflow"
fixtures="$(mktemp -d)"
trap 'rm -rf "$fixtures"' EXIT

sed "s/MARKFLOW_ADS_CUTOVER_DONE == 'true'/MARKFLOW_ADS_CUTOVER_DONE == 'false'/" "$workflow" > "$fixtures/no-activation.yml"
if ( assert_contract "$fixtures/no-activation.yml" ) 2>/dev/null; then die "activation fixture accepted"; fi
sed 's/cache-read-only: true/cache-read-only: false/' "$workflow" > "$fixtures/cache-writer.yml"
if ( assert_contract "$fixtures/cache-writer.yml" ) 2>/dev/null; then die "cache-writer fixture accepted"; fi
sed 's/generate-and-submit/generate-and-upload/' "$workflow" > "$fixtures/no-submit.yml"
if ( assert_contract "$fixtures/no-submit.yml" ) 2>/dev/null; then die "no-submit fixture accepted"; fi
sed '/gradle\/\*\*/d' "$workflow" > "$fixtures/missing-gradle-input.yml"
if ( assert_contract "$fixtures/missing-gradle-input.yml" ) 2>/dev/null; then die "missing-input fixture accepted"; fi

echo "custom Gradle dependency submission contract passed (4 negative controls)"
