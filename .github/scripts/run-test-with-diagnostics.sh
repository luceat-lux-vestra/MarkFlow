#!/usr/bin/env bash
set -euo pipefail

# Capture a bounded causal snapshot without weakening Gradle's failure status.
test -n "$RUNNER_TEMP" || { echo "::error::RUNNER_TEMP is required"; exit 1; }
log_dir="$RUNNER_TEMP/markflow-test-hang"
mkdir -p "$log_dir"
log_file="$log_dir/check.log"

capture() {
  local label="$1" pid comm count=0
  mkdir -p "$log_dir/evidence"
  {
    printf 'stage=%s\nutc=%s\n' "$label" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '\n-- process state (no argv or environment) --\n'
    ps -eo pid,ppid,pgid,stat,etime,%cpu,%mem,comm
    printf '\n-- test progress --\n'
    grep -E 'MARKFLOW_TEST_(START|END)|^> Task :test([[:space:]]|$)' "$log_file" | tail -n 100 || true
  } > "$log_dir/evidence/diagnostics-$label.txt"
  tail -n 120 "$log_file" > "$log_dir/evidence/check-tail-$label.txt" || true
  # No more than eight bounded JVM attach attempts. Avoid logging process argv.
  while read -r pid comm; do
    [[ "$pid" =~ ^[0-9]+$ ]] || continue
    [[ "$comm" == java ]] || continue
    count=$((count + 1))
    (( count <= 8 )) || break
    timeout -k 2s 8s jcmd "$pid" Thread.print -l \
      > "$log_dir/evidence/jvm-$label-$pid.txt" 2>&1 || true
  done < <(ps -eo pid=,comm=)
}

# Gradle, its test JVM and JCEF child processes share a dedicated process group.
setsid ./gradlew check --console=plain \
  --init-script .github/scripts/test-progress.init.gradle > "$log_file" 2>&1 &
gradle_pid=$!
tail -n +1 -F "$log_file" &
tail_pid=$!
cleanup() {
  kill "$tail_pid" 2>/dev/null || true
  wait "$tail_pid" 2>/dev/null || true
}
trap cleanup EXIT

start_test=0
captured=0
expired=0
while kill -0 "$gradle_pid" 2>/dev/null; do
  # kill -0 also succeeds for a zombie child; do not spin after completion.
  if [[ "$(ps -o stat= -p "$gradle_pid" 2>/dev/null || true)" == Z* ]]; then
    break
  fi
  if (( start_test == 0 )) && grep -Eq '^> Task :test([[:space:]]|$)' "$log_file"; then
    start_test=$(date +%s)
  fi
  if (( start_test > 0 )); then
    now=$(date +%s)
    elapsed=$((now - start_test))
    if (( elapsed >= 300 && captured == 0 )); then
      capture five-minutes
      captured=1
    fi
    if (( elapsed >= 480 )); then
      capture eight-minutes
      printf '::error::Gradle :test still active after 8 minutes; fail closed with JVM/process evidence.\n' >&2
      expired=1
      kill -TERM -- "-$gradle_pid" 2>/dev/null || true
      sleep 3
      kill -KILL -- "-$gradle_pid" 2>/dev/null || true
      break
    fi
  fi
  sleep 10
done

set +e
wait "$gradle_pid"
status=$?
set -e
if (( expired != 0 )); then
  exit 124
fi
exit "$status"
