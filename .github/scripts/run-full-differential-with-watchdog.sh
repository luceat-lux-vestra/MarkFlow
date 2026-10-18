#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 <progress-file> <diagnostic-file> -- <command> [args...]" >&2
}

terminate_group() {
  local child_pid="$1"
  local reason="$2"
  local diagnostic_file="$3"
  local progress_file="$4"
  local now
  now="$(date +%s)"
  {
    echo "schema=markflow-full-differential-watchdog/v1"
    echo "reason=$reason"
    echo "epoch_seconds=$now"
    echo "child_pid=$child_pid"
    if [ -f "$progress_file" ]; then
      echo "last_progress=$(tr '\t\n' '  ' < "$progress_file")"
      echo "last_progress_mtime=$(stat -c %Y "$progress_file" 2>/dev/null || true)"
    else
      echo "last_progress=missing"
    fi
    echo "process_snapshot_begin"
    ps -eo pid,ppid,pgid,stat,etime,cmd --forest || true
    echo "process_snapshot_end"
  } > "$diagnostic_file"

  kill -TERM -- "-$child_pid" 2>/dev/null || kill -TERM "$child_pid" 2>/dev/null || true
  for _ in {1..10}; do
    if ! kill -0 "$child_pid" 2>/dev/null; then
      return
    fi
    sleep 1
  done
  kill -KILL -- "-$child_pid" 2>/dev/null || kill -KILL "$child_pid" 2>/dev/null || true
}

run_self_test() {
  local tmp rc
  tmp="$(mktemp -d)"
  set +e
  MARKFLOW_DIFFERENTIAL_PROGRESS_FILE="$tmp/progress.tsv" \
  MARKFLOW_WATCHDOG_IDLE_SECONDS=2 \
  MARKFLOW_WATCHDOG_STARTUP_SECONDS=2 \
  MARKFLOW_WATCHDOG_TOTAL_SECONDS=20 \
  MARKFLOW_WATCHDOG_POLL_SECONDS=1 \
  MARKFLOW_WATCHDOG_REPORT_SECONDS=1 \
  "$0" "$tmp/progress.tsv" "$tmp/watchdog.txt" -- \
    bash -c 'printf "1\tself-test\tcase\n" > "$MARKFLOW_DIFFERENTIAL_PROGRESS_FILE"; sleep 30' \
    >"$tmp/self-test.log" 2>&1
  rc=$?
  set -e
  if [ "$rc" -ne 124 ]; then
    echo "watchdog self-test: expected 124, got $rc" >&2
    rm -rf "$tmp"
    return 1
  fi
  grep -Fq "reason=idle-timeout" "$tmp/watchdog.txt" || {
    echo "watchdog self-test: idle timeout diagnostic missing" >&2
    rm -rf "$tmp"
    return 1
  }
  grep -Fq "last_progress=" "$tmp/watchdog.txt" || {
    echo "watchdog self-test: last progress diagnostic missing" >&2
    rm -rf "$tmp"
    return 1
  }
  grep -Fq "FULL_DIFFERENTIAL_HEARTBEAT" "$tmp/self-test.log" || {
    echo "watchdog self-test: console heartbeat missing" >&2
    rm -rf "$tmp"
    return 1
  }
  grep -Fq "FULL_DIFFERENTIAL_PROCESS_GROUP" "$tmp/self-test.log" || {
    echo "watchdog self-test: process-group identity diagnostic missing" >&2
    rm -rf "$tmp"
    return 1
  }
  rm -rf "$tmp"
  echo "Full differential watchdog self-test PASS"
}

if [ "${1:-}" = "--self-test" ]; then
  run_self_test
  exit 0
fi

if [ "$#" -lt 4 ]; then
  usage
  exit 2
fi

progress_file="$1"
diagnostic_file="$2"
shift 2
if [ "${1:-}" != "--" ]; then
  usage
  exit 2
fi
shift
if [ "$#" -eq 0 ]; then
  usage
  exit 2
fi

idle_seconds="${MARKFLOW_WATCHDOG_IDLE_SECONDS:-180}"
startup_seconds="${MARKFLOW_WATCHDOG_STARTUP_SECONDS:-900}"
total_seconds="${MARKFLOW_WATCHDOG_TOTAL_SECONDS:-5100}"
poll_seconds="${MARKFLOW_WATCHDOG_POLL_SECONDS:-15}"
report_seconds="${MARKFLOW_WATCHDOG_REPORT_SECONDS:-60}"

for value in "$idle_seconds" "$startup_seconds" "$total_seconds" "$poll_seconds" "$report_seconds"; do
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || {
    echo "watchdog limits must be positive integers" >&2
    exit 2
  }
