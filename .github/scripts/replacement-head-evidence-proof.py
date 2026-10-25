#!/usr/bin/env python3
"""Trusted proof for #467 cross-SHA product-evidence reuse.

This script is intended to be fetched from the trusted PR base commit and run
by build.yml. It deliberately supports only normal same-PR ancestor progress:
a previous PR commit with a complete canonical full-product Build run followed
by a replacement HEAD whose exact diff is accepted by the trusted Stage A
classifier.

Unknown, stale, partial, cancelled, force-rewritten, forked, or otherwise
ambiguous evidence returns reuse_product_evidence=false. Once a reusable prior
HEAD is proven, the current replacement HEAD's Hardening audit becomes a
mandatory changed authority and must complete successfully before reuse=true is
published.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from dataclasses import dataclass
from typing import Any


HEX40 = re.compile(r"^[0-9a-f]{40}$")
REQUIRED_PRODUCT_JOBS = (
    "Build",
    "Test",
    "Inspect code",
    "Verify plugin",
    "Dependency Review",
    "Native Runtime Evidence / Native runtime evidence gate",
    "Starter Driver E2E / Deterministic Visual Acceptance Gate",
    "Starter Driver E2E / Starter Driver E2E Gate",
)
ANCHOR_JOB = "Head validation evidence"
ANCHOR_STEP = "Head validation anchor"
HARDENING_JOB = "Hardening audit"
HARDENING_AUTHORITY_PATHS = frozenset(
    {
        ".github/scripts/repository-hardening-contract.sh",
        ".github/scripts/hardening-audit.sh",
        ".github/scripts/check-gradle-cache-seed.sh",
    }
)
TRUST_FRAMEWORK_PATHS = HARDENING_AUTHORITY_PATHS | frozenset(
    {
        ".github/scripts/hardening-audit-test.sh",
        ".github/scripts/pr-ci-evidence-scope.py",
        ".github/scripts/replacement-head-evidence-proof.py",
        ".github/scripts/replacement-head-evidence-reuse.py",
        ".github/merge-gate-policy.json",
        ".github/workflows/hardening-audit.yml",
    }
)
TRUSTED_AUDIT_CHECKS = (
    "policy_shape",
    "policy_producers",
    "action_pinning",
    "workflow_permissions",
    "workflow_security",
    "privileged_workflows",
    "live_readback_boundary",
    "workflow_static_analysis",
    "release_preflight",
)


class ProofUnavailable(RuntimeError):
    """Reuse cannot be proven; caller must fall back to FULL CI."""


class ChangedAuthorityFailed(RuntimeError):
    """Reuse candidate exists, but the replacement authority did not pass."""


@dataclass(frozen=True)
class ReuseDecision:
    reuse: bool
    prior_head_sha: str = ""
    prior_run_id: str = ""
    reason: str = "no-eligible-prior-evidence"


def gh_json(repository: str, suffix: str) -> Any:
    endpoint = f"repos/{repository}/{suffix}"
    completed = subprocess.run(
        ["gh", "api", "-H", "Accept: application/vnd.github+json", endpoint],
        text=True,
        capture_output=True,
        check=False,
    )
    if completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip() or "unknown gh api failure"
        raise ProofUnavailable(f"{endpoint}: {detail}")
    try:
        return json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        raise ProofUnavailable(f"{endpoint}: invalid JSON") from exc


def list_pr_commits(repository: str, pr_number: int) -> list[str]:
    commits: list[str] = []
    for page in range(1, 11):
        payload = gh_json(
            repository,
            f"pulls/{pr_number}/commits?per_page=100&page={page}",
        )
        if not isinstance(payload, list):
            raise ProofUnavailable("PR commit API did not return a list")
        for item in payload:
            sha = item.get("sha", "")
            if not HEX40.fullmatch(sha):
                raise ProofUnavailable("PR commit API returned a malformed SHA")
            commits.append(sha)
        if len(payload) < 100:
            break
    else:
        raise ProofUnavailable("PR commit history exceeds bounded proof window")
    return commits


def valid_base_relation(compare: dict[str, Any], base_sha: str, head_sha: str) -> bool:
    return (
        compare.get("status") == "ahead"
        and compare.get("base_commit", {}).get("sha") == base_sha
        and compare.get("merge_base_commit", {}).get("sha") == base_sha
        and compare.get("head_commit", {}).get("sha") == head_sha
        and compare.get("behind_by") == 0
        and isinstance(compare.get("ahead_by"), int)
        and compare["ahead_by"] > 0
    )


def valid_replacement_relation(
    compare: dict[str, Any], prior_sha: str, head_sha: str
) -> bool:
    return (
        compare.get("status") == "ahead"
        and compare.get("base_commit", {}).get("sha") == prior_sha
        and compare.get("merge_base_commit", {}).get("sha") == prior_sha
        and compare.get("head_commit", {}).get("sha") == head_sha
        and compare.get("behind_by") == 0
        and isinstance(compare.get("ahead_by"), int)
        and compare["ahead_by"] > 0
    )


def job_success(jobs: list[dict[str, Any]], name: str) -> bool:
    matches = [job for job in jobs if job.get("name") == name]
    return (
        len(matches) == 1
        and matches[0].get("status") == "completed"
        and matches[0].get("conclusion") == "success"
    )


def canonical_full_product_evidence(jobs: list[dict[str, Any]]) -> bool:
    anchors = [job for job in jobs if job.get("name") == ANCHOR_JOB]
    if len(anchors) != 1:
        return False
    anchor = anchors[0]
    if anchor.get("status") != "completed" or anchor.get("conclusion") != "success":
        return False
    steps = anchor.get("steps") or []
    if sum(
        1
        for step in steps
        if step.get("name") == ANCHOR_STEP and step.get("conclusion") == "success"
    ) != 1:
        return False
    return all(job_success(jobs, name) for name in REQUIRED_PRODUCT_JOBS)


def content_blob_sha(repository: str, ref: str, path: str) -> str:
    payload = gh_json(repository, f"contents/{path}?ref={ref}")
    sha = payload.get("sha", "") if isinstance(payload, dict) else ""
    if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ProofUnavailable(f"content identity unavailable for {path} at {ref}")
    return sha


def authority_unchanged_before_replacement(
    repository: str,
    base_sha: str,
    prior_sha: str,
) -> bool:
    return all(
        content_blob_sha(repository, base_sha, path)
        == content_blob_sha(repository, prior_sha, path)
        for path in TRUST_FRAMEWORK_PATHS
    )


def classify_replacement(
    classifier: str,
    compare: dict[str, Any],
    prior_sha: str,
    head_sha: str,
) -> str | None:
    if not valid_replacement_relation(compare, prior_sha, head_sha):
        return None
    files = compare.get("files")
    if not isinstance(files, list) or len(files) != 1:
        return None

    item = files[0]
    status = item.get("status", "")
    filename = item.get("filename", "")
    previous = item.get("previous_filename", "")
    if not isinstance(previous, str):
        previous = ""

    completed = subprocess.run(
        [sys.executable, "-I", classifier, "--expected-count", "1"],
        input=f"{status}\t{filename}\t{previous}\n",
        text=True,
        capture_output=True,
        check=False,
    )
    if completed.returncode != 0:
        return None

    parsed: dict[str, str] = {}
    for line in completed.stdout.splitlines():
        if "=" not in line:
            return None
        key, value = line.split("=", 1)
        if key in parsed:
            return None
        parsed[key] = value

    if parsed != {
        "reuse_product_evidence": "true",
        "rerun_authority": "hardening",
        "reason": "hardening-only-correction",
    }:
        return None
    return filename if filename in HARDENING_AUTHORITY_PATHS else None


def run_trusted_authority(script: str, args: list[str], workspace: str) -> None:
    completed = subprocess.run(
        ["bash", script, *args],
        cwd=workspace,
        text=True,
        capture_output=True,
        check=False,
    )
    if completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip() or "trusted authority failed"
        raise ChangedAuthorityFailed(detail)


def verify_trusted_changed_authority(args: argparse.Namespace, changed_path: str) -> None:
    if changed_path not in HARDENING_AUTHORITY_PATHS:
        raise ChangedAuthorityFailed("unsupported changed hardening authority")

    for check in TRUSTED_AUDIT_CHECKS:
        run_trusted_authority(
            args.trusted_hardening_audit,
            ["--root", args.workspace, "--only", check, "--no-network"],
            args.workspace,
        )
    run_trusted_authority(args.trusted_repository_contract, [], args.workspace)
    run_trusted_authority(args.trusted_gradle_cache_seed, [], args.workspace)

def find_canonical_run(
    repository: str,
    prior_sha: str,
    head_ref: str,
    pr_created_at: str,
) -> str | None:
    payload = gh_json(
        repository,
        f"actions/workflows/build.yml/runs?event=pull_request&head_sha={prior_sha}&per_page=100",
    )
    runs = payload.get("workflow_runs") if isinstance(payload, dict) else None
    if not isinstance(runs, list):
        raise ProofUnavailable("Build workflow run API returned malformed data")

    for run in sorted(runs, key=lambda item: int(item.get("id", 0)), reverse=True):
        if (
            run.get("head_sha") != prior_sha
            or run.get("head_branch") != head_ref
            or run.get("event") != "pull_request"
            or run.get("status") != "completed"
            or run.get("conclusion") != "success"
        ):
            continue
        run_created = run.get("created_at", "")
        if (
            not isinstance(run_created, str)
            or not run_created
            or run_created < pr_created_at
        ):
            continue
        run_id = str(run.get("id", ""))
        if not run_id.isdigit():
            continue
        jobs_payload = gh_json(
            repository,
            f"actions/runs/{run_id}/jobs?filter=latest&per_page=100",
        )
        jobs = jobs_payload.get("jobs") if isinstance(jobs_payload, dict) else None
        if isinstance(jobs, list) and canonical_full_product_evidence(jobs):
            return run_id
    return None


def wait_for_hardening(repository: str, head_sha: str, attempts: int = 120) -> None:
    for _ in range(attempts):
        payload = gh_json(
            repository,
            f"actions/workflows/hardening-audit.yml/runs?event=pull_request&head_sha={head_sha}&per_page=100",
        )
        runs = payload.get("workflow_runs") if isinstance(payload, dict) else None
        if not isinstance(runs, list):
            raise ChangedAuthorityFailed("Hardening workflow run API returned malformed data")
        exact = [
            run
            for run in runs
            if run.get("head_sha") == head_sha and run.get("event") == "pull_request"
        ]
        if not exact:
            time.sleep(5)
            continue

        latest = max(exact, key=lambda item: int(item.get("id", 0)))
        if latest.get("status") != "completed":
            time.sleep(5)
            continue
        if latest.get("conclusion") != "success":
            raise ChangedAuthorityFailed(
                f"Hardening audit for replacement HEAD concluded {latest.get('conclusion')!r}"
            )

        run_id = str(latest.get("id", ""))
        if not run_id.isdigit():
            raise ChangedAuthorityFailed("Hardening audit run has malformed id")
        jobs_payload = gh_json(
            repository,
            f"actions/runs/{run_id}/jobs?filter=latest&per_page=100",
        )
        jobs = jobs_payload.get("jobs") if isinstance(jobs_payload, dict) else None
        if not isinstance(jobs, list) or not job_success(jobs, HARDENING_JOB):
            raise ChangedAuthorityFailed(
                "Hardening audit workflow completed without exactly one successful authority job"
            )
        return
    raise ChangedAuthorityFailed(
        "Timed out waiting for exact-HEAD Hardening audit required by replacement evidence reuse"
    )


def decide(args: argparse.Namespace) -> ReuseDecision:
    if (
        args.event_name != "pull_request"
        or args.event_action != "synchronize"
        or args.draft != "false"
        or args.head_repo != args.repository
    ):
        return ReuseDecision(False, reason="event-not-eligible")
    if not HEX40.fullmatch(args.base_sha) or not HEX40.fullmatch(args.head_sha):
        return ReuseDecision(False, reason="malformed-base-or-head")

    classifier_self_test = subprocess.run(
        [sys.executable, "-I", args.classifier, "--self-test"],
        text=True,
        capture_output=True,
        check=False,
    )
    if classifier_self_test.returncode != 0:
        return ReuseDecision(False, reason="trusted-classifier-self-test-failed")

    pr = gh_json(args.repository, f"pulls/{args.pr_number}")
    if not isinstance(pr, dict):
        return ReuseDecision(False, reason="current-pr-identity-invalid")
    pr_head = pr.get("head") or {}
    pr_base = pr.get("base") or {}
    head_repo = pr_head.get("repo") or {}
    head_ref = pr_head.get("ref", "")
    pr_created_at = pr.get("created_at", "")
    if (
        pr.get("number") != args.pr_number
        or pr.get("state") != "open"
        or pr_head.get("sha") != args.head_sha
        or head_repo.get("full_name") != args.repository
        or pr_base.get("sha") != args.base_sha
        or not isinstance(head_ref, str)
        or not head_ref
        or not isinstance(pr_created_at, str)
        or not pr_created_at
    ):
        return ReuseDecision(False, reason="current-pr-identity-invalid")

    commits = list_pr_commits(args.repository, args.pr_number)
    if not commits or commits[-1] != args.head_sha or len(set(commits)) != len(commits):
        return ReuseDecision(False, reason="current-head-not-bound-to-pr-history")

    current_base = gh_json(
        args.repository,
        f"compare/{args.base_sha}...{args.head_sha}",
    )
    if not valid_base_relation(current_base, args.base_sha, args.head_sha):
        return ReuseDecision(False, reason="current-base-relation-invalid")

    for prior_sha in reversed(commits[:-1]):
        prior_base = gh_json(
            args.repository,
            f"compare/{args.base_sha}...{prior_sha}",
        )
        if not valid_base_relation(prior_base, args.base_sha, prior_sha):
            continue

        if not authority_unchanged_before_replacement(
            args.repository,
            args.base_sha,
            prior_sha,
        ):
            continue

        replacement = gh_json(
            args.repository,
            f"compare/{prior_sha}...{args.head_sha}",
        )
        changed_path = classify_replacement(
            args.classifier,
            replacement,
            prior_sha,
            args.head_sha,
        )
        if changed_path is None:
            continue

        prior_run_id = find_canonical_run(
            args.repository,
            prior_sha,
            head_ref,
            pr_created_at,
        )
        if prior_run_id is None:
            continue

        try:
            verify_trusted_changed_authority(args, changed_path)
            wait_for_hardening(args.repository, args.head_sha)
        except ProofUnavailable as exc:
            raise ChangedAuthorityFailed(
                f"Unable to verify exact-HEAD Hardening audit: {exc}"
            ) from exc

        return ReuseDecision(
            True,
            prior_head_sha=prior_sha,
            prior_run_id=prior_run_id,
            reason="canonical-prior-evidence-plus-hardening-only-replacement",
        )

    return ReuseDecision(False)


def self_test() -> None:
    base = "a" * 40
    prior = "b" * 40
    head = "c" * 40
    good_base = {
        "status": "ahead",
        "base_commit": {"sha": base},
        "merge_base_commit": {"sha": base},
        "head_commit": {"sha": head},
        "behind_by": 0,
        "ahead_by": 2,
    }
    assert valid_base_relation(good_base, base, head)
    assert not valid_base_relation({**good_base, "merge_base_commit": {"sha": prior}}, base, head)

    good_replacement = {
        "status": "ahead",
        "base_commit": {"sha": prior},
        "merge_base_commit": {"sha": prior},
        "head_commit": {"sha": head},
        "behind_by": 0,
        "ahead_by": 1,
    }
    assert valid_replacement_relation(good_replacement, prior, head)
    assert not valid_replacement_relation(
        {**good_replacement, "status": "diverged"}, prior, head
    )

    jobs = [
        {
            "name": ANCHOR_JOB,
            "status": "completed",
            "conclusion": "success",
            "steps": [{"name": ANCHOR_STEP, "conclusion": "success"}],
        }
    ] + [
        {"name": name, "status": "completed", "conclusion": "success"}
        for name in REQUIRED_PRODUCT_JOBS
    ]
    assert canonical_full_product_evidence(jobs)
    assert not canonical_full_product_evidence(
        [job for job in jobs if job.get("name") != "Test"]
    )
    broken = [dict(job) for job in jobs]
    broken[-1]["conclusion"] = "skipped"
    assert not canonical_full_product_evidence(broken)
    print("replacement-head evidence proof fixtures: PASS")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--repository")
    parser.add_argument("--pr-number", type=int)
    parser.add_argument("--base-sha")
    parser.add_argument("--head-sha")
    parser.add_argument("--head-repo")
    parser.add_argument("--event-name")
    parser.add_argument("--event-action")
    parser.add_argument("--draft")
    parser.add_argument("--classifier")
    parser.add_argument("--workspace")
    parser.add_argument("--trusted-hardening-audit")
    parser.add_argument("--trusted-repository-contract")
    parser.add_argument("--trusted-gradle-cache-seed")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return 0

    required = (
        "repository",
        "pr_number",
        "base_sha",
        "head_sha",
        "head_repo",
        "event_name",
        "event_action",
        "draft",
        "classifier",
        "workspace",
        "trusted_hardening_audit",
        "trusted_repository_contract",
        "trusted_gradle_cache_seed",
    )
    if any(getattr(args, name) in (None, "") for name in required):
        parser.error("all production proof arguments are required")

    try:
        decision = decide(args)
    except ChangedAuthorityFailed as exc:
        print(f"::error::{exc}", file=sys.stderr)
        return 2
    except (ProofUnavailable, OSError, ValueError) as exc:
        decision = ReuseDecision(False, reason=f"proof-unavailable:{type(exc).__name__}")

    print(f"reuse_product_evidence={str(decision.reuse).lower()}")
    print(f"prior_head_sha={decision.prior_head_sha}")
    print(f"prior_run_id={decision.prior_run_id}")
    print(f"reason={decision.reason}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
