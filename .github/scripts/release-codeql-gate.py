#!/usr/bin/env python3
"""Verify real exact-main CodeQL release evidence BEFORE Marketplace mutation.

This guard runs in the already-public 'released' workflow. It protects
Marketplace publication, NOT initial GitHub Release visibility.
"""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

LANGUAGES = ("actions", "javascript-typescript", "java-kotlin")
SHA = re.compile(r"[0-9a-f]{40}\Z")
REPO = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\Z")
MAX_AGE = dt.timedelta(days=7)


def reject(message):
    raise ValueError(message)


def metadata_ok(run, workflow, jobs, artifacts, repo, sha, now):
    """Validate API evidence; no CI conclusion alone is accepted as SARIF proof."""
    if not REPO.fullmatch(repo) or not SHA.fullmatch(sha):
        reject("invalid repository or SHA")
    if not isinstance(workflow, dict) or not isinstance(workflow.get("id"), int):
        reject("CodeQL workflow identity missing")
    if not isinstance(run, dict):
        reject("CodeQL run missing")
    identity = (
        run.get("workflow_id") == workflow["id"]
        and run.get("path") == ".github/workflows/codeql.yml"
        and run.get("event") == "workflow_dispatch"
        and run.get("head_repository", {}).get("full_name") == repo
        and run.get("head_branch") == "main"
        and run.get("head_sha") == sha
        and run.get("status") == "completed"
        and run.get("conclusion") == "success"
        and isinstance(run.get("id"), int)
        and isinstance(run.get("run_attempt"), int)
        and run["run_attempt"] >= 1
    )
    if not identity:
        reject("untrusted or mismatched CodeQL run identity/state/source")
    created_raw = run.get("created_at")
    if not isinstance(created_raw, str):
        reject("missing scan creation time")
    try:
        created = dt.datetime.fromisoformat(created_raw.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ValueError("invalid scan creation time") from exc
    if created.tzinfo is None or not dt.timedelta() <= now - created <= MAX_AGE:
        reject("scan is stale or has a future timestamp")
    expected_jobs = {f"CodeQL ({lang})" for lang in LANGUAGES}
    if (not isinstance(jobs, dict)
        or jobs.get("total_count") != len(LANGUAGES)
        or not isinstance(jobs.get("jobs"), list)
        or len(jobs["jobs"]) != len(LANGUAGES)
        or {j.get("name") for j in jobs["jobs"] if isinstance(j, dict)} != expected_jobs
        or any(j.get("status") != "completed" or j.get("conclusion") != "success"
               for j in jobs["jobs"] if isinstance(j, dict))):
        reject("missing, extra, incomplete or unsuccessful CodeQL language jobs")
    expected_names = {f"release-codeql-{sha}-{lang}" for lang in LANGUAGES}
    if (not isinstance(artifacts, dict)
        or artifacts.get("total_count") != len(LANGUAGES)
        or not isinstance(artifacts.get("artifacts"), list)
        or len(artifacts["artifacts"]) != len(LANGUAGES)):
        reject("expected precisely three retained SARIF artifacts")
    items = artifacts["artifacts"]
    if ({a.get("name") for a in items if isinstance(a, dict)} != expected_names
        or any(not isinstance(a, dict)
               or a.get("expired") is not False
               or not isinstance(a.get("id"), int)
               or not isinstance(a.get("size_in_bytes"), int)
               or a["size_in_bytes"] <= 0
               or a.get("workflow_run", {}).get("head_sha") not in (None, sha)
               for a in items)):
        reject("missing, ambiguous, expired or wrong-SHA SARIF artifacts")
    return run["id"]


def gh(*args):
    result = subprocess.run(["gh", *args], check=True, capture_output=True, text=True)
    return result.stdout


def api(repo, path):
    return json.loads(gh("api", f"repos/{repo}/{path}"))


def check_repo_sha(repo, sha):
    if not REPO.fullmatch(repo) or not SHA.fullmatch(sha):
        reject("invalid repo/SHA")
    actual = api(repo, "git/ref/heads/main")
    if actual.get("object", {}).get("sha") != sha:
        reject("main no longer matches approved release candidate")


def verify_live(repo, sha, script):
    if not os.environ.get("GH_TOKEN"):
        reject("missing GitHub API credential")
    check_repo_sha(repo, sha)
    workflow = api(repo, "actions/workflows/codeql.yml")
    found = api(repo, f"actions/workflows/codeql.yml/runs?event=workflow_dispatch&head_sha={sha}&per_page=100")
    if not isinstance(found.get("workflow_runs"), list):
        reject("CodeQL scan discovery returned no runs array")
    now = dt.datetime.now(dt.timezone.utc)
    reasons = []
    for candidate in found["workflow_runs"]:
        if not isinstance(candidate, dict):
            continue
        run_id = candidate.get("id")
        if not isinstance(run_id, int):
            continue
        try:
            run = api(repo, f"actions/runs/{run_id}")
            jobs = api(repo, f"actions/runs/{run_id}/jobs?per_page=100")
            artifacts = api(repo, f"actions/runs/{run_id}/artifacts?per_page=100")
            metadata_ok(run, workflow, jobs, artifacts, repo, sha, now)
            with tempfile.TemporaryDirectory(prefix="markflow-release-codeql-") as tmp:
                for lang in LANGUAGES:
                    name = f"release-codeql-{sha}-{lang}"
                    directory = Path(tmp) / lang
                    directory.mkdir()
                    subprocess.run(["gh", "run", "download", str(run_id),
                                    "-R", repo, "-n", name, "-D", str(directory)], check=True)
                    subprocess.run([sys.executable, str(script),
                                    "--sarif-dir", str(directory),
                                    "--language", lang, "--candidate-sha", sha], check=True)
            # Catch a moved main after the scan, download and verdict readback.
            check_repo_sha(repo, sha)
            print(f"Exact-SHA Marketplace security gate passed: {repo}@{sha}, "
                  f"trusted CodeQL run {run_id}, all three SARIF verdicts accepted")
            return
        except (ValueError, subprocess.CalledProcessError, OSError, KeyError, TypeError) as exc:
            reasons.append(f"{run_id}: {exc}")
    reject("no acceptable exact-SHA CodeQL evidence (last errors: "
           + "; ".join(reasons[-3:]) + ")")


def self_test():
    repo = "owner/repo"
    sha = "a" * 40
    now = dt.datetime(2026, 10, 14, tzinfo=dt.timezone.utc)
    workflow = {"id": 42}
    run = {"id": 5, "workflow_id": 42, "path": ".github/workflows/codeql.yml",
           "event": "workflow_dispatch", "head_repository": {"full_name": repo},
           "head_branch": "main", "head_sha": sha, "status": "completed",
           "conclusion": "success", "run_attempt": 1,
           "created_at": "2026-10-14T00:00:00Z"}
    jobs = {"total_count": 3, "jobs": [{"name": f"CodeQL ({lang})",
             "status": "completed", "conclusion": "success"} for lang in LANGUAGES]}
    artifacts = {"total_count": 3, "artifacts": [
        {"name": f"release-codeql-{sha}-{lang}", "id": i + 1,
         "size_in_bytes": 2000, "expired": False,
         "workflow_run": {"head_sha": sha}} for i, lang in enumerate(LANGUAGES)]}
    def probe(expected_error=None, change=None):
        current = json.loads(json.dumps([run, workflow, jobs, artifacts]))
        if change:
            change(*current)
        try:
            accepted = metadata_ok(*current, repo, sha, now)
        except (ValueError, TypeError, AttributeError) as exc:
            if expected_error is None or expected_error not in str(exc):
                raise AssertionError(f"unexpected evidence result: {exc}") from exc
        else:
            if expected_error:
                raise AssertionError(f"fail-open condition: {expected_error}")
            assert accepted == 5
    probe()
    probe("mismatched", lambda r, w, j, a: r.update(head_sha="b" * 40))
    probe("mismatched", lambda r, w, j, a: r.update(event="pull_request"))
    probe("mismatched", lambda r, w, j, a: r.update(conclusion="cancelled"))
    probe("mismatched", lambda r, w, j, a: r.update(path=".github/workflows/unsafe.yml"))
    probe("mismatched", lambda r, w, j, a: r.update(workflow_id=4))
    probe("stale", lambda r, w, j, a: r.update(created_at="2026-09-01T00:00:00Z"))
    probe("language jobs", lambda r, w, j, a: j["jobs"][0].update(conclusion="failure"))
    probe("language jobs", lambda r, w, j, a: j["jobs"].pop())
    probe("SARIF artifacts", lambda r, w, j, a: a["artifacts"].pop())
    probe("wrong-SHA", lambda r, w, j, a: a["artifacts"][0]["workflow_run"].update(head_sha="b" * 40))
    probe("ambiguous", lambda r, w, j, a: a["artifacts"][1].update(expired=True))
    print("exact-SHA Marketplace release CodeQL evidence negative controls passed")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--repository")
    parser.add_argument("--candidate-sha")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if not args.repository or not args.candidate_sha:
        parser.error("--repository and --candidate-sha required")
    script = Path(__file__).with_name("release-codeql-verdict.py")
    if not script.is_file():
        reject("missing local CodeQL SARIF verifier")
    try:
        verify_live(args.repository, args.candidate_sha, script)
    except (ValueError, subprocess.CalledProcessError, OSError, KeyError, TypeError) as exc:
        print(f"::error::Marketplace CodeQL authorization rejected: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc


if __name__ == "__main__":
    main()
