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
  # Protected-main cache writes are only useful when dependency/toolchain inputs
  # change. Keep x64 and ARM64 cache state symmetric without scheduling either
  # architecture for ordinary source-only changes.
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
    die "cache-seed push paths drifted: source edits must not trigger architecture cache writes"
  if grep -Eq '^  (pull_request|pull_request_target|workflow_run|repository_dispatch):' "$workflow"; then
    die "seed must not run on untrusted PR/webhook events"
  fi
  grep -Fxq '  contents: read' "$workflow" || die "seed must use read-only repository permission"
  grep -Fxq "    if: github.repository == 'luceat-lux-vestra/MarkFlow' && github.ref == 'refs/heads/main'" "$workflow" || die "seed main-only writer guard changed"
  grep -Fxq '    name: Gradle cache seed (${{ matrix.arch }})' "$workflow" || die "seed job must expose architecture identity"
  grep -Fxq '    runs-on: ${{ matrix.runner }}' "$workflow" || die "seed must use the audited architecture matrix"
  grep -Fxq '      fail-fast: false' "$workflow" || die "seed architectures must run independently"
  [ "$(grep -Fc '          - arch:' "$workflow" || true)" = "2" ] || die "seed must declare exactly x64 and ARM64"
  for spec in "arm64:ubuntu-24.04-arm" "x64:ubuntu-24.04"; do
    local arch="${spec%%:*}" runner="${spec#*:}"
    awk -v arch="$arch" -v runner="$runner" '
      $0 == "          - arch: " arch { getline; if ($0 == "            runner: " runner) found=1 }
      END { exit !found }
    ' "$workflow" || die "seed runner mapping drifted for $arch ($runner)"
  done
  grep -Fxq '          cache-read-only: false' "$workflow" || die "seed must write cache on main"
  grep -Fq '          persist-credentials: false' "$workflow" || die "checkout credentials must not persist"
  grep -Fq '          ref: ${{ github.sha }}' "$workflow" || die "seed must checkout exact trigger SHA"
  grep -Fq '        run: test "$(git rev-parse HEAD)" = "$EXPECTED_SHA"' "$workflow" || die "exact SHA verification missing"
  grep -Fxq '        run: ./gradlew buildPlugin -PplatformVersion=2026.2.3' "$workflow" || die "seed must populate the authoritative IDEA 2026.2.3 plugin cache"
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

sed '/          - arch: x64/{N;d;}' "$seed" > "$fixtures/missing-x64.yml"
if ( assert_trusted_main_cache_seed "$fixtures/missing-x64.yml" ) 2>/dev/null; then
  die "missing x64 cache writer was accepted"
fi

sed '/          - arch: arm64/{N;d;}' "$seed" > "$fixtures/missing-arm64.yml"
if ( assert_trusted_main_cache_seed "$fixtures/missing-arm64.yml" ) 2>/dev/null; then
  die "missing ARM64 cache writer was accepted"
fi

sed 's/buildPlugin -PplatformVersion=2026.2.3/buildPlugin -PplatformVersion=2026.2.2/' "$seed" > "$fixtures/stale-platform.yml"
if ( assert_trusted_main_cache_seed "$fixtures/stale-platform.yml" ) 2>/dev/null; then
  die "stale IDEA 2026.2.2 cache seed was accepted"
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

echo "trusted protected-main Gradle cache seed contract passed (9 negative controls)"
