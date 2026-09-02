#!/usr/bin/env bash
# Read-only repository hardening and merge-gate drift audit.
# Live checks require an explicitly designated repository-administration credential;
# omitted privileged fields are unavailable state, never safe defaults.
set -euo pipefail

ROOT="."
POLICY=""
MODE="report"
ONLY=""
NETWORK=1
RULESET_FIXTURE=""

die() { echo "hardening-audit: $*" >&2; exit 2; }
while [ $# -gt 0 ]; do
  case "$1" in
    --root) ROOT="${2:-}"; shift 2 ;;
    --policy) POLICY="${2:-}"; shift 2 ;;
    --mode) MODE="${2:-}"; shift 2 ;;
    --only) ONLY="${2:-}"; shift 2 ;;
    --no-network) NETWORK=0; shift ;;
    --ruleset-fixture) RULESET_FIXTURE="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
case "$MODE" in gate|report) ;; *) die "invalid --mode: $MODE" ;; esac
[ -d "$ROOT" ] || die "root directory not found: $ROOT"
[ -n "$POLICY" ] || POLICY="$ROOT/.github/merge-gate-policy.json"
[ -f "$POLICY" ] || die "merge gate policy not found: $POLICY"
[ -z "$RULESET_FIXTURE" ] || [ -f "$RULESET_FIXTURE" ] || die "ruleset fixture not found: $RULESET_FIXTURE"
command -v jq >/dev/null 2>&1 || die "jq is required"
jq -e . "$POLICY" >/dev/null 2>&1 || die "merge gate policy is not valid JSON: $POLICY"
WORKFLOW_DIR="$ROOT/.github/workflows"
[ -d "$WORKFLOW_DIR" ] || die "workflow directory not found: $WORKFLOW_DIR"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

FINDINGS=0
finding() { FINDINGS=$((FINDINGS + 1)); echo "FINDING [$1] $2"; }
info() { echo "  ok    [$1] $2"; }
workflow_files() { find "$WORKFLOW_DIR" -type f \( -name '*.yml' -o -name '*.yaml' \) -print | sort; }

