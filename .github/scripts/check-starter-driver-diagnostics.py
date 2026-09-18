#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
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
MARKFLOW_FRAME = re.compile(r"(?m)^\s*at\s+com\.algorist\.markflow\.")
LOG_RECORD_START = re.compile(r"^\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2},\d{3}\s+\[")
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


def _has_markflow_frame(text: str) -> bool:
    return MARKFLOW_FRAME.search(text) is not None


def _log_records(lines: list[str]) -> list[tuple[int, list[str]]]:
    records: list[tuple[int, list[str]]] = []
    start: int | None = None
    current: list[str] = []

    for index, line in enumerate(lines):
        if LOG_RECORD_START.match(line):
            if current and start is not None:
                records.append((start, current))
            start = index
            current = [line]
        elif current:
            current.append(line)

    if current and start is not None:
        records.append((start, current))
    return records


def analyze(root: Path) -> dict:
    junit_files = sorted((root / "test-results").glob("TEST-*.xml"))
    discovered = set()
    tests = failures = errors = skipped = 0
    parse_errors: list[str] = []

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

    error_dirs = sorted(root.glob("logs/**/errors/error-*"))
    captures = []
    markflow_captures = []
    for directory in error_dirs:
        message = _read(directory / "message.txt").strip()
        stacktrace = _read(directory / "stacktrace.txt")
        active_test = _read(directory / "activeTestName.txt").strip()
        combined = f"{message}\n{stacktrace}"
        classification = "markflow" if _has_markflow_frame(combined) else "platform-only"
        entry = {
            "path": str(directory.relative_to(root)),
            "classification": classification,
            "activeTest": active_test,
            "message": message[:500],
        }
        captures.append(entry)
        if classification == "markflow":
            markflow_captures.append(entry)

    idea_logs = sorted(root.glob("logs/**/idea.log"))
    correlated = []
    platform_only_lifecycle = []
    markflow_error_windows = []
    markflow_load_failures = []

    for path in idea_logs:
        lines = _read(path).splitlines()
        for record_start, record_lines in _log_records(lines):
            headline = record_lines[0]
            record = "\n".join(record_lines)

            for token in LIFECYCLE_TOKENS:
                for offset, line in enumerate(record_lines):
                    if token not in line:
                        continue
                    item = {
                        "path": str(path.relative_to(root)),
                        "line": record_start + offset + 1,
                        "token": token,
                    }
                    if _has_markflow_frame(record):
                        correlated.append(item)
                    else:
                        platform_only_lifecycle.append(item)

            load_line = next(
                (
                    (offset, line)
                    for offset, line in enumerate(record_lines)
                    if "PluginException" in line or "Cannot load" in line or "NoClassDefFoundError" in line
                ),
                None,
            )
            if load_line is not None and (_has_markflow_frame(record) or MARKFLOW_MARKER in record):
                offset, line = load_line
                markflow_load_failures.append(
                    {
                        "path": str(path.relative_to(root)),
                        "line": record_start + offset + 1,
                        "headline": line[:500],
                    }
                )

            if (" ERROR " in headline or " SEVERE " in headline) and _has_markflow_frame(record):
                markflow_error_windows.append(
                    {
                        "path": str(path.relative_to(root)),
                        "line": record_start + 1,
                        "headline": headline[:500],
                    }
                )

    reasons = []
    if parse_errors:
        reasons.append("JUnit XML parse failure")
    if not junit_files:
        reasons.append("Starter/Driver JUnit evidence missing")
    if missing_suites:
        reasons.append("required Starter/Driver suite missing")
    if failures or errors or skipped:
        reasons.append("Starter/Driver suite not fully passing")
    if not idea_logs:
        reasons.append("IDE logs missing")
    if markflow_captures:
        reasons.append("IDE error capture contains MarkFlow frame")
    if correlated:
        reasons.append("lifecycle exception correlates with MarkFlow frame")
    if markflow_load_failures:
        reasons.append("plugin/class loading failure correlates with MarkFlow frame")
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
            "markflowLoadFailures": markflow_load_failures,
            "markflowErrorWindows": markflow_error_windows,
            "captures": captures,
        },
    }


