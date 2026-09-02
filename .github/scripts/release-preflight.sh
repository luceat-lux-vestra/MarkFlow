#!/usr/bin/env bash
# Non-publishing release provenance preflight.
# It validates the exact release identity and a locally built plugin archive. It never
# calls GitHub, Marketplace, signing, or publication APIs.
set -euo pipefail

TAG=""
MAIN_SHA=""
TAG_SHA=""
COMPARE_STATUS=""
ARTIFACT=""
EXISTING_IDENTITY=""
ARTIFACT_PRESENT=false
EXISTING_ARTIFACT_SHA256=""
OUTPUT_MANIFEST=""
GITHUB_OUTPUT_FILE=""
DRY_RUN=false

die() { echo "release-preflight: $*" >&2; exit 1; }
while [ $# -gt 0 ]; do
  case "$1" in
    --tag) TAG="${2:-}"; shift 2 ;;
    --main-sha) MAIN_SHA="${2:-}"; shift 2 ;;
    --tag-sha) TAG_SHA="${2:-}"; shift 2 ;;
    --compare-status) COMPARE_STATUS="${2:-}"; shift 2 ;;
    --artifact) ARTIFACT="${2:-}"; shift 2 ;;
    --existing-identity) EXISTING_IDENTITY="${2:-}"; shift 2 ;;
    --artifact-present) ARTIFACT_PRESENT="${2:-}"; shift 2 ;;
    --existing-artifact-sha256) EXISTING_ARTIFACT_SHA256="${2:-}"; shift 2 ;;
    --output-manifest) OUTPUT_MANIFEST="${2:-}"; shift 2 ;;
    --github-output) GITHUB_OUTPUT_FILE="${2:-}"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

