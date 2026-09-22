#!/usr/bin/env bash
set -euo pipefail

WORKFLOW="${1:-.github/workflows/release.yml}"

die() {
  echo "release-publication-contract: $*" >&2
  exit 1
}

[ -f "$WORKFLOW" ] || die "release workflow not found: $WORKFLOW"

require_fixed() {
  local needle="$1" message="$2"
  grep -Fq -- "$needle" "$WORKFLOW" || die "$message"
}

line_of() {
  local needle="$1"
  grep -nF -- "$needle" "$WORKFLOW" | head -n1 | cut -d: -f1
}

require_fixed "release:" "release workflow must remain release-event-only"
if grep -Eq '^[[:space:]]+(pull_request|pull_request_target|workflow_dispatch):' "$WORKFLOW"; then
  die "publication workflow must not run from PR or manual-dispatch triggers"
fi

require_fixed "      contents: write" "release job needs contents:write for release assets"
require_fixed "      pull-requests: write" "release job needs pull-requests:write for changelog PR recovery"
require_fixed "      id-token: write" "release job must mint OIDC only for provenance attestation"
require_fixed "      attestations: write" "release job must persist provenance attestation"

grep -Eq '^[[:space:]]+uses: actions/attest@[0-9a-f]{40}([[:space:]]+#.*)?$' "$WORKFLOW" ||
  die "actions/attest must use an immutable full SHA"

require_fixed './gradlew signPlugin verifyPluginSignature -PbuildVersion="$RELEASE_VERSION"'   "release workflow must sign and verify the exact candidate"
require_fixed '          subject-path: ${{ steps.signed_artifact.outputs.path }}'   "attestation subject must be the verified signed archive"
require_fixed "          create-storage-record: false"   "release attestation must not require broader artifact-metadata authority"
require_fixed './gradlew publishPlugin -x signPlugin -PbuildVersion="$RELEASE_VERSION"'   "Marketplace publication must consume the already-verified signed archive without re-signing"
require_fixed '          RELEASE_ARTIFACT_PATH: ${{ steps.signed_artifact.outputs.path }}'   "GitHub Release must upload the verified signed archive"
if grep -Fq 'RELEASE_ARTIFACT_PATH: ${{ steps.artifact.outputs.path }}' "$WORKFLOW"; then
  die "unsigned buildPlugin output must not be a GitHub Release asset"
fi

require_fixed '.publication_artifact = $artifact'   "pending release identity must record the signed publication artifact"
require_fixed '.publication_artifact_sha256 = $hash'   "pending release identity must record the signed publication digest"
require_fixed "--published-artifact-present"   "recovery preflight must verify the signed release asset"
require_fixed "--existing-published-artifact-sha256"   "recovery preflight must verify the signed release digest"

sign_line="$(line_of "      - name: Sign and verify exact release artifact")"
capture_line="$(line_of "      - name: Capture verified signed release artifact")"
attest_line="$(line_of "      - name: Attest verified signed release artifact")"
lock_line="$(line_of "      - name: Lock release identity before publication")"
publish_line="$(line_of "      - name: Publish Plugin")"
recheck_line="$(line_of "      - name: Recheck signed artifact identity after Marketplace publication")"
upload_line="$(line_of "      - name: Upload Release Asset")"

for value in "$sign_line" "$capture_line" "$attest_line" "$lock_line" "$publish_line" "$recheck_line" "$upload_line"; do
  [[ "$value" =~ ^[0-9]+$ ]] || die "release publication step order is incomplete"
done

[ "$sign_line" -lt "$capture_line" ] &&
[ "$capture_line" -lt "$attest_line" ] &&
[ "$attest_line" -lt "$lock_line" ] &&
[ "$lock_line" -lt "$publish_line" ] &&
[ "$publish_line" -lt "$recheck_line" ] &&
[ "$recheck_line" -lt "$upload_line" ] ||
  die "release order must be sign/verify -> capture -> attest -> lock -> publish -> identity recheck -> release upload"

echo "release publication contract checks passed"
