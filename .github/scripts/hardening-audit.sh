#!/usr/bin/env bash
#
# Repository hardening / merge-gate drift audit.
#
# Detection is read-only: this script never mutates the repository, its settings, or the tracker.
# It compares .github/merge-gate-policy.json against the workflows that actually produce the
# contexts and, when a token is available, against the live branch ruleset.
#
# Usage:
#   hardening-audit.sh [--root DIR] [--policy FILE] [--mode gate|report] [--only CHECK] [--no-network]
#
#   --mode gate     run only the checks the policy marks "gate" (the pull request merge gate)
#   --mode report   run every check the policy knows about (the scheduled drift audit)
#   --only CHECK    run a single named check regardless of its policy mode
#   --no-network    skip checks that need the GitHub API, instead of failing them
#
# Exit status: 0 when no finding was produced, 1 when at least one finding was produced,
# 2 when the audit itself could not run (which is also a failure, never a silent pass).

set -euo pipefail

ROOT="."
POLICY=""
MODE="report"
ONLY=""
NETWORK=1

die() { echo "hardening-audit: $*" >&2; exit 2; }

while [ $# -gt 0 ]; do
  case "$1" in
    --root) ROOT="${2:-}"; shift 2 ;;
    --policy) POLICY="${2:-}"; shift 2 ;;
    --mode) MODE="${2:-}"; shift 2 ;;
    --only) ONLY="${2:-}"; shift 2 ;;
    --no-network) NETWORK=0; shift ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

case "$MODE" in gate|report) ;; *) die "invalid --mode: $MODE" ;; esac
[ -d "$ROOT" ] || die "root directory not found: $ROOT"
[ -n "$POLICY" ] || POLICY="$ROOT/.github/merge-gate-policy.json"
[ -f "$POLICY" ] || die "merge gate policy not found: $POLICY"
command -v jq >/dev/null 2>&1 || die "jq is required"
jq -e . "$POLICY" >/dev/null 2>&1 || die "merge gate policy is not valid JSON: $POLICY"

WORKFLOW_DIR="$ROOT/.github/workflows"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

FINDINGS=0
finding() {
  FINDINGS=$((FINDINGS + 1))
  echo "FINDING [$1] $2"
}
info() { echo "  ok    [$1] $2"; }

# ---------------------------------------------------------------------------
# Small, portable YAML probes.
#
# These deliberately do not implement YAML. They read the specific, conventional
# constructs GitHub workflow files use, and they fail closed: a construct they
# cannot find is reported as a finding rather than assumed to be fine.
# ---------------------------------------------------------------------------

