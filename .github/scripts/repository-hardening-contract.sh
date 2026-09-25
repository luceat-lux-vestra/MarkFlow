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

assert_recovery_target_validation() {
  local workflow="$1" label="$2"
  grep -q 'name: Validate dispatched target' "$workflow" || die "$label has no pre-checkout recovery target validation"
  grep -q 'Recovery target is not an exact 40-hex commit SHA' "$workflow" || die "$label does not require an exact commit SHA"
  grep -q 'merge_base_commit.sha' "$workflow" || die "$label does not prove recovery target ancestry"
  grep -q 'Recovery target is not the current default branch or one of its ancestors' "$workflow" || die "$label does not fail closed on non-main recovery targets"
}

assert_gradle_catalog_trigger() {
  local workflow="$1" label="$2" count
  count="$(grep -Fc -- "- 'gradle/**'" "$workflow" || true)"
  [ "$count" = "2" ] || die "$label must watch gradle/** for both push and pull_request"
}

assert_webview_dependency_trigger() {
  local workflow="$1" label="$2" package_count lock_count
  package_count="$(grep -Fc -- "- 'webview/package.json'" "$workflow" || true)"
  lock_count="$(grep -Fc -- "- 'webview/package-lock.json'" "$workflow" || true)"
  [ "$package_count" = "2" ] || die "$label must watch webview/package.json for both push and pull_request"
  [ "$lock_count" = "2" ] || die "$label must watch webview/package-lock.json for both push and pull_request"
}

assert_node26_setup_count() {
  local workflow="$1" label="$2" expected="$3" count
  count="$(grep -Ec '^[[:space:]]+node-version:[[:space:]]+26$' "$workflow" || true)"
  [ "$count" = "$expected" ] || die "$label must select Node 26 in every Gradle job (expected $expected, found $count)"
}

[ "$(tr -d '[:space:]' < .nvmrc)" = "26" ] || die ".nvmrc must select Node 26"
jq -e '.engines.node == ">=26 <27"' webview/package.json >/dev/null || die "webview package must constrain Node to the 26.x line"
jq -e '.devDependencies["@types/node"] | startswith("^26.")' webview/package.json >/dev/null || die "@types/node must align to Node 26"
grep -Fq 'major!==26' build.gradle.kts || die "Gradle renderer build does not fail closed outside Node 26"
grep -Fq 'dependsOn(verifyRendererNode)' build.gradle.kts || die "npmInstallWebview does not enforce the Node 26 verification task"

assert_node26_setup_count ".github/workflows/build.yml" "Build workflow" 3
assert_node26_setup_count ".github/workflows/release.yml" "Release workflow" 1
assert_node26_setup_count ".github/workflows/starter-driver-e2e.yml" "Starter Driver workflow" 1
assert_node26_setup_count ".github/workflows/native-editor-shell-evidence.yml" "Native Editor Shell workflow" 1
assert_node26_setup_count ".github/workflows/native-editing-evidence.yml" "Native Editing workflow" 1
assert_node26_setup_count ".github/workflows/derived-renderer-evidence.yml" "Derived Renderer workflow" 1
assert_node26_setup_count ".github/workflows/native-host-resources-evidence.yml" "Native Host Resources workflow" 1
assert_node26_setup_count ".github/workflows/native-projection-evidence.yml" "Native Projection workflow" 1
assert_node26_setup_count ".github/workflows/no-jcef-native-editing-evidence.yml" "No-JCEF Native Editing workflow" 1
assert_node26_setup_count ".github/workflows/native-image-import-evidence.yml" "Native Image Import workflow" 1
assert_node26_setup_count ".github/workflows/native-derived-presentation-evidence.yml" "Native Derived Presentation workflow" 1

for workflow in \
  ".github/workflows/native-projection-evidence.yml" \
  ".github/workflows/native-host-resources-evidence.yml" \
  ".github/workflows/native-image-import-evidence.yml"; do
  assert_gradle_catalog_trigger "$workflow" "$(basename "$workflow")"
done

for workflow in \
  ".github/workflows/starter-driver-e2e.yml" \
  ".github/workflows/native-editor-shell-evidence.yml" \
  ".github/workflows/native-editing-evidence.yml" \
  ".github/workflows/derived-renderer-evidence.yml" \
  ".github/workflows/native-host-resources-evidence.yml" \
  ".github/workflows/native-projection-evidence.yml" \
  ".github/workflows/no-jcef-native-editing-evidence.yml" \
  ".github/workflows/native-image-import-evidence.yml" \
  ".github/workflows/native-derived-presentation-evidence.yml"; do
  assert_webview_dependency_trigger "$workflow" "$(basename "$workflow")"
done

renderer_workflow=".github/workflows/derived-renderer-evidence.yml"
grep -q '^  push:$' "$renderer_workflow" || die "Derived renderer evidence has no push trigger"
grep -q '^    branches: \[ main \]$' "$renderer_workflow" || die "Derived renderer evidence push trigger is not scoped to main"
grep -q '^  workflow_dispatch:$' "$renderer_workflow" || die "Derived renderer evidence has no manual exact-SHA recovery trigger"
grep -q '^      target_sha:$' "$renderer_workflow" || die "Derived renderer evidence recovery trigger has no target_sha input"
grep -Fq "github.event_name == 'workflow_dispatch' && inputs.target_sha" "$renderer_workflow" || die "Derived renderer evidence does not checkout the requested recovery SHA"
assert_recovery_target_validation "$renderer_workflow" "Derived renderer evidence"

