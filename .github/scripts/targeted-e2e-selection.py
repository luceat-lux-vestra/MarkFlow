#!/usr/bin/env python3
"""Fail-closed manual #515 Starter/Differential test selector.

Only diagnostic workflow_dispatch paths consume these outputs. The mandatory
PR workflow_call is always the complete canonical matrix.
"""
from __future__ import annotations

import argparse
import csv
import json
import re
from pathlib import Path

PREFIX = "com.algorist.markflow.e2e."
SHARDS = {
    "canonical": ("MarkFlowStarterFullProductJourneyTest",),
    "core-editing": ("MarkFlowStarterSmokeTest",),
    "ordinary-markdown": (
        "MarkFlowStarterTableTest",
        "MarkFlowStarterTaskListTest",
        "MarkFlowStarterListIndentTest",
    ),
    "derived-content": (
        "MarkFlowStarterSharedJcefDerivedContentTest",
        "MarkFlowStarterDifferentialManifestTest",
        "MarkFlowStarterHeadingRobotTest",
    ),
    "lifecycle": (
        "MarkFlowStarterSplitEditorTest",
        "MarkFlowStarterDegradedPathTest",
    ),
}
CASE_PATTERN = re.compile(r"[a-z0-9]+(?:-+[a-z0-9]+)*")
FENCE_OPEN = re.compile(r"^ {0,3}(\x60{3,}|~{3,})mermaid[ \t]*$")
SHA_PATTERN = re.compile(r"[0-9a-f]{40}")


def demand(value: bool, reason: str) -> None:
    if not value:
        raise ValueError(reason)


def expanded_cases(root: Path) -> dict[str, bool]:
    corpus = root / "fixtures/differential-acceptance/corpus.tsv"
    rows = list(csv.DictReader(corpus.open(encoding="utf-8", newline=""), delimiter="\t"))
    demand(len(rows) == 88, "Expected 88 reviewed corpus rows")
    inventory: dict[str, bool] = {}
    for row in rows:
        case_id = row["case_id"]
        demand(CASE_PATTERN.fullmatch(case_id) is not None, "Malformed manifest ID")
        domain = row["domain"]
        comparable = row["intellij_preview"] == "yes" and row["classification"] == "supported"
        demand(domain in ("markdown", "mermaid"), "Unknown differential domain")
        if domain == "markdown":
            expanded = [case_id]
        else:
            path = Path(row["fixture_path"])
            demand(not path.is_absolute() and ".." not in path.parts, "Unsafe fixture path")
            source = (root / path).read_text(encoding="utf-8").splitlines()
            block_count = 0
            pos = 0
            while pos < len(source):
                opening = FENCE_OPEN.fullmatch(source[pos])
                if opening is None:
                    pos += 1
                    continue
                fence = opening.group(1)
                closing = pos + 1
                while closing < len(source):
                    candidate = source[closing].strip()
                    if len(candidate) >= len(fence) and all(ch == fence[0] for ch in candidate):
                        break
                    closing += 1
                demand(closing < len(source) and closing > pos + 1, "Unclosed Mermaid fence")
                block_count += 1
                pos = closing + 1
            demand(block_count > 0, "Mermaid fixture without fenced blocks")
            expanded = (
                [case_id]
                if block_count == 1
                else [f"{case_id}--block-{index:02d}" for index in range(1, block_count + 1)]
            )
        for case in expanded:
            demand(case not in inventory, "Duplicate expanded case")
            inventory[case] = comparable
    demand(len(inventory) == 89, "Expanded #353 corpus is no longer 89 cases")
    return inventory


def selected_case_ids(root: Path, raw: str) -> list[str]:
    demand(raw != "" and len(raw) <= 4096, "Targeted case IDs required and bounded")
    items = raw.split(",")
    demand(all(item == item.strip() and CASE_PATTERN.fullmatch(item) for item in items),
           "Invalid or empty differential case ID")
    demand(len(items) == len(set(items)), "Duplicate differential case ID")
    available = expanded_cases(root)
    demand(all(case in available for case in items), "Unknown expanded case ID")
    demand(all(available[case] for case in items),
           "Bounded capture supports only comparable/supported cases")
    return items


