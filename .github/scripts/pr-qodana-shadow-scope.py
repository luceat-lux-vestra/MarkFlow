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
CANDIDATE_STATUSES = frozenset({"modified", "changed"})
WEBVIEW_CANDIDATE_FAMILY = "webview-renderer-ts-test"
WEBVIEW_RENDERER_PREFIX = "webview/src/renderer/"
WEBVIEW_TEST_PREFIX = "webview/tests/"
# R2A #104 E3 exact-pair evidence. Hardening pins these digests so a future
# evidence change must be an explicit policy review rather than silent drift.
WEBVIEW_CANDIDATE_RESULT_MULTISET_SHA256 = (
    "6ec345939dd269216719b2914a605f6c88ca957caa26978b32edd4e767f0c6be"
)
WEBVIEW_CANDIDATE_FULL_SARIF_SHA256 = (
    "797629df3664819f5a09f9d07bb06db90e257029a7719e625c8e416d39764691"
)


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


def webview_candidate_evidence_available() -> bool:
    return bool(
        WEBVIEW_CANDIDATE_RESULT_MULTISET_SHA256
        and WEBVIEW_CANDIDATE_FULL_SARIF_SHA256
    )


def webview_renderer_candidate(path: str) -> bool:
    if (
        not path.startswith(WEBVIEW_RENDERER_PREFIX)
        or not path.endswith(".ts")
        or path.endswith(".d.ts")
    ):
        return False
    relative = path[len(WEBVIEW_RENDERER_PREFIX) :]
    parts = PurePosixPath(relative).parts
    if not parts or "generated" in parts:
        return False
    return not parts[-1].endswith((".generated.ts", ".gen.ts"))


def webview_test_candidate(path: str) -> bool:
    return path.startswith(WEBVIEW_TEST_PREFIX) and path.endswith(".test.mjs")


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


