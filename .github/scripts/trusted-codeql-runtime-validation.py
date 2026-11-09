#!/usr/bin/env python3
"""Read-only PR compatibility adapter for production-pinned CodeQL runtime."""

from __future__ import annotations

import argparse
import importlib.util
import json
import re
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent

def load_module(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, SCRIPT_DIR / filename)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {filename}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

_verdict = load_module("markflow_pr_runtime_verdict", "release-codeql-verdict.py")
_gate = load_module("markflow_pr_runtime_gate", "release-codeql-gate.py")
check = _verdict.check
eligible_run = _gate.eligible_run

SHA = re.compile(r"[0-9a-f]{40}\Z")
JOB = re.compile(r"^  ([A-Za-z0-9_-]+):\s*$", re.MULTILINE)
RELEVANT_PATHS = frozenset({
    ".github/workflows/codeql.yml",
    ".github/workflows/build.yml",
    ".github/scripts/release-codeql-verdict.py",
    ".github/scripts/release-codeql-gate.py",
    ".github/scripts/trusted-codeql-runtime-validation.py",
    ".github/scripts/hardening-audit.sh",
})


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def safe_path(value: object) -> str:
    require(isinstance(value, str) and value, "missing changed-file path")
    require(not value.startswith("/") and ".." not in value.split("/"),
            "unsafe changed-file path")
    return value


def require_supported_codeql_patch(entry: dict) -> None:
    require(entry.get("status") == "modified",
            "unsupported CodeQL workflow change type")
    patch = entry.get("patch")
    require(isinstance(patch, str) and patch,
            "CodeQL workflow change has no reviewable patch")
    changed = [
        line[1:].strip()
        for line in patch.splitlines()
        if line.startswith(("+", "-")) and not line.startswith(("+++", "---"))
    ]
    allowed = re.compile(
        r"uses:\s*github/codeql-action/(?:init|analyze)@"
        r"[0-9a-f]{40}(?:\s+#.*)?\Z"
    )
    require(changed and all(allowed.fullmatch(line) for line in changed),
            "CodeQL workflow mutation exceeds the init/analyze runtime adapter")


def classify(pull: dict, pages: list, expected_head: str) -> bool:
    require(SHA.fullmatch(expected_head or "") is not None,
            "invalid expected PR HEAD")
    require(isinstance(pull, dict) and isinstance(pull.get("head"), dict),
            "missing PR metadata")
    require(pull["head"].get("sha") == expected_head,
            "PR HEAD changed during classification")
    expected_count = pull.get("changed_files")
    require(type(expected_count) is int and 0 < expected_count <= 3000,
            "invalid or unsupported PR changed-file count")
    require(isinstance(pages, list) and pages
            and all(isinstance(page, list) for page in pages),
            "invalid paginated changed-file listing")
    entries = [entry for page in pages for entry in page]
    require(len(entries) == expected_count,
            "incomplete PR changed-file listing")

    seen: set[str] = set()
    paths: set[str] = set()
    for entry in entries:
        require(isinstance(entry, dict), "malformed changed-file entry")
        name = safe_path(entry.get("filename"))
        require(name not in seen, "duplicate changed-file entry")
        seen.add(name)
        status = entry.get("status")
        require(status in {
            "added", "modified", "removed", "renamed",
            "copied", "changed", "unchanged",
        }, "unknown changed-file status")
        paths.add(name)
        if status == "renamed":
            paths.add(safe_path(entry.get("previous_filename")))
        if name == ".github/workflows/codeql.yml":
            require_supported_codeql_patch(entry)

    return bool(paths & RELEVANT_PATHS)


def extract_job(workflow: str, job_id: str) -> str:
    marker = re.search(rf"^  {re.escape(job_id)}:\s*$", workflow, re.MULTILINE)
    require(marker is not None, f"missing workflow job {job_id}")
    tail = workflow[marker.end():]
    end = JOB.search(tail)
    return tail[:end.start()] if end else tail


def step_block(job: str, name: str) -> str:
    marker = re.search(
        rf"^\s{{6}}- name: {re.escape(name)}\s*$",
        job,
        re.MULTILINE,
    )
    require(marker is not None, f"missing step {name}")
    tail = job[marker.end():]
    end = re.search(r"^\s{6}- name: ", tail, re.MULTILINE)
    return tail[:end.start()] if end else tail


def action_pin(text: str, action: str) -> str:
    pins = re.findall(
        rf"uses:\s*{re.escape(action)}@([0-9a-f]{{40}})(?:\s+#.*)?$",
        text,
        re.MULTILINE,
    )
    require(len(pins) == 1, f"expected exactly one {action} pin")
    return pins[0]