# These probes cover the conventional workflow syntax used by this repository and fail closed.
job_block() {
  awk -v job="$2" '
    /^jobs:[[:space:]]*$/ { in_jobs=1; next }
    in_jobs && $0 ~ /^[^[:space:]#]/ { exit }
    in_jobs && $0 ~ ("^  " job ":[[:space:]]*$") { capture=1; print; next }
    capture && $0 ~ /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { exit }
    capture { print }
  ' "$1"
}
job_exists() { [ -n "$(job_block "$1" "$2")" ]; }
job_field() {
  job_block "$1" "$2" | awk -v field="$3" '$0 ~ ("^    " field ":[[:space:]]*") { line=$0; sub("^    " field ":[[:space:]]*", "", line) } END { if (line != "") print line }'
}
unquote() {
  local value="$1"
  value="${value%\"}"; value="${value#\"}"; value="${value%\'}"; value="${value#\'}"
  printf '%s' "$value"
}
on_block() {
  awk '/^on:[[:space:]]*$/ { in_on=1; print; next } in_on && $0 ~ /^[^[:space:]#]/ { exit } in_on { print }' "$1"
}

policy_array_has_unique_contexts() {
  jq -e --arg path "$1" '((getpath(($path | split("."))) // []) | map(.context) | length) == ((getpath(($path | split("."))) // []) | map(.context) | unique | length)' "$POLICY" >/dev/null
}

check_policy_shape() {
  local clean=1 class other context workflow job wf jobname staged_count
  for class in required staged_required advisory release_only; do
    if ! jq -e --arg class "$class" '.[$class] | type == "array"' "$POLICY" >/dev/null; then
      finding policy_shape "policy field '$class' is not an array"; clean=0
    elif ! policy_array_has_unique_contexts "$class"; then
      finding policy_shape "policy field '$class' contains duplicate contexts"; clean=0
    fi
  done
  for class in required staged_required advisory release_only; do
    while IFS= read -r context; do
      [ -n "$context" ] || continue
      for other in required staged_required advisory release_only; do
        [ "$class" = "$other" ] && continue
        if jq -e --arg other "$other" --arg context "$context" '.[$other][]?.context == $context' "$POLICY" >/dev/null; then
          finding policy_shape "context '$context' appears in both '$class' and '$other' classifications"; clean=0
        fi
      done
    done < <(jq -r --arg class "$class" '.[$class][]?.context // empty' "$POLICY")
  done
  staged_count="$(jq '.staged_required | map(select(.context == "Hardening audit")) | length' "$POLICY")"
  [ "$staged_count" -eq 1 ] || { finding policy_shape "Hardening audit must remain exactly one staged_required context"; clean=0; }
  while IFS=$'\t' read -r context workflow job; do
    [ -n "$context" ] || continue
    wf="$ROOT/$workflow"
    if [ ! -f "$wf" ]; then finding policy_shape "staged context '$context' names missing workflow '$workflow'"; clean=0; continue; fi
    if ! job_exists "$wf" "$job"; then finding policy_shape "staged context '$context' names missing job '$job'"; clean=0; continue; fi
    jobname="$(unquote "$(job_field "$wf" "$job" name)")"
    [ "$jobname" = "$context" ] || { finding policy_shape "staged context '$context' does not match job name '$jobname'"; clean=0; }
  done < <(jq -r '.staged_required[] | [.context, .workflow, .job] | @tsv' "$POLICY")
  [ "$clean" -eq 1 ] && info policy_shape "policy classifications are unique and internally consistent"
  return 0
}

job_is_substantive() {
  local block="$1"
  printf '%s\n' "$block" | grep -qE '^    steps:[[:space:]]*$' || return 1
  printf '%s\n' "$block" | grep -qE '^        uses:[[:space:]]*[^[:space:]#]+' && return 0
  printf '%s\n' "$block" | grep -E '^        run:[[:space:]]*' | sed -E 's/^        run:[[:space:]]*//' | grep -qvE '^(echo|printf)([[:space:]]|$)|^true$|^exit[[:space:]]+0$|^:$'
}

check_policy_producers() {
  local n i context workflow job wf jobname block cond ontext clean
  n="$(jq '.required | length' "$POLICY")"
  [ "$n" -gt 0 ] || { finding policy_producers "the policy declares no required contexts"; return 0; }
  for i in $(seq 0 $((n - 1))); do
    context="$(jq -r ".required[$i].context" "$POLICY")"; workflow="$(jq -r ".required[$i].workflow" "$POLICY")"; job="$(jq -r ".required[$i].job" "$POLICY")"; wf="$ROOT/$workflow"; clean=1
    if [ ! -f "$wf" ]; then finding policy_producers "required context '$context' names missing workflow '$workflow'"; continue; fi
    if ! job_exists "$wf" "$job"; then finding policy_producers "required context '$context' names missing job '$job'"; continue; fi
    block="$(job_block "$wf" "$job")"; jobname="$(unquote "$(job_field "$wf" "$job" name)")"
    if [ -z "$jobname" ]; then finding policy_producers "job '$job' has no explicit name"; clean=0; elif [ "$jobname" != "$context" ]; then finding policy_producers "required context '$context' does not match job '$job' name '$jobname'"; clean=0; fi
    if printf '%s\n' "$block" | grep -qE '^    if:'; then cond="$(printf '%s\n' "$block" | grep -m1 -E '^    if:')"; finding policy_producers "required context '$context' is conditionally skipped ($cond)"; clean=0; fi
    if ! job_is_substantive "$block"; then finding policy_producers "required context '$context' is produced by a fake or no-op job"; clean=0; fi
    ontext="$(on_block "$wf")"
    if ! printf '%s\n' "$ontext" | grep -qE '^[[:space:]]*pull_request:?[[:space:]]*$'; then finding policy_producers "required context '$context' workflow is not triggered by pull_request"; clean=0; fi
    if printf '%s\n' "$ontext" | grep -qE '^[[:space:]]+paths(-ignore)?:'; then finding policy_producers "required workflow '$workflow' filters pull_request by path"; clean=0; fi
    [ "$clean" -eq 1 ] && info policy_producers "'$context' <- $workflow:$job"
  done
  return 0
}

check_action_pinning() {
  local clean=1 line file ref
  while IFS= read -r line; do
    file="${line%%:*}"; ref="$(printf '%s' "${line#*uses:}" | sed 's/#.*$//' | tr -d ' \t\r')"
    case "$ref" in ./*|"") continue ;; esac
    if ! printf '%s' "$ref" | grep -qE '@[0-9a-f]{40}$'; then finding action_pinning "$(basename "$file") uses mutable action ref '$ref'"; clean=0; fi
  done < <(grep -R -nE '^[[:space:]]*(-[[:space:]]+)?uses:' "$WORKFLOW_DIR" 2>/dev/null || true)
  [ "$clean" -eq 1 ] && info action_pinning "every workflow action uses a full immutable commit SHA"; return 0
}

check_workflow_permissions() {
  local clean=1 wf
  while IFS= read -r wf; do
    if ! grep -qE '^permissions:' "$wf"; then finding workflow_permissions "$(basename "$wf") has no workflow-level permissions block"; clean=0; fi
    if grep -qE '(^|[[:space:]])write-all([[:space:]]|$)' "$wf"; then finding workflow_permissions "$(basename "$wf") grants write-all"; clean=0; fi
  done < <(workflow_files)
  [ "$clean" -eq 1 ] && info workflow_permissions "every workflow has explicit non-write-all permissions"; return 0
}

check_workflow_security() {
  local clean=1 wf jobs job timeout checkout_count persist_count
  while IFS= read -r wf; do
    if ! grep -qE '^concurrency:' "$wf"; then finding workflow_security "$(basename "$wf") has no concurrency policy"; clean=0; fi
    checkout_count="$(grep -cE '^[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]*actions/checkout@' "$wf" || true)"; persist_count="$(grep -cE '^[[:space:]]*persist-credentials:[[:space:]]*false[[:space:]]*$' "$wf" || true)"
    if [ "$checkout_count" -ne "$persist_count" ]; then finding workflow_security "$(basename "$wf") does not set persist-credentials:false for every checkout"; clean=0; fi
    jobs="$(awk '/^jobs:[[:space:]]*$/{in_jobs=1; next} in_jobs && /^[^[:space:]#]/{exit} in_jobs && /^  [A-Za-z0-9_-]+:[[:space:]]*$/{line=$0; sub(/^  /,"",line); sub(/:[[:space:]]*$/, "", line); print line}' "$wf")"
    while IFS= read -r job; do
      [ -n "$job" ] || continue; timeout="$(job_field "$wf" "$job" timeout-minutes)"
      if ! printf '%s' "$timeout" | grep -qE '^[0-9]+$' || [ "$timeout" -le 0 ]; then finding workflow_security "$(basename "$wf") job '$job' has no positive timeout-minutes"; clean=0; fi
    done <<< "$jobs"
  done < <(workflow_files)
  [ "$clean" -eq 1 ] && info workflow_security "checkout credentials, timeouts, and concurrency are bounded"; return 0
}

check_privileged_workflows() {
  local clean=1 wf ontext
  while IFS= read -r wf; do
    ontext="$(on_block "$wf")"; printf '%s\n' "$ontext" | grep -qE '^[[:space:]]*pull_request_target:?[[:space:]]*$' || continue
    if grep -qE '^[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]*actions/checkout@' "$wf"; then finding privileged_workflows "$(basename "$wf") checks out code in pull_request_target"; clean=0; fi
    if grep -qE '\$\{\{[[:space:]]*secrets\.[A-Za-z0-9_]+' "$wf"; then finding privileged_workflows "$(basename "$wf") exposes secrets in pull_request_target"; clean=0; fi
    if grep -qE '^        run:.*\$\{\{[[:space:]]*github\.event\.pull_request\.' "$wf"; then finding privileged_workflows "$(basename "$wf") interpolates PR metadata into a privileged shell"; clean=0; fi
  done < <(workflow_files)
  [ "$clean" -eq 1 ] && info privileged_workflows "privileged workflows do not checkout PR code or expose secrets"; return 0
}

check_workflow_static_analysis() {
  local audit="$WORKFLOW_DIR/hardening-audit.yml" clean=1
  if [ ! -f "$audit" ]; then finding workflow_static_analysis "hardening-audit.yml is missing"; return 0; fi
  grep -qE 'ACTIONLINT_VERSION:[[:space:]]+v[0-9]+\.[0-9]+\.[0-9]+' "$audit" || { finding workflow_static_analysis "actionlint release is not pinned"; clean=0; }
  grep -qE 'go install .*github\.com/rhysd/actionlint/cmd/actionlint@\$\{ACTIONLINT_VERSION\}' "$audit" || { finding workflow_static_analysis "actionlint is not installed from the pinned release"; clean=0; }
  grep -qE 'Run actionlint across all workflows' "$audit" || { finding workflow_static_analysis "actionlint does not cover all workflows"; clean=0; }
  grep -qE 'zizmorcore/zizmor-action@[0-9a-f]{40}' "$audit" || { finding workflow_static_analysis "zizmor action is not pinned"; clean=0; }
  grep -qE 'version:[[:space:]]+v[0-9]+\.[0-9]+\.[0-9]+' "$audit" || { finding workflow_static_analysis "zizmor version is not pinned"; clean=0; }
  grep -qE 'inputs:[[:space:]]+\.github/workflows' "$audit" || { finding workflow_static_analysis "zizmor does not cover all workflows"; clean=0; }
  grep -qE 'advanced-security:[[:space:]]+false' "$audit" || { finding workflow_static_analysis "zizmor upload mode is not explicit"; clean=0; }
  for fixture in invalid-actionlint.yml unsafe-zizmor.yml; do
    [ -f "$ROOT/.github/fixtures/workflow-static-analysis/$fixture" ] || { finding workflow_static_analysis "static-analysis negative fixture is missing: $fixture"; clean=0; }
  done
  grep -qE 'Verify actionlint rejects invalid fixture' "$audit" || { finding workflow_static_analysis "actionlint negative control is not executed"; clean=0; }
  grep -qE 'Enforce actionlint negative control' "$audit" || { finding workflow_static_analysis "actionlint negative control is not enforced"; clean=0; }
  grep -qE 'Verify zizmor rejects unsafe fixture' "$audit" || { finding workflow_static_analysis "zizmor negative control is not executed"; clean=0; }
  grep -qE 'Enforce zizmor negative control' "$audit" || { finding workflow_static_analysis "zizmor negative control is not enforced"; clean=0; }
  [ "$clean" -eq 1 ] && info workflow_static_analysis "actionlint and pinned zizmor cover the complete workflow tree"; return 0
}

check_release_preflight() {
  local audit="$WORKFLOW_DIR/hardening-audit.yml" clean=1
  [ -f "$ROOT/.github/scripts/release-preflight-test.sh" ] || { finding release_preflight "release preflight fixture script is missing"; clean=0; }
  grep -qE 'Run release preflight fixtures' "$audit" || { finding release_preflight "release preflight fixtures are not named in the hardening workflow"; clean=0; }
  grep -qE 'bash \.github/scripts/release-preflight-test\.sh' "$audit" || { finding release_preflight "release preflight fixtures are not executed by the hardening workflow"; clean=0; }
  [ "$clean" -eq 1 ] && info release_preflight "non-publishing release preflight fixtures are wired into the hardening gate"; return 0
}

check_live_readback_boundary() {
  local audit="$WORKFLOW_DIR/hardening-audit.yml" audit_block live_block clean=1
  if [ ! -f "$audit" ]; then finding live_readback_boundary "hardening-audit.yml is missing"; return 0; fi
  if ! job_exists "$audit" live-readback; then
    finding live_readback_boundary "hardening-audit.yml has no live-readback job"; return 0
  fi
  audit_block="$(job_block "$audit" audit)"
  live_block="$(job_block "$audit" live-readback)"
  for expression in \
    "github.event_name == 'schedule'" \
    "github.event_name == 'workflow_dispatch'" \
    "github.ref_name == github.event.repository.default_branch"; do
    if ! grep -qF "$expression" <<< "$live_block"; then
      finding live_readback_boundary "live-readback is not restricted by '$expression'"
      clean=0
    fi
  done
  if ! grep -qF "ref: \${{ github.event.repository.default_branch }}" <<< "$live_block"; then
    finding live_readback_boundary "live-readback does not checkout the default branch"
    clean=0
  fi
  if grep -qE 'ref: \$\{\{ (github\.ref|github\.ref_name|github\.event\.pull_request\.head\.sha)' <<< "$live_block"; then
    finding live_readback_boundary "live-readback checks out a caller-selected or PR ref"
    clean=0
  fi
  if grep -qF 'HARDENING_AUDIT_TOKEN' <<< "$audit_block"; then
    finding live_readback_boundary "the PR-safe audit job can access HARDENING_AUDIT_TOKEN"
    clean=0
  fi
  if ! grep -qF 'HARDENING_AUDIT_TOKEN' <<< "$live_block"; then
    finding live_readback_boundary "live-readback does not receive HARDENING_AUDIT_TOKEN"
    clean=0
  fi
  [ "$clean" -eq 1 ] && info live_readback_boundary "privileged readback is restricted to trusted default-branch schedule/manual execution"; return 0
}

ruleset_readback_available() { [ -n "$RULESET_FIXTURE" ] || [ -n "${HARDENING_AUDIT_TOKEN:-}" ]; }
authoritative_gh() {
  [ -n "${HARDENING_AUDIT_TOKEN:-}" ] || return 1
  GH_TOKEN="$HARDENING_AUDIT_TOKEN" gh "$@"
}
read_ruleset_detail() {
  local kind="$1" live id name
  if [ -n "$RULESET_FIXTURE" ]; then jq -c --arg kind "$kind" '.[$kind] // empty' "$RULESET_FIXTURE"; return; fi
  if [ "$kind" = main ]; then name="$(jq -r '.ruleset_name' "$POLICY")"; else name="$(jq -r '.release_ruleset_name' "$POLICY")"; fi
  live="$(authoritative_gh api "repos/${GITHUB_REPOSITORY}/rulesets?includes_parents=false" 2>/dev/null)" || return 1
  printf '%s' "$live" | jq -e 'type == "array"' >/dev/null || return 2
  id="$(printf '%s' "$live" | jq -r --arg name "$name" '.[] | select(.name == $name and .id != null) | .id' | head -n1)"; [ -n "$id" ] || return 3
  authoritative_gh api "repos/${GITHUB_REPOSITORY}/rulesets/$id" 2>/dev/null
}
validate_ruleset_common() {
  local kind="$1" detail="$2" expected_name
  if [ "$kind" = main ]; then expected_name="$(jq -r '.ruleset_name' "$POLICY")"; else expected_name="$(jq -r '.release_ruleset_name' "$POLICY")"; fi
  if ! jq -e 'type == "object" and (.name | type == "string") and (.enforcement | type == "string") and (.bypass_actors | type == "array") and (.conditions.ref_name.include | type == "array") and (.rules | type == "array")' <<< "$detail" >/dev/null; then
    finding "$kind" "live ruleset omitted an authoritative field; refusing to infer a safe default"; return 1
  fi
  [ "$(jq -r '.name' <<< "$detail")" = "$expected_name" ] || finding "$kind" "live ruleset name does not match policy"
  [ "$(jq -r '.enforcement' <<< "$detail")" = active ] || finding "$kind" "live ruleset is not active"
  [ "$(jq '.bypass_actors | length' <<< "$detail")" -eq 0 ] || finding "$kind" "live ruleset has bypass actors"
  if [ "$kind" = main ]; then jq -e '.conditions.ref_name.include | index("~DEFAULT_BRANCH") != null' <<< "$detail" >/dev/null || finding "$kind" "live main ruleset does not target ~DEFAULT_BRANCH"; else jq -e '.conditions.ref_name.include | index("~ALL") != null' <<< "$detail" >/dev/null || finding "$kind" "live release-tag ruleset does not target ~ALL"; fi
  return 0
}

check_ruleset_sync() {
  local detail want got ctx integration_id clean=1
  if [ "$NETWORK" -eq 0 ]; then info ruleset_sync "skipped (--no-network)"; return 0; fi
  if ! command -v gh >/dev/null 2>&1 || [ -z "${GITHUB_REPOSITORY:-}" ]; then finding ruleset_sync "gh and GITHUB_REPOSITORY are required for live readback"; return 0; fi
  if ! ruleset_readback_available; then finding ruleset_sync "authoritative HARDENING_AUDIT_TOKEN is unavailable; ordinary token/auth cannot prove ruleset state"; return 0; fi
  if ! detail="$(read_ruleset_detail main)"; then finding ruleset_sync "could not read complete main ruleset"; return 0; fi
  if ! validate_ruleset_common main "$detail"; then return 0; fi
  jq -e '.rules | map(select(.type == "required_status_checks")) | length == 1' <<< "$detail" >/dev/null || { finding ruleset_sync "main ruleset lacks exactly one required_status_checks rule"; clean=0; }
  jq -e '.rules[] | select(.type == "required_status_checks") | (.parameters.strict_required_status_checks_policy == true and (.parameters.required_status_checks | type == "array"))' <<< "$detail" >/dev/null || { finding ruleset_sync "main required-status-check rule is missing or not strict"; clean=0; }
  for required_type in deletion non_fast_forward required_linear_history; do
    jq -e --arg type "$required_type" '.rules | any(.type == $type)' <<< "$detail" >/dev/null || { finding ruleset_sync "main ruleset lacks '$required_type' protection"; clean=0; }
  done
  jq -e '.rules | map(select(.type == "pull_request")) | length == 1' <<< "$detail" >/dev/null || { finding ruleset_sync "main ruleset lacks exactly one pull_request rule"; clean=0; }
  jq -e '.rules[] | select(.type == "pull_request") | (.parameters.required_approving_review_count == 0 and .parameters.dismiss_stale_reviews_on_push == true and .parameters.required_review_thread_resolution == true and .parameters.require_code_owner_review == false and .parameters.require_last_push_approval == false and .parameters.require_extra_approval_for_unattributed_changes == true and .parameters.allowed_merge_methods == ["squash"])' <<< "$detail" >/dev/null || { finding ruleset_sync "main pull-request review or squash-only parameters drifted"; clean=0; }
  integration_id="$(jq -r '.actions_integration_id' "$POLICY")"
  jq -e --argjson integration_id "$integration_id" '.rules[] | select(.type == "required_status_checks") | all(.parameters.required_status_checks[]; .integration_id == $integration_id)' <<< "$detail" >/dev/null || { finding ruleset_sync "main required-status-check integration drifted from policy"; clean=0; }
  want="$(jq -r '.required[].context' "$POLICY" | sort)"; got="$(jq -r '.rules[] | select(.type == "required_status_checks") | .parameters.required_status_checks[]?.context // empty' <<< "$detail" | sort)"
  while IFS= read -r ctx; do [ -n "$ctx" ] || continue; printf '%s\n' "$got" | grep -qxF "$ctx" || { finding ruleset_sync "policy requires context '$ctx' but live main does not"; clean=0; }; done <<< "$want"
  while IFS= read -r ctx; do [ -n "$ctx" ] || continue; printf '%s\n' "$want" | grep -qxF "$ctx" || { finding ruleset_sync "live main requires undeclared context '$ctx'"; clean=0; }; done <<< "$got"
  [ "$clean" -eq 1 ] && info ruleset_sync "authoritative live main ruleset matches canonical policy"; return 0
}

check_release_tag_ruleset() {
  local detail clean=1 required_type
  if [ "$NETWORK" -eq 0 ]; then info release_tag_ruleset "skipped (--no-network)"; return 0; fi
  if ! command -v gh >/dev/null 2>&1 || [ -z "${GITHUB_REPOSITORY:-}" ]; then finding release_tag_ruleset "gh and GITHUB_REPOSITORY are required for release-tag readback"; return 0; fi
  if ! ruleset_readback_available; then finding release_tag_ruleset "authoritative HARDENING_AUDIT_TOKEN is unavailable; release-tag safety is unverified"; return 0; fi
  if ! detail="$(read_ruleset_detail release_tag)"; then finding release_tag_ruleset "could not read complete release-tag ruleset"; return 0; fi
  if ! validate_ruleset_common release_tag "$detail"; then return 0; fi
  for required_type in deletion non_fast_forward; do jq -e --arg type "$required_type" '.rules | any(.type == $type)' <<< "$detail" >/dev/null || { finding release_tag_ruleset "release-tag ruleset lacks '$required_type' protection"; clean=0; }; done
  [ "$clean" -eq 1 ] && info release_tag_ruleset "authoritative release-tag ruleset is active, immutable, and bypass-free"; return 0
}

check_label_references() {
  local refs="" existing clean=1 label
  if [ "$NETWORK" -eq 0 ]; then info label_references "skipped (--no-network)"; return 0; fi
  if ! command -v gh >/dev/null 2>&1 || [ -z "${GITHUB_REPOSITORY:-}" ] || [ -z "${HARDENING_AUDIT_TOKEN:-}" ]; then finding label_references "authoritative HARDENING_AUDIT_TOKEN is required to verify live automation labels"; return 0; fi
  if [ -f "$ROOT/.github/labeler.yml" ]; then refs="$refs\n$(grep -E '^[A-Za-z][A-Za-z0-9:_-]*:[[:space:]]*$' "$ROOT/.github/labeler.yml" | sed 's/:[[:space:]]*$//' | grep -vE '^(changed-files-labels-limit|max-files-changed|version)$' || true)"; fi
  if [ -f "$ROOT/.github/dependabot.yml" ]; then refs="$refs\n$(awk '/^[[:space:]]*labels:[[:space:]]*$/ { in_labels=1; next } in_labels && $0 ~ /^[[:space:]]*-[[:space:]]*/ { line=$0; sub(/^[[:space:]]*-[[:space:]]*/, "", line); print line; next } { in_labels=0 }' "$ROOT/.github/dependabot.yml" | tr -d "\"'" || true)"; fi
  if [ -d "$ROOT/.github/ISSUE_TEMPLATE" ]; then refs="$refs\n$(grep -hE '^labels:' "$ROOT"/.github/ISSUE_TEMPLATE/* 2>/dev/null | sed 's/^labels:[[:space:]]*//; s/[][]//g' | tr ',' '\n' | tr -d "\"'" || true)"; fi
  refs="$(printf '%b\n' "$refs" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//' | grep -v '^$' | sort -u || true)"
  if ! existing="$(authoritative_gh label list --repo "$GITHUB_REPOSITORY" --limit 200 --json name --jq '.[].name' 2>/dev/null)"; then finding label_references "could not read complete live label set"; return 0; fi
  while IFS= read -r label; do [ -n "$label" ] || continue; printf '%s\n' "$existing" | grep -qxF "$label" || { finding label_references "automation references missing label '$label'"; clean=0; }; done <<< "$refs"
  [ "$clean" -eq 1 ] && info label_references "all automation-referenced labels exist in the live repository"; return 0
}

check_repository_settings() {
  local detail field expected clean=1
  if [ "$NETWORK" -eq 0 ]; then info repository_settings "skipped (--no-network)"; return 0; fi
  if ! command -v gh >/dev/null 2>&1 || [ -z "${GITHUB_REPOSITORY:-}" ]; then finding repository_settings "gh and GITHUB_REPOSITORY are required for live settings readback"; return 0; fi
  if [ -z "${HARDENING_AUDIT_TOKEN:-}" ]; then finding repository_settings "authoritative HARDENING_AUDIT_TOKEN is unavailable; repository merge settings are unverified"; return 0; fi
  if ! detail="$(authoritative_gh api "repos/${GITHUB_REPOSITORY}" 2>/dev/null)"; then finding repository_settings "could not read complete live repository settings"; return 0; fi
  if ! jq -e 'type == "object"' <<< "$detail" >/dev/null; then finding repository_settings "live repository settings response was not an object"; return 0; fi
  while IFS= read -r field; do
    [ -n "$field" ] || continue
    expected="$(jq -c --arg field "$field" '.repository_settings[$field]' "$POLICY")"
    if ! jq -e --arg field "$field" --argjson expected "$expected" '.[$field] == $expected' <<< "$detail" >/dev/null; then
      finding repository_settings "live repository setting '$field' does not match canonical policy"
      clean=0
    fi
  done < <(jq -r '.repository_settings | keys[]' "$POLICY")
  [ "$clean" -eq 1 ] && info repository_settings "authoritative live repository merge settings match canonical policy"; return 0
}

check_negative_tests() {
  if bash "$SCRIPT_DIR/hardening-audit-test.sh"; then info negative_tests "all negative fixtures fail closed"; else finding negative_tests "at least one negative fixture did not fail closed"; fi
  return 0
}

ALL_CHECKS="policy_shape policy_producers negative_tests action_pinning workflow_permissions workflow_security privileged_workflows live_readback_boundary workflow_static_analysis release_preflight ruleset_sync release_tag_ruleset label_references repository_settings"
should_run() {
  local name="$1" configured
  if [ -n "$ONLY" ]; then [ "$ONLY" = "$name" ]; return; fi
  configured="$(jq -r --arg name "$name" '.checks[$name] // "report"' "$POLICY")"
  [ "$MODE" = report ] || [ "$configured" = gate ]
}
if [ -n "$ONLY" ]; then case " $ALL_CHECKS " in *" $ONLY "*) ;; *) die "unknown check: $ONLY" ;; esac; fi
echo "hardening-audit: root=$ROOT mode=$MODE${ONLY:+ only=$ONLY}"
if [ "$MODE" = report ] && [ -z "$RULESET_FIXTURE" ]; then
  if [ -n "${HARDENING_AUDIT_TOKEN:-}" ]; then
    echo "hardening-audit: authority=repository-administration credential_source=HARDENING_AUDIT_TOKEN"
  else
    echo "hardening-audit: authority=unavailable credential_source=HARDENING_AUDIT_TOKEN"
  fi
fi
RAN=0
for check in $ALL_CHECKS; do
  should_run "$check" || continue
  RAN=$((RAN + 1))
  case "$check" in
    policy_shape) check_policy_shape ;;
    policy_producers) check_policy_producers ;;
    negative_tests) check_negative_tests ;;
    action_pinning) check_action_pinning ;;
    workflow_permissions) check_workflow_permissions ;;
    workflow_security) check_workflow_security ;;
    privileged_workflows) check_privileged_workflows ;;
    live_readback_boundary) check_live_readback_boundary ;;
    workflow_static_analysis) check_workflow_static_analysis ;;
    release_preflight) check_release_preflight ;;
    ruleset_sync) check_ruleset_sync ;;
    release_tag_ruleset) check_release_tag_ruleset ;;
    label_references) check_label_references ;;
    repository_settings) check_repository_settings ;;
    *) die "internal error: unsupported check '$check'" ;;
  esac
done
[ "$RAN" -gt 0 ] || die "no checks selected; refusing to report a pass"
echo
if [ "$FINDINGS" -eq 0 ]; then echo "hardening-audit: $RAN check(s) ran, no findings."; exit 0; fi
echo "hardening-audit: $RAN check(s) ran, $FINDINGS finding(s)."
exit 1