# job_field <file> <job-id> <field>  ->  prints the scalar value, empty if absent
job_field() {
  awk -v job="$2" -v field="$3" '
    /^jobs:[ \t]*$/ { injobs = 1; next }
    injobs == 1 && $0 ~ /^[^ \t#]/ { injobs = 0 }
    injobs == 1 && $0 ~ /^  [A-Za-z0-9_-]+:[ \t]*$/ {
      line = $0; sub(/^  /, "", line); sub(/:[ \t]*$/, "", line); cur = line; next
    }
    injobs == 1 && cur == job && $0 ~ ("^    " field ":") {
      line = $0; sub("^    " field ":[ \t]*", "", line); print line; exit
    }
  ' "$1"
}

# job_exists <file> <job-id>
job_exists() {
  awk -v job="$2" '
    /^jobs:[ \t]*$/ { injobs = 1; next }
    injobs == 1 && $0 ~ /^[^ \t#]/ { injobs = 0 }
    injobs == 1 && $0 ~ ("^  " job ":[ \t]*$") { found = 1; exit }
    END { exit(found ? 0 : 1) }
  ' "$1"
}

# on_block <file>  ->  prints the workflow trigger block
on_block() {
  awk '
    /^on:/ { inon = 1; print; next }
    inon == 1 && $0 ~ /^[^ \t#]/ { inon = 0 }
    inon == 1 { print }
  ' "$1"
}

unquote() {
  local v="$1"
  v="${v%\"}"; v="${v#\"}"
  v="${v%\'}"; v="${v#\'}"
  printf '%s' "$v"
}

# ---------------------------------------------------------------------------
# Checks
# ---------------------------------------------------------------------------

# Every required context must be produced by a real job, on every pull request:
# the producing workflow must be triggered by pull_request, must not sit behind a
# top-level path filter, and the producing job must not be conditionally skipped.
check_policy_producers() {
  local n i context workflow job wf jobname cond ontext clean

  n="$(jq '.required | length' "$POLICY")"
  if [ "$n" -eq 0 ]; then
    finding policy_producers "the policy declares no required contexts"
    return 0
  fi

  for i in $(seq 0 $((n - 1))); do
    context="$(jq -r ".required[$i].context" "$POLICY")"
    workflow="$(jq -r ".required[$i].workflow" "$POLICY")"
    job="$(jq -r ".required[$i].job" "$POLICY")"
    wf="$ROOT/$workflow"
    clean=1

    if [ ! -f "$wf" ]; then
      finding policy_producers "required context '$context' names a missing workflow: $workflow"
      continue
    fi
    if ! job_exists "$wf" "$job"; then
      finding policy_producers "required context '$context' names job '$job', which does not exist in $workflow"
      continue
    fi

    jobname="$(unquote "$(job_field "$wf" "$job" name)")"
    if [ -z "$jobname" ]; then
      finding policy_producers "job '$job' in $workflow has no explicit 'name:', so its check context is not stable"
      clean=0
    elif [ "$jobname" != "$context" ]; then
      finding policy_producers "required context '$context' does not match the name of job '$job' in $workflow (found: '$jobname')"
      clean=0
    fi

    cond="$(job_field "$wf" "$job" if)"
    if [ -n "$cond" ]; then
      finding policy_producers "required context '$context' is produced by job '$job' guarded by 'if: $cond'; a required check must never be skippable"
      clean=0
    fi

    ontext="$(on_block "$wf")"
    if ! printf '%s\n' "$ontext" | grep -qE '^[[:space:]]*pull_request:?[[:space:]]*$'; then
      finding policy_producers "required context '$context' lives in $workflow, which is not triggered by pull_request"
      clean=0
    fi
    if printf '%s\n' "$ontext" | grep -qE '^[[:space:]]*paths(-ignore)?:'; then
      finding policy_producers "$workflow filters triggers by path; a required context must be emitted on every pull request"
      clean=0
    fi

    if [ "$clean" -eq 1 ]; then
      info policy_producers "'$context' <- $workflow:$job"
    fi
  done
  return 0
}

# Every action must be pinned to an immutable full-length commit SHA.
check_action_pinning() {
  local clean=1 line file ref
  while IFS= read -r line; do
    file="${line%%:*}"
    ref="$(printf '%s' "${line#*uses:}" | sed 's/#.*$//' | tr -d ' \t\r')"
    case "$ref" in
      ./*|"") continue ;;
    esac
    if ! printf '%s' "$ref" | grep -qE '@[0-9a-f]{40}$'; then
      finding action_pinning "$(basename "$file") uses a mutable action ref: $ref"
      clean=0
    fi
  done < <(grep -rn '^[[:space:]]*-\?[[:space:]]*uses:' "$WORKFLOW_DIR" 2>/dev/null || true)

  if [ "$clean" -eq 1 ]; then
    info action_pinning "every 'uses:' in .github/workflows is pinned to a commit SHA"
  fi
  return 0
}

# Every workflow must declare an explicit, least-privilege permissions block.
check_workflow_permissions() {
  local clean=1 wf
  for wf in "$WORKFLOW_DIR"/*.yml "$WORKFLOW_DIR"/*.yaml; do
    [ -f "$wf" ] || continue
    if ! grep -qE '^permissions:' "$wf"; then
      finding workflow_permissions "$(basename "$wf") declares no workflow-level 'permissions:' block"
      clean=0
      continue
    fi
    if grep -qE '^permissions:[[:space:]]*write-all' "$wf"; then
      finding workflow_permissions "$(basename "$wf") grants 'write-all' at the workflow level"
      clean=0
    fi
  done

  if [ "$clean" -eq 1 ]; then
    info workflow_permissions "every workflow declares an explicit permissions block"
  fi
  return 0
}

# A privileged workflow must never check out or execute pull request head code.
check_privileged_workflows() {
  local clean=1 wf
  for wf in "$WORKFLOW_DIR"/*.yml "$WORKFLOW_DIR"/*.yaml; do
    [ -f "$wf" ] || continue
    grep -qE '^[[:space:]]*pull_request_target:?' "$wf" || continue
    if grep -qE 'ref:[[:space:]]*\$\{\{[[:space:]]*github\.(event\.pull_request\.head|head_ref)' "$wf"; then
      finding privileged_workflows "$(basename "$wf") is a pull_request_target workflow that checks out pull request head code"
      clean=0
    fi
    if grep -qE '\$\{\{[[:space:]]*secrets\.' "$wf"; then
      finding privileged_workflows "$(basename "$wf") is a pull_request_target workflow that hands secrets to its steps"
      clean=0
    fi
  done

  if [ "$clean" -eq 1 ]; then
    info privileged_workflows "no privileged workflow checks out pull request head code or exposes secrets"
  fi
  return 0
}

# The live ruleset must require exactly the contexts the policy declares required.
check_ruleset_sync() {
  local branch ruleset_name live id detail want got ctx clean=1

  if [ "$NETWORK" -eq 0 ]; then
    info ruleset_sync "skipped (--no-network)"
    return 0
  fi
  if ! command -v gh >/dev/null 2>&1; then
    finding ruleset_sync "gh is required to read the live ruleset"
    return 0
  fi
  if [ -z "${GITHUB_REPOSITORY:-}" ]; then
    finding ruleset_sync "GITHUB_REPOSITORY is not set, so the live ruleset cannot be read"
    return 0
  fi

  branch="$(jq -r '.protected_branch' "$POLICY")"
  ruleset_name="$(jq -r '.ruleset_name' "$POLICY")"

  if ! live="$(gh api "repos/${GITHUB_REPOSITORY}/rulesets?includes_parents=false" 2>/dev/null)"; then
    finding ruleset_sync "could not read repository rulesets (a repository-admin token is required)"
    return 0
  fi

  id="$(printf '%s' "$live" | jq -r --arg n "$ruleset_name" '.[] | select(.name == $n) | .id' | head -n1)"
  if [ -z "$id" ]; then
    finding ruleset_sync "no ruleset named '$ruleset_name' protects '$branch'"
    return 0
  fi
  if ! detail="$(gh api "repos/${GITHUB_REPOSITORY}/rulesets/$id" 2>/dev/null)"; then
    finding ruleset_sync "could not read ruleset '$ruleset_name' ($id)"
    return 0
  fi

  if [ "$(printf '%s' "$detail" | jq -r '.enforcement')" != "active" ]; then
    finding ruleset_sync "ruleset '$ruleset_name' is not actively enforced"
    clean=0
  fi
  if [ "$(printf '%s' "$detail" | jq -r '.bypass_actors | length')" != "0" ]; then
    finding ruleset_sync "ruleset '$ruleset_name' has bypass actors configured"
    clean=0
  fi

  want="$(jq -r '.required[].context' "$POLICY" | sort)"
  got="$(printf '%s' "$detail" |
    jq -r '.rules[] | select(.type == "required_status_checks") | .parameters.required_status_checks[].context' |
    sort)"

  while IFS= read -r ctx; do
    [ -n "$ctx" ] || continue
    if ! printf '%s\n' "$got" | grep -qxF "$ctx"; then
      finding ruleset_sync "policy requires context '$ctx' but the live ruleset does not"
      clean=0
    fi
  done <<< "$want"

  while IFS= read -r ctx; do
    [ -n "$ctx" ] || continue
    if ! printf '%s\n' "$want" | grep -qxF "$ctx"; then
      finding ruleset_sync "the live ruleset requires context '$ctx', which the policy does not declare"
      clean=0
    fi
  done <<< "$got"

  while IFS= read -r ctx; do
    [ -n "$ctx" ] || continue
    if printf '%s\n' "$got" | grep -qxF "$ctx"; then
      finding ruleset_sync "advisory context '$ctx' must not be a required check"
      clean=0
    fi
  done < <(jq -r '.advisory[]?.context // empty' "$POLICY")

  if [ "$clean" -eq 1 ]; then
    info ruleset_sync "live ruleset matches the policy exactly"
  fi
  return 0
}

# Every label referenced by repository automation must actually exist.
check_label_references() {
  local refs="" existing clean=1 label

  if [ "$NETWORK" -eq 0 ]; then
    info label_references "skipped (--no-network)"
    return 0
  fi
  if ! command -v gh >/dev/null 2>&1 || [ -z "${GITHUB_REPOSITORY:-}" ]; then
    finding label_references "gh and GITHUB_REPOSITORY are required to list labels"
    return 0
  fi

  if [ -f "$ROOT/.github/labeler.yml" ]; then
    refs="$refs
$(grep -E '^[A-Za-z][A-Za-z0-9:_-]*:[[:space:]]*$' "$ROOT/.github/labeler.yml" |
      sed 's/:[[:space:]]*$//' |
      grep -vE '^(changed-files-labels-limit|max-files-changed|version)$' || true)"
  fi
  if [ -f "$ROOT/.github/dependabot.yml" ]; then
    refs="$refs
$(awk '
      /^[[:space:]]*labels:[[:space:]]*$/ { inl = 1; next }
      inl == 1 && $0 ~ /^[[:space:]]*-[[:space:]]*/ {
        line = $0; sub(/^[[:space:]]*-[[:space:]]*/, "", line); print line; next
      }
      { inl = 0 }
    ' "$ROOT/.github/dependabot.yml" | tr -d "\"'" || true)"
  fi
  if [ -d "$ROOT/.github/ISSUE_TEMPLATE" ]; then
    refs="$refs
$(grep -hE '^labels:' "$ROOT"/.github/ISSUE_TEMPLATE/* 2>/dev/null |
      sed 's/^labels:[[:space:]]*//; s/[][]//g' | tr ',' '\n' | tr -d "\"'" || true)"
  fi

  refs="$(printf '%s\n' "$refs" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//' | grep -v '^$' | sort -u || true)"
  if [ -z "$refs" ]; then
    info label_references "no repository automation references labels yet"
    return 0
  fi

  if ! existing="$(gh label list --repo "$GITHUB_REPOSITORY" --limit 200 --json name --jq '.[].name' 2>/dev/null)"; then
    finding label_references "could not list repository labels"
    return 0
  fi

  while IFS= read -r label; do
    [ -n "$label" ] || continue
    if ! printf '%s\n' "$existing" | grep -qxF "$label"; then
      finding label_references "automation references label '$label', which does not exist in the repository"
      clean=0
    fi
  done <<< "$refs"

  if [ "$clean" -eq 1 ]; then
    info label_references "every label referenced by repository automation exists"
  fi
  return 0
}

# The negative tests live beside this script and prove the audit fails closed.
check_negative_tests() {
  if bash "$SCRIPT_DIR/hardening-audit-test.sh"; then
    info negative_tests "the audit fails closed on every negative fixture"
  else
    finding negative_tests "the audit did not fail closed on at least one negative fixture"
  fi
  return 0
}

# ---------------------------------------------------------------------------
# Dispatch
# ---------------------------------------------------------------------------

ALL_CHECKS="policy_producers negative_tests ruleset_sync action_pinning workflow_permissions privileged_workflows label_references"

should_run() {
  local name="$1" configured
  if [ -n "$ONLY" ]; then
    [ "$ONLY" = "$name" ]
    return
  fi
  configured="$(jq -r --arg n "$name" '.checks[$n] // "report"' "$POLICY")"
  case "$MODE" in
    gate) [ "$configured" = "gate" ] ;;
    report) true ;;
  esac
}

if [ -n "$ONLY" ]; then
  case " $ALL_CHECKS " in
    *" $ONLY "*) ;;
    *) die "unknown check: $ONLY" ;;
  esac
fi

echo "hardening-audit: root=$ROOT mode=$MODE${ONLY:+ only=$ONLY}"
RAN=0
for check in $ALL_CHECKS; do
  should_run "$check" || continue
  RAN=$((RAN + 1))
  "check_$check"
done

[ "$RAN" -gt 0 ] || die "no checks selected; refusing to report a pass"

echo
if [ "$FINDINGS" -eq 0 ]; then
  echo "hardening-audit: $RAN check(s) ran, no findings."
  exit 0
fi
echo "hardening-audit: $RAN check(s) ran, $FINDINGS finding(s)."
exit 1
