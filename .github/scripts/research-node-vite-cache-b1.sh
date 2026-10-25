#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

ROLE="${1:-}"
REPLICA="${REPLICA:-producer}"
case "$ROLE" in
  producer|cold|warm) ;;
  *) echo "usage: $0 producer|cold|warm" >&2; exit 2 ;;
esac

: "${npm_config_cache:?npm_config_cache must point at the isolated B1 npm cache directory}"

REPORT_DIR="$ROOT/build/reports/node-vite-cache-b1"
LOG_DIR="$REPORT_DIR/logs"
mkdir -p "$LOG_DIR"
REPORT="$REPORT_DIR/summary.tsv"
: > "$REPORT"
printf 'metric\tvalue\n' >> "$REPORT"

record() {
  printf '%s\t%s\n' "$1" "$2" >> "$REPORT"
}

duration_ms() {
  local start_ns="$1" end_ns="$2"
  printf '%s\n' "$(( (end_ns - start_ns) / 1000000 ))"
}

measure_gradle() {
  local label="$1" task="$2"
  local log="$LOG_DIR/${label}-${task}.log"
  local start_ns end_ns rc
  start_ns="$(date +%s%N)"
  set +e
  ./gradlew "$task" --info --console=plain >"$log" 2>&1
  rc=$?
  set -e
  end_ns="$(date +%s%N)"
  record "${label}.${task}.wall_ms" "$(duration_ms "$start_ns" "$end_ns")"
  record "${label}.${task}.rc" "$rc"
  if grep -Fq "> Task :$task FROM-CACHE" "$log"; then
    record "${label}.${task}.outcome" "FROM-CACHE"
  elif grep -Fq "> Task :$task UP-TO-DATE" "$log"; then
    record "${label}.${task}.outcome" "UP-TO-DATE"
  elif grep -Eq "^> Task :$task([[:space:]]|$)" "$log"; then
    record "${label}.${task}.outcome" "EXECUTED"
  else
    record "${label}.${task}.outcome" "UNKNOWN"
  fi
  if [ "$rc" -ne 0 ]; then
    cat "$log" >&2
    return "$rc"
  fi
}

snapshot_tree() {
  local label="$1" root="$2"
  local manifest="$REPORT_DIR/${label}.manifest.tsv"
  local metrics files file_bytes digest
  metrics="$(python3 .github/scripts/research-node-modules-manifest.py snapshot "$root" "$manifest")"
  IFS='|' read -r files file_bytes digest <<<"$metrics"
  record "${label}.entries" "$files"
  record "${label}.file_bytes" "$file_bytes"
  record "${label}.disk_bytes" "$(du -sb "$root" | awk '{print $1}')"
  record "${label}.digest" "$digest"
}

record_test_summary() {
  local log="$1"
  local metric value
  for metric in tests pass fail skipped; do
    value="$(grep -E "^# $metric [0-9]+$" "$log" | tail -1 | awk '{print $3}' || true)"
    [ -n "$value" ] && record "source_tests.$metric" "$value"
  done
}

expected_sha="${GITHUB_SHA:-}"
actual_sha="$(git rev-parse HEAD)"
[ -n "$expected_sha" ] || { echo "GITHUB_SHA is empty" >&2; exit 1; }
[ "$actual_sha" = "$expected_sha" ] || {
  echo "checkout drift: expected=$expected_sha actual=$actual_sha" >&2
  exit 1
}

lock_sha="$(sha256sum webview/package-lock.json | awk '{print $1}')"
node_pin="$(tr -d '\r\n' < .nvmrc)"
[ "$node_pin" = "26.10.0" ] || {
  echo "unexpected Node pin: $node_pin" >&2
  exit 1
}

record "role" "$ROLE"
record "replica" "$REPLICA"
record "source_sha" "$actual_sha"
record "runner_os" "${RUNNER_OS:-unknown}"
record "runner_arch" "${RUNNER_ARCH:-unknown}"
record "node_pin" "$node_pin"
record "package_lock_sha256" "$lock_sha"
record "npm_cache_path" "$npm_config_cache"

rm -rf webview/node_modules build/webview build/distributions build/renderer-node

if [ "$ROLE" = "producer" ] || [ "$ROLE" = "cold" ]; then
  rm -rf "$npm_config_cache"
  mkdir -p "$npm_config_cache"
else
  [ -d "$npm_config_cache" ] || {
    echo "warm consumer cache directory is missing: $npm_config_cache" >&2
    exit 1
  }
  snapshot_tree "npm_cache_restored" "$npm_config_cache"
fi

measure_gradle "$ROLE" npmInstallWebview

node_exe="$(find build/renderer-node -type f -path '*/bin/node' -print -quit)"
npm_cli="$(find build/renderer-node -type f \( -path '*/lib/node_modules/npm/bin/npm-cli.js' -o -path '*/node_modules/npm/bin/npm-cli.js' \) -print -quit)"
[ -x "$node_exe" ] || { echo "provisioned node executable missing" >&2; exit 1; }
[ -f "$npm_cli" ] || { echo "provisioned npm CLI missing" >&2; exit 1; }

node_version="$("$node_exe" --version)"
npm_version="$("$node_exe" "$npm_cli" --version)"
record "node_version" "$node_version"
record "npm_version" "$npm_version"
[ "$node_version" = "v26.10.0" ] || { echo "unexpected Node version: $node_version" >&2; exit 1; }
[ "$npm_version" = "11.19.1" ] || { echo "unexpected npm version: $npm_version" >&2; exit 1; }

snapshot_tree "npm_cache_after_install" "$npm_config_cache"
snapshot_tree "node_modules_after_install" webview/node_modules

if [ "$ROLE" = "producer" ]; then
  cat "$REPORT"
  exit 0
fi

measure_gradle "$ROLE" buildWebview
snapshot_tree "renderer_output" build/webview

measure_gradle "$ROLE" testWebviewSource
record_test_summary "$LOG_DIR/${ROLE}-testWebviewSource.log"

measure_gradle "$ROLE" buildPlugin

mapfile -t plugin_zips < <(find build/distributions -maxdepth 1 -type f -name '*.zip' -print | LC_ALL=C sort)
[ "${#plugin_zips[@]}" -eq 1 ] || {
  printf 'expected one plugin ZIP, found %s\n' "${#plugin_zips[@]}" >&2
  printf '%s\n' "${plugin_zips[@]}" >&2
  exit 1
}
record "plugin_zip.path" "${plugin_zips[0]#"$ROOT/"}"
record "plugin_zip.bytes" "$(stat -c '%s' "${plugin_zips[0]}")"
record "plugin_zip.sha256" "$(sha256sum "${plugin_zips[0]}" | awk '{print $1}')"

cat "$REPORT"
