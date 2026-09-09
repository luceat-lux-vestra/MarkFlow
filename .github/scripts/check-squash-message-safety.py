#!/usr/bin/env python3
"""Reject GitHub Actions skip directives that would enter a squash commit message."""

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


def findings(title: str, body: str) -> list[str]:
    message = f"{title}\n\n{body}"
    return [directive.name for directive in DIRECTIVES if directive.pattern.search(message)]


def validate(title: str, body: str) -> int:
    detected = findings(title, body)
    if not detected:
        print("Squash commit metadata safety check passed.")
        return 0

    print(
        "::error::PR title/body contains GitHub Actions skip directive(s) that could "
        "suppress post-main validation when copied into a squash commit: "
        + ", ".join(detected)
    )
    print(
        "::error::Remove or neutralize the directive in PR metadata, or use an explicitly "
        "sanitized squash commit message after the required checks pass."
    )
    return 1


def self_test() -> int:
    unsafe = (
        ("title [skip ci]", ""),
        ("title", "release [ci skip] notes"),
        ("title", "[NO CI]"),
        ("title", "upstream [skip actions] marker"),
        ("title", "upstream [actions skip] marker"),
        ("title", "notes\n\nskip-checks:true"),
        ("title", "notes\n\nskip-checks: true"),
        ("title", "notes\n\nSKIP-CHECKS : TRUE"),
    )
    safe = (
        ("title", ""),
        ("title", "[ci  skip]"),
        ("title", "skip-checks: false"),
        ("title", "skip checks: true"),
        ("title", "the words ci skip without brackets"),
    )

    for title, body in unsafe:
        if not findings(title, body):
            print(f"self-test failed: unsafe fixture was accepted: {title!r} / {body!r}", file=sys.stderr)
            return 1
    for title, body in safe:
        if findings(title, body):
            print(f"self-test failed: safe fixture was rejected: {title!r} / {body!r}", file=sys.stderr)
            return 1

    print(f"Squash message safety self-test passed ({len(unsafe)} unsafe, {len(safe)} safe fixtures).")
    return 0


def main() -> int:
    if sys.argv[1:] == ["--self-test"]:
        return self_test()
    if sys.argv[1:]:
        print("usage: check-squash-message-safety.py [--self-test]", file=sys.stderr)
        return 2
    return validate(os.environ.get("PR_TITLE", ""), os.environ.get("PR_BODY", ""))


if __name__ == "__main__":
    raise SystemExit(main())