done

mkdir -p "$(dirname "$progress_file")" "$(dirname "$diagnostic_file")"
rm -f "$progress_file" "$diagnostic_file"

start_epoch="$(date +%s)"
setsid "$@" &
child_pid=$!

wrapper_pgid="$(ps -o pgid= -p "$$" 2>/dev/null | tr -d '[:space:]')"
child_pgid=""
for _ in {1..20}; do
  child_pgid="$(ps -o pgid= -p "$child_pid" 2>/dev/null | tr -d '[:space:]')"
  if [ "$child_pgid" = "$child_pid" ]; then
    break
  fi
  kill -0 "$child_pid" 2>/dev/null || break
  sleep 0.1
done
if [ -z "$child_pgid" ] || [ "$child_pgid" != "$child_pid" ] ||
  [ -z "$wrapper_pgid" ] || [ "$child_pgid" = "$wrapper_pgid" ]; then
  echo "watchdog process-group isolation failed: child_pid=$child_pid child_pgid=$child_pgid wrapper_pgid=$wrapper_pgid" >&2
  kill -TERM "$child_pid" 2>/dev/null || true
  wait "$child_pid" 2>/dev/null || true
  exit 2
fi
echo "FULL_DIFFERENTIAL_PROCESS_GROUP child_pid=$child_pid child_pgid=$child_pgid wrapper_pid=$$ wrapper_pgid=$wrapper_pgid"

next_report_epoch=$((start_epoch + report_seconds))

on_signal() {
  # Hosted-runner shutdown gives the wrapper only a small termination window.
  # Keep the signal path parser-simple and bounded: do not collect a process tree
  # or run command substitutions here. The normal watchdog timeout path retains
  # the richer terminate_group diagnostic.
  trap - INT TERM HUP
  {
    echo "schema=markflow-full-differential-watchdog/v1"
    echo "reason=wrapper-signal"
    echo "child_pid=$child_pid"
    if [ -f "$progress_file" ]; then
      printf 'last_progress='
      tr '\t\n' '  ' < "$progress_file" || true
      printf '\n'
    else
      echo "last_progress=missing"
    fi
  } > "$diagnostic_file" 2>/dev/null || true
  kill -TERM -- "-$child_pid" 2>/dev/null || kill -TERM "$child_pid" 2>/dev/null || true
  sleep 1
  kill -KILL -- "-$child_pid" 2>/dev/null || kill -KILL "$child_pid" 2>/dev/null || true
  exit 130
}
trap on_signal INT TERM HUP

while kill -0 "$child_pid" 2>/dev/null; do
  now="$(date +%s)"
  elapsed=$((now - start_epoch))
  progress="missing"
  phase="missing"
  idle=-1
  if [ -f "$progress_file" ]; then
    progress="$(tr '\t\n' '  ' < "$progress_file")"
    phase="$(awk -F '\t' 'END {print $2}' "$progress_file")"
    mtime="$(stat -c %Y "$progress_file" 2>/dev/null || echo "$now")"
    idle=$((now - mtime))
  fi

  if [ "$now" -ge "$next_report_epoch" ]; then
    echo "FULL_DIFFERENTIAL_HEARTBEAT elapsed_s=$elapsed idle_s=$idle phase=$phase progress=$progress"
    next_report_epoch=$((now + report_seconds))
  fi

  if [ "$elapsed" -ge "$total_seconds" ]; then
    terminate_group "$child_pid" "total-timeout" "$diagnostic_file" "$progress_file"
    set +e
    wait "$child_pid"
    set -e
    exit 124
  fi

  if [ -f "$progress_file" ]; then
    if [ "$phase" = "test-start" ] || [ "$phase" = "starter-launch" ]; then
      if [ "$elapsed" -ge "$startup_seconds" ]; then
        terminate_group "$child_pid" "startup-timeout" "$diagnostic_file" "$progress_file"
        set +e
        wait "$child_pid"
        set -e
        exit 124
      fi
    elif [ "$idle" -ge "$idle_seconds" ]; then
      terminate_group "$child_pid" "idle-timeout" "$diagnostic_file" "$progress_file"
      set +e
      wait "$child_pid"
      set -e
      exit 124
    fi
  elif [ "$elapsed" -ge "$startup_seconds" ]; then
    terminate_group "$child_pid" "startup-timeout" "$diagnostic_file" "$progress_file"
    set +e
    wait "$child_pid"
    set -e
    exit 124
  fi

  sleep "$poll_seconds"
done

set +e
wait "$child_pid"
status=$?
set -e
exit "$status"
