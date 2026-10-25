#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

REPORT_DIR="$ROOT/build/reports/node-vite-cacheability"
LOG_DIR="$REPORT_DIR/logs"
mkdir -p "$LOG_DIR"

REPLICA="${REPLICA:-unknown}"
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
  local phase="$1" task="$2"
  local log="$LOG_DIR/${phase}-${task}.log"
  local start_ns end_ns rc
  start_ns="$(date +%s%N)"
  set +e
  ./gradlew "$task" --info --console=plain >"$log" 2>&1
  rc=$?
  set -e
  end_ns="$(date +%s%N)"
  record "${phase}.${task}.wall_ms" "$(duration_ms "$start_ns" "$end_ns")"
  record "${phase}.${task}.rc" "$rc"
  if grep -Fq "> Task :$task FROM-CACHE" "$log"; then
    record "${phase}.${task}.outcome" "FROM-CACHE"
  elif grep -Fq "> Task :$task UP-TO-DATE" "$log"; then
    record "${phase}.${task}.outcome" "UP-TO-DATE"
  elif grep -Eq "^> Task :$task([[:space:]]|$)" "$log"; then
    record "${phase}.${task}.outcome" "EXECUTED"
  else
    record "${phase}.${task}.outcome" "UNKNOWN"
  fi
  if [ "$rc" -ne 0 ]; then
    cat "$log" >&2
    return "$rc"
  fi
}

tree_digest() {
  local root="$1"
  python3 - "$root" <<'PY'
import hashlib
import os
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
if not root.exists():
    raise SystemExit(f"missing tree: {root}")
entries = []
for path in sorted(root.rglob("*"), key=lambda p: p.as_posix()):
    rel = path.relative_to(root).as_posix()
    if path.is_symlink():
        entries.append(f"L\t{rel}\t{os.readlink(path)}")
    elif path.is_file():
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        entries.append(f"F\t{rel}\t{digest}")
payload = "\n".join(entries).encode()
print(hashlib.sha256(payload).hexdigest())
PY
}

tree_bytes() {
  du -sb "$1" | awk '{print $1}'
}
snapshot_node_modules() {
  local label="$1"
  local manifest="$REPORT_DIR/node-modules-$label.manifest.tsv"
  local metrics files file_bytes digest
  metrics="$(python3 .github/scripts/research-node-modules-manifest.py snapshot webview/node_modules "$manifest")"
  IFS="|" read -r files file_bytes digest <<<"$metrics"
  record "node_modules.files.$label" "$files"
  record "node_modules.file_bytes.$label" "$file_bytes"
  record "node_modules.bytes.$label" "$(tree_bytes webview/node_modules)"
  record "node_modules.digest.$label" "$digest"
}

diff_node_modules() {
  local before="$1" after="$2" label="$3"
  local output="$REPORT_DIR/node-modules-diff-$label.tsv"
  local counts added removed changed
  counts="$(python3 .github/scripts/research-node-modules-manifest.py diff \
    "$REPORT_DIR/node-modules-$before.manifest.tsv" \
    "$REPORT_DIR/node-modules-$after.manifest.tsv" \
    "$output")"
  IFS="|" read -r added removed changed <<<"$counts"
  record "node_modules.diff.$label.added" "$added"
  record "node_modules.diff.$label.removed" "$removed"
  record "node_modules.diff.$label.changed" "$changed"
}

expected_sha="${GITHUB_SHA:-}"
actual_sha="$(git rev-parse HEAD)"
[ -n "$expected_sha" ] || { echo "GITHUB_SHA is empty" >&2; exit 1; }
[ "$actual_sha" = "$expected_sha" ] || {
  echo "checkout drift: expected=$expected_sha actual=$actual_sha" >&2
  exit 1
}

record "replica" "$REPLICA"
record "source_sha" "$actual_sha"
record "runner_arch" "${RUNNER_ARCH:-unknown}"
record "runner_os" "${RUNNER_OS:-unknown}"
record "kernel" "$(uname -srmo | tr '\t' ' ')"
record "node_pin" "$(tr -d '\r\n' < .nvmrc)"
record "gradle_wrapper" "$(grep '^distributionUrl=' gradle/wrapper/gradle-wrapper.properties | sed 's/.*gradle-\([0-9.]*\)-.*/\1/')"

