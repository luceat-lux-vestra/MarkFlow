#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

REQUIRED_SUITES = {
    "com.algorist.markflow.e2e.MarkFlowStarterDegradedPathTest",
    "com.algorist.markflow.e2e.MarkFlowStarterSplitEditorTest",
    "com.algorist.markflow.e2e.MarkFlowStarterSmokeTest",
    "com.algorist.markflow.e2e.MarkFlowStarterTableTest",
    "com.algorist.markflow.e2e.MarkFlowStarterProductionWiringTest",
}
MARKFLOW_MARKER = "com.algorist.markflow."
LIFECYCLE_TOKENS = ("AlreadyDisposedException", "ConcurrentModificationException")


def _read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def _suite_name(path: Path) -> str:
    name = path.name
    if name.startswith("TEST-") and name.endswith(".xml"):
        return name[5:-4]
    return name


def analyze(root: Path) -> dict:
    junit_files = sorted(root.glob("build/test-results/integrationTest/TEST-*.xml"))
    discovered = set()
    tests = failures = errors = skipped = 0
    parse_errors = []

    for path in junit_files:
        try:
            suite = ET.parse(path).getroot()
        except (ET.ParseError, OSError) as failure:
            parse_errors.append(f"{path}: {failure}")
            continue
        discovered.add(_suite_name(path))
        tests += int(suite.attrib.get("tests", "0"))
        failures += int(suite.attrib.get("failures", "0"))
        errors += int(suite.attrib.get("errors", "0"))
        skipped += int(suite.attrib.get("skipped", "0"))

    missing_suites = sorted(REQUIRED_SUITES - discovered)

    error_dirs = sorted(root.glob("out/ide-tests/**/log/errors/error-*"))
    captures = []
    markflow_captures = []
    for directory in error_dirs:
        message = _read(directory / "message.txt").strip()
        stacktrace = _read(directory / "stacktrace.txt")
        active_test = _read(directory / "activeTestName.txt").strip()
        combined = f"{message}\n{stacktrace}"
        classification = "markflow" if MARKFLOW_MARKER in combined else "platform-only"
        entry = {
            "path": str(directory.relative_to(root)),
            "classification": classification,
            "activeTest": active_test,
            "message": message[:500],
        }
        captures.append(entry)
        if classification == "markflow":
            markflow_captures.append(entry)

    idea_logs = sorted(root.glob("out/ide-tests/**/log/idea.log"))
    correlated = []
    platform_only_lifecycle = []
    markflow_error_windows = []

    for path in idea_logs:
        lines = _read(path).splitlines()
        for index, line in enumerate(lines):
            if any(token in line for token in LIFECYCLE_TOKENS):
                lo = max(0, index - 35)
                hi = min(len(lines), index + 36)
                window = "\n".join(lines[lo:hi])
                item = {
                    "path": str(path.relative_to(root)),
                    "line": index + 1,
                    "token": next(token for token in LIFECYCLE_TOKENS if token in line),
                }
                if MARKFLOW_MARKER in window:
                    correlated.append(item)
                else:
                    platform_only_lifecycle.append(item)

            if " ERROR " in line or " SEVERE " in line:
                lo = max(0, index - 20)
                hi = min(len(lines), index + 21)
                window = "\n".join(lines[lo:hi])
                if MARKFLOW_MARKER in window:
                    markflow_error_windows.append(
                        {
                            "path": str(path.relative_to(root)),
                            "line": index + 1,
                            "headline": line[:500],
                        }
                    )

    reasons = []
    if parse_errors:
        reasons.append("JUnit XML parse failure")
    if missing_suites:
        reasons.append("required Starter/Driver suite missing")
    if not junit_files:
        reasons.append("Starter/Driver JUnit evidence missing")
    if failures or errors or skipped:
        reasons.append("Starter/Driver suite not fully passing")
    if not idea_logs:
        reasons.append("IDE logs missing")
    if markflow_captures:
        reasons.append("IDE error capture contains MarkFlow frame")
    if correlated:
        reasons.append("lifecycle exception correlates with MarkFlow frame")
    if markflow_error_windows:
        reasons.append("IDE ERROR/SEVERE window correlates with MarkFlow frame")

    return {
        "schemaVersion": 1,
        "verdict": "PASS" if not reasons else "FAIL",
        "reasons": reasons,
        "junit": {
            "files": len(junit_files),
            "requiredSuites": sorted(REQUIRED_SUITES),
            "discoveredSuites": sorted(discovered),
            "missingSuites": missing_suites,
            "tests": tests,
            "failures": failures,
            "errors": errors,
            "skipped": skipped,
            "parseErrors": parse_errors,
        },
        "ide": {
            "ideaLogs": len(idea_logs),
            "errorCaptures": len(captures),
            "markflowErrorCaptures": len(markflow_captures),
            "platformOnlyErrorCaptures": len(captures) - len(markflow_captures),
            "markflowLifecycleExceptions": correlated,
            "platformOnlyLifecycleExceptions": platform_only_lifecycle,
            "markflowErrorWindows": markflow_error_windows,
            "captures": captures,
        },
    }


def _write_required_suites(root: Path) -> None:
    result_dir = root / "build/test-results/integrationTest"
    result_dir.mkdir(parents=True, exist_ok=True)
    for suite in REQUIRED_SUITES:
        (result_dir / f"TEST-{suite}.xml").write_text(
            f'<testsuite name="{suite}" tests="1" failures="0" errors="0" skipped="0"></testsuite>',
            encoding="utf-8",
        )


def self_test() -> None:
    with tempfile.TemporaryDirectory(prefix="markflow-starter-diagnostics-") as temp:
        root = Path(temp)
        _write_required_suites(root)
        log_dir = root / "out/ide-tests/tests/IU-fixture/platform/log"
        log_dir.mkdir(parents=True)
        (log_dir / "idea.log").write_text(
            "2026-01-01 ERROR platform\njava.util.ConcurrentModificationException\n"
            "at com.intellij.ui.tree.StructureTreeModel.invalidate(StructureTreeModel.java:1)\n",
            encoding="utf-8",
        )
        error_dir = log_dir / "errors/error-1"
        error_dir.mkdir(parents=True)
        (error_dir / "message.txt").write_text("java.util.ConcurrentModificationException\n", encoding="utf-8")
        (error_dir / "stacktrace.txt").write_text(
            "java.util.ConcurrentModificationException\n"
            "at com.intellij.ui.tree.StructureTreeModel.invalidate(StructureTreeModel.java:1)\n",
            encoding="utf-8",
        )
        baseline = analyze(root)
        assert baseline["verdict"] == "PASS", baseline
        assert baseline["ide"]["platformOnlyErrorCaptures"] == 1, baseline

        (error_dir / "stacktrace.txt").write_text(
            "java.lang.IllegalStateException\n"
            "at com.algorist.markflow.editor.native.NativePresentationController.refresh(NativePresentationController.kt:1)\n",
            encoding="utf-8",
        )
        failing = analyze(root)
        assert failing["verdict"] == "FAIL", failing
        assert failing["ide"]["markflowErrorCaptures"] == 1, failing

    print("starter-driver diagnostics self-test passed")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    parser.add_argument("--output")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return 0

    root = Path(args.root).resolve()
    result = analyze(root)
    payload = json.dumps(result, indent=2, sort_keys=True)
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(payload + "\n", encoding="utf-8")
    print(payload)

    if result["verdict"] != "PASS":
        print("Starter/Driver diagnostics gate failed: " + "; ".join(result["reasons"]), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
