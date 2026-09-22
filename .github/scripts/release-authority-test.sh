#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORKFLOW="$ROOT/.github/workflows/release.yml"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/markflow-release-authority.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

check_release_authority() {
  local workflow="$1"
  grep -qE '^[[:space:]]+types:[[:space:]]*\[[[:space:]]*released[[:space:]]*\][[:space:]]*$' "$workflow" || {
    echo "release publication trigger must be stable released only" >&2
    return 1
  }
  grep -qE '^    environment:[[:space:]]+jetbrains-marketplace[[:space:]]*$' "$workflow" || {
    echo "release job is not bound to jetbrains-marketplace environment" >&2
    return 1
  }
}

expect_fail() {
  local needle="$1"; shift
  local output status=0
  output="$("$@" 2>&1)" || status=$?
  [ "$status" -ne 0 ] || { echo "negative fixture unexpectedly passed: $needle" >&2; return 1; }
  case "$output" in
    *"$needle"*) ;;
    *) echo "negative fixture failed for wrong reason: $needle" >&2; printf '%s\n' "$output" >&2; return 1 ;;
  esac
}

check_release_authority "$WORKFLOW"

prerelease="$TMP/prerelease.yml"
cp "$WORKFLOW" "$prerelease"
perl -0pi -e 's/types: \[ released \]/types: [ prereleased, released ]/' "$prerelease"
expect_fail "release publication trigger must be stable released only" check_release_authority "$prerelease"

missing_environment="$TMP/missing-environment.yml"
cp "$WORKFLOW" "$missing_environment"
perl -0pi -e 's/    environment: jetbrains-marketplace\n//' "$missing_environment"
expect_fail "release job is not bound to jetbrains-marketplace environment" check_release_authority "$missing_environment"

echo "release authority boundary fixtures passed"
