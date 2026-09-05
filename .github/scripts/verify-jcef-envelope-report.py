#!/usr/bin/env python3
import json
import pathlib
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: verify-jcef-envelope-report.py <report.json>")

path = pathlib.Path(sys.argv[1])
if not path.is_file():
    raise SystemExit(f"missing JCEF envelope report: {path}")

report = json.loads(path.read_text(encoding="utf-8"))
if report.get("schema") != 1:
    raise SystemExit(f"unexpected report schema: {report.get('schema')!r}")

cases = report.get("cases")
if not isinstance(cases, list) or not cases:
    raise SystemExit("report contains no probe cases")

required = [case for case in cases if case.get("required") is True]
if not required:
    raise SystemExit("report contains no required probe cases")

failed = [case for case in required if case.get("status") != "PASS"]
if failed:
    print(json.dumps(failed, ensure_ascii=False, indent=2))
    raise SystemExit(f"{len(failed)} required JCEF envelope case(s) did not PASS")

conclusion = str(report.get("conclusion", ""))
if conclusion.startswith("BLOCKED"):
    raise SystemExit(f"probe conclusion is blocked: {conclusion}")

print(
    f"JCEF envelope evidence verified: ide={report.get('ideBuild')} "
    f"os={report.get('osName')} required_cases={len(required)} conclusion={conclusion}"
)
