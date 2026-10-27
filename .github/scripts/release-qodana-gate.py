#!/usr/bin/env python3
"""Fail-closed, non-publishing Qodana release evidence verifier (R2A #104 Stage B).

This slice verifies completed original SARIF/source evidence and reuses, joins or
dispatches trusted full scans. The release workflow dependency is a later checkpoint.
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
import time
from pathlib import Path
from unittest.mock import patch

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
WAIT_SECONDS = 80 * 60
POLL_SECONDS = 15
DISCOVERY_SECONDS = 150
MAX_PAGES = 10
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


def verify_artifacts(payload: dict, sha: str, run_id: int,
                     allow_missing: bool = False) -> dict[str, str] | None:
    items = payload.get("artifacts")
    require(isinstance(items, list) and payload.get("total_count") == len(items),
            "incomplete Qodana artifact listing")
    required = {"sarif": f"qodana-{sha}-sarif", "source": f"qodana-source-{sha}"}
    missing = False
    for name in required.values():
        matches = [a for a in items if isinstance(a, dict) and a.get("name") == name]
        require(len(matches) <= 1, f"duplicate artifact: {name}")
        if not matches:
            require(allow_missing, f"missing artifact: {name}")
            missing = True
            continue
        a = matches[0]
        require(isinstance(a.get("size_in_bytes"), int) and a["size_in_bytes"] > 0,
                f"empty artifact: {name}")
        require(a.get("workflow_run", {}).get("id") == run_id,
                f"artifact from another Qodana run: {name}")
        if a.get("expired") is True:
            require(allow_missing, f"expired artifact: {name}")
            missing = True
            continue
        require(a.get("expired") is False, f"artifact has unknown expiry: {name}")
    # Only genuinely absent or expired original archives may cause re-generation.
    # Existing corrupt, duplicate, zero-length or cross-run evidence always fails.
    return None if missing else required


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
                    config: Path, allow_missing: bool = False) -> dict | None:
    verify_run(run, sha, workflow_id, repo)
    rid = run["id"]
    jobs = gh_json(f"repos/{repo}/actions/runs/{rid}/jobs?filter=latest&per_page=100")
    verify_jobs(jobs)
    # The event SHA is trusted main history; the historical candidate's actual
    # checkout identity is independently attested by the verified manifest.
    subprocess.run(["git", "fetch", "--no-tags", "origin", "main"], check=True)
    require(subprocess.run(["git", "merge-base", "--is-ancestor",
                            run["head_sha"], "FETCH_HEAD"], check=False).returncode == 0,
            "Qodana event source is not protected-main history")
    artifacts = gh_json(f"repos/{repo}/actions/runs/{rid}/artifacts?per_page=100")
    names = verify_artifacts(artifacts, sha, rid, allow_missing=allow_missing)
    if names is None:
        return None
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


def eligible_run(run: dict, sha: str, workflow_id: int) -> bool:
    if not isinstance(run, dict):
        return False
    return (run.get("workflow_id") == workflow_id
            and run.get("path") == ".github/workflows/qodana-full.yml"
            and run.get("head_branch") == "main"
            and run.get("event") in {"schedule", "workflow_dispatch"}
            and run.get("display_title") == PREFIX + sha)


def find_runs(repo: str, sha: str, workflow_id: int) -> list[dict]:
    found: list[dict] = []
    # Event SHA can be newer than historical candidate SHA. Never query by head_sha.
    # Exhaust finite pages or fail closed rather than silently truncate history.
    for event in ("workflow_dispatch", "schedule"):
        for page in range(1, MAX_PAGES + 1):
            payload = gh_json(f"repos/{repo}/actions/workflows/{WORKFLOW}/runs"
                              f"?event={event}&per_page=100&page={page}")
            records = payload.get("workflow_runs")
            require(isinstance(records, list) and len(records) <= 100
                    and all(isinstance(r, dict) for r in records),
                    "malformed Qodana workflow-run discovery")
            found.extend(r for r in records if eligible_run(r, sha, workflow_id))
            if len(records) < 100:
                break
        else:
            raise ValueError("Qodana discovery exceeded safe 1000-run bound")
    # Defend against duplicate API discovery records, which otherwise might
    # camouflage a failed scanner or cause ambiguous join/selection.
    ids = [r.get("id") for r in found]
    require(all(isinstance(rid, int) and rid > 0 for rid in ids)
            and len(ids) == len(set(ids)), "duplicate or invalid Qodana run IDs")
    return sorted(found, key=lambda r: (r.get("created_at", ""), r["id"]),
                  reverse=True)


def intentional_schedule_skip(repo: str, run: dict) -> bool:
    if (run.get("event") != "schedule"
            or run.get("status") != "completed"
            or run.get("conclusion") != "success"):
        return False
    rid = run.get("id")
    require(isinstance(rid, int) and rid > 0, "invalid schedule guard run ID")
    jobs = gh_json(f"repos/{repo}/actions/runs/{rid}/jobs?filter=latest&per_page=100")
    entries = jobs.get("jobs")
    require(isinstance(entries, list) and jobs.get("total_count") == len(entries),
            "incomplete schedule guard jobs")
    guard = [j for j in entries if isinstance(j, dict) and j.get("name") == GUARD]
    scanner = [j for j in entries if isinstance(j, dict) and j.get("name") == SCAN]
    require(len(guard) == 1 and len(scanner) == 1 and len(entries) == 2,
            "ambiguous schedule guard/scanner jobs")
    return (guard[0].get("status") == "completed"
            and guard[0].get("conclusion") == "success"
            and scanner[0].get("status") == "completed"
            and scanner[0].get("conclusion") == "skipped")


def select_run(repo: str, sha: str, workflow_id: int,
               ignored: set[int] | None = None) -> dict | None:
    runs = find_runs(repo, sha, workflow_id)
    for run in runs:
        require(run.get("run_attempt") == 1,
                f"Qodana rerun/attempt ambiguity in run {run.get('id')}")
    runs = [run for run in runs if not intentional_schedule_skip(repo, run)]
    # Failed scans cannot be masked by subsequent green scanner runs.
    for run in runs:
        status = run.get("status")
        if status == "completed":
            require(run.get("conclusion") == "success",
                    f"Qodana run {run.get('id')} ended: {run.get('conclusion')}")
        else:
            require(status in {"queued", "in_progress", "waiting", "pending", "requested"},
                    f"unknown Qodana run status: {status}")
    active = [run for run in runs if run.get("status") != "completed"]
    require(len(active) <= 1, "multiple active full Qodana scans for exact candidate")
    if active:
        return active[0]
    return next((run for run in runs if run["id"] not in (ignored or set())), None)


def dispatch(repo: str, sha: str) -> None:
    # This tool never publishes. The destination Qodana workflow enforces main
    # ancestry and exact-source checkout with its own limited read-only token.
    subprocess.run(["gh", "api", "-X", "POST",
                    f"repos/{repo}/actions/workflows/{WORKFLOW}/dispatches",
                    "-f", "ref=main", "-f", f"inputs[candidate_sha]={sha}"],
                   check=True)


def run_preflight(sha: str, tag: str, repo: str,
                  timeout: int = WAIT_SECONDS, poll: int = POLL_SECONDS) -> dict:
    verify_source(sha, tag, repo)
    workflow = gh_json(f"repos/{repo}/actions/workflows/{WORKFLOW}")
    workflow_id = workflow.get("id")
    require(isinstance(workflow_id, int) and workflow_id > 0
            and workflow.get("state") == "active",
            "trusted Qodana full-scan workflow unavailable")
    candidate = select_run(repo, sha, workflow_id)
    # Records excluded below are *only* scans with genuinely expired/missing
    # original artifacts. Never exclude failed scans or invalid existing proof.
    expired_ids: set[int] = set()
    verified_ids: set[int] = set()
    verified_proof: dict | None = None
    dispatched = False
    if candidate is None:
        dispatch(repo, sha)
        dispatched = True
        print(f"Dispatched {WORKFLOW} for exact source {sha}", flush=True)
    else:
        print(f"Joining/reusing Qodana run {candidate['id']} for {sha}", flush=True)

    deadline = time.monotonic() + timeout
    registration_deadline = min(deadline, time.monotonic() + DISCOVERY_SECONDS)
    while True:
        current = select_run(repo, sha, workflow_id, expired_ids | verified_ids)
        if current is not None:
            status = current.get("status")
            if status == "completed":
                require(current.get("conclusion") == "success",
                        f"Qodana run {current.get('id')} failed")
                proof = verify_evidence(current, sha, workflow_id, repo,
                                        Path("qodana.yml"),
                                        allow_missing=not dispatched)
                if proof is None:
                    require(not dispatched,
                            "dispatched Qodana run lacks required original evidence")
                    expired_ids.add(current["id"])
                    print(f"Original Qodana evidence expired/missing for {current['id']}",
                          flush=True)
                    continue
                if not dispatched:
                    # Inspect all same-candidate source attestations, not just
                    # the most recent green scan. The last green cannot launder
                    # another run's malformed or security-positive evidence.
                    verified_ids.add(current["id"])
                    if verified_proof is None:
                        verified_proof = proof
                    continue
                verify_source(sha, tag, repo)
                proof["execution"] = "dispatched"
                return proof
            require(status in {"queued", "in_progress", "waiting", "pending", "requested"},
                    f"unknown Qodana run status: {status}")
        elif not dispatched:
            if verified_proof is not None:
                verify_source(sha, tag, repo)
                verified_proof["execution"] = "reused-or-joined"
                return verified_proof
            require(bool(expired_ids),
                    "previously visible Qodana scan vanished before verification")
            # At most one replacement when all original archives expired.
            dispatch(repo, sha)
            dispatched = True
            registration_deadline = min(deadline, time.monotonic() + DISCOVERY_SECONDS)
            print(f"Re-generating exact-source Qodana original evidence for {sha}",
                  flush=True)
        if time.monotonic() >= deadline:
            raise ValueError("Qodana release preflight timed out")
        if current is None and dispatched and time.monotonic() >= registration_deadline:
            raise ValueError("dispatched Qodana run did not register within 150 seconds")
        time.sleep(min(poll, max(0.1, deadline - time.monotonic())))


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
    # Discovery/dispatch state-machine negative controls. No network, no
    # scheduler delays, no GitHub Release publication and no actual dispatch.
    mock_active = dict(run, status="in_progress", conclusion=None)
    mock_failure = dict(run, conclusion="failure")
    new_scan = dict(run, id=18)
    proof = {"run_id": 17, "candidate_sha": sha}

    with patch(__name__ + ".gh_json", return_value={"workflow_runs": [run]}) as api:
        # Same record appearing under multiple event endpoints must fail closed.
        rejects(lambda: find_runs(repo, sha, workflow_id), "duplicate discovery run")
        assert api.call_count == 2

    def discovery(endpoint: str) -> dict:
        if "event=workflow_dispatch" in endpoint:
            return {"workflow_runs": [run]}
        return {"workflow_runs": []}

    with patch(__name__ + ".gh_json", side_effect=discovery):
        assert find_runs(repo, sha, workflow_id) == [run]
    with patch(__name__ + ".gh_json", return_value={"workflow_runs": [{}] * 100}):
        rejects(lambda: find_runs(repo, sha, workflow_id), "incomplete paged discovery")
    skipped = dict(run, event="schedule", head_sha=sha)
    skip_jobs = {"jobs": [
        {"name": GUARD, "status": "completed", "conclusion": "success"},
        {"name": SCAN, "status": "completed", "conclusion": "skipped"}], "total_count": 2}
    with patch(__name__ + ".gh_json", return_value=skip_jobs):
        assert intentional_schedule_skip(repo, skipped)
    with patch(__name__ + ".find_runs", return_value=[skipped]), \
         patch(__name__ + ".intentional_schedule_skip", return_value=True):
        assert select_run(repo, sha, workflow_id) is None
    for bad_list, description in [
        ([run, mock_failure], "failed same-source run"),
        ([dict(run, run_attempt=2)], "rerun ambiguity"),
        ([mock_active, dict(mock_active, id=18)], "multiple active scans")]:
        with patch(__name__ + ".find_runs", return_value=bad_list), \
             patch(__name__ + ".intentional_schedule_skip", return_value=False):
            rejects(lambda: select_run(repo, sha, workflow_id), description)
    with patch(__name__ + ".find_runs", return_value=[run]), \
         patch(__name__ + ".intentional_schedule_skip", return_value=False):
        assert select_run(repo, sha, workflow_id, ignored={17}) is None

    def exercise(sequence: list[dict | None], verification: list[dict | None],
                 expected_dispatches: int, expected_execution: str,
                 expected_error: str | None = None) -> None:
        def selection(*_args, **_kwargs):
            if not sequence:
                raise AssertionError("unexpected extra scan selection")
            return sequence.pop(0)
        def evidence(*_args, **_kwargs):
            if not verification:
                raise AssertionError("unexpected evidence read")
            return verification.pop(0)
        with patch(__name__ + ".verify_source") as check_source, \
             patch(__name__ + ".gh_json", return_value={"id": workflow_id, "state": "active"}), \
             patch(__name__ + ".select_run", side_effect=selection), \
             patch(__name__ + ".verify_evidence", side_effect=evidence), \
             patch(__name__ + ".dispatch") as send, \
             patch(__name__ + ".time.sleep"):
            if expected_error:
                try:
                    run_preflight(sha, "v1.0.0", repo, timeout=5, poll=0.01)
                except ValueError as exc:
                    require(expected_error in str(exc),
                            f"unexpected failure: {exc}")
                else:
                    raise AssertionError("unsafe state-machine path succeeded")
            else:
                result = run_preflight(sha, "v1.0.0", repo, timeout=5, poll=0.01)
                require(result["execution"] == expected_execution,
                        "wrong release Qodana execution mode")
                require(check_source.call_count >= 2,
                        "source identity not rechecked after scanner")
            require(send.call_count == expected_dispatches,
                    f"expected {expected_dispatches} dispatch(es), got {send.call_count}")

    exercise([run, run, None], [dict(proof)], 0, "reused-or-joined")
    exercise([mock_active, mock_active, run, None],
             [dict(proof)], 0, "reused-or-joined")
    exercise([None, None, new_scan], [dict(proof)], 1, "dispatched")
    exercise([run, run, None, None, new_scan],
             [None, dict(proof)], 1, "dispatched")
    exercise([None, mock_failure], [], 1, "", "failed")
    exercise([None, new_scan], [None], 1, "",
             "lacks required original evidence")

    print("release Qodana identity/evidence and dispatch-state negative controls passed")


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
    if not all([args.repository, args.candidate_sha, args.tag]):
        parser.error("--repository, --candidate-sha, and --tag required")
    try:
        if args.run_id is not None:
            verify_source(args.candidate_sha, args.tag, args.repository)
            wf = gh_json(f"repos/{args.repository}/actions/workflows/{WORKFLOW}")
            require(wf.get("state") == "active", "trusted Qodana workflow is unavailable")
            run = gh_json(f"repos/{args.repository}/actions/runs/{args.run_id}")
            proof = verify_evidence(run, args.candidate_sha, wf.get("id"),
                                    args.repository, Path("qodana.yml"))
            require(proof is not None, "original Qodana evidence is unavailable")
            verify_source(args.candidate_sha, args.tag, args.repository)
            proof["execution"] = "explicit-run"
        else:
            proof = run_preflight(args.candidate_sha, args.tag, args.repository)
        print(json.dumps(proof, sort_keys=True))
        return 0
    except (ValueError, OSError, subprocess.CalledProcessError,
            json.JSONDecodeError) as error:
        print(f"::error::Release Qodana proof rejected: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
