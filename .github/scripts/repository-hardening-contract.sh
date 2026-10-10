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
if [ "$wrapper_gradle" = "9.8.0" ] && [ "$wrapper_sha" != "bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c" ]; then
  die "Gradle 9.8.0 distribution checksum does not match the reviewed upstream checksum"
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
assert_gradle_managed_node ".github/workflows/native-editor-shell-evidence.yml" "Native Editor Shell workflow"
assert_gradle_managed_node ".github/workflows/native-editing-evidence.yml" "Native Editing workflow"
assert_gradle_managed_node ".github/workflows/derived-renderer-evidence.yml" "Derived Renderer workflow"
assert_gradle_managed_node ".github/workflows/native-host-resources-evidence.yml" "Native Host Resources workflow"
assert_gradle_managed_node ".github/workflows/native-projection-evidence.yml" "Native Projection workflow"
assert_gradle_managed_node ".github/workflows/no-jcef-native-editing-evidence.yml" "No-JCEF Native Editing workflow"
assert_gradle_managed_node ".github/workflows/native-image-import-evidence.yml" "Native Image Import workflow"
assert_gradle_managed_node ".github/workflows/native-derived-presentation-evidence.yml" "Native Derived Presentation workflow"

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
grep -Fq 'actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c' "$starter_workflow" || die "Starter workflow exact-artifact download action pin drifted"
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
  die "JCEF profile installer must be restricted to derived-content"
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
for native_workflow in "$starter_workflow" ".github/workflows/native-image-import-evidence.yml"; do
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
visual_bridge="src/main/kotlin/com/algorist/markflow/editor/native/NativeVisualAcceptanceE2EBridge.kt"
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
[ "$(grep -Fc '    needs: [ headEvidence ]' "$build_workflow" || true)" = "2" ] || die "Build and Dependency Review must both depend on head validation evidence"

[ "$(grep -c '^  scope:$' "$build_workflow" || true)" = "0" ] || die "docs-only classification must not depend on a non-required scope job"
grep -Fq 'docs_only: ${{ steps.change-scope.outputs.docs_only }}' "$build_workflow" || die "Build component does not expose docs-only classification"
grep -q '^        id: change-scope$' "$build_workflow" || die "Build component is missing the changed-file classifier step"
grep -Fq 'EXPECTED_COUNT: ${{ github.event.pull_request.changed_files }}' "$build_workflow" || die "Build workflow does not bind docs-only classification to GitHub changed-file count"
grep -Fq 'contents/.github/scripts/docs-only-scope.py?ref=$BASE_SHA' "$build_workflow" || die "Build workflow does not load docs-only policy from the trusted PR base"
grep -Fq 'pulls/$PR_NUMBER/files?per_page=100' "$build_workflow" || die "Build workflow does not enumerate PR changed files"
grep -Fq '[.status, .filename, (.previous_filename // "")] | @tsv' "$build_workflow" || die "Build workflow does not preserve rename/copy provenance for docs-only classification"
grep -Fq 'gh api --paginate' "$build_workflow" || die "Build workflow changed-file enumeration is not paginated"
[ "$(grep -Fc '    needs: [ build ]' "$build_workflow" || true)" = "3" ] || die "Test/Inspect/Verify must depend on required Build"
[ "$(grep -Fc '      - name: Docs-only fast path' "$build_workflow" || true)" = "4" ] || die "every product-validation component must materialize a docs-only success path"
[ "$(grep -Fc '      - name: Metadata-only event fast path' "$build_workflow" || true)" = "4" ] || die "every product-validation component must materialize a metadata-only success path"
[ "$(grep -Fc "steps.change-scope.outputs.docs_only == 'true'" "$build_workflow" || true)" = "1" ] || die "Build docs-only path must use its own classifier output"
[ "$(grep -Fc "needs.build.outputs.docs_only == 'true'" "$build_workflow" || true)" = "3" ] || die "Test/Inspect/Verify docs-only paths must consume required Build output"

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
grep -Fq "steps.change-scope.outputs.docs_only != 'true'" <<<"$build_plugin_if" || die "Build plugin is not guarded by Build-owned docs-only classification"
grep -Fq "github.event.action != 'edited'" <<<"$build_plugin_if" || die "Build plugin is not suppressed on metadata-only events"
for heavy_step in "Run Tests" "Qodana - Code Inspection" "Run Plugin Verification tasks"; do
  heavy_if="$(step_if "$heavy_step")"
  [ -n "$heavy_if" ] || die "$heavy_step has no step-level guard"
  grep -Fq "needs.build.outputs.docs_only != 'true'" <<<"$heavy_if" || die "$heavy_step is not guarded by Build component docs-only output"
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
expected_coverage_if='if: ${{ github.event.action != '"'"'edited'"'"' && needs.build.outputs.docs_only != '"'"'true'"'"' && (github.event_name != '"'"'pull_request'"'"' || github.event.action != '"'"'ready_for_review'"'"' || steps.fast-evidence.outputs.reuse != '"'"'true'"'"') }}'
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
grep -q 'If a default-branch workflow is ever suppressed before a run exists' GOVERNANCE.md || die "missing default-branch suppression recovery governance"
grep -q 'treat the original missing run as an audit anomaly rather than as PASS' GOVERNANCE.md || die "missing fail-closed audit-anomaly governance"

echo "repository hardening contract checks passed"
