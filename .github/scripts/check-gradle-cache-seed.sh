#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

die() {
  echo "gradle-cache-seed: $*" >&2
  exit 1
}

assert_trusted_main_cache_seed() {
  local workflow="$1"
  [ -f "$workflow" ] || die "main Gradle cache seed workflow is missing"
  grep -Fxq '  push:' "$workflow" || die "seed must run on main pushes"
  grep -Fxq '    branches:' "$workflow" || die "seed push branch selector is missing"
  grep -Fxq '      - main' "$workflow" || die "seed must select main"
  grep -Fxq '  workflow_dispatch:' "$workflow" || die "seed manual recovery is missing"
  # A protected-main cache write is only useful when the build's dependency or
  # toolchain inputs change. On unchanged inputs, do not schedule an ARM64 runner
  # just to rebuild product sources already checked by the PR Merge Gate.
  grep -Fxq '    paths:' "$workflow" || die "seed push path filter is missing"
  local actual_paths expected_paths
  actual_paths="$(awk '
    /^    paths:[[:space:]]*$/ { inside=1; next }
    inside && /^  [^ ]/ { exit }
    inside && /^      - / { print }
  ' "$workflow" | LC_ALL=C sort)"
  expected_paths="$(printf '%s\n' \
    "      - '.github/workflows/gradle-cache-seed.yml'" \
    "      - '.nvmrc'" \
    "      - 'build.gradle.kts'" \
    "      - 'settings.gradle.kts'" \
    "      - 'gradle.properties'" \
    "      - 'gradle/**'" \
    "      - 'webview/package.json'" \
    "      - 'webview/package-lock.json'" | LC_ALL=C sort)"
  [ "$actual_paths" = "$expected_paths" ] ||
    die "cache-seed push paths drifted: source edits must not trigger ARM64 cache writes"
  if grep -Eq '^  (pull_request|pull_request_target|workflow_run|repository_dispatch):' "$workflow"; then
    die "seed must not run on untrusted PR/webhook events"
  fi
  grep -Fxq '  contents: read' "$workflow" || die "seed must use read-only repository permission"
  grep -Fxq "    if: github.repository == 'luceat-lux-vestra/MarkFlow' && github.ref == 'refs/heads/main'" "$workflow" || die "seed main-only writer guard changed"
  grep -Fxq '    runs-on: ubuntu-24.04-arm' "$workflow" || die "seed must use ARM64"
  grep -Fxq '          cache-read-only: false' "$workflow" || die "seed must write cache on main"
  grep -Fq '          persist-credentials: false' "$workflow" || die "checkout credentials must not persist"
  grep -Fq '          ref: ${{ github.sha }}' "$workflow" || die "seed must checkout exact trigger SHA"
  grep -Fq '        run: test "$(git rev-parse HEAD)" = "$EXPECTED_SHA"' "$workflow" || die "exact SHA verification missing"
  grep -Fxq '        run: ./gradlew buildPlugin' "$workflow" || die "seed must build actual plugin"
}

seed=".github/workflows/gradle-cache-seed.yml"
assert_trusted_main_cache_seed "$seed"

fixtures="$(mktemp -d)"
trap 'rm -rf "$fixtures"' EXIT

sed "s/github.ref == 'refs\/heads\/main'/github.ref == 'refs\/heads\/feature'/" "$seed" > "$fixtures/wrong-ref.yml"
if ( assert_trusted_main_cache_seed "$fixtures/wrong-ref.yml" ) 2>/dev/null; then
  die "wrong-ref negative fixture was accepted"
fi

sed 's/cache-read-only: false/cache-read-only: true/' "$seed" > "$fixtures/read-only.yml"
if ( assert_trusted_main_cache_seed "$fixtures/read-only.yml" ) 2>/dev/null; then
  die "read-only negative fixture was accepted"
fi

# Regression: the wrapper launcher is validated by PR CI but does not own
# reusable Gradle User Home state. gradle/** already covers wrapper properties.
sed "/^      - 'gradle\/\*\*'$/a\\      - 'gradlew'" "$seed" > "$fixtures/launcher-only.yml"
if ( assert_trusted_main_cache_seed "$fixtures/launcher-only.yml" ) 2>/dev/null; then
  die "launcher-only gradlew unexpectedly allowed to trigger cache seed"
fi

# Regression: never restore a broad source glob to the post-merge seed trigger.
sed "/^      - 'webview\\/package-lock.json'$/a\\      - 'src/**'" "$seed" > "$fixtures/broad-source.yml"
if ( assert_trusted_main_cache_seed "$fixtures/broad-source.yml" ) 2>/dev/null; then
  die "source-only pushes unexpectedly allowed to trigger cache seed"
fi

sed "/^      - 'webview\\/package-lock.json'$/d" "$seed" > "$fixtures/missing-lockfile.yml"
if ( assert_trusted_main_cache_seed "$fixtures/missing-lockfile.yml" ) 2>/dev/null; then
  die "dependency lockfile change no longer triggers cache seed"
fi

sed "s#webview/package.json#webview/**#" "$seed" > "$fixtures/broad-webview.yml"
if ( assert_trusted_main_cache_seed "$fixtures/broad-webview.yml" ) 2>/dev/null; then
  die "webview source-only pushes unexpectedly allowed to trigger cache seed"
fi

echo "trusted protected-main Gradle cache seed contract passed (6 negative controls)"
