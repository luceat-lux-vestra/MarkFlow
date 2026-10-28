#!/usr/bin/env python3
"""Reject GitHub Actions skip directives that would enter the squash commit title."""

from __future__ import annotations

import os
import re
import sys
from dataclasses import dataclass


@dataclass(frozen=True)
class Directive:
    name: str
    pattern: re.Pattern[str]


DIRECTIVES = (
    Directive("skip-ci", re.compile(r"\[skip ci\]", re.IGNORECASE)),
    Directive("ci-skip", re.compile(r"\[ci skip\]", re.IGNORECASE)),
    Directive("no-ci", re.compile(r"\[no ci\]", re.IGNORECASE)),
    Directive("skip-actions", re.compile(r"\[skip actions\]", re.IGNORECASE)),
    Directive("actions-skip", re.compile(r"\[actions skip\]", re.IGNORECASE)),
    Directive(
        "skip-checks-trailer",
        re.compile(r"(?im)^[ \t]*skip-checks[ \t]*:[ \t]*true[ \t]*$"),
    ),
)


def findings(title: str) -> list[str]:
    return [directive.name for directive in DIRECTIVES if directive.pattern.search(title)]


def validate(title: str) -> int:
    detected = findings(title)
    if not detected:
        print("Squash commit metadata safety check passed.")
        return 0

    print(
        "::error::PR title contains GitHub Actions skip directive(s) that could "
        "suppress default-branch workflows when copied into the squash commit title: "
        + ", ".join(detected)
    )
    print(
        "::error::Remove or neutralize the directive in the PR title. The configured "
        "squash commit body is blank and PR body text is not commit-message authority."
    )
    return 1


def self_test() -> int:
    unsafe = (
        "title [skip ci]",
        "release [ci skip] notes",
        "[NO CI]",
        "upstream [skip actions] marker",
        "upstream [actions skip] marker",
        "skip-checks:true",
        "skip-checks: true",
        "SKIP-CHECKS : TRUE",
    )
    safe = (
        "title",
        "[ci  skip]",
        "skip-checks: false",
        "skip checks: true",
        "the words ci skip without brackets",
    )

    for title in unsafe:
        if not findings(title):
            print(f"self-test failed: unsafe fixture was accepted: {title!r}", file=sys.stderr)
            return 1
    for title in safe:
        if findings(title):
            print(f"self-test failed: safe fixture was rejected: {title!r}", file=sys.stderr)
            return 1

    print(f"Squash message safety self-test passed ({len(unsafe)} unsafe, {len(safe)} safe fixtures).")
    return 0


def main() -> int:
    if sys.argv[1:] == ["--self-test"]:
        return self_test()
    if sys.argv[1:]:
        print("usage: check-squash-message-safety.py [--self-test]", file=sys.stderr)
        return 2
    return validate(os.environ.get("PR_TITLE", ""))


if __name__ == "__main__":
    raise SystemExit(main())
