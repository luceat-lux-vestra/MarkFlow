"""Filter only explicitly supported runtime-only workflow patch edits.

Action edits remain under each existing adapter. Runner changes retain ARM64;
the Java patch selector is an opt-in addition, not a change of major or trust.
No arbitrary workflow settings or job moves are accepted.
"""
import re

RUNNER = re.compile(r"(?P<indent> +)runs-on: (?P<label>ubuntu-[0-9]+\.[0-9]+-arm)\Z")
JAVA = re.compile(r"(?P<indent> +)java-version: ['\"]?[0-9]+['\"]?\Z")


def filter_toolchain_patch(patch: str) -> tuple[str, bool]:
    lines = patch.splitlines()
    result = []
    changed = False
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.startswith("-") and i + 1 < len(lines) and lines[i + 1].startswith("+"):
            old = RUNNER.fullmatch(line[1:])
            new = RUNNER.fullmatch(lines[i + 1][1:])
            if old and new and old["indent"] == new["indent"] and old["label"] != new["label"]:
                changed = True
                i += 2
                continue
        if line.startswith("+") and i > 0 and lines[i - 1].startswith(" "):
            java = JAVA.fullmatch(lines[i - 1][1:])
            if java and line[1:] == java["indent"] + "check-latest: true":
                changed = True
                i += 1
                continue
        result.append(line)
        i += 1
    return "\n".join(result), changed


def has_changes(patch: str) -> bool:
    return any(line.startswith(("+", "-")) and not line.startswith(("+++", "---"))
               for line in patch.splitlines())
