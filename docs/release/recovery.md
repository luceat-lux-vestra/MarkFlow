# Marketplace Publication Recovery Runbook

This runbook is the maintainer-owned recovery path for a MarkFlow release that stopped after the workflow uploaded `markflow-release-identity.json` but before the workflow completed its normal publication sequence.

It does not authorize publication. It exists to resolve an already-started release without guessing, rewriting provenance, or publishing different source under an existing version.

## Recovery invariants

The release identity is anchored by all of the following values together:

- release/tag/version (`yy.MM.dd.HHmmss`);
- immutable tag commit (`tag_commit`);
- artifact filename (`artifact`);
- SHA-256 of the exact archive produced by the workflow preflight (`artifact_sha256`);
- plugin version embedded in the archive's single `META-INF/plugin.xml`;
- GitHub Release that owns the identity assets;
- publication state (`pending` or `published`).

`main_commit_at_preflight` is retained as audit evidence of the reviewed-main state seen during preflight. The tag commit, not a mutable branch tip, is the release source anchor.

Never recover by repointing, force-updating, deleting/recreating, or otherwise rewriting the release tag. Never rebuild different source and publish it under an existing version. If the immutable identity is wrong, stop and prepare a new version/release instead.

## When this runbook applies

Use this runbook when the GitHub Release contains `markflow-release-identity.json` with `publication_state: "pending"` and the release workflow did not reach a trustworthy completed state.

Typical causes include:

- runner/process termination after the pending identity was uploaded;
- a network/API timeout while `publishPlugin` was running;
- publication succeeding but a later GitHub release-asset upload failing;
- publication and release-asset upload succeeding but creation of `markflow-release-published.json` failing;
- later changelog-PR failure after publication completed.

A failure before `markflow-release-identity.json` is uploaded is not an ambiguous partial publication. The normal workflow may be started again only after the ordinary preflight conditions still pass.

## 1. Freeze the incident

Do not blindly rerun the Release workflow while a pending identity exists. The current preflight intentionally refuses automatic republish when it finds `publication_state: "pending"`.

Record:

- GitHub Release URL and release ID;
- workflow run ID and failed step;
- release tag/version;
- current immutable tag commit SHA;
- the pending identity asset ID;
- any plugin ZIP release asset and its asset ID;
- any `markflow-release-published.json` asset and its asset ID;
- what JetBrains Marketplace currently reports for the same plugin/version.

If more than one pending identity or more than one published identity asset exists, stop. The automated workflow treats duplicate identity assets as ambiguous and manual recovery must not collapse them by guesswork.

## 2. Read the authoritative GitHub-side identity

Download the identity assets without modifying the release:

```bash
repo="luceat-lux-vestra/MarkFlow-private"
version="YY.MM.DD.HHMMSS"

gh api "repos/$repo/releases/tags/$version" > /tmp/markflow-release.json
jq '{id, tag_name, assets: [.assets[] | {id, name, size}]}' /tmp/markflow-release.json

pending_id="$(jq -r '.assets[] | select(.name == "markflow-release-identity.json") | .id' /tmp/markflow-release.json)"
published_id="$(jq -r '.assets[] | select(.name == "markflow-release-published.json") | .id' /tmp/markflow-release.json)"

[ -n "$pending_id" ] && gh api -H 'Accept: application/octet-stream' \
  "repos/$repo/releases/assets/$pending_id" > /tmp/markflow-release-identity.json
[ -n "$published_id" ] && gh api -H 'Accept: application/octet-stream' \
  "repos/$repo/releases/assets/$published_id" > /tmp/markflow-release-published.json
```

For the pending identity, verify at minimum:

```bash
jq -e '
  .schema == 1 and
  (.tag | type == "string") and
  (.version == .tag) and
  (.tag_commit | test("^[0-9a-f]{40}$")) and
  (.artifact | type == "string") and
  (.artifact_sha256 | test("^[0-9a-f]{64}$")) and
  .publication_state == "pending"
' /tmp/markflow-release-identity.json
```

The release tag must still resolve to exactly the recorded commit:

```bash
tag_commit="$(jq -r '.tag_commit' /tmp/markflow-release-identity.json)"
resolved="$(git rev-list -n 1 "$version")"
[ "$resolved" = "$tag_commit" ]
```

Also verify that the tag commit is reachable from reviewed `main` using the same rule as the release workflow (`identical` or `behind` for `main...tag`). Any tag/source mismatch is a hard stop.

## 3. Verify the artifact identity

The pending manifest's `artifact_sha256` identifies the archive produced by the workflow preflight before the Marketplace call.

If the corresponding GitHub Release ZIP already exists, download it and compare its digest to the pending identity:

```bash
artifact_name="$(jq -r '.artifact' /tmp/markflow-release-identity.json)"
expected_sha="$(jq -r '.artifact_sha256' /tmp/markflow-release-identity.json)"
artifact_id="$(jq -r --arg name "$artifact_name" '.assets[] | select(.name == $name) | .id' /tmp/markflow-release.json)"

if [ -n "$artifact_id" ]; then
  gh api -H 'Accept: application/octet-stream' \
    "repos/$repo/releases/assets/$artifact_id" > "/tmp/$artifact_name"
  actual_sha="$(sha256sum "/tmp/$artifact_name" | awk '{print $1}')"
  [ "$actual_sha" = "$expected_sha" ]
fi
```

The archive must contain exactly one `META-INF/plugin.xml`, and that manifest's `<version>` must equal the release tag/version. The repository's `.github/scripts/release-preflight.sh` performs this check and should be used as the executable reference.

Important: do not require the byte-for-byte Marketplace-hosted download to equal `artifact_sha256`. `publishPlugin` may publish the signed distribution produced by the IntelliJ Platform Gradle plugin, while the preflight digest records the archive built and checked before publication. Recovery uses the pending manifest and GitHub Release artifact as repository-side provenance evidence; Marketplace verification is by plugin/version/publication state, not by inventing an unsupported remote byte digest equivalence.

If the GitHub Release artifact exists and its digest differs from the pending identity, stop. Do not overwrite it and do not publish/retry under the same version.

## 4. Determine Marketplace state

The maintainer must determine whether the exact MarkFlow plugin version in the pending identity exists in JetBrains Marketplace.

Use the Marketplace publisher UI or an official Marketplace API/read path that identifies the MarkFlow plugin (`com.algorist.markflow`) and the exact version. Record the evidence used (URL/API response and observation time) in the release incident notes or issue.

Classify the result as exactly one of:

1. **Exists** — the exact version is confirmed as uploaded/present in Marketplace state, including a state that is uploaded but still undergoing Marketplace processing/review.
2. **Absent** — an authoritative Marketplace read confirms the exact version does not exist.
3. **Unknown** — the read failed, authentication/authorization is insufficient, Marketplace returned an ambiguous/transient result, or there is otherwise no trustworthy determination.

`Unknown` is a hard stop. Do not retry publication merely because the version is not yet publicly visible; Marketplace processing/approval can make public visibility lag behind upload acceptance.

## 5. Recovery decision table

| Marketplace state | GitHub-side identity | Allowed action |
| --- | --- | --- |
| Exists | exact pending identity; release ZIP absent | upload only the already-proven repository artifact if it can be reproduced byte-for-byte from the immutable tag, then create the published marker; do **not** call Marketplace again |
| Exists | exact pending identity; release ZIP present with exact digest | create the published marker; do **not** call Marketplace again |
| Exists | any tag/artifact/version mismatch | STOP; investigate. No retry and no identity rewrite |
| Absent | exact pending identity; artifact can be reproduced byte-for-byte | a maintainer may explicitly authorize one recovery publication attempt of that exact identity |
| Absent | artifact cannot be reproduced exactly | STOP; use a new release/version instead |
| Unknown | any | STOP; no retry |
| Any | duplicate/conflicting identity assets | STOP; investigate before mutation |

A Marketplace publication retry is never inferred from a failed workflow run. It requires an explicit maintainer decision after the Marketplace state has been classified as `Absent` and all immutable identity checks pass.

