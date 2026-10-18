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
if [ "$wrapper_gradle" = "9.8.1" ] && [ "$wrapper_sha" != "dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f" ]; then
  die "Gradle 9.8.1 distribution checksum does not match the reviewed upstream checksum"
fi

assert_recovery_target_validation() {
  local workflow="$1" label="$2"
  grep -q 'name: Validate dispatched target' "$workflow" || die "$label has no pre-checkout recovery target validation"
  grep -q 'Recovery target is not an exact 40-hex commit SHA' "$workflow" || die "$label does not require an exact commit SHA"
  grep -q 'merge_base_commit.sha' "$workflow" || die "$label does not prove recovery target ancestry"
  grep -q 'Recovery target is not the current default branch or one of its ancestors' "$workflow" || die "$label does not fail closed on non-main recovery targets"
}

assert_pr_only_source_trigger() {
  local workflow="$1" label="$2"
  grep -Eq '^  pull_request:([[:space:]]|$)' "$workflow" || die "$label must retain pull_request validation"
  grep -Eq '^  workflow_dispatch:([[:space:]]|$)' "$workflow" || die "$label must retain manual recovery"
  if grep -Eq '^  push:([[:space:]]|$)' "$workflow"; then
    die "$label must not rerun pre-merge source validation on push"
  fi
}

# Negative controls use rewindable temporary regular files because each
# guard performs multiple grep passes (a process substitution pipe is consumed).
trigger_fixture_dir="$(mktemp -d)"
trap 'rm -rf "$trigger_fixture_dir"' EXIT
printf 'on:\n  pull_request:\n  workflow_dispatch:\n' > "$trigger_fixture_dir/valid.yml"
printf 'on:\n  pull_request:\n  push: # regression\n  workflow_dispatch:\n' > "$trigger_fixture_dir/push.yml"
printf 'on:\n  workflow_dispatch:\n' > "$trigger_fixture_dir/missing-pr.yml"
assert_pr_only_source_trigger "$trigger_fixture_dir/valid.yml" "PR-only fixture"
if ( assert_pr_only_source_trigger "$trigger_fixture_dir/push.yml" "forbidden push fixture" ) 2>/dev/null; then
  die "PR-only trigger guard accepted a reintroduced push event"
fi
if ( assert_pr_only_source_trigger "$trigger_fixture_dir/missing-pr.yml" "missing PR fixture" ) 2>/dev/null; then
  die "PR-only trigger guard accepted a missing PR event"
fi

assert_ephemeral_workflow_lifecycle() {
  local workflow="$1" label="$2" name
  name="$(basename "$workflow")"

  if [[ ! "$name" =~ ^(research|temporary|tmp|experiment)[-_].*\.ya?ml$ ]]; then
    return 0
  fi

  head -n 20 "$workflow" | grep -Eq '^[[:space:]]*#[[:space:]]*lifecycle-owner:[[:space:]]*[^[:space:]].*$' ||
    die "$label requires non-empty # lifecycle-owner: header metadata"
  head -n 20 "$workflow" | grep -Eq '^[[:space:]]*#[[:space:]]*lifecycle-retire:[[:space:]]*[^[:space:]].*$' ||
    die "$label requires non-empty # lifecycle-retire: header metadata"
}

cat > "$trigger_fixture_dir/research-lifecycle-valid.yml" <<'EOF'
# lifecycle-owner: issue #439
# lifecycle-retire: delete after bounded research evidence is recorded
name: lifecycle fixture
EOF
cat > "$trigger_fixture_dir/research-lifecycle-missing-owner.yml" <<'EOF'
# lifecycle-owner:
# lifecycle-retire: delete after bounded research evidence is recorded
name: lifecycle fixture
EOF
cat > "$trigger_fixture_dir/experiment-lifecycle-missing-retire.yaml" <<'EOF'
# lifecycle-owner: issue #439
# lifecycle-retire:
name: lifecycle fixture
EOF
cat > "$trigger_fixture_dir/ci-maintained-fixture.yml" <<'EOF'
name: maintained workflow fixture
EOF

assert_ephemeral_workflow_lifecycle "$trigger_fixture_dir/research-lifecycle-valid.yml" "valid ephemeral workflow fixture"
if ( assert_ephemeral_workflow_lifecycle "$trigger_fixture_dir/research-lifecycle-missing-owner.yml" "missing-owner fixture" ) 2>/dev/null; then
  die "ephemeral workflow lifecycle guard accepted a missing owner"
fi
if ( assert_ephemeral_workflow_lifecycle "$trigger_fixture_dir/experiment-lifecycle-missing-retire.yaml" "missing-retirement fixture" ) 2>/dev/null; then
  die "ephemeral workflow lifecycle guard accepted a missing retirement condition"
fi
assert_ephemeral_workflow_lifecycle "$trigger_fixture_dir/ci-maintained-fixture.yml" "maintained workflow fixture"

