#!/usr/bin/env bash
# Disposable fail-closed fixtures for producer, workflow-security, and live-policy checks.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
AUDIT="$SCRIPT_DIR/hardening-audit.sh"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/markflow-hardening.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

copy_root() {
  local destination="$1"
  mkdir -p "$destination/.github"
  cp "$ROOT/.github/merge-gate-policy.json" "$destination/.github/"
  cp -R "$ROOT/.github/workflows" "$destination/.github/"
  cp -R "$ROOT/.github/scripts" "$destination/.github/"
}

expect_pass() {
  local root="$1" check="${2:-policy_producers}"
  bash "$AUDIT" --root "$root" --only "$check" --no-network >/dev/null
}

expect_fail() {
  local root="$1" check="$2" needle="$3" fixture="${4:-}"
  local output status=0
  if [ -n "$fixture" ]; then
    output="$(HARDENING_AUDIT_TOKEN=fixture GITHUB_REPOSITORY=fixture bash "$AUDIT" --root "$root" --only "$check" --ruleset-fixture "$fixture" 2>&1)" || status=$?
  else
    output="$(bash "$AUDIT" --root "$root" --only "$check" --no-network 2>&1)" || status=$?
  fi
  [ "$status" -ne 0 ] || { echo "fixture unexpectedly passed: $check" >&2; return 1; }
  case "$output" in *"$needle"*) ;; *) echo "fixture failed for the wrong reason; expected: $needle" >&2; printf '%s\n' "$output" >&2; return 1 ;; esac
}

expect_live_fail() {
  local root="$1" check="$2" needle="$3"
  local output status=0
  output="$(env -u HARDENING_AUDIT_TOKEN -u GH_TOKEN GITHUB_REPOSITORY=fixture bash "$AUDIT" --root "$root" --only "$check" 2>&1)" || status=$?
  [ "$status" -ne 0 ] || { echo "live fixture unexpectedly passed: $check" >&2; return 1; }
  case "$output" in *"$needle"*) ;; *) echo "live fixture failed for the wrong reason: $needle" >&2; printf '%s\n' "$output" >&2; return 1 ;; esac
}

baseline="$TMP/baseline"
copy_root "$baseline"
expect_pass "$baseline"

renamed="$TMP/renamed"
copy_root "$renamed"
perl -0pi -e 's/    name: Build\n/    name: Renamed Build\n/' "$renamed/.github/workflows/build.yml"
expect_fail "$renamed" policy_producers "does not match job"

filtered="$TMP/filtered"
copy_root "$filtered"
perl -0pi -e "s/  pull_request:\n/  pull_request:\n    paths:\n      - 'webview\\/**'\n/" "$filtered/.github/workflows/build.yml"
expect_fail "$filtered" policy_producers "filters pull_request by path"

missing="$TMP/missing"
copy_root "$missing"
perl -0pi -e 's/\.github\/workflows\/build\.yml/.github\/workflows\/missing.yml/' "$missing/.github/merge-gate-policy.json"
expect_fail "$missing" policy_producers "missing workflow"

conditional="$TMP/conditional"
copy_root "$conditional"
perl -0pi -e 's/  build:\n    name: Build\n/  build:\n    name: Build\n    if: false\n/' "$conditional/.github/workflows/build.yml"
expect_fail "$conditional" policy_producers "conditionally skipped"

noop="$TMP/noop"
copy_root "$noop"
perl -0pi -e 's/  build:\n.*?\n  test:/  build:\n    name: Build\n    runs-on: ubuntu-latest\n    timeout-minutes: 1\n    steps:\n      - name: Fake green\n        run: echo "passed"\n\n  test:/s' "$noop/.github/workflows/build.yml"
expect_fail "$noop" policy_producers "fake or no-op job"

mutable="$TMP/mutable"
copy_root "$mutable"
perl -0pi -e 's/\@54081f138730dfa15788a46383842cd2f914a1be/\@v1.3.1/' "$mutable/.github/workflows/build.yml"
expect_fail "$mutable" action_pinning "mutable action ref"

missing_permissions="$TMP/missing-permissions"
copy_root "$missing_permissions"
perl -0pi -e 's/\npermissions:\n  contents: read\n//' "$missing_permissions/.github/workflows/run-ui-tests.yml"
expect_fail "$missing_permissions" workflow_permissions "no workflow-level permissions"

unsafe_permissions="$TMP/unsafe-permissions"
copy_root "$unsafe_permissions"
perl -0pi -e 's/pull-requests: write/pull-requests: write-all/' "$unsafe_permissions/.github/workflows/pr-labeler.yml"
expect_fail "$unsafe_permissions" workflow_permissions "grants write-all"

unsafe_privileged="$TMP/unsafe-privileged"
copy_root "$unsafe_privileged"
cat > "$unsafe_privileged/.github/workflows/unsafe.yml" <<'YAML'
name: Unsafe privileged fixture
on:
  pull_request_target:
permissions:
  contents: write
jobs:
  unsafe:
    runs-on: ubuntu-latest
    timeout-minutes: 5
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1
        with:
          ref: ${{ github.event.pull_request.head.sha }}
      - run: echo "${{ secrets.PUBLISH_TOKEN }}"
YAML
expect_fail "$unsafe_privileged" privileged_workflows "checks out code"

ruleset_fixture="$TMP/rulesets.json"
jq -n '{
  main: {
    name: "main protection", enforcement: "active", bypass_actors: [],
    conditions: {ref_name: {include: ["~DEFAULT_BRANCH"], exclude: []}},
    rules: [{type: "required_status_checks", parameters: {
      strict_required_status_checks_policy: true,
      required_status_checks: [{context: "Test", integration_id: 15368}]
    }}]
  },
  release_tag: {
    name: "release tag immutability", enforcement: "active", bypass_actors: [],
    conditions: {ref_name: {include: ["~ALL"], exclude: []}},
    rules: [{type: "deletion"}, {type: "non_fast_forward"}]
  }
}' > "$ruleset_fixture"
expect_fail "$baseline" ruleset_sync "policy requires context 'Build'" "$ruleset_fixture"

incomplete_ruleset="$TMP/incomplete-ruleset.json"
jq -n '{main: {name: "main protection"}, release_tag: {name: "release tag immutability"}}' > "$incomplete_ruleset"
expect_fail "$baseline" ruleset_sync "omitted an authoritative field" "$incomplete_ruleset"

live_without_credential="$TMP/live-without-credential"
copy_root "$live_without_credential"
expect_live_fail "$live_without_credential" ruleset_sync "authoritative HARDENING_AUDIT_TOKEN is unavailable"

echo "hardening-audit negative fixtures passed"
