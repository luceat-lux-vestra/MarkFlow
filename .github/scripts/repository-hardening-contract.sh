#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

die() {
  echo "repository-hardening-contract: $*" >&2
  exit 1
}

wrapper="gradle/wrapper/gradle-wrapper.properties"
configured_gradle="$(sed -n 's/^gradleVersion[[:space:]]*=[[:space:]]*//p' gradle.properties)"
wrapper_gradle="$(sed -n 's#^distributionUrl=.*gradle-\([0-9][0-9.]*\)-bin\.zip$#\1#p' "$wrapper")"
wrapper_sha="$(sed -n 's/^distributionSha256Sum=//p' "$wrapper")"

[ -n "$configured_gradle" ] || die "gradleVersion is missing from gradle.properties"
[ -n "$wrapper_gradle" ] || die "Gradle wrapper distribution version could not be parsed"
[ "$configured_gradle" = "$wrapper_gradle" ] || die "gradleVersion ($configured_gradle) does not match wrapper ($wrapper_gradle)"
[[ "$wrapper_sha" =~ ^[0-9a-f]{64}$ ]] || die "Gradle wrapper distributionSha256Sum is missing or malformed"
if [ "$wrapper_gradle" = "9.7.1" ] && [ "$wrapper_sha" != "acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a" ]; then
  die "Gradle 9.7.1 distribution checksum does not match the reviewed upstream checksum"
fi

jcef_workflow=".github/workflows/jcef-transport-evidence.yml"
grep -q '^  push:$' "$jcef_workflow" || die "JCEF evidence has no push trigger"
grep -q '^    branches: \[ main \]$' "$jcef_workflow" || die "JCEF evidence push trigger is not scoped to main"

if grep -Eq '^[[:space:]]+gradle[[:space:]]+runIdeForUiTests' .github/workflows/run-ui-tests.yml; then
  die "UI workflow invokes runner Gradle instead of the repository wrapper"
fi
grep -q './gradlew runIdeForUiTests' .github/workflows/run-ui-tests.yml || die "UI workflow does not use the Gradle wrapper"

build_workflow=".github/workflows/build.yml"
if grep -qi 'codecov' "$build_workflow"; then
  die "required Build workflow must not hide a non-authoritative external Codecov upload"
fi
test_job="$(awk '
  /^  test:$/ { on=1 }
  on && /^  inspectCode:$/ { exit }
  on { print }
' "$build_workflow")"
[ -n "$test_job" ] || die "required Test job could not be located"
coverage_count="$(grep -c '^[[:space:]]*- name: Upload Kover Coverage Report$' <<<"$test_job" || true)"
[ "$coverage_count" = "1" ] || die "required Test job must contain exactly one Kover coverage artifact step"
coverage_block="$(awk '
  /^[[:space:]]*- name: Upload Kover Coverage Report$/ { on=1; start=NR }
  on && NR > start && /^      - name:/ { exit }
  on { print }
' <<<"$test_job")"
grep -q 'uses: actions/upload-artifact@' <<<"$coverage_block" || die "Kover coverage evidence is not uploaded as a GitHub artifact"
grep -q 'name: kover-coverage' <<<"$coverage_block" || die "Kover coverage artifact name is missing"
grep -Fq 'path: ${{ github.workspace }}/build/reports/kover/report.xml' <<<"$coverage_block" || die "Kover coverage artifact path is not the verified XML report"
grep -q 'if-no-files-found: error' <<<"$coverage_block" || die "Kover coverage artifact does not fail closed when the report is missing"
if grep -q '^[[:space:]]*if:' <<<"$coverage_block"; then
  die "Kover coverage artifact step must not be conditionally suppressed"
fi
if grep -q 'continue-on-error:[[:space:]]*true' <<<"$coverage_block"; then
  die "Kover coverage artifact upload must not continue on error"
fi

dependabot=".github/dependabot.yml"
gradle_block="$(awk '/package-ecosystem: "gradle"/{on=1} /package-ecosystem: "npm"/{on=0} on' "$dependabot")"
npm_block="$(awk '/package-ecosystem: "npm"/{on=1} /package-ecosystem: "github-actions"/{on=0} on' "$dependabot")"
actions_block="$(awk '/package-ecosystem: "github-actions"/{on=1} on' "$dependabot")"

if grep -q 'groups:' <<<"$gradle_block"; then
  die "Gradle updates must remain individually reviewable"
fi
grep -q 'npm-low-risk-dev-minor-and-patch:' <<<"$npm_block" || die "bounded npm routine-update group is missing"
grep -q 'applies-to: version-updates' <<<"$npm_block" || die "npm routine group is not limited to version updates"
grep -q 'dependency-type: development' <<<"$npm_block" || die "npm routine group is not limited to development dependencies"
grep -Fq -- '- "@types/*"' <<<"$npm_block" || die "npm routine group is missing @types/*"
grep -Fq -- '- "jsdom"' <<<"$npm_block" || die "npm routine group is missing jsdom"
if grep -Fq -- '- "*"' <<<"$npm_block"; then
  die "npm routine group must not wildcard all editor/renderer/toolchain dependencies"
fi
for level in minor patch; do
  grep -Fq -- "- \"$level\"" <<<"$npm_block" || die "npm routine group is missing $level update type"
done

grep -q 'github-actions-minor-and-patch:' <<<"$actions_block" || die "GitHub Actions routine-update group is missing"
grep -q 'applies-to: version-updates' <<<"$actions_block" || die "GitHub Actions group is not limited to version updates"
for level in minor patch; do
  grep -Fq -- "- \"$level\"" <<<"$actions_block" || die "GitHub Actions group is missing $level update type"
done

jq -e '.staged_required[] | select(.context == "Hardening audit") | .promotion | contains("fork PR")' \
  .github/merge-gate-policy.json >/dev/null || die "Hardening audit staged rationale is missing fork-PR re-evaluation"

if grep -q 'MarkFlow-private' docs/release/recovery.md; then
  die "release recovery still references the retired private repository name"
fi
grep -q '#139 and #141 are completed' docs/architecture/README.md || die "architecture index does not record completed reset/reconciliation gates"
grep -q '^## Dependency update governance$' GOVERNANCE.md || die "dependency update governance contract is missing"

echo "repository hardening contract checks passed"
