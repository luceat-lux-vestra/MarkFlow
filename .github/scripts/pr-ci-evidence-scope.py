#!/usr/bin/env python3
"""Classify specialist PR evidence from a complete GitHub PR-files API TSV.
The caller must fetch this script at the *trusted base SHA*. Any ambiguous
data returns both=true (fail closed to executing all specialized proofs).
"""
import argparse
import fnmatch
import re
import sys

# Mirrors the original workflow pull_request paths (union for native probes).
NATIVE = [".github/workflows/native-runtime-evidence.yml","build.gradle.kts","gradle.properties","gradle/**","src/main/resources/META-INF/plugin.xml","src/main/resources/META-INF/markflow-jcef.xml","src/main/kotlin/com/algorist/markflow/renderer/**","src/main/kotlin/com/algorist/markflow/browser/MarkFlowWebviewResourceManager.kt","webview/package.json","webview/package-lock.json","webview/derived-renderer.html","webview/vite.derived-renderer.config.ts","webview/src/renderer/**","webview/src/app/derived-renderer-service.ts","webview/src/app/browser-derived-renderer-backends.ts","src/main/kotlin/com/algorist/markflow/editor/native/**","src/test/kotlin/com/algorist/markflow/editor/native/**","docs/architecture/native-edit-semantics.md","docs/architecture/native-editor-shell-selection.md","docs/architecture/native-markdown-projection-foundation.md","docs/architecture/README.md","docs/architecture/0001-native-authority-projection-architecture.md","src/main/kotlin/com/algorist/markflow/trust/**","src/test/kotlin/com/algorist/markflow/trust/**","docs/architecture/native-host-resources.md","docs/product/image-file-import-contract.md","fixtures/markdown-fidelity/**","src/main/kotlin/com/algorist/markflow/settings/**","docs/architecture/0002-mermaid-katex-renderer-continuity.md"]
STARTER = [".github/workflows/starter-driver-e2e.yml",".github/scripts/check-starter-driver-diagnostics.py","build.gradle.kts","gradle/libs.versions.toml","webview/package.json","webview/package-lock.json","src/integrationTest/**","src/main/**"]
# Orchestrator or classifier changes invalidate both evidence authorities.
TOPOLOGY = (".github/workflows/build.yml", ".github/scripts/pr-ci-evidence-scope.py", ".github/scripts/replacement-head-evidence-reuse.py")
STATUSES = {"added", "modified", "removed", "renamed", "copied", "changed"}


def safe_path(p):
    return bool(p and not p.startswith("/") and
                not any(x in ("", ".", "..") for x in p.split("/")) and
                "\x00" not in p and "\n" not in p and "\r" not in p)


def classify(rows, count):
    if not re.fullmatch(r"[1-9][0-9]*", count) or len(rows) != int(count):
        return True, True
    changed, targets = set(), set()
    for row in rows:
        cells = row.split("\t")
        if len(cells) != 3:
            return True, True
        status, filename, previous = cells
        if status not in STATUSES or not safe_path(filename) or filename in targets:
            return True, True
        if status == "renamed" and not safe_path(previous):
            return True, True
        if previous and not safe_path(previous):
            return True, True
        targets.add(filename)
        changed.add(filename)
        if previous:
            changed.add(previous)
    # fnmatch is deliberately conservative across '/' (possible over-routing).
    native = any(fnmatch.fnmatchcase(p, g) for p in changed for g in NATIVE + list(TOPOLOGY))
    starter = any(fnmatch.fnmatchcase(p, g) for p in changed for g in STARTER + list(TOPOLOGY))
    return native, starter


def self_test():
    row = lambda p: [f"modified\t{p}\t"]
    assert classify(row("README.md"), "1") == (False, False)
    assert classify(row("docs/architecture/native-edit-semantics.md"), "1") == (True, False)
    assert classify(row("src/integrationTest/kotlin/Smoke.kt"), "1") == (False, True)
    assert classify(row("src/main/kotlin/com/algorist/markflow/editor/native/Edit.kt"), "1") == (True, True)
    assert classify(row("webview/package-lock.json"), "1") == (True, True)
    assert classify(row(".github/workflows/build.yml"), "1") == (True, True)
    assert classify(row(".github/scripts/pr-ci-evidence-scope.py"), "1") == (True, True)
    assert classify(row(".github/scripts/replacement-head-evidence-reuse.py"), "1") == (True, True)
    assert classify(["renamed\tdocs/new.md\tsrc/integrationTest/Old.kt"], "1") == (False, True)
    assert classify(["renamed\tdocs/new.md\t"], "1") == (True, True)
    assert classify(row("README.md"), "2") == (True, True)
    assert classify(row("README.md"), "unknown") == (True, True)
    assert classify(["invalid\tREADME.md\t"], "1") == (True, True)
    assert classify(["modified\t../escape\t"], "1") == (True, True)
    assert classify(row("README.md") * 2, "2") == (True, True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--expected-count")
    a = parser.parse_args()
    if a.self_test:
        self_test()
        return
    n, s = classify(sys.stdin.read().splitlines(), a.expected_count or "")
    print(f"run_native={str(n).lower()}")
    print(f"run_starter={str(s).lower()}")


if __name__ == "__main__":
    main()