def check_contract(codeql: str, pr_workflow: str) -> None:
    require("  pull_request:" not in codeql and "  push:" not in codeql,
            "production CodeQL must remain schedule/manual-only")
    require("      security-events: write" in codeql,
            "production CodeQL lost isolated security-events authority")

    prod_init = action_pin(codeql, "github/codeql-action/init")
    prod_analyze = action_pin(codeql, "github/codeql-action/analyze")
    require(prod_init == prod_analyze,
            "production CodeQL init/analyze pins must match")

    job = extract_job(pr_workflow, "dependencySubmissionValidation")
    header = job.split("\n    steps:", 1)[0]
    require(
        re.search(
            r"^    permissions:\n"
            r"      contents: read\n"
            r"      pull-requests: read$",
            header,
            re.MULTILINE,
        ) is not None,
        "PR CodeQL runtime job permissions must be explicitly read-only",
    )
    require(re.search(r"^\s+\S+:\s*write(?:\s+#.*)?$", header, re.MULTILINE) is None,
            "PR CodeQL runtime job gained write authority")

    checkout = step_block(job, "Fetch exact validation source")
    require(
        "repository: ${{ github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name || github.repository }}" in checkout
        and "ref: ${{ github.event_name == 'pull_request' && github.event.pull_request.head.sha || inputs.target_sha || github.sha }}" in checkout,
        "Trusted workflow validation job must checkout exact PR-head repository/SHA",
    )
    require("persist-credentials: false" in checkout,
            "PR CodeQL runtime checkout credentials must not persist")

    init = step_block(job, "Initialize PR-only CodeQL runtime")
    analyze = step_block(job, "Analyze PR-only CodeQL runtime without upload")
    verify = step_block(job, "Verify local PR-only CodeQL runtime SARIF and reject promotion")

    require(action_pin(init, "github/codeql-action/init") == prod_init,
            "PR CodeQL init pin does not match production")
    require(action_pin(analyze, "github/codeql-action/analyze") == prod_analyze,
            "PR CodeQL analyze pin does not match production")
    require("          languages: actions" in init,
            "PR CodeQL runtime representative language drifted")
    require("          build-mode: none" in init,
            "PR CodeQL runtime must remain build-mode:none")
    require('          category: "/language:actions"' in analyze,
            "PR CodeQL runtime category drifted")
    require("          upload: never" in analyze,
            "PR CodeQL runtime must never upload security evidence")
    require("          output: ${{ runner.temp }}/pr-codeql-runtime" in analyze,
            "PR CodeQL runtime must keep SARIF local")
    require("trusted-codeql-runtime-validation.py verify" in verify,
            "PR CodeQL runtime lost fail-closed local SARIF validation")


def verify_runtime(directory: Path, source_sha: str) -> dict:
    require(SHA.fullmatch(source_sha or "") is not None,
            "invalid PR source SHA")
    verdict = check(directory, "actions", source_sha)
    require(verdict["high_or_critical"] == 0,
            "PR CodeQL runtime verdict failed")
    require(not eligible_run({
        "workflow_id": 77,
        "path": ".github/workflows/codeql.yml",
        "head_branch": "feature",
        "event": "pull_request",
        "head_sha": source_sha,
        "display_title": "CodeQL source " + source_sha,
    }, source_sha, 77),
            "PR CodeQL runtime was admitted as trusted production evidence")
    return {
        "schema": 1,
        "trust_domain": "untrusted-pr-codeql-runtime",
        "production_evidence": False,
        "event": "pull_request",
        "source_sha": source_sha,
        "language": "actions",
        "sarif_sha256": verdict["sarif_sha256"],
        "rules": verdict["rules"],
        "results": verdict["results"],
        "extracted_files": verdict["extracted_files"],
        "high_or_critical": verdict["high_or_critical"],
    }