[ ! -e .github/workflows/run-ui-tests.yml ] || die "obsolete Robot UI workflow must stay deleted"
if grep -Eq 'runIdeForUiTests|robotServerPlugin|robot-server\.port' build.gradle.kts; then
  die "obsolete Robot UI test infrastructure remains in build.gradle.kts"
fi

starter_workflow=".github/workflows/starter-driver-e2e.yml"
starter_diagnostics=".github/scripts/check-starter-driver-diagnostics.py"
[ -f "$starter_diagnostics" ] || die "Starter/Driver diagnostics gate is missing"
python3 "$starter_diagnostics" --self-test >/dev/null || die "Starter/Driver diagnostics self-test failed"
grep -Fq ".github/scripts/check-starter-driver-diagnostics.py" "$starter_workflow" || die "Starter workflow does not trigger on diagnostics-gate changes"
grep -q 'check-starter-driver-diagnostics.py --self-test' "$starter_workflow" || die "Starter workflow does not execute diagnostics negative controls"
grep -q -- '--root "$pass_dir"' "$starter_workflow" || die "Starter workflow does not validate each retained pass independently"
grep -q -- '--output "$pass_dir/diagnostics.json"' "$starter_workflow" || die "Starter workflow does not persist structured diagnostics for each pass"
grep -q 'build/e2e-evidence/\*\*' "$starter_workflow" || die "Starter workflow does not upload pass-scoped diagnostics evidence"

build_workflow=".github/workflows/build.yml"
if grep -qi 'codecov' "$build_workflow"; then
  die "required Build workflow must not hide a non-authoritative external Codecov upload"
fi
squash_guard=".github/scripts/check-squash-message-safety.py"
[ -f "$squash_guard" ] || die "squash message safety guard is missing"
python3 "$squash_guard" --self-test >/dev/null || die "squash message safety guard fixtures failed"
grep -Fq 'types: [opened, synchronize, reopened, ready_for_review]' "$build_workflow" || die "required Build workflow does not revalidate final-gate readiness"
grep -q '^  workflow_dispatch:$' "$build_workflow" || die "required Build workflow has no exact-SHA recovery trigger"
grep -q '^      target_sha:$' "$build_workflow" || die "required Build recovery trigger has no target_sha input"
failure_workflow=".github/workflows/failure-declaration.yml"
grep -q '^[[:space:]]*- edited$' "$failure_workflow" || die "failure declaration workflow does not revalidate squash metadata edits"
grep -q 'Reject squash CI-skip directives' "$failure_workflow" || die "failure declaration workflow does not reject unsafe squash metadata"
grep -Fq 'PR_TITLE: ${{ github.event.pull_request.title }}' "$failure_workflow" || die "squash guard does not inspect PR title"
grep -Fq "PR_BODY: \${{ github.event.pull_request.body || '' }}" "$failure_workflow" || die "squash guard does not inspect PR body"
grep -q 'python3 .github/scripts/check-squash-message-safety.py' "$failure_workflow" || die "failure declaration workflow does not invoke squash message safety guard"
grep -Fq "github.event_name == 'workflow_dispatch' && inputs.target_sha" "$build_workflow" || die "required Build workflow does not checkout the requested recovery SHA"
assert_recovery_target_validation "$build_workflow" "required Build workflow"

hardening_workflow=".github/workflows/hardening-audit.yml"
grep -q '^  workflow_dispatch:$' "$hardening_workflow" || die "hardening audit has no manual recovery trigger"
grep -q '^      target_sha:$' "$hardening_workflow" || die "hardening recovery trigger has no target_sha input"
grep -q 'Verify squash message guard fixtures' "$hardening_workflow" || die "hardening audit does not execute squash guard fixtures"
grep -q 'check-squash-message-safety.py --self-test' "$hardening_workflow" || die "hardening audit does not enforce squash guard negative controls"
grep -Fq "github.event_name == 'workflow_dispatch' && inputs.target_sha" "$hardening_workflow" || die "hardening audit does not checkout the requested recovery SHA"
assert_recovery_target_validation "$hardening_workflow" "hardening audit"

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
coverage_if="$(grep -E '^[[:space:]]*if:' <<<"$coverage_block" | sed -E 's/^[[:space:]]+//' || true)"
expected_coverage_if='if: ${{ github.event_name != '"'"'pull_request'"'"' || github.event.action != '"'"'ready_for_review'"'"' }}'
[ "$coverage_if" = "$expected_coverage_if" ] || die "Kover coverage artifact must only be suppressed by exact-SHA ready-for-review evidence reuse"
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
if jq -e '.devDependencies | has("jsdom")' webview/package.json >/dev/null; then
  grep -Fq -- '- "jsdom"' <<<"$npm_block" || die "npm routine group is missing declared jsdom"
elif grep -Fq -- '- "jsdom"' <<<"$npm_block"; then
  die "npm routine group still targets removed jsdom"
fi
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
if grep -Eq '^\| .*`TEMPORARY`' docs/architecture/leap-migration-inventory.md; then
  die "#141 migration inventory still contains an unresolved TEMPORARY production row"
fi
grep -q '^## Dependency update governance$' GOVERNANCE.md || die "dependency update governance contract is missing"
grep -q 'PR titles and bodies must not contain GitHub Actions skip directives' GOVERNANCE.md || die "squash message safety governance is missing"
grep -q 'exact-SHA `workflow_dispatch` recovery input' GOVERNANCE.md || die "exact-SHA post-main recovery governance is missing"

echo "repository hardening contract checks passed"
