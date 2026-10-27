#!/usr/bin/env python3
"""Fail-closed, non-publishing Qodana release evidence verifier (R2A #104 Stage B).

This first slice verifies an explicitly selected completed full-scan run. Automatic
reuse/join/dispatch and the release workflow dependency are separate checkpoints.
The source under examination is the release-tag checkout; policy lives on main.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "markflow_qodana_sarif", Path(__file__).with_name("check-qodana-sarif.py"))
_verifier = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_verifier)
check_sarif = _verifier.check

SHA = re.compile(r"^[0-9a-f]{40}$")
TAG = re.compile(r"v[1-9][0-9]*\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\Z")
WORKFLOW = "qodana-full.yml"
PREFIX = "Qodana source "
GUARD = "Qodana schedule guard"
SCAN = "Qodana full scan"
ACTION = "JetBrains/qodana-action@3e8d76ff0e0241618abd808682f426afe84bab3f"
IMAGE = "jetbrains/qodana-jvm-community:2026.2"
REQUIRED_STEPS = (
    "Checkout trusted main policy",
    "Verify exact protected-main candidate",
    "Checkout exact candidate",
    "Verify exact checkout",
    "Setup Java",
    "Qodana - Full Code Inspection",
    "Enforce full Qodana SARIF",
    "Retain verified original Qodana SARIF",
    "Record exact-source Qodana provenance",
    "Upload exact-source Qodana provenance",
)


def require(ok: bool, message: str) -> None:
    if not ok:
        raise ValueError(message)


def gh_json(endpoint: str) -> dict:
    result = json.loads(subprocess.check_output(["gh", "api", endpoint], text=True))
    require(isinstance(result, dict), f"invalid GitHub response: {endpoint}")
    return result


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True).strip()


def verify_source(sha: str, tag: str, repo: str) -> None:
    require(bool(SHA.fullmatch(sha)), "release candidate must be a lowercase 40-hex SHA")
    require(bool(TAG.fullmatch(tag)), "release tag must be stable vMAJOR.MINOR.PATCH")
    require(repo == os.environ.get("GITHUB_REPOSITORY"), "repository identity mismatch")
    require(git("rev-parse", "HEAD") == sha, "release checkout SHA mismatch")
    require(git("rev-parse", f"refs/tags/{tag}^{{commit}}") == sha, "tag SHA mismatch")
    refs = dict(reversed(line.split("\t", 1)) for line in git(
        "ls-remote", "--tags", "origin", f"refs/tags/{tag}",
        f"refs/tags/{tag}^{{}}").splitlines())
    require(refs.get(f"refs/tags/{tag}^{{}}", refs.get(f"refs/tags/{tag}")) == sha,
            "remote release tag moved or unavailable")
    subprocess.run(["git", "fetch", "--no-tags", "origin", "main"], check=True)
    require(subprocess.run(["git", "merge-base", "--is-ancestor", sha, "FETCH_HEAD"],
                           check=False).returncode == 0,
            "release candidate is not protected-main history")


def verify_run(run: dict, sha: str, workflow_id: int, repo: str) -> None:
    require(isinstance(run, dict), "invalid Qodana run")
    require(isinstance(workflow_id, int) and workflow_id > 0, "invalid workflow ID")
    require(run.get("workflow_id") == workflow_id
            and run.get("path") == ".github/workflows/qodana-full.yml"
            and run.get("head_branch") == "main"
            and run.get("event") in {"schedule", "workflow_dispatch"}
            and run.get("display_title") == PREFIX + sha,
            "wrong Qodana workflow or source identity")
    require(run.get("head_repository", {}).get("full_name") == repo,
            "untrusted Qodana run repository")
    require(run.get("run_attempt") == 1, "Qodana rerun attempt is not reusable")
    require(isinstance(run.get("id"), int) and run["id"] > 0, "invalid Qodana run ID")
    require(run.get("status") == "completed" and run.get("conclusion") == "success",
            "Qodana full scan did not complete successfully")
    require(isinstance(run.get("head_sha"), str)
            and bool(SHA.fullmatch(run["head_sha"])), "invalid Qodana event SHA")
    if run["event"] == "schedule":
        require(run["head_sha"] == sha, "scheduled scan source SHA mismatch")


def verify_jobs(payload: dict) -> None:
    jobs = payload.get("jobs")
    require(isinstance(jobs, list) and payload.get("total_count") == len(jobs),
            "incomplete Qodana job listing")
    require(len(jobs) == 2 and {j.get("name") for j in jobs if isinstance(j, dict)}
            == {GUARD, SCAN}, "unexpected, duplicate or absent Qodana job")
    for job in jobs:
        require(job.get("status") == "completed" and job.get("conclusion") == "success",
                f"Qodana job {job.get('name')} failed or skipped")
    full = next(job for job in jobs if job["name"] == SCAN)
    steps = full.get("steps")
    require(isinstance(steps, list), "full scan step attestation is missing")
    for name in REQUIRED_STEPS:
        matching = [s for s in steps if isinstance(s, dict) and s.get("name") == name]
        require(len(matching) == 1 and matching[0].get("conclusion") == "success",
                f"Qodana full scan missing successful {name}")


def verify_artifacts(payload: dict, sha: str, run_id: int) -> dict[str, str]:
    items = payload.get("artifacts")
    require(isinstance(items, list) and payload.get("total_count") == len(items),
            "incomplete Qodana artifact listing")
    required = {"sarif": f"qodana-{sha}-sarif", "source": f"qodana-source-{sha}"}
    for name in required.values():
        matches = [a for a in items if isinstance(a, dict) and a.get("name") == name]
        require(len(matches) == 1, f"missing or duplicate artifact: {name}")
        a = matches[0]
        require(a.get("expired") is False and isinstance(a.get("size_in_bytes"), int)
                and a["size_in_bytes"] > 0, f"expired or empty artifact: {name}")
        require(a.get("workflow_run", {}).get("id") == run_id,
                f"artifact from another Qodana run: {name}")
    return required


def only_file(directory: Path, name: str) -> Path:
    files = [p for p in directory.rglob("*") if p.is_file() or p.is_symlink()]
    require(len(files) == 1 and files[0].name == name and not files[0].is_symlink(),
            f"ambiguous or missing evidence file: {name}")
    return files[0]


def verify_bundle(manifest: dict, sarif: Path, config: Path, run: dict,
                  sha: str, repo: str) -> dict:
    verdict = check_sarif(sarif)
    require(verdict["high_critical"] == 0, "High/Critical Qodana findings")
    require(config.is_file(), "exact release source qodana.yml missing")
    require(isinstance(manifest, dict), "invalid Qodana source manifest")
    expected = {
        "schema": 1,
        "candidate_sha": sha,
        "checkout_sha": sha,
        "event_name": run["event"],
        "event_sha": run["head_sha"],
        "repository": repo,
        "run_id": run["id"],
        "run_attempt": 1,
        "qodana_action": ACTION,
        "qodana_image": IMAGE,
        "profile": "qodana.recommended",
        "severity_gate": ["High", "Critical"],
        "high_critical": 0,
        "total_results": verdict["results"],
        "sarif_sha256": verdict["sarif_sha256"],
        "qodana_config_sha256": hashlib.sha256(config.read_bytes()).hexdigest(),
    }
    require(manifest == expected, "Qodana manifest/source/SARIF digest mismatch")
    return {"run_id": run["id"], "attempt": 1, "candidate_sha": sha,
            "event_sha": run["head_sha"], "findings": verdict["results"],
            "high_critical": 0, "sarif_sha256": verdict["sarif_sha256"]}


def verify_evidence(run: dict, sha: str, workflow_id: int, repo: str,
                    config: Path) -> dict:
    verify_run(run, sha, workflow_id, repo)
    rid = run["id"]
    jobs = gh_json(f"repos/{repo}/actions/runs/{rid}/jobs?filter=latest&per_page=100")
    verify_jobs(jobs)
    artifacts = gh_json(f"repos/{repo}/actions/runs/{rid}/artifacts?per_page=100")
    names = verify_artifacts(artifacts, sha, rid)
    # The event SHA is a trusted main ancestor independent of the chosen tag SHA.
    subprocess.run(["git", "fetch", "--no-tags", "origin", "main"], check=True)
    require(subprocess.run(["git", "merge-base", "--is-ancestor",
                            run["head_sha"], "FETCH_HEAD"], check=False).returncode == 0,
            "Qodana event source is not protected-main history")
    with tempfile.TemporaryDirectory(prefix="release-qodana-proof-") as tmp:
        root = Path(tmp)
        for kind, name in names.items():
            dest = root / kind
            dest.mkdir()
            subprocess.run(["gh", "run", "download", str(rid), "-R", repo,
                            "-n", name, "-D", str(dest)], check=True)
        sarif = only_file(root / "sarif", "qodana.sarif.json")
        manifest_file = only_file(root / "source", "qodana-source-manifest.json")
        manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
        return verify_bundle(manifest, sarif, config, run, sha, repo)


def self_test() -> None:
    sha, other, repo, workflow_id = "a" * 40, "b" * 40, "owner/repo", 41
    run = {"id": 17, "workflow_id": workflow_id,
           "path": ".github/workflows/qodana-full.yml", "head_branch": "main",
           "head_sha": other, "head_repository": {"full_name": repo},
           "event": "workflow_dispatch", "display_title": PREFIX + sha,
           "run_attempt": 1, "status": "completed", "conclusion": "success"}
    steps = [{"name": name, "conclusion": "success"} for name in REQUIRED_STEPS]
    jobs = {"total_count": 2, "jobs": [
        {"name": GUARD, "status": "completed", "conclusion": "success"},
        {"name": SCAN, "status": "completed", "conclusion": "success", "steps": steps}]}
    names = [f"qodana-{sha}-sarif", f"qodana-source-{sha}"]
    artifacts = {"total_count": 2, "artifacts": [
        {"name": name, "expired": False, "size_in_bytes": 20,
         "workflow_run": {"id": 17}} for name in names]}

    def rejects(fn, description: str) -> None:
        try:
            fn()
        except (ValueError, KeyError, TypeError):
            return
        raise AssertionError(f"negative control accepted: {description}")

    verify_run(run, sha, workflow_id, repo)
    verify_jobs(jobs)
    verify_artifacts(artifacts, sha, 17)
    for changes in (
        {"head_branch": "feature"}, {"event": "pull_request"},
        {"display_title": PREFIX + other}, {"run_attempt": 2},
        {"conclusion": "failure"}, {"head_sha": "bad"},
        {"head_repository": {"full_name": "other/repo"}},
    ):
        rejects(lambda d=changes: verify_run(dict(run, **d), sha, workflow_id, repo),
                f"run identity {changes}")
    broken = json.loads(json.dumps(jobs))
    broken["jobs"][1]["steps"][2]["conclusion"] = "skipped"
    rejects(lambda: verify_jobs(broken), "skipped source checkout")
    broken = json.loads(json.dumps(jobs))
    broken["jobs"][1]["conclusion"] = "skipped"
    rejects(lambda: verify_jobs(broken), "guard-only workflow")
    broken = json.loads(json.dumps(artifacts))
    broken["artifacts"][1]["expired"] = True
    rejects(lambda: verify_artifacts(broken, sha, 17), "expired provenance")
    broken = json.loads(json.dumps(artifacts))
    broken["artifacts"][1]["workflow_run"]["id"] = 99
    rejects(lambda: verify_artifacts(broken, sha, 17), "cross-run provenance")
    broken = json.loads(json.dumps(artifacts))
    broken["artifacts"].append(dict(broken["artifacts"][0]))
    broken["total_count"] = 3
    rejects(lambda: verify_artifacts(broken, sha, 17), "duplicate artifact")
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        config = root / "qodana.yml"
        config.write_text("version: '1.0'\n", encoding="utf-8")
        sarif = root / "qodana.sarif.json"
        data = {"version": "2.1.0", "runs": [{"tool": {"driver": {"name": "Qodana"}},
                "results": [{"ruleId": "L", "properties": {"qodanaSeverity": "Moderate"}}]}]}
        sarif.write_text(json.dumps(data), encoding="utf-8")
        verdict = check_sarif(sarif)
        manifest = {
            "schema": 1, "candidate_sha": sha, "checkout_sha": sha,
            "event_name": run["event"], "event_sha": other, "repository": repo,
            "run_id": 17, "run_attempt": 1, "qodana_action": ACTION,
            "qodana_image": IMAGE, "profile": "qodana.recommended",
            "severity_gate": ["High", "Critical"], "high_critical": 0,
            "total_results": 1, "sarif_sha256": verdict["sarif_sha256"],
            "qodana_config_sha256": hashlib.sha256(config.read_bytes()).hexdigest(),
        }
        verify_bundle(manifest, sarif, config, run, sha, repo)
        for field, value in (("candidate_sha", other), ("checkout_sha", other),
                             ("event_sha", sha), ("run_id", 99),
                             ("run_attempt", 2), ("total_results", 0),
                             ("sarif_sha256", "0" * 64),
                             ("qodana_config_sha256", "0" * 64),
                             ("qodana_action", "unpinned")):
            rejects(lambda f=field, v=value: verify_bundle(
                dict(manifest, **{f: v}), sarif, config, run, sha, repo),
                f"manifest {field}")
        data["runs"][0]["results"][0]["properties"]["qodanaSeverity"] = "High"
        sarif.write_text(json.dumps(data), encoding="utf-8")
        rejects(lambda: verify_bundle(manifest, sarif, config, run, sha, repo),
                "High/Critical finding")
        sarif.write_text("{", encoding="utf-8")
        rejects(lambda: verify_bundle(manifest, sarif, config, run, sha, repo),
                "malformed SARIF")
    print("release Qodana identity/evidence negative controls passed")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--repository")
    parser.add_argument("--candidate-sha")
    parser.add_argument("--tag")
    parser.add_argument("--run-id", type=int)
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if not all([args.repository, args.candidate_sha, args.tag, args.run_id]):
        parser.error("--repository, --candidate-sha, --tag, and --run-id required")
    try:
        verify_source(args.candidate_sha, args.tag, args.repository)
        wf = gh_json(f"repos/{args.repository}/actions/workflows/{WORKFLOW}")
        require(wf.get("state") == "active", "trusted Qodana workflow is unavailable")
        run = gh_json(f"repos/{args.repository}/actions/runs/{args.run_id}")
        proof = verify_evidence(run, args.candidate_sha, wf.get("id"),
                                args.repository, Path("qodana.yml"))
        verify_source(args.candidate_sha, args.tag, args.repository)
        print(json.dumps(proof, sort_keys=True))
        return 0
    except (ValueError, OSError, subprocess.CalledProcessError,
            json.JSONDecodeError) as error:
        print(f"::error::Release Qodana proof rejected: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