## 6. Safe recovery when Marketplace publication did not occur

Only when Marketplace state is authoritatively `Absent`:

1. check out the immutable release tag, never a branch tip;
2. rebuild with `-PbuildVersion="$version"`;
3. run the same non-publishing preflight validation against the pending identity;
4. require the rebuilt artifact filename and SHA-256 to match the pending identity exactly;
5. require the embedded plugin version to equal the release version;
6. require the tag to still resolve to the recorded `tag_commit` and remain reachable from reviewed `main`;
7. obtain explicit maintainer publication authorization;
8. perform at most the publication action needed for this existing identity;
9. after authoritative confirmation that Marketplace accepted the version, reconcile the GitHub Release artifact and published marker as described below.

If any identity check fails, do not "repair" the old release. Create a new version instead.

## 7. Safe recovery when Marketplace publication did occur

If the exact version is confirmed as existing in Marketplace, never call `publishPlugin` again for that version.

### Release ZIP missing

Rebuild from the immutable tag using the exact version and run `.github/scripts/release-preflight.sh` against the pending identity. Upload a GitHub Release ZIP only if its filename and SHA-256 exactly match the pending identity. If exact reproduction is impossible, stop rather than uploading a different artifact under the same release identity.

### Release ZIP present

Verify its filename and digest exactly match the pending manifest. A mismatch is a hard stop.

### Published marker missing

After Marketplace existence and the repository artifact identity are both confirmed, create `markflow-release-published.json` from the pending identity by changing only:

```json
"publication_state": "published"
```

Before upload, compare every other field to the pending identity. No tag, version, commit, artifact name, digest, or preflight-main field may change.

Upload the marker only after explicit maintainer authorization for this recovery mutation.

### Changelog PR failed

A changelog PR is downstream metadata work and is not evidence that Marketplace publication failed. Once Marketplace and the published marker are confirmed, recreate or repair only the deterministic `changelog-update-$version` PR path. Never republish the plugin to repair changelog automation.

## 8. Recording successful recovery

Recovery is complete only when the GitHub Release contains:

- one `markflow-release-identity.json` with the original exact pending identity;
- one plugin ZIP whose filename and SHA-256 match that identity;
- one `markflow-release-published.json` that is field-for-field identical to the pending identity except for `publication_state: "published"`;
- authoritative evidence that the exact plugin version exists in Marketplace.

After that state is established, a later Release-workflow rerun is deterministic: the workflow prefers the published marker, verifies the rebuilt artifact and existing release ZIP against the immutable identity, and returns `publish=false` rather than invoking Marketplace again.

Record the recovery evidence with the release incident/issue, including the version, tag commit, artifact digest, Marketplace observation, GitHub Release URL, workflow run IDs, and recovery mutations performed.

## 9. Withdrawal, rollback, and incorrect releases

Do not use tag rewriting as rollback.

If an incorrect version is already in Marketplace:

- stop further automated publication attempts for that version;
- preserve the immutable Git tag and release evidence;
- decide separately whether the Marketplace version should be withdrawn/hidden using the Marketplace maintainer controls;
- treat withdrawal/unpublish as an explicit maintainer/user-authority action;
- publish a corrected build only under a new version and new immutable release identity.

Git/source rollback, Marketplace withdrawal, GitHub Release metadata reconciliation, and a corrected follow-up release are separate actions. None permits changing the historical tag or reusing the version for different source.

## 10. Hard-stop conditions

Stop recovery and do not publish or mutate identity state if any of the following is true:

- Marketplace state is unknown;
- the release tag does not resolve to the recorded commit;
- the tag is no longer reachable from reviewed `main` under the release policy;
- plugin version differs from the tag/version;
- rebuilt artifact filename or SHA-256 differs from the pending identity;
- an existing GitHub Release ZIP differs from the pending digest;
- pending and published identity fields conflict;
- duplicate identity assets exist;
- recovery would require rewriting/repointing/deleting the immutable tag;
- recovery would require publishing different source/artifact under the same version.

Fail closed and use a new release/version when an immutable identity cannot be proven exactly.
