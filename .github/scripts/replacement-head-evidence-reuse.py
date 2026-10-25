#!/usr/bin/env python3
"""Fail-closed classifier for #467 replacement-HEAD product-evidence reuse.

This classifier deliberately does *not* claim that two commits are globally
semantically equivalent. It answers the narrower question needed by #467:

    Can prior full-product evidence remain applicable when the replacement
    HEAD changes only files that are outside the product-validation graph?

The initial supported correction class is intentionally small: modifications
confined to hardening-only scripts that are executed by Hardening Audit, not
by Build/Test/Qodana/Plugin Verifier/Native/Starter product evidence.

The replacement HEAD must still rerun its hardening authority. Any malformed,
mixed, renamed, added, removed, duplicated, unsafe, or unknown change falls
back to FULL CI.
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import PurePosixPath


HARDENING_ONLY_PATHS = frozenset(
    {
        ".github/scripts/repository-hardening-contract.sh",
        ".github/scripts/hardening-audit.sh",
        ".github/scripts/check-gradle-cache-seed.sh",
    }
)
SUPPORTED_STATUS = "modified"


@dataclass(frozen=True)
class Change:
    status: str
    filename: str
    previous_filename: str = ""


@dataclass(frozen=True)
class Decision:
    reuse_product_evidence: bool
    rerun_authority: str
    reason: str


def safe_path(path: str) -> bool:
    if not path or path.startswith("/") or "\\" in path:
        return False
    parts = PurePosixPath(path).parts
    return bool(parts) and all(part not in {"", ".", ".."} for part in parts)


def parse_record(line: str) -> Change | None:
    fields = line.rstrip("\n").split("\t")
    if len(fields) != 3:
        return None
    status, filename, previous_filename = fields
    if not status or not filename:
        return None
    return Change(status, filename, previous_filename)


def classify(changes: list[Change], expected_count: int) -> Decision:
    if expected_count <= 0 or len(changes) != expected_count:
        return Decision(False, "full", "changed-file-count-invalid")
    if not changes:
        return Decision(False, "full", "empty-change-set")
    if len(set(changes)) != len(changes):
        return Decision(False, "full", "duplicate-change-record")

    seen_paths: set[str] = set()
    for change in changes:
        if (
            change.status != SUPPORTED_STATUS
            or change.previous_filename
            or not safe_path(change.filename)
        ):
            return Decision(False, "full", "unsupported-or-unsafe-change")
        if change.filename in seen_paths:
            return Decision(False, "full", "duplicate-path")
        seen_paths.add(change.filename)
        if change.filename not in HARDENING_ONLY_PATHS:
            return Decision(False, "full", "product-or-routing-impact")

    return Decision(True, "hardening", "hardening-only-correction")


def self_test() -> None:
    for path in HARDENING_ONLY_PATHS:
        assert classify([Change("modified", path)], 1) == Decision(
            True, "hardening", "hardening-only-correction"
        )

    all_allowed = [Change("modified", path) for path in sorted(HARDENING_ONLY_PATHS)]
    assert classify(all_allowed, len(all_allowed)).reuse_product_evidence

    negatives = [
        [],
        [Change("modified", ".github/workflows/build.yml")],
        [Change("modified", ".github/workflows/hardening-audit.yml")],
        [Change("modified", ".github/scripts/replacement-head-evidence-reuse.py")],
        [Change("modified", "src/main/kotlin/App.kt")],
        [
            Change("modified", ".github/scripts/repository-hardening-contract.sh"),
            Change("modified", "src/main/kotlin/App.kt"),
        ],
        [Change("added", ".github/scripts/repository-hardening-contract.sh")],
        [Change("removed", ".github/scripts/repository-hardening-contract.sh")],
        [
            Change(
                "renamed",
                ".github/scripts/repository-hardening-contract.sh",
                ".github/scripts/old-hardening-contract.sh",
            )
        ],
        [
            Change(
                "modified",
                ".github/scripts/repository-hardening-contract.sh",
                ".github/scripts/old-hardening-contract.sh",
            )
        ],
        [Change("changed", ".github/scripts/repository-hardening-contract.sh")],
        [Change("modified", "../repository-hardening-contract.sh")],
        [Change("modified", "/.github/scripts/repository-hardening-contract.sh")],
    ]
    for changes in negatives:
        assert not classify(changes, max(1, len(changes))).reuse_product_evidence, changes

    one = [Change("modified", ".github/scripts/repository-hardening-contract.sh")]
    assert not classify(one, 2).reuse_product_evidence
    assert not classify(one + one, 2).reuse_product_evidence
    assert not classify(one, 0).reuse_product_evidence

    assert parse_record(
        "modified\t.github/scripts/repository-hardening-contract.sh\t\n"
    ) == Change("modified", ".github/scripts/repository-hardening-contract.sh")
    assert parse_record("broken\n") is None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--expected-count", type=int)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        print("replacement-head evidence-reuse fixtures: PASS")
        return 0

    if args.expected_count is None:
        parser.error("--expected-count is required unless --self-test is used")

    changes: list[Change] = []
    for line in sys.stdin:
        change = parse_record(line)
        if change is None:
            decision = Decision(False, "full", "changed-file-record-invalid")
            break
        changes.append(change)
    else:
        decision = classify(changes, args.expected_count)

    print(f"reuse_product_evidence={str(decision.reuse_product_evidence).lower()}")
    print(f"rerun_authority={decision.rerun_authority}")
    print(f"reason={decision.reason}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