def selection(root: Path, workflow: str, mode: str, shard: str, suite: str,
              case_ids: str) -> dict[str, str]:
    demand(workflow in ("starter", "differential"), "Unknown workflow")
    demand(mode in ("full", "targeted"), "Unknown mode")
    if workflow == "differential":
        demand(not shard and not suite, "Differential does not take Starter shard/suite")
        if mode == "full":
            demand(not case_ids, "Full differential forbids a case subset")
            return {"case_ids": "", "case_count": "89"}
        ids = selected_case_ids(root, case_ids)
        return {"case_ids": ",".join(ids), "case_count": str(len(ids))}
    if mode == "full":
        demand(not shard and not suite and not case_ids,
               "Full Starter cannot be reduced by target selectors")
        entries = [
            {"shard": key, "runner": "ubuntu-24.04-arm",
             "suites": "|".join(PREFIX + name for name in names)}
            for key, names in SHARDS.items()
        ]
        return {"test_matrix": json.dumps({"include": entries}, separators=(",", ":")),
                "case_ids": "", "case_count": "0"}
    demand(shard in SHARDS or shard == "visual", "Unknown targeted Starter shard")
    if shard == "visual":
        demand(not suite and not case_ids, "Visual cannot select a suite or case IDs")
        entries = [{"shard": "canonical", "runner": "ubuntu-24.04-arm",
                    "suites": PREFIX + SHARDS["canonical"][0]}]
        return {"test_matrix": json.dumps({"include": entries}, separators=(",", ":")),
                "case_ids": "", "case_count": "0"}
    allowed = [PREFIX + name for name in SHARDS[shard]]
    demand(not suite or suite in allowed, "Test suite is not in the selected shard")
    chosen = [suite] if suite else allowed
    if case_ids:
        demand(shard == "derived-content" and
               chosen == [PREFIX + "MarkFlowStarterDifferentialManifestTest"],
               "Differential case subset requires only the manifest suite")
        ids = selected_case_ids(root, case_ids)
    else:
        ids = []
    entries = [{"shard": shard, "runner": "ubuntu-24.04-arm",
                "suites": "|".join(chosen)}]
    return {"test_matrix": json.dumps({"include": entries}, separators=(",", ":")),
            "case_ids": ",".join(ids), "case_count": str(len(ids))}


def self_test(root: Path) -> None:
    demand(len(expanded_cases(root)) == 89, "Corpus inventory drift")
    assert selection(root, "differential", "targeted", "", "", "mermaid-info")["case_count"] == "1"
    assert selection(root, "differential", "targeted", "", "",
                     "mermaid-flowchart-large--block-01,mermaid-flowchart-large--block-02")["case_count"] == "2"
    assert len(json.loads(selection(root, "starter", "full", "", "", "")["test_matrix"])["include"]) == 5
    suite = PREFIX + "MarkFlowStarterDifferentialManifestTest"
    assert len(json.loads(selection(root, "starter", "targeted", "derived-content",
                                    suite, "mermaid-info")["test_matrix"])["include"]) == 1
    assert selection(root, "starter", "targeted", "visual", "", "")["case_count"] == "0"
    for workflow, mode, shard, suite, cases in [
        ("differential", "targeted", "", "", ""),
        ("differential", "full", "", "", "mermaid-info"),
        ("differential", "targeted", "", "", "mermaid-info,mermaid-info"),
        ("differential", "targeted", "", "", "md-raw-html-hostile"),
        ("differential", "targeted", "", "", "not-a-valid-case"),
        ("differential", "targeted", "", "", "mermaid-info,"),
        ("starter", "full", "derived-content", "", ""),
        ("starter", "targeted", "core-editing", suite, ""),
        ("starter", "targeted", "derived-content", "", "mermaid-info"),
        ("starter", "targeted", "visual", "", "mermaid-info"),
    ]:
        try:
            selection(root, workflow, mode, shard, suite, cases)
        except ValueError:
            continue
        raise AssertionError(f"Unexpectedly accepted: {(workflow, mode, shard, suite, cases)}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--workflow", choices=("starter", "differential"))
    parser.add_argument("--mode", default="full")
    parser.add_argument("--shard", default="")
    parser.add_argument("--suite", default="")
    parser.add_argument("--case-ids", default="")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    if args.self_test:
        self_test(root)
        print("Targeted E2E selector negative controls: PASS")
        return
    demand(args.workflow is not None, "Workflow is required")
    values = selection(root, args.workflow, args.mode, args.shard, args.suite, args.case_ids)
    for key, value in values.items():
        print(f"{key}={value}")


if __name__ == "__main__":
    main()
