#!/usr/bin/env bash
# Non-publishing fixtures for release provenance and recovery behavior.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PREFLIGHT="$SCRIPT_DIR/release-preflight.sh"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/markflow-release.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

tag="26.09.02.123456"
main_sha="1111111111111111111111111111111111111111"
tag_sha="2222222222222222222222222222222222222222"
artifact="$TMP/MarkFlow-$tag.zip"
stage="$TMP/plugin"
mkdir -p "$stage/META-INF"
printf '<idea-plugin><id>com.algorist.markflow</id><version>%s</version></idea-plugin>\n' "$tag" > "$stage/META-INF/plugin.xml"
(cd "$stage" && zip -qr "$artifact" .)

expect_fail() {
  local needle="$1"; shift
  local output status=0
  output="$("$@" 2>&1)" || status=$?
  [ "$status" -ne 0 ] || { echo "release fixture unexpectedly passed: $needle" >&2; return 1; }
  case "$output" in
    *"$needle"*) ;;
    *) echo "release fixture failed for the wrong reason: $needle" >&2; printf '%s\n' "$output" >&2; return 1 ;;
  esac
}

manifest="$TMP/identity.json"
output="$TMP/github-output"
bash "$PREFLIGHT" --dry-run --tag "$tag" --main-sha "$main_sha" --tag-sha "$tag_sha" --compare-status behind --artifact "$artifact" --output-manifest "$manifest" --github-output "$output"
grep -qx 'publish=true' "$output"
jq -e '.publication_state == "pending" and .artifact_sha256 != null' "$manifest" >/dev/null

expect_fail "pending publication identity" bash "$PREFLIGHT" --dry-run --tag "$tag" --main-sha "$main_sha" --tag-sha "$tag_sha" --compare-status behind --artifact "$artifact" --existing-identity "$manifest" --artifact-present false --output-manifest "$TMP/unused.json"
expect_fail "not reachable from reviewed main" bash "$PREFLIGHT" --dry-run --tag "$tag" --main-sha "$main_sha" --tag-sha "$tag_sha" --compare-status ahead --artifact "$artifact" --output-manifest "$TMP/ahead.json"
wrong_source="$TMP/wrong-source.json"
jq '.tag_commit = "3333333333333333333333333333333333333333"' "$manifest" > "$wrong_source"
expect_fail "immutable tag/artifact" bash "$PREFLIGHT" --dry-run --tag "$tag" --main-sha "$main_sha" --tag-sha "$tag_sha" --compare-status behind --artifact "$artifact" --existing-identity "$wrong_source" --artifact-present false --output-manifest "$TMP/wrong-source-output.json"

bad_stage="$TMP/bad-plugin"
mkdir -p "$bad_stage/META-INF"
printf '<idea-plugin><version>26.09.02.999999</version></idea-plugin>\n' > "$bad_stage/META-INF/plugin.xml"
bad_artifact="$TMP/MarkFlow-bad-$tag.zip"
(cd "$bad_stage" && zip -qr "$bad_artifact" .)
expect_fail "does not equal release tag" bash "$PREFLIGHT" --dry-run --tag "$tag" --main-sha "$main_sha" --tag-sha "$tag_sha" --compare-status behind --artifact "$bad_artifact" --output-manifest "$TMP/bad.json"

published="$TMP/published.json"
jq '.publication_state = "published"' "$manifest" > "$published"
published_output="$TMP/published-output"
artifact_sha="$(sha256sum "$artifact" | awk '{print $1}')"
bash "$PREFLIGHT" --dry-run --tag "$tag" --main-sha "$main_sha" --tag-sha "$tag_sha" --compare-status behind --artifact "$artifact" --existing-identity "$published" --artifact-present true --existing-artifact-sha256 "$artifact_sha" --github-output "$published_output"
grep -qx 'publish=false' "$published_output"
expect_fail "does not match the rebuilt artifact" bash "$PREFLIGHT" --dry-run --tag "$tag" --main-sha "$main_sha" --tag-sha "$tag_sha" --compare-status behind --artifact "$artifact" --existing-identity "$published" --artifact-present true --existing-artifact-sha256 "$(printf '0%.0s' {1..64})" --github-output "$TMP/wrong-digest-output"
expect_fail "artifact is absent" bash "$PREFLIGHT" --dry-run --tag "$tag" --main-sha "$main_sha" --tag-sha "$tag_sha" --compare-status behind --artifact "$artifact" --existing-identity "$published" --artifact-present false --github-output "$TMP/absent-output"

echo "release-preflight non-publishing fixtures passed"