def self_test() -> None:
    sha = "a" * 40
    pull = {"head": {"sha": sha}, "changed_files": 1}
    assert classify(pull, [[{
        "filename": ".github/scripts/trusted-codeql-runtime-validation.py",
        "status": "modified",
    }]], sha)
    assert not classify(pull, [[{
        "filename": "README.md",
        "status": "modified",
    }]], sha)

    pin_patch = (
        "@@ -1,2 +1,2 @@\n"
        "-        uses: github/codeql-action/init@" + "b" * 40 + " # v4.39.2\n"
        "+        uses: github/codeql-action/init@" + "c" * 40 + " # v4.39.3\n"
        "-        uses: github/codeql-action/analyze@" + "b" * 40 + " # v4.39.2\n"
        "+        uses: github/codeql-action/analyze@" + "c" * 40 + " # v4.39.3"
    )
    assert classify(pull, [[{
        "filename": ".github/workflows/codeql.yml",
        "status": "modified",
        "patch": pin_patch,
    }]], sha)

    for bad_pull, bad_pages, bad_sha in (
        ({"head": {"sha": sha}, "changed_files": 2},
         [[{"filename": "README.md", "status": "modified"}]], sha),
        (pull, [[{"filename": "../bad", "status": "modified"}]], sha),
        (pull, [[{"filename": "new", "status": "renamed"}]], sha),
        (pull, [[{
            "filename": ".github/workflows/codeql.yml",
            "status": "modified",
            "patch": "@@ -1 +1 @@\n-upload: always\n+upload: never",
        }]], sha),
        (pull, [[{"filename": "README.md", "status": "modified"}]], "d" * 40),
    ):
        try:
            classify(bad_pull, bad_pages, bad_sha)
        except ValueError:
            pass
        else:
            raise AssertionError("unsafe CodeQL PR classification was accepted")

    pin = "1" * 40
    prod = (
        "on:\n  workflow_dispatch:\n  schedule:\n"
        "jobs:\n  analyze:\n"
        "    permissions:\n      security-events: write\n"
        "    steps:\n"
        f"      - uses: github/codeql-action/init@{pin}\n"
        f"      - uses: github/codeql-action/analyze@{pin}\n"
    )
    pr = (
        "jobs:\n  dependencySubmissionValidation:\n"
        "    permissions:\n      contents: read\n      pull-requests: read\n"
        "    steps:\n"
        "      - name: Fetch exact validation source\n"
        "        uses: actions/checkout@" + "2" * 40 + "\n"
        "        with:\n"
        "          repository: ${{ github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name || github.repository }}\n"
        "          ref: ${{ github.event_name == 'pull_request' && github.event.pull_request.head.sha || inputs.target_sha || github.sha }}\n"
        "          persist-credentials: false\n"
        "      - name: Initialize PR-only CodeQL runtime\n"
        f"        uses: github/codeql-action/init@{pin}\n"
        "        with:\n          languages: actions\n          build-mode: none\n"
        "      - name: Analyze PR-only CodeQL runtime without upload\n"
        f"        uses: github/codeql-action/analyze@{pin}\n"
        "        with:\n"
        '          category: "/language:actions"\n'
        "          upload: never\n"
        "          output: ${{ runner.temp }}/pr-codeql-runtime\n"
        "      - name: Verify local PR-only CodeQL runtime SARIF and reject promotion\n"
        "        run: trusted-codeql-runtime-validation.py verify\n"
    )
    check_contract(prod, pr)

    for mutated in (
        pr.replace("upload: never", "upload: always"),
        pr.replace("pull-requests: read", "security-events: write"),
        pr.replace("github/codeql-action/init@" + pin,
                   "github/codeql-action/init@" + "3" * 40),
        pr.replace("languages: actions", "languages: java-kotlin"),
        pr.replace("trusted-codeql-runtime-validation.py verify", "echo skip"),
    ):
        try:
            check_contract(prod, mutated)
        except ValueError:
            pass
        else:
            raise AssertionError("unsafe CodeQL PR runtime contract was accepted")

    print("trusted CodeQL PR runtime adapter self-tests passed")


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)

    classify_p = sub.add_parser("classify")
    classify_p.add_argument("--pull", type=Path, required=True)
    classify_p.add_argument("--pages", type=Path, required=True)
    classify_p.add_argument("--head", required=True)

    contract_p = sub.add_parser("check-contract")
    contract_p.add_argument("--codeql", type=Path, required=True)
    contract_p.add_argument("--pr-workflow", type=Path, required=True)

    verify_p = sub.add_parser("verify")
    verify_p.add_argument("--dir", type=Path, required=True)
    verify_p.add_argument("--head", required=True)

    sub.add_parser("self-test")
    args = parser.parse_args()

    try:
        if args.command == "classify":
            required = classify(
                json.loads(args.pull.read_text(encoding="utf-8")),
                json.loads(args.pages.read_text(encoding="utf-8")),
                args.head,
            )
            print("required=" + str(required).lower())
        elif args.command == "check-contract":
            check_contract(
                args.codeql.read_text(encoding="utf-8"),
                args.pr_workflow.read_text(encoding="utf-8"),
            )
        elif args.command == "verify":
            print(json.dumps(
                verify_runtime(args.dir, args.head),
                sort_keys=True,
            ))
        else:
            self_test()
    except (
        ValueError,
        OSError,
        KeyError,
        TypeError,
        RuntimeError,
        json.JSONDecodeError,
    ) as exc:
        print(
            f"::error::Trusted CodeQL PR runtime validation rejected: {exc}",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