for workflow in .github/workflows/*.yml .github/workflows/*.yaml; do
  [ -e "$workflow" ] || continue
  assert_ephemeral_workflow_lifecycle "$workflow" "$(basename "$workflow")"
done

# Stage D approved CI optimization: CodeQL must not run on PRs or pushes.
# Keep weekly default-branch scanning and manual exact-SHA release evidence.
assert_codeql_schedule_manual_only() {
  local workflow="$1"
  grep -Eq '^  schedule:([[:space:]]|$)' "$workflow" || die "CodeQL must retain weekly schedule"
  grep -Eq '^  workflow_dispatch:([[:space:]]|$)' "$workflow" || die "CodeQL must retain manual candidate scan"
  if grep -Eq '^  (pull_request|pull_request_target|push):([[:space:]]|$)' "$workflow"; then
    die "CodeQL PR/push scanning has been intentionally retired; use schedule and dispatch only"
  fi
}
printf 'on:\n  schedule:\n  workflow_dispatch:\n' > "$trigger_fixture_dir/codeql-schedule-manual.yml"
printf 'on:\n  schedule:\n  workflow_dispatch:\n  pull_request:\n' > "$trigger_fixture_dir/codeql-pr-regression.yml"
assert_codeql_schedule_manual_only "$trigger_fixture_dir/codeql-schedule-manual.yml"
if ( assert_codeql_schedule_manual_only "$trigger_fixture_dir/codeql-pr-regression.yml" ) 2>/dev/null; then
  die "CodeQL trigger guard accepted PR-trigger regression"
fi
assert_codeql_schedule_manual_only .github/workflows/codeql.yml

assert_codeql_schedule_noop_guard() {
  local workflow="$1"
  grep -Fq 'name: CodeQL schedule guard' "$workflow" ||
    die "CodeQL scheduled scans must have an unchanged-main guard"
  grep -Fq 'if [ "$GITHUB_EVENT_NAME" != "schedule" ]; then' "$workflow" ||
    die "CodeQL manual/release dispatch must bypass the schedule-only skip decision"
  grep -Fq 'echo "should_scan=true" >> "$GITHUB_OUTPUT"' "$workflow" ||
    die "CodeQL explicit dispatch must always authorize a full scan"
  grep -Fq 'event=schedule&status=success&branch=main&per_page=100' "$workflow" ||
    die "CodeQL schedule guard must compare against successful main schedule history"
  grep -Fq '.head_sha == $sha' "$workflow" ||
    die "CodeQL schedule guard must use exact protected-main SHA equality"
  grep -Fq 'needs: scan-guard' "$workflow" ||
    die "CodeQL analyze matrix must depend on the schedule guard"
  grep -Fq "if: needs.scan-guard.outputs.should_scan == 'true'" "$workflow" ||
    die "CodeQL analyze matrix can run only when the guard authorizes it"
}

assert_codeql_schedule_noop_guard .github/workflows/codeql.yml
cp .github/workflows/codeql.yml "$trigger_fixture_dir/codeql-guard-regression.yml"
sed -i 's/\.head_sha == \$sha/.head_branch == "main"/' "$trigger_fixture_dir/codeql-guard-regression.yml"
if ( assert_codeql_schedule_noop_guard "$trigger_fixture_dir/codeql-guard-regression.yml" ) 2>/dev/null; then
  die "CodeQL schedule no-op guard accepted non-exact source identity"
fi

assert_gradle_catalog_trigger() {
  local workflow="$1" label="$2" count
  count="$(grep -Fc -- "- 'gradle/**'" "$workflow" || true)"
  [ "$count" = "1" ] || die "$label must watch gradle/** exactly once on pull_request"
}

assert_webview_dependency_trigger() {
  local workflow="$1" label="$2" package_count lock_count
  package_count="$(grep -Fc -- "- 'webview/package.json'" "$workflow" || true)"
  lock_count="$(grep -Fc -- "- 'webview/package-lock.json'" "$workflow" || true)"
  [ "$package_count" = "1" ] || die "$label must watch webview/package.json exactly once on pull_request"
  [ "$lock_count" = "1" ] || die "$label must watch webview/package-lock.json exactly once on pull_request"
}

assert_gradle_managed_node() {
  local workflow="$1" label="$2"
  if grep -Fq 'actions/setup-node@' "$workflow"; then
    die "$label must exercise Gradle-managed renderer Node provisioning instead of actions/setup-node"
  fi
}

[ "$(tr -d '[:space:]' < .nvmrc)" = "26.10.0" ] || die ".nvmrc must pin renderer Node 26.10.0"
jq -e '.engines.node == ">=26 <27"' webview/package.json >/dev/null || die "webview package must constrain Node to the 26.x line"
jq -e '.devDependencies["@types/node"] | startswith("^26.")' webview/package.json >/dev/null || die "@types/node must align to Node 26"
grep -Fq 'url = uri("https://nodejs.org/dist/")' build.gradle.kts || die "Gradle renderer build must resolve Node from the official distribution repository"
grep -Fq 'artifact("v[revision]/[artifact]-v[revision]-[classifier].[ext]")' build.gradle.kts || die "Gradle renderer Node repository must use the pinned distribution artifact pattern"
grep -Fq 'val provisionRendererNode = tasks.register<Sync>("provisionRendererNode")' build.gradle.kts || die "Gradle renderer build must own Node provisioning"
grep -Fq 'dependsOn(provisionRendererNode)' build.gradle.kts || die "renderer Node verification must depend on Gradle provisioning"
grep -Fq 'rendererNodeExecutable.get().asFile.absolutePath' build.gradle.kts || die "renderer tasks must execute the Gradle-provisioned Node binary"
grep -Fq 'environment("PATH", rendererNodePath.get())' build.gradle.kts || die "renderer npm tasks must expose the Gradle-provisioned Node binary to child scripts"
grep -Fq 'val testWebviewSource = tasks.register<Exec>("testWebviewSource")' build.gradle.kts || die "webview source tests must be available through the Gradle-managed Node toolchain"
grep -Fq 'disabledForTestTasks.add("integrationTest")' build.gradle.kts || die "Kover onCheck coverage must not pull Starter/Driver integrationTest into ordinary check/build"
[ -f '.run/Build Plugin.run.xml' ] || die "shared Build Plugin run configuration is missing"
grep -Fq 'name="Build Plugin"' '.run/Build Plugin.run.xml' || die "shared build-only run configuration must be named Build Plugin"
grep -Fq '<option value="buildPlugin" />' '.run/Build Plugin.run.xml' || die "shared Build Plugin run configuration must invoke Gradle buildPlugin"
if grep -Fq '<option value="build" />' '.run/Build Plugin.run.xml' \
  || grep -Fq '<option value="check" />' '.run/Build Plugin.run.xml' \
  || grep -Fq '<option value="integrationTest" />' '.run/Build Plugin.run.xml'; then
  die "shared Build Plugin run configuration must not invoke build/check/Starter E2E"
fi
[ ! -e '.run/Build.run.xml' ] || die "stale shared Build -> build configuration must be removed"
[ -f '.run/Run E2E.run.xml' ] || die "shared Run E2E configuration is missing"
grep -Fq '<option value="integrationTest" />' '.run/Run E2E.run.xml' || die "shared Run E2E configuration must invoke integrationTest"
if grep -Fq 'commandLine("node"' build.gradle.kts || grep -Fq 'commandLine("npm"' build.gradle.kts; then
  die "renderer Gradle tasks must not depend on PATH-resolved node/npm executables"
fi
grep -Fq 'run: ./gradlew testWebviewSource' .github/workflows/build.yml || die "Build workflow must run webview source tests through Gradle-managed Node"

assert_gradle_managed_node ".github/workflows/build.yml" "Build workflow"
assert_gradle_managed_node ".github/workflows/release.yml" "Release workflow"
assert_gradle_managed_node ".github/workflows/starter-driver-e2e.yml" "Starter Driver workflow"
assert_gradle_managed_node ".github/workflows/native-runtime-evidence.yml" "Native Runtime Evidence workflow"

assert_gradle_catalog_trigger ".github/workflows/native-runtime-evidence.yml" "Native Runtime Evidence workflow"

for workflow in \
  ".github/workflows/starter-driver-e2e.yml" \
  ".github/workflows/native-runtime-evidence.yml"; do
  assert_pr_only_source_trigger "$workflow" "$(basename "$workflow")"
  assert_webview_dependency_trigger "$workflow" "$(basename "$workflow")"
done

native_evidence_workflow=".github/workflows/native-runtime-evidence.yml"
grep -q '^  workflow_dispatch:$' "$native_evidence_workflow" || die "Native runtime evidence has no manual exact-SHA recovery trigger"
grep -q '^      target_sha:$' "$native_evidence_workflow" || die "Native runtime evidence recovery trigger has no target_sha input"
grep -Fq 'needs.target.outputs.source_sha' "$native_evidence_workflow" || die "Native runtime evidence jobs do not share the resolved exact source SHA"
assert_recovery_target_validation "$native_evidence_workflow" "Native runtime evidence"

for job in \
  derived_renderer \
  native_derived_presentation \
  native_editing \
  native_editor_shell \
  native_host_resources \
  native_image_import \
  native_projection \
  no_jcef_native_editing; do
  grep -Eq "^  ${job}:" "$native_evidence_workflow" || die "Native runtime evidence lost probe job: $job"
done

for semantic_probe in \
  'markflow-derived-renderer-probe' \
  'markflow.nativeDerivedPresentationProbe.output' \
  'runIdeForNativeEditingProbe' \
  'runIdeForNativeEditorShellProbe' \
  'runIdeForNativeHostResourcesProbe' \
  'markflow.nativeImageImportProbe.output' \
  'runIdeForNativeProjectionProbe' \
  'runIdeForNoJcefNativeEditingProbe'; do
  grep -Fq "$semantic_probe" "$native_evidence_workflow" || die "Native runtime evidence lost semantic probe: $semantic_probe"
done

exact_native_checkout_count="$(grep -Fc 'ref: ${{ needs.target.outputs.source_sha }}' "$native_evidence_workflow")"
[ "$exact_native_checkout_count" -ge 8 ] ||
  die "Native runtime evidence does not pin every probe checkout to the resolved exact source SHA"


grep -Fq 'pull-requests: read' "$native_evidence_workflow" ||
  die "Native runtime evidence changed-file router lacks pull-request read permission"
grep -Fq 'pulls/$PR_NUMBER/files?per_page=100' "$native_evidence_workflow" ||
  die "Native runtime evidence no longer routes from the complete PR changed-file list"
for job in \
  derived_renderer \
  native_derived_presentation \
  native_editing \
  native_editor_shell \
  native_host_resources \
  native_image_import \
  native_projection \
  no_jcef_native_editing; do
  grep -Fq "run_${job}:" "$native_evidence_workflow" ||
    die "Native runtime evidence lost router output name for $job"
  grep -Fq "steps.route.outputs.run_${job}" "$native_evidence_workflow" ||
    die "Native runtime evidence lost router output binding for $job"
  grep -Fq "needs.target.outputs.run_${job} == 'true'" "$native_evidence_workflow" ||
    die "Native runtime evidence $job no longer honors per-probe path routing"
done

[ ! -e .github/workflows/run-ui-tests.yml ] || die "obsolete Robot UI workflow must stay deleted"
if grep -Eq 'runIdeForUiTests|robotServerPlugin|robot-server\.port' build.gradle.kts; then
  die "obsolete Robot UI test infrastructure remains in build.gradle.kts"
fi

starter_workflow=".github/workflows/starter-driver-e2e.yml"
full_differential_workflow=".github/workflows/full-differential-acceptance.yml"
[ -f "$full_differential_workflow" ] || die "#353 canonical full differential workflow is missing"
[ ! -e .github/workflows/full-differential-acceptance-post-main.yml ] || die "#353 obsolete post-main push workflow must stay deleted"
grep -Eq '^  workflow_dispatch:([[:space:]]|$)' "$full_differential_workflow" || die "#353 full differential must retain manual exact-SHA execution"
grep -Eq '^  schedule:([[:space:]]|$)' "$full_differential_workflow" || die "#353 full differential must retain temporary stabilization cadence"
grep -Fq "cron: '17 18 1,15 * *'" "$full_differential_workflow" || die "#353 stabilization cadence must remain twice monthly until explicitly retired"
if grep -Eq '^  (pull_request|pull_request_target|push):([[:space:]]|$)' "$full_differential_workflow"; then
  die "#353 full corpus must not return to ordinary PR/push CI"
fi
grep -Fq 'target_sha:' "$full_differential_workflow" || die "#353 manual full corpus lost exact-SHA input"
grep -Fq 'Full differential target must be an exact 40-character commit SHA' "$full_differential_workflow" || die "#353 manual full corpus no longer rejects mutable/ambiguous refs"
grep -Fq 'event=schedule&status=completed' "$full_differential_workflow" || die "#353 scheduled cadence no longer anchors its no-change guard to the previous scheduled attempt"
grep -Fq 'git diff --name-only "$previous_sha" "$SOURCE_SHA"' "$full_differential_workflow" || die "#353 scheduled cadence no longer checks changes since the previous scheduled SHA"
grep -Fq 'src/main/' "$full_differential_workflow" || die "#353 scheduled relevant-change guard lost production sources"
grep -Fq 'src/integrationTest/' "$full_differential_workflow" || die "#353 scheduled relevant-change guard lost real-IDE acceptance sources"
grep -Fq 'fixtures/differential-acceptance/' "$full_differential_workflow" || die "#353 scheduled relevant-change guard lost acceptance fixtures"
grep -Fq 'run_full=false' "$full_differential_workflow" || die "#353 scheduled no-change path no longer skips the expensive corpus"
grep -Fq "if: needs.policy.outputs.run_full == 'true'" "$full_differential_workflow" || die "#353 expensive jobs no longer honor cadence policy"
grep -Fq 'cache-read-only: true' "$full_differential_workflow" || die "#353 full differential must not write Gradle cache state"
grep -Fq 'STARTER_SHARD: full-differential-acceptance' "$full_differential_workflow" || die "#353 full differential lost the bounded JCEF AppArmor identity"
full_differential_watchdog=".github/scripts/run-full-differential-with-watchdog.sh"
[ -x "$full_differential_watchdog" ] || die "#353 full differential watchdog is missing or not executable"
bash "$full_differential_watchdog" --self-test >/dev/null || die "#353 full differential watchdog self-test failed"
grep -Fq 'MARKFLOW_WATCHDOG_IDLE_SECONDS:-180' "$full_differential_watchdog" || die "#353 watchdog lost the three-minute active-phase inactivity bound"
grep -Fq 'MARKFLOW_WATCHDOG_STARTUP_SECONDS:-900' "$full_differential_watchdog" || die "#353 watchdog lost the fifteen-minute startup bound"
grep -Fq 'MARKFLOW_WATCHDOG_TOTAL_SECONDS:-5100' "$full_differential_watchdog" || die "#353 watchdog lost the 85-minute absolute bound"
grep -Fq 'MARKFLOW_WATCHDOG_REPORT_SECONDS:-60' "$full_differential_watchdog" || die "#353 watchdog lost the one-minute console keepalive cadence"
grep -Fq 'FULL_DIFFERENTIAL_HEARTBEAT' "$full_differential_watchdog" || die "#353 watchdog no longer emits live progress to the Actions log"
grep -Fq 'phase="$(awk -F' "$full_differential_watchdog" || die "#353 watchdog no longer parses progress phases"
grep -Fq '[ "$phase" = "test-start" ] || [ "$phase" = "starter-launch" ]' "$full_differential_watchdog" || die "#353 watchdog no longer preserves the startup allowance before IDE readiness"
grep -Fq 'FULL_DIFFERENTIAL_PROCESS_GROUP' "$full_differential_watchdog" || die "#353 watchdog lost process-group identity diagnostics"
grep -Fq '[ "$child_pgid" != "$child_pid" ]' "$full_differential_watchdog" || die "#353 watchdog no longer fails closed when setsid isolation is broken"
grep -Fq '[ "$child_pgid" = "$wrapper_pgid" ]' "$full_differential_watchdog" || die "#353 watchdog no longer protects the runner shell process group"
grep -Fq 'kill -TERM -- "-$child_pid"' "$full_differential_watchdog" || die "#353 watchdog no longer terminates the whole Gradle/IDE process group"
grep -Fq 'kill -KILL -- "-$child_pid"' "$full_differential_watchdog" || die "#353 watchdog no longer escalates stuck process cleanup"
grep -Fq 'MARKFLOW_DIFFERENTIAL_PROGRESS_FILE="$progress_file"' "$full_differential_workflow" || die "#353 full differential no longer exports heartbeat location"
grep -Fq 'run-full-differential-with-watchdog.sh' "$full_differential_workflow" || die "#353 full differential bypasses the fail-fast watchdog"
grep -Fq -- '-PdifferentialContract=acceptance' "$full_differential_workflow" || die "#353 full differential no longer executes the acceptance contract"
if grep -Fq -- '-PdifferentialCaseIds=' "$full_differential_workflow"; then
  die "#353 canonical full corpus must never reduce the 89-case inventory"
fi
grep -Fq 'MarkFlowStarterDifferentialManifestTest' "$full_differential_workflow" || die "#353 full differential no longer executes the differential manifest suite"
grep -Fq '.captured_unverified == 89' "$full_differential_workflow" || die "#353 full differential failure diagnostics no longer distinguish complete capture from review failure"
grep -Fq '.full_differential_acceptance == true' "$full_differential_workflow" || die "#353 full differential no longer requires the reviewed final verdict"
grep -Fq '.release_eligible == false' "$full_differential_workflow" || die "#353 full differential must not bypass #348/#309 release gates"
grep -Fq 'if: ${{ always() }}' "$full_differential_workflow" || die "#353 full differential must retain artifacts even on fail-closed review runs"
grep -Fq 'name: Stage stable full differential evidence' "$full_differential_workflow" ||
  die "#353 full differential no longer snapshots stable failure evidence before upload"
grep -Fq 'build/full-differential-upload/**' "$full_differential_workflow" ||
  die "#353 full differential upload no longer uses the immutable staging bundle"
grep -Fq "find build/test-results/integrationTest -maxdepth 1 -type f -name '*.xml'" "$full_differential_workflow" ||
  die "#353 full differential no longer copies only stable JUnit XML results"
if grep -Fq 'build/test-results/integrationTest/**' "$full_differential_workflow"; then
  die "#353 full differential must not upload transient Gradle binary/in-progress results directly"
fi
grep -Fq 'run_attempt=$GITHUB_RUN_ATTEMPT' "$full_differential_workflow" ||
  die "#353 full differential failure bundle lost attempt identity"

starter_diagnostics=".github/scripts/check-starter-driver-diagnostics.py"
[ -f "$starter_diagnostics" ] || die "Starter/Driver diagnostics gate is missing"
python3 "$starter_diagnostics" --self-test >/dev/null || die "Starter/Driver diagnostics self-test failed"
grep -Fq ".github/scripts/check-starter-driver-diagnostics.py" "$starter_workflow" || die "Starter workflow does not trigger on diagnostics-gate changes"
grep -q 'check-starter-driver-diagnostics.py --self-test' "$starter_workflow" || die "Starter workflow does not execute diagnostics negative controls"
if grep -q 'repeat_count:' "$starter_workflow"; then
  die "Starter workflow must not repeat the full acceptance suite in ordinary CI"
fi
if grep -q -- '--rerun-tasks' "$starter_workflow"; then
  die "Starter workflow must not force the Gradle task graph to rerun"
fi
if grep -Eq 'for[[:space:]]+pass[[:space:]]+in' "$starter_workflow"; then
  die "Starter workflow must not implement ordinary-CI pass loops"
fi
grep -q -- '--root "$pass_dir"' "$starter_workflow" || die "Starter workflow does not validate each retained shard independently"
grep -q -- '--output "$pass_dir/diagnostics.json"' "$starter_workflow" || die "Starter workflow does not persist structured diagnostics for each pass"
grep -q 'build/e2e-evidence/\*\*' "$starter_workflow" || die "Starter workflow does not upload pass-scoped diagnostics evidence"


grep -Fq "STARTER_PLATFORM_VERSION: '2026.2.3'" "$starter_workflow" || die "Starter full-product authority must stay pinned to IDEA 2026.2.3"
if grep -Eq '263\.|2026\.3|eap2|EAP2' "$starter_workflow"; then
  die "Starter full-product workflow must not make the 2026.3 EAP probe release-blocking again"
fi
[ "$(grep -Fc './gradlew buildPlugin ' "$starter_workflow" || true)" = "1" ] || die "Starter workflow must package the candidate plugin exactly once"
[ "$(grep -Fc -- '-x buildPlugin' "$starter_workflow" || true)" -ge "2" ] || die "Starter shard/repeat jobs must explicitly forbid packaged-plugin rebuilds"
grep -Fq 'actions/download-artifact@9000827ccba6bdab643e8b6fd33ac0654aef8333' "$starter_workflow" || die "Starter workflow exact-artifact download action pin drifted"
grep -Fq 'scenario_sets=canonical,core-editing,ordinary-markdown,derived-content,lifecycle,visual-acceptance' "$starter_workflow" || die "Starter package manifest does not freeze the maintained semantic and visual acceptance sets"
for shard in canonical core-editing ordinary-markdown derived-content lifecycle; do
  grep -Fq "shard: $shard" "$starter_workflow" || die "Starter workflow is missing $shard shard"
done
grep -Fq 'MarkFlowStarterFullProductJourneyTest' "$starter_workflow" || die "Starter workflow is missing canonical full-product journey"
grep -Fq 'release_candidate_repeat:' "$starter_workflow" || die "Starter workflow has no explicit release-candidate repeat control"
grep -Fq 'name: Release candidate canonical repeat' "$starter_workflow" || die "Starter workflow has no independent canonical repeat job"
grep -Fq 'name: Starter Driver E2E Gate' "$starter_workflow" || die "Starter workflow has no aggregate stable-shard gate"
grep -Fq -- '--required-suite "$suite"' "$starter_workflow" || die "Starter shard diagnostics are not scoped to their declared suites"
grep -Fq 'MarkFlowStarterFullProductJourneyTest' "$starter_diagnostics" || die "Starter diagnostics do not recognize the canonical full-product journey"

grep -Fq 'required_suites=required_suites' "$starter_diagnostics" || die "Starter diagnostics do not apply shard-scoped required suites"

# #353 genuine platform Markdown Preview uses JCEF. The original ARM64
# Starter run 37984591105 displayed its suspended-browser/AppArmor stub.
# Limit the x64 reference exception to derived-content; keep other shards ARM64.
grep -Fq 'runs-on: ${{ matrix.runner }}' "$starter_workflow" ||
  die "Starter shards no longer use audited per-shard runner mapping"
grep -F -A1 -- '- shard: canonical' "$starter_workflow" |
  grep -Fq 'runner: ubuntu-24.04-arm' ||
  die "Starter runner drift for canonical (ubuntu-24.04-arm)"
grep -F -A1 -- '- shard: core-editing' "$starter_workflow" |
  grep -Fq 'runner: ubuntu-24.04-arm' ||
  die "Starter runner drift for core-editing (ubuntu-24.04-arm)"
grep -F -A1 -- '- shard: ordinary-markdown' "$starter_workflow" |
  grep -Fq 'runner: ubuntu-24.04-arm' ||
  die "Starter runner drift for ordinary-markdown (ubuntu-24.04-arm)"
grep -F -A1 -- '- shard: derived-content' "$starter_workflow" |
  grep -Fq 'runner: ubuntu-24.04' ||
  die "Starter runner drift for derived-content (ubuntu-24.04)"
grep -F -A1 -- '- shard: lifecycle' "$starter_workflow" |
  grep -Fq 'runner: ubuntu-24.04-arm' ||
  die "Starter runner drift for lifecycle (ubuntu-24.04-arm)"
grep -Fq "MarkFlowStarterDifferentialPreviewSmokeTest" "$starter_workflow" ||
  die "Starter workflow silently dropped bundled Markdown Preview capture"
grep -Fq "MarkFlowStarterDifferentialPreviewSmokeTest" "$starter_diagnostics" ||
  die "Starter diagnostics silently dropped bundled Markdown Preview suite"
# #353 Stage D remains a bounded capture probe, not full acceptance.
manifest_test="src/integrationTest/kotlin/com/algorist/markflow/e2e/MarkFlowStarterDifferentialManifestTest.kt"
grep -Fq 'MARKFLOW_DIFFERENTIAL_PROGRESS_FILE' "$manifest_test" ||
  die "#353 full differential test lost workflow-visible heartbeat output"
grep -Fq 'heartbeat("case-start", entry.id)' "$manifest_test" ||
  die "#353 full differential test no longer marks case progress before blocking Driver calls"
grep -Fq 'heartbeat("case-complete", entry.id)' "$manifest_test" ||
  die "#353 full differential test no longer marks successful case completion"
for phase in case-opened case-native-ready case-native-evidence-complete case-reference-open case-reference-visible case-reference-painted case-evidence-complete case-cleanup-start case-cleanup-complete; do
  grep -Fq "heartbeat(\"$phase\", entry.id)" "$manifest_test" ||
    die "#353 full differential lost intra-case heartbeat phase: $phase"
done
grep -Fq 'FULL_CORPUS_REFERENCE_BLANK_RECOVERY_ONCE' "$manifest_test" ||
  die "#353 full differential lost bounded blank Preview recovery"
grep -Fq 'initial_result=BLANK_AFTER_20S' "$manifest_test" ||
  die "#353 Preview recovery no longer retains the first blank observation"
grep -Fq 'recovery_attempts=1' "$manifest_test" ||
  die "#353 Preview recovery must remain single-attempt and fail closed"
grep -Fq 'preview.restoreNative(remoteEditor)' "$manifest_test" ||
  die "#353 Preview recovery no longer cycles through the authoritative native editor"
grep -Fq 'Bundled Preview recovery altered authoritative source state' "$manifest_test" ||
  die "#353 Preview recovery lost source/stamp/caret/unsaved invariants"
grep -Fq 'Bundled JCEF paints after single recovery' "$manifest_test" ||
  die "#353 Preview recovery no longer requires a genuine nonblank second paint"
grep -Fq 'Bundled Preview panel detached for recovery:' "$manifest_test" ||
  die "#353 Preview recovery no longer proves the old JCEF panel was disposed before retry"
grep -Fq '!preview.referencePanelAttached(remoteEditor)' "$manifest_test" ||
  die "#353 Preview recovery no longer waits for panel detach before re-showing Preview"
grep -Fq 'detached_before_retry=true' "$manifest_test" ||
  die "#353 Preview recovery no longer retains lifecycle evidence for the detached panel"
grep -Fq 'FULL_CORPUS_CASE_EDITOR_LIFECYCLE_CLOSE' "$manifest_test" ||
  die "#353 manifest capture lost per-case editor/JCEF lifecycle cleanup"
grep -Fq 'FULL_CORPUS_CASE_EDITOR_LIFECYCLE_SINGLE_OWNER' "$manifest_test" ||
  die "#353 full corpus no longer documents the single lifecycle owner boundary"
if grep -Fq 'markFlow.close(editor)' "$manifest_test"; then
  die "#353 primary case editor must not be UI-closed before finally owns Preview detach/FileEditor disposal"
fi
grep -Fq 'markFlow.closeMarkdown(remote, entry.id + ".md")' "$manifest_test" ||
  die "#353 case cleanup no longer closes the exact retained native Editor"
grep -Fq 'Bundled Preview panel detached before close:' "$manifest_test" ||
  die "#353 case cleanup no longer requires Preview panel detach before FileEditor close"
grep -Fq '!preview.referencePanelAttached(remote)' "$manifest_test" ||
  die "#353 case cleanup no longer proves the bundled JCEF panel detached"
grep -Fq 'reference-lifecycle.txt' "$manifest_test" ||
  die "#353 case cleanup no longer persists bundled Preview lifecycle evidence"
grep -Fq 'fun referencePanelAttached(editor: Editor): Boolean' "src/main/kotlin/com/algorist/markflow/editor/native/NativeDifferentialPreviewE2EBridge.kt" ||
  die "#353 bundled Preview bridge lost panel attachment observation"
grep -Fq 'fun previewLifecycleEvidence(editor: Editor): String' "src/main/kotlin/com/algorist/markflow/editor/native/NativeDifferentialPreviewE2EBridge.kt" ||
  die "#353 bundled Preview bridge lost lifecycle diagnostics"
grep -Fq 'getEditors(file)' "src/main/kotlin/com/algorist/markflow/editor/native/NativeDifferentialPreviewE2EBridge.kt" ||
  die "#353 bundled Preview bridge regressed to selected-editor lifecycle lookup"
grep -Fq 'fun closeMarkdown(editor: Editor, fileName: String)' "src/integrationTest/kotlin/com/algorist/markflow/e2e/MarkFlowIdeDriver.kt" ||
  die "#353 retained-editor Markdown lifecycle close helper is missing"
grep -Fq 'manager.closeFile(file)' "src/integrationTest/kotlin/com/algorist/markflow/e2e/MarkFlowIdeDriver.kt" ||
  die "#353 exact-file cleanup no longer closes the VirtualFile through FileEditorManager"
grep -Fq '!manager.isFileOpen(file) && remoteEditor.isDisposed()' "src/integrationTest/kotlin/com/algorist/markflow/e2e/MarkFlowIdeDriver.kt" ||
  die "#353 exact-file cleanup no longer waits for both FileEditor and native Editor disposal"
grep -Fq 'rendererLifecycle.liveRuntimeCount()' "$manifest_test" ||
  die "#353 full corpus no longer proves MarkFlow JCEF runtime release between cases"
grep -Fq 'MarkFlow derived JCEF runtime returns to baseline:' "$manifest_test" ||
  die "#353 case cleanup no longer waits for the renderer runtime baseline"
[ -f "src/main/kotlin/com/algorist/markflow/renderer/jcef/JcefDerivedRendererLifecycleE2EBridge.kt" ] ||
  die "#353 JCEF lifecycle diagnostic bridge is missing"
grep -Fq 'runtimeProvider = ::createRendererRuntimeOrNull' "src/main/kotlin/com/algorist/markflow/editor/native/NativeMarkFlowProductionLifecycle.kt" ||
  die "Native production lifecycle no longer lazily creates the optional derived JCEF runtime"
if grep -Fq 'runtime = createRendererRuntimeOrNull()' "src/main/kotlin/com/algorist/markflow/editor/native/NativeMarkFlowProductionLifecycle.kt"; then
  die "Native production lifecycle must not eagerly create JCEF for every Markdown editor"
fi
grep -Fq 'testDerivedRuntimeIsLazyReusedAndDisposedOnce' "src/test/kotlin/com/algorist/markflow/editor/native/NativeDerivedPresentationControllerTest.kt" ||
  die "Lazy derived-runtime lifecycle regression test is missing"
if grep -Fq 'close(selectNativeMarkdown(fileName))' "src/integrationTest/kotlin/com/algorist/markflow/e2e/MarkFlowIdeDriver.kt"; then
  die "#353 lifecycle cleanup must not reopen/reselect Markdown or toggle Preview layout before close"
fi
grep -Fq 'Case lifecycle close observed source identity drift:' "$manifest_test" ||
  die "#353 case cleanup lost source/stamp/unsaved fail-closed identity checks"
visual_bridge="src/main/kotlin/com/algorist/markflow/editor/native/NativeVisualAcceptanceE2EBridge.kt"
markdown_anchor_ledger="fixtures/differential-acceptance/markdown-anchors.tsv"
[ -f "$markdown_anchor_ledger" ] || die "reviewed Markdown source-anchor ledger missing"
grep -Fq 'markdown-anchors.tsv' "$manifest_test" ||
  die "Full differential harness no longer consumes reviewed Markdown source anchors"
grep -Fq 'Reviewed Markdown source anchor drifted' "$manifest_test" ||
  die "Markdown source anchors no longer fail closed on fixture drift"
grep -Fq 'reviewedMarkdownAnchorGitBlobSha' "webview/tests/differential-acceptance-corpus.test.mjs" ||
  die "Markdown source-anchor ledger lost its reviewed snapshot pin"

[ -f "$manifest_test" ] || die "Stage D real-manifest capture consumer is missing"
grep -Fq 'MarkFlowStarterDifferentialManifestTest' "$starter_workflow" ||
  die "Starter workflow silently dropped Stage D manifest capture"
grep -Fq 'MarkFlowStarterDifferentialManifestTest' "$starter_diagnostics" ||
  die "Starter diagnostics silently dropped Stage D manifest suite"
grep -Fq 'full_differential_acceptance' "$manifest_test" ||
  die "Stage D partial coverage must never be promoted to full acceptance"
grep -Fq '"NOT_EXECUTED"' "$manifest_test" ||
  die "Stage D must retain unexecuted cases in its inventory"
grep -Fq '"CAPTURED_UNVERIFIED"' "$manifest_test" ||
  die "Stage D captures must not be treated as geometry acceptance"
grep -Fq 'rasterGeometryNegativeControls()' "$manifest_test" ||
  die "Stage D no longer executes native raster negative controls"
grep -Fq 'native-raster-geometry.txt' "$manifest_test" ||
  die "Stage D dropped intrinsic/display raster geometry artifact"
grep -Fq 'fun nativeRasterGeometry(editor: Editor)' "src/main/kotlin/com/algorist/markflow/editor/native/NativeDifferentialPreviewE2EBridge.kt" ||
  die "Stage D lost real inlay raster geometry bridge"
# #353 E18: genuine native scrolling to both raster edges is a required
# source-neutral Starter regression; prevent silently reverting to one screenshot.
for edge in TOP BOTTOM; do
  grep -Fq '"native-scroll-" + edge.lowercase() + ".txt"' "$manifest_test" ||
    die "Stage E18 dropped native raster scroll evidence for $edge"
  grep -Fq '"edge_visible=true"' "$manifest_test" ||
    die "Stage E18 no longer gates actual raster edge reachability"
done
grep -Fq 'rasterScrollNegativeControls()' "$manifest_test" ||
  die "Stage E18 scroll-edge geometry negative controls missing"
grep -Fq 'fun nativeRasterScrollProbe(editor: Editor, index: Int, edge: String)' "src/main/kotlin/com/algorist/markflow/editor/native/NativeDifferentialPreviewE2EBridge.kt" ||
  die "Stage E18 dropped real editor scroll-probe bridge"
grep -Fq 'restoreNativeVerticalScroll(remoteEditor, beforeScroll)' "$manifest_test" ||
  die "Stage E18 no longer restores source capture scroll position"
# #353 Stage E1: preserve source-anchored heading instrumentation (not preview parity).
grep -Fq 'nativeHeadingGeometryNegativeControls()' "$manifest_test" ||
  die "Stage E1 lost deterministic heading source-span negative controls"
grep -Fq 'md-atx-headings-h1-h6,mermaid-flowchart-minimal,mermaid-gantt' "$manifest_test" ||
  die "Stage E2 no longer captures the #352 Gantt diagnostic in bounded CI"
grep -Fq ',md-setext-headings,md-tight-loose-lists,mermaid-sequence' "$manifest_test" ||
  die "Stage E17 lost Setext/list/sequence cases from the single-session differential cohort"
grep -Fq ',mermaid-class,mermaid-state,mermaid-flowchart-large--block-01' "$manifest_test" ||
  die "#352 lost class/state/wide Mermaid layout cases from the single-session cohort"
grep -Fq ',mermaid-flowchart-large--block-02' "$manifest_test" ||
  die "#352 lost the tall/second large-flowchart Mermaid layout case"
grep -Fq '#352 default layout cohort was silently reduced' "$manifest_test" ||
  die "#352 runtime default-cohort guard is missing"
grep -Fq 'mermaidSizeMode == "ACTUAL_SIZE_SCROLL"' "src/main/kotlin/com/algorist/markflow/editor/native/NativeDerivedPresentationController.kt" ||
  die "#352 ACTUAL_SIZE_SCROLL no longer preserves explicit detail mode"
grep -Fq '1523 to 2390' "src/test/kotlin/com/algorist/markflow/editor/native/NativeDerivedPresentationControllerTest.kt" ||
  die "#352 lost tall ACTUAL_SIZE_SCROLL dimension regression"
grep -Fq 'nativeRasterHorizontalScrollProbe' "$manifest_test" ||
  die "#352 lost real wide-raster horizontal reachability probe"
grep -Fq 'mermaid-actual-native-raster-geometry.txt' "$manifest_test" ||
  die "#352 lost real ACTUAL_SIZE_SCROLL geometry evidence"
grep -Fq '#352 target Mermaid block has no unique installed raster' "$manifest_test" ||
  die "#352 multi-block Mermaid cases lost source-owned raster selection"
grep -Fq 'targetRasterIndex' "$manifest_test" ||
  die "#352 scroll probe reverted to a file-level single-raster assumption"
grep -Fq 'native-heading-source-geometry.txt' "$manifest_test" ||
  die "Stage E1 dropped source-anchored heading geometry evidence"
grep -Fq 'measuredHeadings == 6' "$manifest_test" ||
  die "Stage E1 no longer requires all H1-H6 installed heading inlays"
grep -Fq 'fun nativeHeadingSourceGeometry(editor: Editor)' "src/main/kotlin/com/algorist/markflow/editor/native/NativeDifferentialPreviewE2EBridge.kt" ||
  die "Stage E1 lost production-inlay source-geometry bridge"
# #353 Stage E2: browser DOM measurements must be source-identical and retained.
grep -Fq 'val dom = driver.ui.jcef()' "$manifest_test" ||
  die "Stage E2 dropped actual JCEF DOM platform-reference acquisition"
grep -Fq 'intellij-heading-dom-geometry.txt' "$manifest_test" ||
  die "Stage E2 dropped real reference heading geometry artifact"
grep -Fq 'source-heading-relative-drift.tsv' "$manifest_test" ||
  die "Stage E2 dropped native/reference source-anchor delta ledger"
grep -Fq 'headingSourceIdentityNegativeControls()' "$manifest_test" ||
  die "Stage E2 lost adversarial heading comparator checks"
grep -Fq 'DIAGNOSTIC_ONLY_NO_REVIEWED_THRESHOLD' "$manifest_test" ||
  die "Stage E2 must not silently certify unreviewed DOM drift tolerance"
# #350 Stage E8 pilot: pin custom line-fold non-duplication geometry, without
# replacing the product heading renderer before editor/reveal validation.
grep -Fq 'testCustomSingleLineHeadingFoldReplacesOriginalEditorLineHeight' "src/test/kotlin/com/algorist/markflow/editor/native/NativeOrdinaryPresentationTest.kt" ||
  die "Stage E8 lost single-line heading replacement pilot"
grep -Fq 'testCustomHeadingFoldCoexistsWithPlatformSyntaxFoldAndSourceReveal' "src/test/kotlin/com/algorist/markflow/editor/native/NativeOrdinaryPresentationTest.kt" ||
  die "Stage E9 lost custom heading/foreign syntax-fold coexistence proof"
grep -Fq 'testCustomHeadingAndVerifiedBlankLineFoldDoesNotDoubleAllocateHeight' "src/test/kotlin/com/algorist/markflow/editor/native/NativeOrdinaryPresentationTest.kt" ||
  die "Stage E9 lost verified heading+blank two-line geometry proof"
grep -Fq 'testCustomTwoLineHeadingFoldLeftClickOwnershipAndForeignSyntaxPreservation' "src/test/kotlin/com/algorist/markflow/editor/native/NativeOrdinaryPresentationTest.kt" ||
  die "Stage E10 lost owned custom heading click/reveal and foreign fold preservation proof"
grep -Fq 'internal class NativeHeadingCustomFoldRenderer(' "src/main/kotlin/com/algorist/markflow/editor/native/NativeHeadingPresentationController.kt" ||
  die "Stage E11 lost custom heading production-capable renderer"
grep -Fq 'testHeadingCustomFoldRendererMatchesPreviewH2TypographyAndPaintsPixels' "src/test/kotlin/com/algorist/markflow/editor/native/NativeOrdinaryPresentationTest.kt" ||
  die "Stage E11 lost CustomFold glyph-paint and preview-font metrics test"
grep -Fq 'internal object NativeHeadingCustomFoldLinePlanner' "src/main/kotlin/com/algorist/markflow/editor/native/NativeHeadingPresentationController.kt" ||
  die "Stage E11 lost fail-closed full-line eligibility checks"
grep -Fq 'testHeadingCustomFoldLinePlannerRejectsNonblankAndMultilineCases' "src/test/kotlin/com/algorist/markflow/editor/native/NativeOrdinaryPresentationTest.kt" ||
  die "Stage E11 lost source-line fold planner negative controls"
grep -Fq 'testSourceReplacingHeadingControllerOwnedClickRestoreAndInactiveReattach' "src/test/kotlin/com/algorist/markflow/editor/native/NativeOrdinaryPresentationTest.kt" ||
  die "Stage E12 lost guarded heading controller test"
grep -Fq 'testSourceReplacingHeadingUsesRealEditorMouseHitTestingForSourceReveal' "src/test/kotlin/com/algorist/markflow/editor/native/NativeOrdinaryPresentationTest.kt" ||
  die "Stage E13 lost real editor hit-test evidence"
grep -Fq 'private val sourceLineReplacement: Boolean = false' "src/main/kotlin/com/algorist/markflow/editor/native/NativeHeadingPresentationController.kt" ||
  die "Stage E12 accidentally enabled unvalidated custom heading rendering by default"
grep -Fq 'CANDIDATE_GANTT_MIN_INTRINSIC_WIDTH_800' "$manifest_test" ||
  die "Stage E7 lost intrinsic Gantt timeline width regression"
grep -Fq 'ganttIntrinsicRasterWidthTooNarrow(799)' "$manifest_test" ||
  die "Stage E7 lost Gantt minimum-width negative controls"

# #352 E7: Gantt layout must not depend on hidden JCEF parent offsetWidth.
grep -Fq 'gantt: {useMaxWidth, useWidth: 960}' "webview/src/app/runtime-settings.ts" ||
  die "Stage E7 lost fixed-width Mermaid Gantt layout protection"
grep -Fq 'Gantt uses a bounded drawing width independent of hidden JCEF parent size' "webview/tests/runtime-settings.test.mjs" ||
  die "Stage E7 Gantt config missing size-mode regression controls"
# #352 production fix must retain the default Mermaid FIT raster-density ceiling.
grep -Fq 'MAX_MERMAID_FIT_RASTER_UPSCALE = 2.0' "src/main/kotlin/com/algorist/markflow/editor/native/NativeDerivedPresentationController.kt" ||
  die "Stage E4 lost bounded PNG fit scaling"
grep -Fq 'testDefaultMermaidFitDoesNotMagnifySmallRasterBeyondTwoTimes' "src/test/kotlin/com/algorist/markflow/editor/native/NativeDerivedPresentationControllerTest.kt" ||
  die "Stage E4 lost Gantt intrinsic-raster and size-mode regression"
# #353 E3 known-defect repros must fail AFTER saving the three source-identical pairs.
grep -Fq 'candidateRasterScaleNegativeControls()' "$manifest_test" ||
  die "Stage E3 lost Gantt x2 boundary adversarial control"
# Stage E6: same-source visible paragraphs are explicit DOM text ranges, not
# outer CSS boxes or guessed scroll offsets, and remain diagnostic-only.
grep -Fq 'source-following-text-line-drift.tsv' "$manifest_test" ||
  die "Stage E6 lost genuine following-content text range comparison"
grep -Fq 'intellij-following-paragraph-dom-geometry.txt' "$manifest_test" ||
  die "Stage E6 dropped genuine bundled Preview paragraph DOM evidence"
grep -Fq 'followingTextAnchorNegativeControls()' "$manifest_test" ||
  die "Stage E6 lost wrong-source/collapsed-range/non-Preview controls"
grep -Fq 'DIAGNOSTIC_NATIVE_LOGICAL_LINE_VS_JCEF_TEXT_RANGE' "$manifest_test" ||
  die "Stage E6 cannot assert unreviewed font-baseline parity"

# #353 E19: preserve real painted heading text Y plus JCEF DOM baseline.
grep -Fq 'headingInkVerticalNegativeControls()' "$manifest_test" ||
  die "Stage E19 dropped painted-text raster Y negative controls"
grep -Fq 'heading-ink-y-baseline-diagnostic.tsv' "$manifest_test" ||
  die "Stage E19 lost six-heading screenshot baseline evidence"
grep -Fq 'reference_baseline_marker_css_px' "$manifest_test" ||
  die "Stage E19 lost real JCEF inline baseline marker"
grep -Fq 'native_paint_baseline_document_px' "src/main/kotlin/com/algorist/markflow/editor/native/NativeDifferentialPreviewE2EBridge.kt" ||
  die "Stage E19 lost renderer paint baseline inputs"
grep -Fq 'RESEARCH_ONLY_B_HEADING_PAINT_NOT_PRODUCTION_DEFAULT' "$manifest_test" ||
  die "Stage E19 cannot declare research-only headings production parity"
# Stage E5 supersedes the invalid source-line-to-DOM-top numeric guard.
# Source positions remain diagnostic, while native inlay boxes and Preview DOM
# boxes have their own independently measured, source-joined ledger.
grep -Fq 'PREVIEW_DIAGNOSTIC_ONLY_MARKFLOW_FIRST' "$manifest_test" ||
  die "Stage E5 lost the MarkFlow-first Preview-reference policy"
if grep -Fq 'HEADING_LAYOUT_ANCHOR_CONTRACT_UNREVIEWED' "$manifest_test"; then
  die "Reviewed #350 Heading geometry must not be re-blocked on non-normative Preview coordinates"
fi
grep -Fq 'source-heading-rendered-box-drift.tsv' "$manifest_test" ||
  die "Stage E5 dropped installed native-inlay vs bundled DOM box evidence"
grep -Fq 'renderedHeadingBoxNegativeControls()' "$manifest_test" ||
  die "Stage E5 dropped source identity, collapsed-box or wrong-DOM controls"
if grep -Fq 'CANDIDATE_H1_H6_MAX_DRIFT_TWO_LINES' "$manifest_test"; then
  die "Stage E5 must not present source-line-vs-DOM-top deltas as accepted heading parity"
fi
grep -Fq 'CANDIDATE_GANTT_MAX_UPSCALE_X2' "$manifest_test" ||
  die "Stage E3 lost deterministic Gantt upscaling gate"
grep -Fq 'candidate-structural-failures.tsv' "$manifest_test" ||
  die "Stage E3 lost retained failure ledger"
# Bounded capture may complete with a separately retained, unreviewed
# source-baseline acceptance blocker, but genuine capture/structural defects
# still fail. Full #353 release acceptance must remain independently strict.
grep -Fq 'check(captureFailures.isEmpty())' "$manifest_test" ||
  die "Stage D unexpected structural capture defects must remain fail-closed"
grep -Fq 'if (contract == "acceptance")' "$manifest_test" ||
  die "Full #353 acceptance contract selector is missing"
grep -Fq 'check(structuralFailures.isEmpty() &&' "$manifest_test" ||
  die "Full #353 acceptance must reject every unresolved structural finding"
grep -Fq 'Full #353 acceptance cannot be reduced with differentialCaseIds' "$manifest_test" ||
  die "Full #353 acceptance must reject partial case selection"
grep -Fq 'inventory.map { it.id }' "$manifest_test" ||
  die "Full #353 acceptance must execute the entire expanded inventory"
grep -Fq 'NOT_APPLICABLE_BY_MANIFEST' "$manifest_test" ||
  die "Manifest-declared non-Preview cases lost explicit disposition"
acceptance_ledger="fixtures/differential-acceptance/acceptance-ledger.tsv"
[ -f "$acceptance_ledger" ] ||
  die "Full #353 repository-owned acceptance ledger is missing"
expected_acceptance_header=$'case_id\tmanifest_case_id\tsource_sha256\tblock_sha256\tclassification\tvisual_reference_expectation\treview_result\tevidence_source_head\tevidence_run_id\treview_note'
[ "$(head -n 1 "$acceptance_ledger")" = "$expected_acceptance_header" ] ||
  die "Full #353 acceptance ledger schema drifted"
grep -Fq 'parseReviewedAcceptanceLedger' "$manifest_test" ||
  die "Full #353 acceptance lost source-bound reviewed-ledger parsing"
grep -Fq 'acceptanceLedgerNegativeControls()' "$manifest_test" ||
  die "Full #353 acceptance lost reviewed-ledger adversarial controls"
grep -Fq 'Reviewed acceptance ledger coverage mismatch' "$manifest_test" ||
  die "Full #353 acceptance no longer requires complete expanded-case ledger coverage"
grep -Fq 'Reviewed acceptance source bytes drifted' "$manifest_test" ||
  die "Full #353 acceptance no longer binds review decisions to exact source bytes"
grep -Fq 'Reviewed acceptance Mermaid block drifted' "$manifest_test" ||
  die "Full #353 acceptance no longer binds expanded Mermaid block identity"
grep -Fq 'reviewResult != "ACCEPT"' "$manifest_test" ||
  die "Full #353 acceptance no longer rejects explicit reviewed failures"
grep -Fq 'output.resolve("acceptance.json")' "$manifest_test" ||
  die "Full #353 acceptance no longer emits a distinct final reviewed verdict"
grep -Fq '\"full_differential_acceptance\":true' "$manifest_test" ||
  die "Full #353 final verdict cannot signal reviewed full-corpus acceptance"
grep -Fq 'runTimeout = if (contract == "acceptance") 75.minutes else 12.minutes' "$manifest_test" ||
  die "Full #353 acceptance lost its 75-minute Starter lifetime or bounded capture lost its 12-minute limit"
grep -Fq '"markflow-353-full-manifest-real-pairs"' "$manifest_test" ||
  die "Full #353 Starter launch lost its distinct diagnostic identity"
grep -Fq 'timeout-minutes: 100' "$full_differential_workflow" ||
  die "Full #353 workflow no longer leaves job cleanup headroom beyond the 85-minute watchdog bound"
grep -Fq 'val hasDerivedPresentation =' "$visual_bridge" ||
  die "#353 visual bridge lost its derived-presentation refresh discriminator"
grep -Fq '(controller.derivedEvidenceSnapshot()?.derivedFragments ?: 0) > 0' "$visual_bridge" ||
  die "#353 visual bridge no longer derives refresh suppression from the real native plan"
grep -Fq 'if (!hasDerivedPresentation) {' "$visual_bridge" ||
  die "#353 visual bridge no longer suppresses layout refresh for derived rendering"
grep -Fq 'NativeMarkFlowProductionLifecycle.refreshAll()' "$visual_bridge" ||
  die "#353 visual bridge lost required ordinary layout refresh after viewport normalization"
if grep -Fq 'full_differential_acceptance:' "$starter_workflow" ||
  grep -Fq 'Full Differential Acceptance Evidence' "$starter_workflow"; then
  die "Starter workflow must not duplicate the canonical full differential workflow"
fi
grep -Fq '\"full_differential_acceptance\":false' "$manifest_test" ||
  die "Incomplete Stage D capture cannot claim full visual acceptance"
grep -Fq '\"release_eligible\":false' "$manifest_test" ||
  die "Unreviewed Stage D capture cannot authorize release"
differential_test="src/integrationTest/kotlin/com/algorist/markflow/e2e/MarkFlowStarterDifferentialPreviewSmokeTest.kt"
differential_bridge="src/main/kotlin/com/algorist/markflow/editor/native/NativeDifferentialPreviewE2EBridge.kt"
[ -f "$differential_test" ] && [ -f "$differential_bridge" ] ||
  die "bundled Markdown Preview real-E2E consumer or bridge missing"
grep -Fq "preview.showReferenceAtSourceLine(sourceEditor, sourceLine)" "$differential_test" ||
  die "Bundled Preview lost source-anchor navigation through retained Editor"
grep -Fq "Embedded Browser is suspended" "$differential_bridge" ||
  die "Bundled Preview lost fail-closed suspended-browser guard"
# #353: fail-closed sandboxed-JCEF AppArmor installation and negative controls.
jcef_profile_script=".github/scripts/install-starter-jcef-apparmor.sh"
[ -f "$jcef_profile_script" ] || die "pinned JCEF userns profile installer missing"
bash "$jcef_profile_script" --self-test >/dev/null ||
  die "JCEF AppArmor profile unsafe-path self-test failed"
grep -Fq 'Install pinned sandboxed JCEF AppArmor profile (derived-content only)' "$starter_workflow" ||
  die "Starter has no scoped JCEF AppArmor setup"
grep -Fq "bash .github/scripts/install-starter-jcef-apparmor.sh" "$starter_workflow" ||
  die "Starter no longer installs its pinned JBR profile"
grep -Fq "if: matrix.shard == 'derived-content'" "$starter_workflow" ||
  die "Matrix JCEF profile installer must remain restricted to derived-content"
grep -Fq 'STARTER_SHARD: full-differential-acceptance' "$full_differential_workflow" ||
  die "Full differential JCEF setup lost its explicit scoped shard identity"
grep -Fq 'derived-content|full-differential-acceptance' "$jcef_profile_script" ||
  die "JCEF profile installer scope allowlist drifted"
grep -Fq '/out/ide-tests/cache/builds/IU-262.10968.63/idea-IU-262.10968.63/bin/idea" flags=(unconfined)' "$jcef_profile_script" ||
  die "JCEF policy no longer targets actual IDEA executable, run 38009726635 source evidence"
grep -Fq 'userns,' "$jcef_profile_script" ||
  die "JCEF AppArmor profile lost userns permission"
grep -Fq '/sys/kernel/security/apparmor/profiles' "$jcef_profile_script" ||
  die "JCEF AppArmor installer no longer verifies loaded kernel policy"
differential_preview_bridge="src/main/kotlin/com/algorist/markflow/editor/native/NativeDifferentialPreviewE2EBridge.kt"
differential_preview_test="src/integrationTest/kotlin/com/algorist/markflow/e2e/MarkFlowStarterDifferentialPreviewSmokeTest.kt"
grep -Fq 'fun jcefRuntimeEvidence()' "$differential_preview_bridge" ||
  die "JCEF preview runtime evidence reporter was removed"
grep -Fq 'jcef-runtime-evidence.txt' "$differential_preview_test" ||
  die "JCEF first-failure evidence no longer retained"
for diagnostic_file in 'side-by-side.png' 'diff.png' 'metrics.json'; do
  grep -Fq "output.resolve(\"$diagnostic_file\")" "$differential_preview_test" ||
    die "Real bundled-preview diagnostic artifact $diagnostic_file was removed"
done
grep -Fq '"differential_pass":false' "$differential_preview_test" ||
  die "Unregistered real-preview raw pixels must not be promoted to parity PASS"
grep -Fq 'raw_pixel_registration' "$differential_preview_test" ||
  die "Preview differential diagnostics lost explicit raw-pixel limitation"


if grep -Eq '(sysctl.*apparmor_restrict|ide\.browser\.jcef\.sandbox\.enable.*false|--no-sandbox)' "$jcef_profile_script"; then
  die "JCEF installer contains global sandbox bypass"
fi

visual_test="src/integrationTest/kotlin/com/algorist/markflow/e2e/MarkFlowStarterVisualAcceptanceTest.kt"
visual_goldens="src/integrationTest/resources/visual-goldens"
[ -f "$visual_test" ] || die "deterministic visual acceptance test is missing"
[ -f "$visual_goldens/README.md" ] || die "visual golden policy documentation is missing"
grep -Fq 'name: Deterministic Visual Acceptance' "$starter_workflow" || die "Starter workflow has no separate deterministic visual job"
grep -Fq 'name: Deterministic Visual Acceptance Gate' "$starter_workflow" || die "Starter workflow has no separate visual acceptance gate"
for gated_job in visual-gate gate; do
  actual_guard="$(awk -v wanted="$gated_job" '
    $0 == "  " wanted ":" { getline; print; exit }
  ' "$starter_workflow")"
  [ "$actual_guard" = "    if: \${{ always() && (github.event_name != 'pull_request' || github.event.pull_request.draft == false) }}" ] || die "$gated_job must skip intentionally unvalidated Draft PRs but fail closed for Ready/release"
done
# The ARM64 visual sweep was measured against reviewed Ubuntu 24.04 x64
# golden images. Derived KaTeX/Mermaid exceeded the fail-closed pixel budget;
# keep that exact x64 visual oracle, while its aggregate gate remains ARM64.
for spec in "visual:ubuntu-24.04" "visual-gate:ubuntu-24.04-arm"; do
  visual_job="${spec%%:*}"
  visual_runner="${spec#*:}"
  awk -v wanted="$visual_job" -v label="$visual_runner" '
    $0 == "  " wanted ":" { in_visual=1; next }
    in_visual && /^  [A-Za-z0-9_-]+:$/ { exit }
    in_visual && $0 == "    runs-on: " label { found=1 }
    END { exit !found }
  ' "$starter_workflow" || die "$visual_job does not use pinned $visual_runner runner"
done
# Fail closed if ARM-native browser and EGL requirements are silently removed.
grep -Fq 'sudo apt-get install -y chromium-browser' ".github/workflows/build.yml" || die "ARM64 browser acceptance lacks native Chromium installer"
grep -Fq 'CHROME_BIN: /snap/bin/chromium' ".github/workflows/build.yml" || die "ARM64 browser acceptance lacks native Chromium path"
for native_workflow in "$starter_workflow" "$native_evidence_workflow"; do
  grep -Fq 'sudo apt-get install -y --no-install-recommends libegl1' "$native_workflow" || die "$native_workflow lacks required ARM64 Skiko EGL runtime"
  grep -Fq 'ldconfig -p | grep -F libEGL.so.1' "$native_workflow" || die "$native_workflow does not prove EGL availability"
done
grep -Fq 'Xvfb :99 -screen 0 1920x1080x24 -dpi 96' "$starter_workflow" || die "visual acceptance Xvfb envelope is not pinned"
grep -Fq 'MarkFlowStarterVisualAcceptanceTest' "$starter_workflow" || die "Starter workflow does not execute the visual acceptance suite"
grep -Fq 'PER_CHANNEL_TOLERANCE = 8' "$visual_test" || die "visual comparator per-channel tolerance drifted"
grep -Fq 'MAX_DIFFERING_PIXEL_RATIO = 0.0002' "$visual_test" || die "visual comparator changed-pixel tolerance drifted"
grep -Fq 'CAPTURE_WIDTH = 1200' "$visual_test" || die "visual acceptance fixed capture width drifted"
grep -Fq 'CAPTURE_HEIGHT = 760' "$visual_test" || die "visual acceptance fixed capture height drifted"
grep -Fq 'comparatorRejectsMeaningfulPerturbation' "$visual_test" || die "visual comparator negative control is missing"
grep -Fq 'candidate.svg' "$visual_test" || die "visual acceptance does not retain reviewable baseline candidates"
grep -Fq 'private const val TARGET_VIEWPORT_WIDTH = 1200' "$visual_bridge" || die "visual acceptance viewport width drifted"
grep -Fq 'private const val TARGET_VIEWPORT_HEIGHT = 760' "$visual_bridge" || die "visual acceptance viewport height drifted"
grep -Fq '"viewport=${editor.scrollingModel.visibleArea.width}x${editor.scrollingModel.visibleArea.height}"' "$visual_bridge" || die "visual environment identity no longer records the editor viewport"
grep -Fq 'bounds.width == CAPTURE_WIDTH && bounds.height == CAPTURE_HEIGHT' "$visual_test" || die "visual capture no longer requires the exact pinned viewport"
grep -Fq 'lafManager.currentUIThemeLookAndFeel = lightTheme' "$visual_bridge" || die "visual acceptance no longer pins the default Light UI theme"
grep -Fq 'colors.setGlobalScheme(defaultScheme)' "$visual_bridge" || die "visual acceptance no longer pins the default editor color scheme"
grep -Fq '@Suppress("UsePropertyAccessSyntax")' "$visual_bridge" || die "visual acceptance lost the narrow synthetic-property suppression for EditorColorsManager"
grep -Fq 'selectedFileEditor.setLayout(TextEditorWithPreview.Layout.SHOW_EDITOR)' "$visual_bridge" || die "visual acceptance no longer forces editor-only Markdown layout"
grep -Fq 'fun prepareEditorChromeBeforeOpen()' "$visual_bridge" || die "visual acceptance no longer pins inspection chrome before editor creation"
grep -Fq 'fun normalizeEditorChromeForCapture(editor: Editor): Int' "$visual_bridge" || die "visual acceptance no longer normalizes IDE chrome before capture"
grep -Fq 'getNotificationsOfType(Notification::class.java, project)' "$visual_bridge" || die "visual acceptance project notification normalization drifted"
grep -Fq 'getNotificationsOfType(Notification::class.java, null)' "$visual_bridge" || die "visual acceptance application notification normalization drifted"
grep -Fq 'EditorSettingsExternalizable.getInstance().isShowInspectionWidget = false' "$visual_bridge" || die "visual acceptance no longer disables the inspection widget"
grep -Fq 'markupModel.setErrorStripeRenderer(null)' "$visual_bridge" || die "visual acceptance error-stripe renderer normalization drifted"
grep -Fq 'markupModel.setErrorStripeVisible(false)' "$visual_bridge" || die "visual acceptance error-stripe visibility normalization drifted"
grep -Fq 'scrollPane.setStatusComponent(null)' "$visual_bridge" || die "visual acceptance status-component normalization drifted"
grep -Fq 'check(scrollPane.statusComponent == null)' "$visual_bridge" || die "visual acceptance no longer verifies status-component removal"
grep -Fq '"error_stripe_renderer=${(editor.markupModel as? EditorMarkupModel)?.errorStripeRenderer != null}"' "$visual_bridge" || die "visual environment identity no longer records error-stripe renderer state"
grep -Fq '"error_stripe_visible=${(editor.markupModel as? EditorMarkupModel)?.isErrorStripeVisible == true}"' "$visual_bridge" || die "visual environment identity no longer records error-stripe visibility"
grep -Fq '"status_component=${((editor as? EditorEx)?.scrollPane as? JBScrollPane)?.statusComponent != null}"' "$visual_bridge" || die "visual environment identity no longer records status-component state"
grep -Fq 'EditorFactory.getInstance().addEditorFactoryListener(' "$visual_bridge" || die "visual acceptance no longer installs pre-editor table-inlay normalization"
grep -Fq 'PLATFORM_MARKDOWN_TABLE_INLAY_PROVIDER_CLASS' "$visual_bridge" || die "visual acceptance bundled Markdown table-inlay provider identity drifted"
grep -Fq 'PLATFORM_MARKDOWN_TABLE_INLAY_KEY_NAME = "MarkdownDisableTableInlaysKey"' "$visual_bridge" || die "visual acceptance bundled Markdown table-inlay key identity drifted"
grep -Fq 'Key.findKeyByName(PLATFORM_MARKDOWN_TABLE_INLAY_KEY_NAME)' "$visual_bridge" || die "visual acceptance no longer resolves the platform-owned table-inlay key instance"
grep -Fq 'event.editor.putUserData(platformMarkdownTableInlayKey, true)' "$visual_bridge" || die "visual acceptance no longer disables bundled Markdown table inlays at editor creation"
grep -Fq 'check(editor.getUserData(platformMarkdownTableInlayKey) == true)' "$visual_bridge" || die "visual acceptance no longer fails closed on bundled Markdown table-inlay suppression"
grep -Fq '"platform_markdown_table_inlays=${editor.getUserData(platformMarkdownTableInlayKey) != true}"' "$visual_bridge" || die "visual environment identity no longer records bundled Markdown table-inlay state"
if grep -Fq 'setTrafficLightIconVisible' "$visual_bridge"; then
  die "visual acceptance must not depend on experimental traffic-light visibility APIs"
fi
grep -Fq '"inspection_widget=${EditorSettingsExternalizable.getInstance().isShowInspectionWidget}"' "$visual_bridge" || die "visual environment identity no longer records inspection-widget state"
grep -Fq 'visual.prepareEditorChromeBeforeOpen()' "$visual_test" || die "visual acceptance no longer disables inspection chrome before opening each editor"
grep -Fq 'visual.normalizeEditorChromeForCapture(editor.editor)' "$visual_test" || die "visual capture no longer normalizes IDE chrome"
grep -Fq 'visual.environmentIdentity(editor.editor)' "$visual_test" || die "visual capture no longer records post-normalization environment identity"
grep -Fq 'robot.waitForIdle()' "$visual_test" || die "visual capture no longer waits for notification removal to repaint"
if grep -Eq 'cp .*candidate.*src/integrationTest/resources/visual-goldens|mv .*candidate.*src/integrationTest/resources/visual-goldens' "$starter_workflow"; then
  die "visual workflow must never auto-accept generated baselines"
fi


build_workflow=".github/workflows/build.yml"
docs_scope=".github/scripts/docs-only-scope.py"
[ -f "$docs_scope" ] || die "docs-only scope classifier is missing"
python3 "$docs_scope" --self-test >/dev/null || die "docs-only scope classifier fixtures failed"

grep -Fq "github.event.action == 'edited' && 'metadata' || 'validation'" "$build_workflow" || die "Build workflow does not isolate metadata-edit concurrency from head validation"

head_evidence_job="$(awk '
  /^  headEvidence:$/ { on=1 }
  on && /^  build:$/ { exit }
  on { print }
' "$build_workflow")"
[ -n "$head_evidence_job" ] || die "Head validation evidence job could not be located"
grep -q '^    name: Head validation evidence$' <<<"$head_evidence_job" || die "head validation evidence job name drifted"
grep -q 'name: Head validation anchor' <<<"$head_evidence_job" || die "head validation evidence has no canonical-run anchor"
anchor_guard="$(awk '/^      - name: Head validation anchor$/ { getline; print; exit }' <<<"$head_evidence_job")"
[ "$anchor_guard" = "        if: \${{ github.event_name != 'pull_request' || github.event.action != 'edited' }}" ] || die "canonical head evidence anchor must include draft PR runs and exclude metadata replays"
grep -q 'name: Reuse exact-SHA head validation' <<<"$head_evidence_job" || die "metadata-only replay does not verify exact-SHA head evidence"
grep -Fq 'github.event.action == '"'"'edited'"'"'' <<<"$head_evidence_job" || die "head validation reuse is not scoped to metadata edits"
for component in "Build" "Test" "Inspect code" "Verify plugin" "Dependency Review"; do
  grep -Fq "\"$component\"" <<<"$head_evidence_job" || die "head validation reuse does not require successful $component evidence"
done
grep -Fq '    needs: [ headEvidence ]' "$build_workflow" || die "Build component does not depend on head validation evidence"
[ "$(grep -Fc '    needs: [ headEvidence ]' "$build_workflow" || true)" = "5" ] || die "Build/Test/Inspect/Verify/Dependency Review must each depend on head validation evidence"

[ "$(grep -c '^  scope:$' "$build_workflow" || true)" = "0" ] || die "docs-only classification must not depend on a non-required scope job"
grep -Fq 'docs_only: ${{ steps.change-scope.outputs.docs_only }}' <<<"$head_evidence_job" || die "head evidence does not export its trusted docs-only classification"
grep -Fq 'docs_only: ${{ needs.headEvidence.outputs.docs_only }}' "$build_workflow" || die "Build component does not preserve the docs-only output contract"
grep -q '^        id: change-scope$' <<<"$head_evidence_job" || die "classifier is not owned by head validation evidence"
grep -q '^        id: change-scope$' "$build_workflow" || die "required head-evidence classifier step is missing"
grep -Fq 'EXPECTED_COUNT: ${{ github.event.pull_request.changed_files }}' "$build_workflow" || die "Build workflow does not bind docs-only classification to GitHub changed-file count"
grep -Fq 'contents/.github/scripts/docs-only-scope.py?ref=$BASE_SHA' "$build_workflow" || die "Build workflow does not load docs-only policy from the trusted PR base"
grep -Fq 'pulls/$PR_NUMBER/files?per_page=100' "$build_workflow" || die "Build workflow does not enumerate PR changed files"
grep -Fq '[.status, .filename, (.previous_filename // "")] | @tsv' "$build_workflow" || die "Build workflow does not preserve rename/copy provenance for docs-only classification"
grep -Fq 'gh api --paginate' "$build_workflow" || die "Build workflow changed-file enumeration is not paginated"
[ "$(grep -Fc '    needs: [ build ]' "$build_workflow" || true)" = "0" ] || die "independent validation jobs must not wait for Build"
for job in build test inspectCode verify dependencyReview; do
  dependency="$(awk -v wanted="$job" '
    $0 == "  " wanted ":" { on=1; next }
    on && /^  [A-Za-z]/ { exit }
    on && /^    needs:/ { print; exit }
  ' "$build_workflow")"
  [ "$dependency" = '    needs: [ headEvidence ]' ] || die "$job bypasses or serializes the required head evidence"
done
[ "$(grep -Fc '      - name: Docs-only fast path' "$build_workflow" || true)" = "4" ] || die "every product-validation component must materialize a docs-only success path"
[ "$(grep -Fc '      - name: Metadata-only event fast path' "$build_workflow" || true)" = "4" ] || die "every product-validation component must materialize a metadata-only success path"
[ "$(grep -Fc "needs.headEvidence.outputs.docs_only == 'true'" "$build_workflow" || true)" = "4" ] || die "all four product components must consume trusted head docs-only classification"
[ "$(grep -Fc 'needs.build.outputs.docs_only' "$build_workflow" || true)" = "0" ] || die "product validation must not use the serial Build docs-only output"

step_if() {
  local step_name="$1"
  awk -v wanted="$step_name" '
    $0 == "      - name: " wanted { found=1; next }
    found && /^        if:/ { print; exit }
    found && /^      - name:/ { exit }
  ' "$build_workflow"
}
build_plugin_if="$(step_if "Build plugin")"
[ -n "$build_plugin_if" ] || die "Build plugin has no step-level guard"
grep -Fq "needs.headEvidence.outputs.docs_only != 'true'" <<<"$build_plugin_if" || die "Build plugin is not guarded by head-evidence docs-only classification"
grep -Fq "github.event.action != 'edited'" <<<"$build_plugin_if" || die "Build plugin is not suppressed on metadata-only events"
for heavy_step in "Run Tests" "Qodana - Code Inspection" "Run Plugin Verification tasks"; do
  heavy_if="$(step_if "$heavy_step")"
  [ -n "$heavy_if" ] || die "$heavy_step has no step-level guard"
  grep -Fq "needs.headEvidence.outputs.docs_only != 'true'" <<<"$heavy_if" || die "$heavy_step is not guarded by trusted head-evidence docs-only output"
  grep -Fq "github.event.action != 'edited'" <<<"$heavy_if" || die "$heavy_step is not suppressed on metadata-only events"
done

if grep -qi 'codecov' "$build_workflow"; then
  die "required Build workflow must not hide a non-authoritative external Codecov upload"
fi
squash_guard=".github/scripts/check-squash-message-safety.py"
[ -f "$squash_guard" ] || die "squash message safety guard is missing"
python3 "$squash_guard" --self-test >/dev/null || die "squash message safety guard fixtures failed"
grep -Fq 'types: [opened, synchronize, reopened, edited, ready_for_review]' "$build_workflow" || die "required Build workflow does not revalidate final-gate readiness and metadata edits"
grep -q '^  workflow_dispatch:$' "$build_workflow" || die "required Build workflow has no exact-SHA recovery trigger"
grep -q '^      target_sha:$' "$build_workflow" || die "required Build recovery trigger has no target_sha input"
[ ! -e .github/workflows/pr-metadata-safety.yml ] || die "standalone PR Metadata Safety workflow must stay deleted after graph consolidation"
[ ! -e .github/workflows/dependency-review.yml ] || die "standalone Dependency Review workflow must stay deleted after graph consolidation"

metadata_job="$(awk '
  /^  metadataSafety:$/ { on=1 }
  on && /^  dependencyReview:$/ { exit }
  on { print }
' "$build_workflow")"
[ -n "$metadata_job" ] || die "integrated PR Metadata Safety job could not be located"
grep -q '^    name: PR Metadata Safety$' <<<"$metadata_job" || die "PR Metadata Safety component name drifted"
grep -q 'Reject squash CI-skip directives' <<<"$metadata_job" || die "PR Metadata Safety does not reject unsafe squash metadata"
grep -Fq 'PR_TITLE: ${{ github.event.pull_request.title }}' <<<"$metadata_job" || die "squash guard does not inspect PR title"
grep -Fq "PR_BODY: \${{ github.event.pull_request.body || '' }}" <<<"$metadata_job" || die "squash guard does not inspect PR body"
grep -q 'python3 .github/scripts/check-squash-message-safety.py' <<<"$metadata_job" || die "PR Metadata Safety does not invoke squash message safety guard"

dependency_job="$(awk '
  /^  dependencyReview:$/ { on=1 }
  on && /^  mergeGate:$/ { exit }
  on { print }
' "$build_workflow")"
[ -n "$dependency_job" ] || die "integrated Dependency Review job could not be located"
grep -q '^    name: Dependency Review$' <<<"$dependency_job" || die "Dependency Review component name drifted"
grep -q 'uses: actions/dependency-review-action@a1d282b36b6f3519aa1f3fc636f609c47dddb294' <<<"$dependency_job" || die "Dependency Review action pin drifted"
grep -q 'fail-on-severity: moderate' <<<"$dependency_job" || die "Dependency Review severity gate drifted"

merge_gate_job="$(awk '
  /^  mergeGate:$/ { on=1 }
  on { print }
' "$build_workflow")"
[ -n "$merge_gate_job" ] || die "Merge Gate job could not be located"
grep -q '^    name: Merge Gate$' <<<"$merge_gate_job" || die "Merge Gate required context name drifted"
grep -Fq 'needs: [ build, test, inspectCode, verify, metadataSafety, dependencyReview ]' <<<"$merge_gate_job" || die "Merge Gate component dependency set drifted"
grep -Fq 'if: ${{ always() }}' <<<"$merge_gate_job" || die "Merge Gate does not use always()"
grep -Fq 'needs.build.result' <<<"$merge_gate_job" || die "Merge Gate does not inspect Build result"
grep -Fq 'needs.test.result' <<<"$merge_gate_job" || die "Merge Gate does not inspect Test result"
grep -Fq 'needs.inspectCode.result' <<<"$merge_gate_job" || die "Merge Gate does not inspect Inspect code result"
grep -Fq 'needs.verify.result' <<<"$merge_gate_job" || die "Merge Gate does not inspect Verify plugin result"
grep -Fq 'needs.metadataSafety.result' <<<"$merge_gate_job" || die "Merge Gate does not inspect PR Metadata Safety result"
grep -Fq 'needs.dependencyReview.result' <<<"$merge_gate_job" || die "Merge Gate does not inspect Dependency Review result"
grep -Fq 'if [ "$result" != "success" ]; then' <<<"$merge_gate_job" || die "Merge Gate does not fail closed on non-success component results"
grep -Fq '[ "$failed" -eq 0 ]' <<<"$merge_gate_job" || die "Merge Gate does not enforce aggregate failure state"
grep -Fq "github.event_name == 'workflow_dispatch' && inputs.target_sha" "$build_workflow" || die "required Build workflow does not checkout the requested recovery SHA"
assert_recovery_target_validation "$build_workflow" "required Build workflow"

hardening_workflow=".github/workflows/hardening-audit.yml"
grep -q '^  workflow_dispatch:$' "$hardening_workflow" || die "hardening audit has no manual recovery trigger"
grep -q '^      target_sha:$' "$hardening_workflow" || die "hardening recovery trigger has no target_sha input"
grep -q 'Verify squash message guard fixtures' "$hardening_workflow" || die "hardening audit does not execute squash guard fixtures"
grep -q 'check-squash-message-safety.py --self-test' "$hardening_workflow" || die "hardening audit does not enforce squash guard negative controls"
grep -Fq "github.event_name == 'workflow_dispatch' && inputs.target_sha" "$hardening_workflow" || die "hardening audit does not checkout the requested recovery SHA"
assert_recovery_target_validation "$hardening_workflow" "hardening audit"

for hardening_path in \
  ".github/**" \
  "gradle/**" \
  "gradle.properties" \
  "build.gradle.kts" \
  "settings.gradle.kts" \
  ".nvmrc" \
  "webview/package.json" \
  "webview/package-lock.json" \
  ".run/**" \
  "GOVERNANCE.md" \
  "docs/architecture/**" \
  "docs/release/**"; do
  grep -Fq -- "- '$hardening_path'" "$hardening_workflow" || die "hardening audit PR scope is missing $hardening_path"
done
if grep -Fq -- "- 'src/**'" "$hardening_workflow"; then
  die "hardening audit must not return to every product-source PR"
fi

test_job="$(awk '
  /^  test:$/ { on=1 }
  on && /^  inspectCode:$/ { exit }
  on { print }
' "$build_workflow")"
[ -n "$test_job" ] || die "Test component could not be located"
coverage_count="$(grep -c '^[[:space:]]*- name: Upload Kover Coverage Report$' <<<"$test_job" || true)"
[ "$coverage_count" = "1" ] || die "Test component must contain exactly one Kover coverage artifact step"
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
expected_coverage_if='if: ${{ github.event.action != '"'"'edited'"'"' && needs.headEvidence.outputs.docs_only != '"'"'true'"'"' && (github.event_name != '"'"'pull_request'"'"' || github.event.action != '"'"'ready_for_review'"'"' || steps.fast-evidence.outputs.reuse != '"'"'true'"'"') }}'
[ "$coverage_if" = "$expected_coverage_if" ] || die "Kover coverage artifact may only be suppressed by trusted docs-only scope or exact-SHA ready-for-review evidence reuse"
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

jq -e '.advisory[] | select(.context == "Hardening audit") | (.workflow == ".github/workflows/hardening-audit.yml" and .job == "audit" and (.reason | contains("path-scoped")))' \
  .github/merge-gate-policy.json >/dev/null || die "Hardening audit advisory path-scope rationale is missing"

if grep -q 'MarkFlow-private' docs/release/recovery.md; then
  die "release recovery still references the retired private repository name"
fi
grep -q '#139 and #141 are completed' docs/architecture/README.md || die "architecture index does not record completed reset/reconciliation gates"
if grep -Eq '^\| .*`TEMPORARY`' docs/architecture/leap-migration-inventory.md; then
  die "#141 migration inventory still contains an unresolved TEMPORARY production row"
fi
grep -q '^## Dependency update governance$' GOVERNANCE.md || die "dependency update governance contract is missing"
grep -q 'PR titles and bodies must not contain GitHub Actions skip directives' GOVERNANCE.md || die "squash message safety governance is missing"
grep -q 'If a default-branch workflow is ever suppressed before a run exists' GOVERNANCE.md || die "missing default-branch suppression recovery governance"
grep -q 'treat the original missing run as an audit anomaly rather than as PASS' GOVERNANCE.md || die "missing fail-closed audit-anomaly governance"

echo "repository hardening contract checks passed"
