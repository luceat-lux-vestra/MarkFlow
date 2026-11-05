#!/usr/bin/env python3
"""Trusted-base shadow classifier for future MarkFlow PR-time Qodana routing.

Stage C is observational only. UNKNOWN and unclassified input fail closed to
qodana_required=true. Production Qodana/Inspect-code routing must not consume
this output until a separately approved Stage D.
"""
from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import PurePosixPath

ROOT_DOCS = frozenset(
    {
        "AGENTS.md",
        "CHANGELOG.md",
        "CONTRIBUTING.md",
        "GOVERNANCE.md",
        "README.md",
        "SECURITY.md",
    }
)
DOC_PREFIXES = ("docs/", "plans/")
SIMPLE_STATUSES = frozenset({"added", "modified", "removed", "changed"})
MOVE_STATUSES = frozenset({"renamed", "copied"})

QODANA_CONTROL_PATHS = frozenset(
    {
        "qodana.yml",
        ".github/workflows/build.yml",
        ".github/workflows/qodana-full.yml",
        ".github/workflows/release.yml",
        ".github/scripts/check-qodana-sarif.py",
        ".github/scripts/release-qodana-gate.py",
        ".github/scripts/pr-qodana-shadow-scope.py",
        ".github/scripts/repository-hardening-contract.sh",
        ".github/scripts/hardening-audit.sh",
        ".github/scripts/hardening-audit-test.sh",
    }
)
BUILD_ROOT_FILES = frozenset(
    {
        "build.gradle",
        "build.gradle.kts",
        "settings.gradle",
        "settings.gradle.kts",
        "gradle.properties",
        "gradlew",
        "gradlew.bat",
    }
)
JVM_SUFFIXES = (".kt", ".kts", ".java", ".groovy")


@dataclass(frozen=True)
class Change:
    status: str
    filename: str
    previous_filename: str = ""


def valid_path(path: str) -> bool:
    if not path or path.startswith("/") or "\\" in path:
        return False
    parts = PurePosixPath(path).parts
    return bool(parts) and not any(part in {"", ".", ".."} for part in parts)


def docs_safe(path: str) -> bool:
    if path in ROOT_DOCS:
        return True
    return path.endswith(".md") and any(path.startswith(prefix) for prefix in DOC_PREFIXES)


def github_metadata_safe(path: str) -> bool:
    if path == ".github/pull_request_template.md":
        return True
    if path.startswith(".github/PULL_REQUEST_TEMPLATE/"):
        return path.endswith(".md")
    if path.startswith(".github/ISSUE_TEMPLATE/"):
        return path.endswith((".md", ".yml", ".yaml"))
    return False


def qodana_relevant(path: str) -> bool:
    if path in QODANA_CONTROL_PATHS or path in BUILD_ROOT_FILES:
        return True
    if path.startswith(("src/", "buildSrc/", "gradle/")):
        return True
    return path.endswith(JVM_SUFFIXES)


def explicitly_non_qodana(path: str) -> bool:
    return docs_safe(path) or github_metadata_safe(path)


def parse_record(line: str) -> Change | None:
    fields = line.rstrip("\n").split("\t")
    if len(fields) != 3:
        return None
    status, filename, previous_filename = fields
    if not status or not filename:
        return None
    return Change(status=status, filename=filename, previous_filename=previous_filename)


def classify(changes: list[Change], expected_count: int | None) -> tuple[bool, str, int]:
    count = len(changes)
    if expected_count is None or expected_count <= 0:
        return True, "unknown:changed-file-count-invalid", count
    if count != expected_count:
        return True, "unknown:changed-file-count-mismatch", count
    if not changes:
        return True, "unknown:empty-change-set", count

    seen: set[str] = set()
    paths: list[str] = []
    for change in changes:
        if change.status not in SIMPLE_STATUSES | MOVE_STATUSES:
            return True, "unknown:unsupported-status", count
        if not valid_path(change.filename):
            return True, "unknown:unsafe-path", count
        if change.filename in seen:
            return True, "unknown:duplicate-path", count
        seen.add(change.filename)

        if change.status in MOVE_STATUSES:
            if not valid_path(change.previous_filename):
                return True, "unknown:move-without-safe-previous-path", count
            paths.append(change.previous_filename)
        elif change.previous_filename:
            return True, "unknown:unexpected-previous-path", count

        paths.append(change.filename)

    for path in paths:
        if qodana_relevant(path):
            return True, f"qodana-relevant:{path}", count

    for path in paths:
        if not explicitly_non_qodana(path):
            return True, f"unclassified-path:{path}", count

    return False, "all-paths-proven-non-qodana", count


def self_test() -> None:
    def required(*changes: Change, expected: int | None = None) -> None:
        exp = len(changes) if expected is None else expected
        assert classify(list(changes), exp)[0], changes

    def safe(*changes: Change) -> None:
        result = classify(list(changes), len(changes))
        assert result == (False, "all-paths-proven-non-qodana", len(changes)), (changes, result)

    for path in (
        "src/main/kotlin/App.kt",
        "src/test/kotlin/AppTest.kt",
        "build.gradle.kts",
        "settings.gradle.kts",
        "gradle.properties",
        "gradle/libs.versions.toml",
        "gradle/wrapper/gradle-wrapper.properties",
        "qodana.yml",
        ".github/workflows/build.yml",
        ".github/workflows/qodana-full.yml",
        ".github/workflows/release.yml",
        ".github/scripts/check-qodana-sarif.py",
        ".github/scripts/release-qodana-gate.py",
        ".github/scripts/pr-qodana-shadow-scope.py",
        ".github/scripts/repository-hardening-contract.sh",
    ):
        required(Change("modified", path))

    required(Change("modified", "README.md"), Change("modified", "src/main/kotlin/App.kt"))
    required(Change("renamed", "README.md", "src/main/kotlin/Old.kt"))
    required(Change("renamed", "src/main/kotlin/New.kt", "docs/old.md"))

    safe(Change("modified", "README.md"))
    safe(Change("added", "docs/architecture/qodana-shadow.md"))
    safe(Change("renamed", "docs/new.md", "docs/old.md"))
    safe(Change("modified", ".github/pull_request_template.md"))
    safe(Change("added", ".github/ISSUE_TEMPLATE/bug.yml"))
    safe(Change("renamed", ".github/PULL_REQUEST_TEMPLATE/new.md", ".github/PULL_REQUEST_TEMPLATE/old.md"))

    required(expected=1)
    required(Change("modified", "README.md"), expected=2)
    required(Change("modified", "README.md"), Change("modified", "README.md"))
    required(Change("mystery", "README.md"))
    required(Change("renamed", "docs/new.md", ""))
    required(Change("modified", "/README.md"))
    required(Change("modified", "docs/../README.md"))
    required(Change("modified", "webview/package.json"))
    required(Change("modified", ".github/workflows/codeql.yml"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--expected-count")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        print("Qodana shadow scope self-test: PASS")
        return 0

    rows = sys.stdin.read().splitlines()
    changes: list[Change] = []
    malformed = False
    for row in rows:
        change = parse_record(row)
        if change is None:
            malformed = True
            break
        changes.append(change)

    try:
        expected = int(args.expected_count) if args.expected_count is not None else None
    except ValueError:
        expected = None

    if malformed:
        decision = (True, "unknown:malformed-change-record", len(rows))
    else:
        decision = classify(changes, expected)

    required_value, reason, changed_count = decision
    print(f"qodana_required={str(required_value).lower()}")
    print(f"reason={reason}")
    print(f"changed_count={changed_count}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
