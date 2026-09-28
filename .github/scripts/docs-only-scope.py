#!/usr/bin/env python3
"""Fail-closed classifier for MarkFlow's docs-only CI fast path."""

from __future__ import annotations

import argparse
import sys
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


def is_allowed_path(path: str) -> bool:
    if not path or path.startswith("/") or "\\" in path:
        return False

    parts = PurePosixPath(path).parts
    if not parts or any(part in {"", ".", ".."} for part in parts):
        return False

    if path in ROOT_DOCS:
        return True

    return path.endswith(".md") and any(path.startswith(prefix) for prefix in DOC_PREFIXES)


def classify(paths: list[str], expected_count: int) -> bool:
    if expected_count <= 0 or not paths:
        return False
    if len(paths) != expected_count:
        return False
    if len(set(paths)) != len(paths):
        return False
    return all(is_allowed_path(path) for path in paths)


def self_test() -> None:
    positive = [
        ["README.md"],
        ["AGENTS.md", "GOVERNANCE.md"],
        ["docs/architecture/overview.md"],
        ["docs/release/recovery.md", "plans/001-native-editor.md"],
    ]
    negative = [
        [],
        [".github/pull_request_template.md"],
        ["examples/demo.md"],
        ["fixtures/sample.md"],
        ["src/test/resources/sample.md"],
        ["webview/README.md"],
        ["docs/architecture/diagram.yml"],
        ["README.md", "src/main/kotlin/App.kt"],
        ["../README.md"],
        ["docs/../README.md"],
        ["docs/readme.MD"],
    ]

    for paths in positive:
        assert classify(paths, len(paths)), paths
    for paths in negative:
        assert not classify(paths, len(paths)), paths

    assert not classify(["README.md"], 2)
    assert not classify(["README.md", "README.md"], 2)


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

    paths = [line.rstrip("\n") for line in sys.stdin]
    print("true" if classify(paths, args.expected_count) else "false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