def _write_required_suites(root: Path, *, skipped_suite: str | None = None) -> None:
    result_dir = root / "test-results"
    result_dir.mkdir(parents=True, exist_ok=True)
    for suite in REQUIRED_SUITES:
        skipped = "1" if suite == skipped_suite else "0"
        (result_dir / f"TEST-{suite}.xml").write_text(
            f'<testsuite name="{suite}" tests="1" failures="0" errors="0" skipped="{skipped}"></testsuite>',
            encoding="utf-8",
        )


def _write_platform_log(root: Path) -> Path:
    log_dir = root / "logs/out/ide-tests/IU-fixture/log"
    log_dir.mkdir(parents=True, exist_ok=True)
    log = log_dir / "idea.log"
    log.write_text(
        "2026-01-01 00:00:00,000 [      1] ERROR - #platform - platform failure\n"
        "java.util.ConcurrentModificationException\n"
        "at com.intellij.ui.tree.StructureTreeModel.invalidate(StructureTreeModel.java:1)\n",
        encoding="utf-8",
    )
    return log


def self_test() -> None:
    with tempfile.TemporaryDirectory(prefix="markflow-starter-diagnostics-") as temp:
        root = Path(temp)
        _write_required_suites(root)
        log = _write_platform_log(root)

        baseline = analyze(root)
        assert baseline["verdict"] == "PASS", baseline
        assert len(baseline["ide"]["platformOnlyLifecycleExceptions"]) == 1, baseline

        log.write_text(
            "2026-01-01 00:00:00,000 [      1] ERROR - #platform - platform failure\n"
            "java.util.ConcurrentModificationException\n"
            "at com.intellij.ui.tree.StructureTreeModel.invalidate(StructureTreeModel.java:1)\n"
            "2026-01-01 00:00:00,001 [      2] WARN - #com.algorist.markflow.lifecycle - optional renderer unavailable\n"
            "java.lang.IllegalStateException\n"
            "at com.algorist.markflow.editor.native.NativePresentationController.refresh(NativePresentationController.kt:1)\n",
            encoding="utf-8",
        )
        adjacent_markflow_log = analyze(root)
        assert adjacent_markflow_log["verdict"] == "PASS", adjacent_markflow_log

        log.write_text(
            "2026-01-01 00:00:00,000 [      1] ERROR - #MarkFlow - MarkFlow failure\n"
            "java.lang.IllegalStateException\n"
            "at com.algorist.markflow.editor.native.NativePresentationController.refresh(NativePresentationController.kt:1)\n",
            encoding="utf-8",
        )
        markflow_failure = analyze(root)
        assert markflow_failure["verdict"] == "FAIL", markflow_failure
        assert markflow_failure["ide"]["markflowErrorWindows"], markflow_failure

    with tempfile.TemporaryDirectory(prefix="markflow-starter-diagnostics-missing-") as temp:
        root = Path(temp)
        _write_required_suites(root)
        _write_platform_log(root)
        missing = root / "test-results" / f"TEST-{sorted(REQUIRED_SUITES)[0]}.xml"
        missing.unlink()
        result = analyze(root)
        assert result["verdict"] == "FAIL", result
        assert result["junit"]["missingSuites"], result

    with tempfile.TemporaryDirectory(prefix="markflow-starter-diagnostics-skip-") as temp:
        root = Path(temp)
        skipped_suite = sorted(REQUIRED_SUITES)[0]
        _write_required_suites(root, skipped_suite=skipped_suite)
        _write_platform_log(root)
        result = analyze(root)
        assert result["verdict"] == "FAIL", result
        assert result["junit"]["skipped"] == 1, result

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