[ "$DRY_RUN" = true ] || die "--dry-run is required; this helper is validation-only"
[ -n "$TAG" ] || die "tag is required"
case "$TAG" in */*) die "tag must not contain a slash" ;; esac
[[ "$TAG" =~ ^[0-9]{2}\.[0-9]{2}\.[0-9]{2}\.[0-9]{6}$ ]] || die "tag '$TAG' is not a MarkFlow release version"
[[ "$MAIN_SHA" =~ ^[0-9a-f]{40}$ ]] || die "main SHA is not a full immutable commit SHA"
[[ "$TAG_SHA" =~ ^[0-9a-f]{40}$ ]] || die "tag SHA is not a full immutable commit SHA"
case "$COMPARE_STATUS" in identical|behind) ;; *) die "tag is not reachable from reviewed main (compare status: $COMPARE_STATUS)" ;; esac
[ "$ARTIFACT_PRESENT" = true ] || [ "$ARTIFACT_PRESENT" = false ] || die "--artifact-present must be true or false"
[ -f "$ARTIFACT" ] || die "artifact not found: $ARTIFACT"
command -v python3 >/dev/null 2>&1 || die "python3 is required to inspect plugin.xml"

python3 - "$ARTIFACT" "$TAG" <<'PY'
import sys
import io
import zipfile
import xml.etree.ElementTree as ET

archive, expected = sys.argv[1:]
with zipfile.ZipFile(archive) as zf:
    names = zf.namelist()
    unsafe = [name for name in names if name.startswith("/") or any(part == ".." for part in name.split("/"))]
    if unsafe:
        raise SystemExit(f"artifact contains unsafe archive paths: {unsafe[:3]}")
    manifests = [(name, zf.read(name)) for name in names if name == "META-INF/plugin.xml" or name.endswith("/META-INF/plugin.xml")]
    for jar_name in [name for name in names if name.lower().endswith(".jar")]:
        with zipfile.ZipFile(io.BytesIO(zf.read(jar_name))) as jar:
            jar_names = jar.namelist()
            jar_unsafe = [name for name in jar_names if name.startswith("/") or any(part == ".." for part in name.split("/"))]
            if jar_unsafe:
                raise SystemExit(f"nested plugin jar contains unsafe archive paths: {jar_unsafe[:3]}")
            manifests.extend((f"{jar_name}!{name}", jar.read(name)) for name in jar_names if name == "META-INF/plugin.xml" or name.endswith("/META-INF/plugin.xml"))
    if len(manifests) != 1:
        raise SystemExit(f"expected exactly one META-INF/plugin.xml in distribution, found {len(manifests)}")
    manifest_name, manifest_bytes = manifests[0]
    root = ET.fromstring(manifest_bytes)
    if root.tag.rsplit("}", 1)[-1] != "idea-plugin":
        raise SystemExit("plugin.xml root is not idea-plugin")
    versions = [child.text or "" for child in root if child.tag.rsplit("}", 1)[-1] == "version"]
    if versions != [expected]:
        raise SystemExit(f"plugin.xml version {versions!r} in {manifest_name} does not equal release tag {expected!r}")
PY

case "$(basename "$ARTIFACT")" in
  *-"$TAG".zip) ;;
  *) die "artifact filename does not end in the exact release version: $(basename "$ARTIFACT")" ;;
esac

ARTIFACT_SHA="$(sha256sum "$ARTIFACT" | awk '{print $1}')"
ARTIFACT_NAME="$(basename "$ARTIFACT")"
if [ "$ARTIFACT_PRESENT" = true ]; then
  [[ "$EXISTING_ARTIFACT_SHA256" =~ ^[0-9a-f]{64}$ ]] || die "existing release artifact digest is unavailable"
  [ "$EXISTING_ARTIFACT_SHA256" = "$ARTIFACT_SHA" ] || die "existing release artifact digest does not match the rebuilt artifact"
fi
publish=true

if [ -n "$EXISTING_IDENTITY" ] && [ -s "$EXISTING_IDENTITY" ]; then
  command -v jq >/dev/null 2>&1 || die "jq is required to inspect an existing release identity"
  jq -e --arg tag "$TAG" --arg tag_sha "$TAG_SHA" --arg artifact "$ARTIFACT_NAME" --arg hash "$ARTIFACT_SHA" '
    .schema == 1 and (.version == $tag) and (.tag == $tag) and (.tag_commit == $tag_sha) and
    (.artifact == $artifact) and (.artifact_sha256 == $hash) and
    (.publication_state == "pending" or .publication_state == "published")
  ' "$EXISTING_IDENTITY" >/dev/null || die "existing release identity does not match this immutable tag/artifact"
  state="$(jq -r '.publication_state' "$EXISTING_IDENTITY")"
  case "$state" in
    pending) die "a pending publication identity already exists; refusing automatic republish (manual recovery required)" ;;
    published)
      [ "$ARTIFACT_PRESENT" = true ] || die "published identity exists but the release artifact is absent; refusing recovery ambiguity"
      publish=false
      ;;
  esac
elif [ "$ARTIFACT_PRESENT" = true ]; then
  die "release artifact exists without a release identity; refusing ambiguous recovery"
fi

if [ "$publish" = true ]; then
  [ -n "$OUTPUT_MANIFEST" ] || die "new publication requires --output-manifest"
  umask 077
  jq -n --arg tag "$TAG" --arg tag_sha "$TAG_SHA" --arg main_sha "$MAIN_SHA" --arg artifact "$ARTIFACT_NAME" --arg hash "$ARTIFACT_SHA" '{
    schema: 1,
    tag: $tag,
    version: $tag,
    tag_commit: $tag_sha,
    main_commit_at_preflight: $main_sha,
    artifact: $artifact,
    artifact_sha256: $hash,
    publication_state: "pending"
  }' > "$OUTPUT_MANIFEST"
fi

if [ -n "$GITHUB_OUTPUT_FILE" ]; then
  printf 'publish=%s\nartifact=%s\nartifact_sha256=%s\n' "$publish" "$ARTIFACT_NAME" "$ARTIFACT_SHA" >> "$GITHUB_OUTPUT_FILE"
else
  printf 'release-preflight: dry-run valid; publish=%s artifact=%s sha256=%s\n' "$publish" "$ARTIFACT_NAME" "$ARTIFACT_SHA"
fi
