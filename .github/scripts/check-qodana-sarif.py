#!/usr/bin/env python3
"""Fail-closed verifier for MarkFlow Qodana SARIF evidence.

MarkFlow's required PR gate permits lower-severity findings but rejects every
High/Critical Qodana result. Reusable exact-SHA evidence must preserve the same
contract: valid SARIF 2.1.0, structurally valid results, and zero High/Critical
findings. Scanner exit status alone is not sufficient evidence.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import tempfile
from pathlib import Path
from typing import Any


BLOCKED = {"High", "Critical"}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def check(path: Path) -> dict[str, Any]:
    require(path.is_file(), "Qodana SARIF is missing")
    require(path.stat().st_size > 0, "Qodana SARIF is empty")
    try:
        raw = path.read_bytes()
        payload = json.loads(raw.decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError("Qodana SARIF is unreadable or malformed") from exc

    require(isinstance(payload, dict), "Qodana SARIF root must be an object")
    require(payload.get("version") == "2.1.0", "Qodana SARIF must use SARIF 2.1.0")
    runs = payload.get("runs")
    require(isinstance(runs, list) and runs, "Qodana SARIF must contain at least one run")

    result_count = 0
    high_critical = 0
    severity_counts: dict[str, int] = {}
    for index, run in enumerate(runs):
        require(isinstance(run, dict), f"Qodana SARIF run {index} must be an object")
        results = run.get("results", [])
        require(isinstance(results, list), f"Qodana SARIF run {index} results must be an array")
        for result in results:
            require(isinstance(result, dict), "Qodana SARIF result must be an object")
            properties = result.get("properties", {})
            require(isinstance(properties, dict), "Qodana SARIF result properties must be an object")
            severity = properties.get("qodanaSeverity")
            if severity is not None:
                require(isinstance(severity, str) and severity, "qodanaSeverity must be a non-empty string")
                severity_counts[severity] = severity_counts.get(severity, 0) + 1
                if severity in BLOCKED:
                    high_critical += 1
            result_count += 1

    require(
        high_critical == 0,
        f"Qodana SARIF contains {high_critical} High/Critical finding(s)",
    )
    return {
        "sarif_version": "2.1.0",
        "runs": len(runs),
        "results": result_count,
        "high_critical": high_critical,
        "severity_counts": dict(sorted(severity_counts.items())),
        "sarif_sha256": hashlib.sha256(raw).hexdigest(),
    }


def self_test() -> None:
    good = {
        "version": "2.1.0",
        "runs": [{
            "tool": {"driver": {"name": "Qodana"}},
            "results": [
                {"ruleId": "LowExample", "properties": {"qodanaSeverity": "Low"}},
                {"ruleId": "ModerateExample", "properties": {"qodanaSeverity": "Moderate"}},
            ],
        }],
    }
    high = {
        "version": "2.1.0",
        "runs": [{"results": [{"ruleId": "HighExample", "properties": {"qodanaSeverity": "High"}}]}],
    }
    critical = {
        "version": "2.1.0",
        "runs": [{"results": [{"ruleId": "CriticalExample", "properties": {"qodanaSeverity": "Critical"}}]}],
    }
    cases = [
        ("good", good, True),
        ("high", high, False),
        ("critical", critical, False),
        ("no-runs", {"version": "2.1.0", "runs": []}, False),
        ("wrong-version", {"version": "2.0.0", "runs": [{"results": []}]}, False),
        ("non-array-results", {"version": "2.1.0", "runs": [{"results": {}}]}, False),
        ("bad-properties", {"version": "2.1.0", "runs": [{"results": [{"properties": []}]}]}, False),
    ]
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        for name, payload, should_pass in cases:
            path = root / f"{name}.sarif.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            passed = True
            try:
                check(path)
            except ValueError:
                passed = False
            if passed != should_pass:
                raise AssertionError(f"self-test case {name} expected pass={should_pass}, got {passed}")

        malformed = root / "malformed.sarif.json"
        malformed.write_text("{", encoding="utf-8")
        try:
            check(malformed)
        except ValueError:
            pass
        else:
            raise AssertionError("malformed SARIF must fail")

        try:
            check(root / "missing.sarif.json")
        except ValueError:
            pass
        else:
            raise AssertionError("missing SARIF must fail")

    print("MarkFlow Qodana SARIF self-test passed.")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sarif-file", type=Path)
    parser.add_argument("--verdict-file", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return 0
    if args.sarif_file is None:
        parser.error("--sarif-file is required unless --self-test is used")

    verdict = check(args.sarif_file)
    encoded = json.dumps(verdict, sort_keys=True)
    print(encoded)
    if args.verdict_file is not None:
        args.verdict_file.write_text(encoded + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