# A fresh GitHub runner has none of these project-local outputs. Remove them
# explicitly so Phase A does not accidentally measure residue from a prior step.
rm -rf webview/node_modules build/webview build/renderer-node

measure_gradle cold npmInstallWebview

node_exe="$(find build/renderer-node -type f -path '*/bin/node' -print -quit)"
npm_cli="$(find build/renderer-node -type f \( -path '*/lib/node_modules/npm/bin/npm-cli.js' -o -path '*/node_modules/npm/bin/npm-cli.js' \) -print -quit)"
[ -x "$node_exe" ] || { echo "provisioned node executable missing" >&2; exit 1; }
[ -f "$npm_cli" ] || { echo "provisioned npm CLI missing" >&2; exit 1; }

record "node_version" "$("$node_exe" --version)"
record "npm_version" "$("$node_exe" "$npm_cli" --version)"
record "typescript_version" "$(cd webview && ../"$node_exe" -p "require('./node_modules/typescript/package.json').version")"
record "vite_version" "$(cd webview && ../"$node_exe" -p "require('./node_modules/vite/package.json').version")"

record "node_modules.bytes.cold" "$(tree_bytes webview/node_modules)"
record "node_modules.digest.cold" "$(tree_digest webview/node_modules)"
snapshot_node_modules after-install


measure_gradle cold buildWebview
snapshot_node_modules after-build
diff_node_modules after-install after-build install-to-build
record "webview_output.bytes.cold" "$(tree_bytes build/webview)"
record "webview_output.digest.cold" "$(tree_digest build/webview)"

measure_gradle cold testWebviewSource
snapshot_node_modules after-test
diff_node_modules after-build after-test build-to-test
grep -E '^# (tests|pass|fail|skipped|duration_ms) ' "$LOG_DIR/cold-testWebviewSource.log" > "$REPORT_DIR/source-test-cold.tap-summary" || true

measure_gradle warm npmInstallWebview
snapshot_node_modules after-warm-install
diff_node_modules after-test after-warm-install test-to-warm-install

measure_gradle warm buildWebview
snapshot_node_modules after-warm-build
diff_node_modules after-warm-install after-warm-build warm-install-to-build

measure_gradle warm testWebviewSource
snapshot_node_modules after-warm-test
diff_node_modules after-warm-build after-warm-test warm-build-to-test

record "node_modules.bytes.warm" "$(tree_bytes webview/node_modules)"
record "node_modules.digest.warm" "$(tree_digest webview/node_modules)"
record "webview_output.bytes.warm" "$(tree_bytes build/webview)"
record "webview_output.digest.warm" "$(tree_digest build/webview)"

grep -E '^# (tests|pass|fail|skipped|duration_ms) ' "$LOG_DIR/warm-testWebviewSource.log" > "$REPORT_DIR/source-test-warm.tap-summary" || true

test_manifest="$REPORT_DIR/source-test-files.txt"
find webview/tests -type f -print | LC_ALL=C sort > "$test_manifest"
record "source_test_files.count" "$(wc -l < "$test_manifest" | tr -d ' ')"
record "source_test_files.digest" "$(sha256sum "$test_manifest" | awk '{print $1}')"

measure_gradle package buildPlugin

mapfile -t plugin_zips < <(find build/distributions -maxdepth 1 -type f -name '*.zip' -print | LC_ALL=C sort)
[ "${#plugin_zips[@]}" -eq 1 ] || {
  printf 'expected one plugin ZIP, found %s\n' "${#plugin_zips[@]}" >&2
  printf '%s\n' "${plugin_zips[@]}" >&2
  exit 1
}
record "plugin_zip.path" "${plugin_zips[0]#"$ROOT/"}"
record "plugin_zip.bytes" "$(stat -c '%s' "${plugin_zips[0]}")"
record "plugin_zip.sha256" "$(sha256sum "${plugin_zips[0]}" | awk '{print $1}')"

# node_modules mutation is the object of this measurement.
# Exact stage manifests/diffs above replace the prior false immutability assertion.
[ "$(awk -F '\t' '$1=="webview_output.digest.cold"{print $2}' "$REPORT")" =   "$(awk -F '\t' '$1=="webview_output.digest.warm"{print $2}' "$REPORT")" ] || {
  echo "renderer output changed between cold and warm measurement" >&2
  exit 1
}

cat "$REPORT"
