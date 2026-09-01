#!/usr/bin/env bash
# Small fail-closed fixtures for the required-context producer check.
# This script only mutates disposable copies under a temporary directory.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
AUDIT="$SCRIPT_DIR/hardening-audit.sh"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/markflow-hardening.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

copy_root() {
  local destination="$1"
  mkdir -p "$destination/.github"
  cp -R "$ROOT/.github/merge-gate-policy.json" "$destination/.github/"
  cp -R "$ROOT/.github/workflows" "$destination/.github/"
  cp -R "$ROOT/.github/scripts" "$destination/.github/"
}

expect_pass() {
  local root="$1"
  bash "$AUDIT" --root "$root" --only policy_producers --no-network >/dev/null
}

expect_fail() {
  local root="$1" needle="$2"
  local output status
  status=0
  output="$(bash "$AUDIT" --root "$root" --only policy_producers --no-network 2>&1)" || status=$?
  [ "$status" -ne 0 ] || {
    echo "fixture unexpectedly passed" >&2
    return 1
  }
  case "$output" in
    *"$needle"*) ;;
    *)
      echo "fixture failed for the wrong reason; expected: $needle" >&2
      printf '%s\n' "$output" >&2
      return 1
      ;;
  esac
}

baseline="$TMP/baseline"
copy_root "$baseline"
expect_pass "$baseline"

renamed="$TMP/renamed"
copy_root "$renamed"
perl -0pi -e 's/    name: Build\n/    name: Renamed Build\n/' "$renamed/.github/workflows/build.yml"
expect_fail "$renamed" "does not match the name"

filtered="$TMP/filtered"
copy_root "$filtered"
perl -0pi -e "s/  pull_request:\n/  pull_request:\n    paths:\n      - 'webview\\/**'\n/" "$filtered/.github/workflows/build.yml"
expect_fail "$filtered" "filters triggers by path"

missing="$TMP/missing"
copy_root "$missing"
perl -0pi -e 's/\.github\/workflows\/build\.yml/.github\/workflows\/missing.yml/' \
  "$missing/.github/merge-gate-policy.json"
expect_fail "$missing" "missing workflow"

echo "hardening-audit negative fixtures passed"