def classify_candidate(
    changes: list[Change],
    expected_count: int | None,
    *,
    evidence_available: bool = True,
) -> tuple[bool, str, str, int]:
    count = len(changes)
    if not evidence_available:
        return False, "none", "unknown:paired-evidence-unavailable", count
    if expected_count is None or expected_count <= 0:
        return False, "none", "unknown:changed-file-count-invalid", count
    if count != expected_count:
        return False, "none", "unknown:changed-file-count-mismatch", count
    if not changes:
        return False, "none", "unknown:empty-change-set", count

    seen: set[str] = set()
    renderer_sources = 0
    for change in changes:
        if change.status not in CANDIDATE_STATUSES:
            return False, "none", "not-candidate:unsupported-status", count
        if not valid_path(change.filename):
            return False, "none", "not-candidate:unsafe-path", count
        if change.filename in seen:
            return False, "none", "not-candidate:duplicate-path", count
        seen.add(change.filename)
        if change.previous_filename:
            return False, "none", "not-candidate:unexpected-previous-path", count

        if webview_renderer_candidate(change.filename):
            renderer_sources += 1
        elif webview_test_candidate(change.filename):
            pass
        else:
            return (
                False,
                "none",
                f"not-candidate:out-of-family:{change.filename}",
                count,
            )

    if renderer_sources == 0:
        return False, "none", "not-candidate:no-renderer-source", count

    return (
        True,
        WEBVIEW_CANDIDATE_FAMILY,
        "candidate:webview-renderer-ts-test",
        count,
    )


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

    def candidate_accepted(*changes: Change) -> None:
        result = classify_candidate(
            list(changes),
            len(changes),
            evidence_available=webview_candidate_evidence_available(),
        )
        assert result == (
            True,
            WEBVIEW_CANDIDATE_FAMILY,
            "candidate:webview-renderer-ts-test",
            len(changes),
        ), (changes, result)

    candidate_negative_count = 0

    def candidate_rejected(
        *changes: Change,
        expected: int | None = -1,
        evidence_available: bool = True,
    ) -> None:
        nonlocal candidate_negative_count
        candidate_negative_count += 1
        exp = len(changes) if expected == -1 else expected
        result = classify_candidate(
            list(changes),
            exp,
            evidence_available=evidence_available,
        )
        assert not result[0] and result[1] == "none", (changes, result)

    candidate_accepted(
        Change("modified", "webview/src/renderer/derived-renderer-bootstrap.ts"),
        Change("modified", "webview/tests/native-renderer-capture-architecture.test.mjs"),
    )
    candidate_accepted(Change("modified", "webview/src/renderer/derived-renderer-bootstrap.ts"))
    candidate_accepted(
        Change("changed", "webview/src/renderer/derived-renderer-bootstrap.ts"),
        Change("modified", "webview/tests/color.test.mjs"),
        Change("modified", "webview/tests/runtime-settings.test.mjs"),
    )

    candidate_rejected(Change("modified", "webview/tests/color.test.mjs"))
    candidate_rejected(Change("modified", "src/main/kotlin/App.kt"))
    candidate_rejected(Change("modified", "build.gradle.kts"))
    candidate_rejected(Change("modified", "qodana.yml"))
    candidate_rejected(Change("modified", ".github/workflows/build.yml"))
    candidate_rejected(Change("modified", ".github/workflows/qodana-full.yml"))
    candidate_rejected(Change("modified", "webview/package.json"))
    candidate_rejected(Change("modified", "webview/package-lock.json"))
    candidate_rejected(Change("modified", "webview/src/renderer/derived-renderer-bootstrap.ts"), Change("modified", "src/main/kotlin/App.kt"))
    candidate_rejected(Change("modified", "webview/src/renderer/derived-renderer-bootstrap.ts"), Change("modified", "build.gradle.kts"))
    candidate_rejected(Change("modified", "webview/src/renderer/derived-renderer-bootstrap.ts"), Change("modified", "webview/package-lock.json"))
    candidate_rejected(Change("modified", "webview/src/app/color.ts"))
    candidate_rejected(Change("modified", "webview/tests/mermaid-browser-acceptance.html"))
    candidate_rejected(Change("modified", "webview/tests/typescript-cli.mjs"))
    candidate_rejected(Change("modified", "webview/src/vite-env.d.ts"))
    candidate_rejected(Change("modified", "webview/src/renderer/contracts.d.ts"))
    candidate_rejected(Change("modified", "webview/src/renderer/generated/output.ts"))
    candidate_rejected(Change("modified", "webview/src/renderer/output.generated.ts"))
    candidate_rejected(Change("added", "webview/src/renderer/new.ts"))
    candidate_rejected(Change("removed", "webview/src/renderer/old.ts"))
    candidate_rejected(Change("renamed", "webview/src/renderer/new.ts", "webview/src/renderer/old.ts"))
    candidate_rejected(Change("copied", "webview/src/renderer/new.ts", "webview/src/renderer/old.ts"))
    candidate_rejected(Change("renamed", "webview/src/renderer/new.ts", "src/main/kotlin/App.kt"))
    candidate_rejected(Change("modified", "webview/src/renderer/derived-renderer-bootstrap.ts", "webview/src/renderer/old.ts"))
    candidate_rejected(Change("mystery", "webview/src/renderer/derived-renderer-bootstrap.ts"))
    candidate_rejected(Change("modified", "/webview/src/renderer/derived-renderer-bootstrap.ts"))
    candidate_rejected(Change("modified", "webview/src/renderer/../renderer/derived-renderer-bootstrap.ts"))
    candidate_rejected(Change("modified", r"webview\src\renderer\derived-renderer-bootstrap.ts"))
    candidate_rejected(
        Change("modified", "webview/src/renderer/derived-renderer-bootstrap.ts"),
        Change("modified", "webview/src/renderer/derived-renderer-bootstrap.ts"),
    )
    candidate_rejected(Change("modified", "webview/src/renderer/derived-renderer-bootstrap.ts"), expected=2)
    candidate_rejected(Change("modified", "webview/src/renderer/derived-renderer-bootstrap.ts"), expected=None)
    candidate_rejected(expected=1)
    candidate_rejected(Change("modified", "README.md"))
    candidate_rejected(
        Change("modified", "webview/src/renderer/derived-renderer-bootstrap.ts"),
        evidence_available=False,
    )
    assert candidate_negative_count == 34, candidate_negative_count


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
        candidate_decision = (False, "none", "unknown:malformed-change-record", len(rows))
    else:
        decision = classify(changes, expected)
        candidate_decision = classify_candidate(
            changes,
            expected,
            evidence_available=webview_candidate_evidence_available(),
        )

    required_value, reason, changed_count = decision
    candidate, candidate_family, candidate_reason, candidate_count = candidate_decision
    print(f"qodana_required={str(required_value).lower()}")
    print(f"reason={reason}")
    print(f"changed_count={changed_count}")
    print(f"qodana_shadow_candidate={str(candidate).lower()}")
    print(f"qodana_shadow_candidate_family={candidate_family}")
    print(f"qodana_shadow_candidate_reason={candidate_reason}")
    print(f"qodana_shadow_candidate_changed_count={candidate_count}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
