#!/usr/bin/env python3
"""Fail-closed classifier for MarkFlow's docs-only CI fast path."""

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
SIMPLE_STATUSES = frozenset({"added", "modified", "removed"})
MOVE_STATUSES = frozenset({"renamed", "copied"})


@dataclass(frozen=True)
class Change:
    status: str
    filename: str
    previous_filename: str = ""


def is_allowed_path(path: str) -> bool:
    if not path or path.startswith("/") or "\\" in path:
        return False

    parts = PurePosixPath(path).parts
    if not parts or any(part in {"", ".", ".."} for part in parts):
        return False

    if path in ROOT_DOCS:
        return True

    return path.endswith(".md") and any(path.startswith(prefix) for prefix in DOC_PREFIXES)


def is_docs_only_change(change: Change) -> bool:
    if change.status in SIMPLE_STATUSES:
        return not change.previous_filename and is_allowed_path(change.filename)

    if change.status in MOVE_STATUSES:
        return (
            bool(change.previous_filename)
            and is_allowed_path(change.filename)
            and is_allowed_path(change.previous_filename)
        )

    return False


def classify(changes: list[Change], expected_count: int) -> bool:
    if expected_count <= 0 or not changes:
        return False
    if len(changes) != expected_count:
        return False
    if len(set(changes)) != len(changes):
        return False
    return all(is_docs_only_change(change) for change in changes)


def parse_record(line: str) -> Change | None:
    fields = line.rstrip("\n").split("\t")
    if len(fields) != 3:
        return None
    status, filename, previous_filename = fields
    if not status or not filename:
        return None
    return Change(status=status, filename=filename, previous_filename=previous_filename)


def self_test() -> None:
    positive = [
        [Change("modified", "README.md")],
        [Change("modified", "AGENTS.md"), Change("modified", "GOVERNANCE.md")],
        [Change("added", "docs/architecture/overview.md")],
        [Change("removed", "plans/001-native-editor.md")],
        [Change("renamed", "docs/new-name.md", "docs/old-name.md")],
        [Change("copied", "plans/copy.md", "plans/source.md")],
    ]
    negative = [
        [],
        [Change("modified", ".github/pull_request_template.md")],
        [Change("modified", "examples/demo.md")],
        [Change("modified", "fixtures/sample.md")],
        [Change("modified", "src/test/resources/sample.md")],
        [Change("modified", "webview/README.md")],
        [Change("modified", "docs/architecture/diagram.yml")],
        [Change("modified", "README.md"), Change("modified", "src/main/kotlin/App.kt")],
        [Change("modified", "../README.md")],
        [Change("modified", "docs/../README.md")],
        [Change("modified", "docs/readme.MD")],
        [Change("renamed", "docs/runtime-moved-here.md", "src/main/kotlin/App.kt")],
        [Change("renamed", "docs/new-name.md", "")],
        [Change("copied", "docs/copy.md", "webview/runtime.ts")],
        [Change("changed", "README.md")],
        [Change("modified", "README.md", "docs/old.md")],
    ]

    for changes in positive:
        assert classify(changes, len(changes)), changes
    for changes in negative:
        assert not classify(changes, len(changes)), changes

    one = [Change("modified", "README.md")]
    assert not classify(one, 2)
    assert not classify(one + one, 2)

    assert parse_record("modified\tREADME.md\t\n") == Change("modified", "README.md")
    assert parse_record("renamed\tdocs/new.md\tdocs/old.md\n") == Change(
        "renamed", "docs/new.md", "docs/old.md"
    )
    assert parse_record("broken\n") is None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--expected-count", type=int)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        print("docs-only scope self-test passed")
        return 0

    if args.expected_count is None:
        parser.error("--expected-count is required unless --self-test is used")

    changes: list[Change] = []
    for line in sys.stdin:
        change = parse_record(line)
        if change is None:
            print("false")
            return 0
        changes.append(change)

    print("true" if classify(changes, args.expected_count) else "false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
