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

REPORT_DIR="$ROOT/build/reports/node-vite-cache-b2"
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
  local label="$1"
  shift
  local safe_label="${label//[^A-Za-z0-9_.-]/_}"
  local log="$LOG_DIR/${safe_label}.log"
  local start_ns end_ns rc
  start_ns="$(date +%s%N)"
  set +e
  ./gradlew "$@" --info --console=plain >"$log" 2>&1
  rc=$?
  set -e
  end_ns="$(date +%s%N)"
  record "${label}.wall_ms" "$(duration_ms "$start_ns" "$end_ns")"
  record "${label}.rc" "$rc"
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

record_source_test_summary() {
  local log="$1"
  python3 - "$log" "$REPORT" <<'PY'
import pathlib
import re
import sys

log = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
report = pathlib.Path(sys.argv[2])
metrics = {}
for raw in log.splitlines():
    line = re.sub(r"\x1b\[[0-9;]*m", "", raw).strip()
    match = re.match(r"^(?:#|ℹ)\s+(tests|pass|fail|skipped)\s+(\d+)\s*$", line)
    if match:
        metrics[match.group(1)] = match.group(2)

required = {"tests", "pass", "fail", "skipped"}
missing = sorted(required - metrics.keys())
if missing:
    raise SystemExit(f"missing Node test summary metrics: {missing}")

with report.open("a", encoding="utf-8") as f:
    for key in ("tests", "pass", "fail", "skipped"):
        f.write(f"source_tests.{key}\t{metrics[key]}\n")

if metrics["tests"] != "47" or metrics["pass"] != "47" or metrics["fail"] != "0" or metrics["skipped"] != "0":
    raise SystemExit(f"unexpected source-test summary: {metrics}")
PY
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

# The cached tree must never contain Vite's generated source-test optimizer state.
if [ -d webview/node_modules/.vite ]; then
  echo "node_modules/.vite must be absent before B2 measurement" >&2
  exit 1
fi

if [ "$ROLE" = "producer" ]; then
  rm -rf webview/node_modules build/webview build/distributions build/renderer-node
  measure_gradle "producer.verifyRendererNode" verifyRendererNode
  measure_gradle "producer.npmInstallWebview" npmInstallWebview
  [ ! -d webview/node_modules/.vite ] || {
    echo "producer npm install unexpectedly created node_modules/.vite" >&2
    exit 1
  }
  snapshot_tree "node_modules_clean" webview/node_modules
  cat "$REPORT"
  exit 0
fi

measure_gradle "${ROLE}.verifyRendererNode" verifyRendererNode

if [ "$ROLE" = "cold" ]; then
  rm -rf webview/node_modules build/webview build/distributions
  measure_gradle "cold.npmInstallWebview" npmInstallWebview
  [ ! -d webview/node_modules/.vite ] || {
    echo "cold npm install unexpectedly created node_modules/.vite" >&2
    exit 1
  }
  snapshot_tree "node_modules_before_test" webview/node_modules
else
  [ -d webview/node_modules ] || {
    echo "warm consumer restored node_modules tree is missing" >&2
    exit 1
  }
  [ ! -d webview/node_modules/.vite ] || {
    echo "restored cache illegally contains node_modules/.vite" >&2
    exit 1
  }
  snapshot_tree "node_modules_before_test" webview/node_modules
  [ -n "${EXPECTED_NODE_MODULES_DIGEST:-}" ] || {
    echo "EXPECTED_NODE_MODULES_DIGEST is required for warm consumers" >&2
    exit 1
  }
  actual_digest="$(awk -F '\t' '$1=="node_modules_before_test.digest"{print $2}' "$REPORT")"
  [ "$actual_digest" = "$EXPECTED_NODE_MODULES_DIGEST" ] || {
    echo "restored node_modules digest mismatch: expected=$EXPECTED_NODE_MODULES_DIGEST actual=$actual_digest" >&2
    exit 1
  }
fi

# Keep the downstream Gradle graph identical across cold and warm arms.
# npmInstallWebview is explicitly excluded because preparation was already completed
# by either a real cold npm ci or an exact producer-bound restore.
measure_gradle "${ROLE}.buildWebview" buildWebview -x npmInstallWebview
snapshot_tree "renderer_output" build/webview

measure_gradle "${ROLE}.testWebviewSource" testWebviewSource -x npmInstallWebview
record_source_test_summary "$LOG_DIR/${ROLE}.testWebviewSource.log"

measure_gradle "${ROLE}.buildPlugin" buildPlugin -x npmInstallWebview

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
