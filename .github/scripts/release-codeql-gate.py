#!/usr/bin/env python3
"""Fail-closed exact-source-SHA CodeQL release preflight.

The release candidate is a protected-main ancestor, independently of the
workflow_dispatch event SHA. Reuse authenticated completed evidence, join an
eligible in-flight scan, or dispatch *one* trusted workflow and wait. This file
never publishes a release or reads publication credentials.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from unittest.mock import patch

import importlib.util

# Reuse MarkFlow's existing strict original-SARIF verdict without duplicating it.
_spec = importlib.util.spec_from_file_location(
    "markflow_codeql_verdict", Path(__file__).with_name("release-codeql-verdict.py"))
_verdict = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_verdict)
check = _verdict.check

LANGUAGES = ("actions", "javascript-typescript", "java-kotlin")
SHA = re.compile(r"^[0-9a-f]{40}$")
TAG = re.compile(r"v[1-9][0-9]*\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\Z")
WORKFLOW = "codeql.yml"
RUN_PREFIX = "CodeQL source "
GUARD_JOB = "CodeQL schedule guard"
WAIT_SECONDS = 80 * 60
POLL_SECONDS = 15


def require(ok: bool, message: str) -> None:
    if not ok:
        raise ValueError(message)


def gh_json(endpoint: str) -> dict:
    result = json.loads(subprocess.check_output(["gh", "api", endpoint], text=True))
    require(isinstance(result, dict), f"invalid API response: {endpoint}")
    return result


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True).strip()


def verify_source(sha: str, tag: str, repo: str) -> None:
    require(bool(SHA.fullmatch(sha)), "release candidate must be an exact SHA")
    require(bool(TAG.fullmatch(tag)), "release tag must be v-prefixed SemVer")
    require(repo == os.environ.get("GITHUB_REPOSITORY"), "repository mismatch")
    require(git("rev-parse", "HEAD") == sha, "release checkout SHA mismatch")
    require(git("rev-parse", f"refs/tags/{tag}^{{commit}}") == sha,
            "tag or release candidate SHA mismatch")
    # A local checkout/ref does not prove the remote tag stayed unchanged while
    # a long-running scan completed. Dereference annotated or lightweight tags.
    refs = dict(reversed(line.split("\t", 1)) for line in git(
        "ls-remote", "--tags", "origin", f"refs/tags/{tag}",
        f"refs/tags/{tag}^{{}}").splitlines())
    remote_tag = refs.get(f"refs/tags/{tag}^{{}}", refs.get(f"refs/tags/{tag}"))
    require(remote_tag == sha, "remote release tag moved or is unavailable")
    # Protected main and protected publication tag are verified independently
    # by the repository rulesets. Never require the tag SHA to be today's HEAD.
    subprocess.run(["git", "fetch", "--no-tags", "origin", "main"], check=True)
    require(subprocess.run(["git", "merge-base", "--is-ancestor", sha, "FETCH_HEAD"],
                           check=False).returncode == 0,
            "candidate is not in current protected main history")


def eligible_run(run: dict, sha: str, workflow_id: int) -> bool:
    if not isinstance(run, dict):
        return False
    if (run.get("workflow_id") != workflow_id
            or run.get("path") != ".github/workflows/codeql.yml"
            or run.get("head_branch") != "main"
            or run.get("event") not in {"schedule", "workflow_dispatch"}):
        return False
    # Historical runs without an independently checked source manifest are not
    # eligible for publication, even when their event SHA equals the candidate.
    return run.get("display_title") == RUN_PREFIX + sha


def verify_run(run: dict, sha: str, workflow_id: int) -> bool:
    require(eligible_run(run, sha, workflow_id), "wrong CodeQL run source or identity")
    require(run.get("run_attempt") == 1, "rerun/attempt ambiguity")
    require(isinstance(run.get("id"), int) and run["id"] > 0,
            "invalid CodeQL run ID")
    require(run.get("status") == "completed" and run.get("conclusion") == "success",
            "CodeQL workflow did not complete successfully")
    require(isinstance(run.get("head_sha"), str)
            and bool(SHA.fullmatch(run["head_sha"])), "invalid workflow event SHA")
    return run.get("display_title") == RUN_PREFIX + sha


def verify_jobs(payload: dict, sha: str, modern: bool) -> None:
    jobs = payload.get("jobs")
    require(isinstance(jobs, list) and payload.get("total_count") == len(jobs),
            "incomplete CodeQL job listing")
    expected_names = {f"CodeQL ({language})" for language in LANGUAGES}
    allowed_names = expected_names | {GUARD_JOB}
    names = {j.get("name") for j in jobs if isinstance(j, dict)}
    require(names <= allowed_names, "unexpected CodeQL workflow job")
    guards = [j for j in jobs if isinstance(j, dict) and j.get("name") == GUARD_JOB]
    require(len(guards) <= 1, "duplicate CodeQL schedule guard job")
    if guards:
        require(guards[0].get("status") == "completed"
                and guards[0].get("conclusion") == "success",
                "CodeQL schedule guard did not complete successfully")
    for language in LANGUAGES:
        matches = [j for j in jobs if isinstance(j, dict)
                   and j.get("name") == f"CodeQL ({language})"]
        require(len(matches) == 1, f"missing or duplicate {language} CodeQL job")
        job = matches[0]
        require(job.get("status") == "completed" and job.get("conclusion") == "success",
                f"{language} CodeQL job failed, skipped or was cancelled")
        # GitHub job.head_sha is the *event* SHA, not the historical checkout.
        require(job.get("head_sha") is None or bool(SHA.fullmatch(job["head_sha"])),
                f"{language} has malformed job event SHA")
        steps = job.get("steps")
        require(isinstance(steps, list), f"no {language} job-step attestation")
        required = ["Verify exact checkout"]
        if modern:
            required += ["Verify protected-main ancestry", "Record analyzed source",
                         "Verify protected-main ancestry after analysis"]
        for name in required:
            matches = [s for s in steps if isinstance(s, dict) and s.get("name") == name]
            require(len(matches) == 1 and matches[0].get("conclusion") == "success",
                    f"{language} missing successful {name} attestation")


def verify_artifacts(payload: dict, sha: str, run_id: int, modern: bool,
                     allow_missing: bool = False) -> dict | None:
    items = payload.get("artifacts")
    require(isinstance(items, list) and payload.get("total_count") == len(items),
            "incomplete CodeQL artifact listing")
    found = {}
    missing = False
    for language in LANGUAGES:
        names = {"sarif": f"release-codeql-{sha}-{language}"}
        if modern:
            names["source"] = f"codeql-source-{sha}-{language}"
        for kind, name in names.items():
            matches = [a for a in items if isinstance(a, dict) and a.get("name") == name]
            require(len(matches) <= 1, f"duplicate {language} {kind} artifact")
            if not matches:
                if allow_missing:
                    missing = True
                    continue
                require(False, f"missing or duplicate {language} {kind} artifact")
            art = matches[0]
            require(isinstance(art.get("size_in_bytes"), int)
                    and art["size_in_bytes"] > 0, f"{language} {kind} artifact expired or empty")
            if isinstance(art.get("workflow_run"), dict):
                require(art["workflow_run"].get("id") == run_id,
                        f"{language} {kind} belongs to another run")
            if art.get("expired") is True:
                if allow_missing:
                    missing = True
                    continue
                require(False, f"{language} {kind} artifact expired or empty")
            require(art.get("expired") is False,
                    f"{language} {kind} unknown expiry state")
        found[language] = names
    # Expiration means *absence of usable proof*, not acceptance of a bad
    # manifest or a failed security finding. Malformed present artifacts fail.
    return None if missing else found


def download(run_id: int, repo: str, name: str, destination: Path) -> None:
    subprocess.run(["gh", "run", "download", str(run_id), "-R", repo,
                    "-n", name, "-D", str(destination)], check=True)


def verify_manifest(root: Path, sha: str, run: dict, language: str) -> None:
    files = [x for x in root.rglob("*") if x.is_file()]
    require(len(files) == 1 and files[0].name == "source-identity.json",
            f"{language} source manifest missing or ambiguous")
    document = json.loads(files[0].read_text(encoding="utf-8"))
    require(isinstance(document, dict), "source manifest is malformed")
    expected = {"schema": 1, "candidate_sha": sha, "checkout_sha": sha,
                "event_sha": run["head_sha"], "run_id": run["id"],
                "run_attempt": 1, "language": language}
    require(document == expected, f"{language} actual checkout identity mismatch")


def verify_evidence(run: dict, sha: str, workflow_id: int, repo: str,
                    allow_missing: bool = False) -> dict | None:
    modern = verify_run(run, sha, workflow_id)
    require(run.get("head_repository", {}).get("full_name") == repo,
            "CodeQL head repository does not match trusted repository")
    rid = run["id"]
    # The event SHA must itself come from the protected-main history. New
    # source identity is independently attested by the verified checkout step.
    subprocess.run(["git", "fetch", "--no-tags", "origin", "main"], check=True)
    require(subprocess.run(["git", "merge-base", "--is-ancestor",
                            run["head_sha"], "FETCH_HEAD"], check=False).returncode == 0,
            "CodeQL event SHA is not trusted main history")
    jobs = gh_json(f"repos/{repo}/actions/runs/{rid}/jobs?filter=latest&per_page=100")
    verify_jobs(jobs, sha, modern)
    artifacts = gh_json(f"repos/{repo}/actions/runs/{rid}/artifacts?per_page=100")
    names = verify_artifacts(artifacts, sha, rid, modern, allow_missing=allow_missing)
    if names is None:
        return None
    proof = {}
    with tempfile.TemporaryDirectory(prefix="codeql-preflight-") as temp:
        root = Path(temp)
        for lang, entry in names.items():
            if modern:
                source_dir = root / lang / "source"
                source_dir.mkdir(parents=True)
                download(rid, repo, entry["source"], source_dir)
                verify_manifest(source_dir, sha, run, lang)
            sarif_dir = root / lang / "sarif"
            sarif_dir.mkdir(parents=True)
            download(rid, repo, entry["sarif"], sarif_dir)
            proof[lang] = check(sarif_dir, lang, sha)
    return {"run_id": rid, "attempt": 1, "candidate_sha": sha, "event_sha": run["head_sha"],
            "mode": "source-attested" if modern else "legacy-exact-event-sha",
            "languages": proof, "high_or_critical": 0}


def find_runs(repo: str, sha: str, workflow_id: int) -> list[dict]:
    found = []
    # Searching by event head_sha would exclude historical-commit scans.
    # Search only approved trigger types. Continue page-wise with an explicit
    # limit; silent truncation would permit missing a failed exact-source scan.
    # A history beyond the finite bound is a security NO-GO, not a green reuse.
    for event in ("workflow_dispatch", "schedule"):
        # Paginate instead of silently interpreting the most recent 100
        # runs as a complete source history. An exceeded bound is NO-GO.
        for page in range(1, 11):
            data = gh_json(
                f"repos/{repo}/actions/workflows/{WORKFLOW}/runs"
                f"?event={event}&per_page=100&page={page}"
            )
            runs = data.get("workflow_runs")
            require(isinstance(runs, list) and len(runs) <= 100,
                    "malformed workflow run listing")
            require(all(isinstance(x, dict) for x in runs),
                    "malformed CodeQL discovery record")
            found.extend(x for x in runs if eligible_run(x, sha, workflow_id))
            if len(runs) < 100:
                break
        else:
            raise ValueError("CodeQL discovery exceeded the safe 1000-run bound")
    return sorted(found, key=lambda r: (r.get("created_at", ""), r.get("id", 0)),
                  reverse=True)


def intentional_schedule_skip(repo: str, run: dict) -> bool:
    if (run.get("event") != "schedule"
            or run.get("status") != "completed"
            or run.get("conclusion") != "success"
            or not isinstance(run.get("id"), int)):
        return False
    payload = gh_json(f"repos/{repo}/actions/runs/{run['id']}/jobs?filter=latest&per_page=100")
    jobs = payload.get("jobs")
    require(isinstance(jobs, list) and payload.get("total_count") == len(jobs),
            "incomplete CodeQL schedule-guard job listing")
    guards = [j for j in jobs if isinstance(j, dict) and j.get("name") == GUARD_JOB]
    if len(guards) != 1 or guards[0].get("conclusion") != "success":
        return False
    others = [j for j in jobs if j is not guards[0]]
    return all(isinstance(j, dict) and j.get("conclusion") == "skipped" for j in others)


def select_run(repo: str, sha: str, workflow_id: int,
               ignored: set[int] | None = None) -> dict | None:
    runs = [r for r in find_runs(repo, sha, workflow_id)
            if not intentional_schedule_skip(repo, r)]
    # A later green scanner cannot mask a historical failed/rerun attempt.
    for run in runs:
        require(run.get("run_attempt") == 1,
                "ambiguous rerun/attempt for candidate CodeQL scan")
        if run.get("status") == "completed":
            require(run.get("conclusion") == "success",
                    f"CodeQL run {run.get('id')} ended: {run.get('conclusion')}")
    active = [r for r in runs if r.get("status") != "completed"]
    require(len(active) <= 1, "multiple concurrent CodeQL scans for candidate SHA")
    if active:
        return active[0]
    return next((r for r in runs if r.get("id") not in (ignored or set())), None)


def dispatch(repo: str, sha: str) -> None:
    subprocess.run(["gh", "api", "-X", "POST",
                    f"repos/{repo}/actions/workflows/{WORKFLOW}/dispatches",
                    "-f", "ref=main", "-f", f"inputs[candidate_sha]={sha}"], check=True)


def run_preflight(sha: str, tag: str, repo: str,
                  timeout: int = WAIT_SECONDS, poll: int = POLL_SECONDS) -> dict:
    verify_source(sha, tag, repo)
    workflow = gh_json(f"repos/{repo}/actions/workflows/{WORKFLOW}")
    workflow_id = workflow.get("id")
    require(isinstance(workflow_id, int) and workflow_id > 0
            and workflow.get("state") == "active", "trusted CodeQL workflow unavailable")
    selected = select_run(repo, sha, workflow_id)
    # A previously successful full scan whose original archive aged out is
    # skipped only after its identity and complete matrix were verified.
    expired_ids: set[int] = set()
    verified_ids: set[int] = set()
    verified_proof: dict | None = None
    dispatched = False
    if selected is None:
        # The destination workflow only accepts trusted protected-main sources.
        # All independent release jobs are serialized per release tag, and the
        # scanner itself serializes identical SHA requests via concurrency.
        dispatch(repo, sha)
        dispatched = True
        print(f"Dispatched {WORKFLOW} for exact source {sha}", flush=True)
    else:
        print(f"Joining/reusing CodeQL run {selected['id']} for {sha}", flush=True)

    deadline = time.monotonic() + timeout
    discovery_deadline = min(deadline, time.monotonic() + 150)
    while True:
        current = select_run(repo, sha, workflow_id, expired_ids | verified_ids)
        if current is not None:
            require(current.get("run_attempt") == 1,
                    "CodeQL rerun/attempt ambiguity")
            status = current.get("status")
            if status == "completed":
                require(current.get("conclusion") == "success",
                        f"CodeQL run {current.get('id')} ended: {current.get('conclusion')}")
                result = verify_evidence(current, sha, workflow_id, repo,
                                         allow_missing=not dispatched)
                if result is None:
                    # Only expiration/absent archives reach here. A newly
                    # dispatched successful scanner lacking proof is NO-GO.
                    expired_ids.add(current["id"])
                    print(f"Original CodeQL evidence expired/missing for run "
                          f"{current['id']}; searching older verified proof",
                          flush=True)
                    continue
                if not dispatched:
                    # Do not let the first green scanner conceal malformed,
                    # security-positive or untrusted same-source duplicates.
                    verified_ids.add(current["id"])
                    if verified_proof is None:
                        verified_proof = result
                    print(f"Independently verified complete CodeQL run {current['id']}",
                          flush=True)
                    continue
                verify_source(sha, tag, repo)
                result["execution"] = "dispatched"
                return result
            require(status in {"queued", "in_progress", "waiting", "pending", "requested"},
                    f"unrecognized CodeQL run status: {status}")
        elif not dispatched:
            if verified_proof is not None:
                verify_source(sha, tag, repo)
                verified_proof["execution"] = "reused-or-joined"
                return verified_proof
            if not expired_ids:
                raise ValueError("previously visible CodeQL run vanished")
            # Exhausted all completed candidates with genuinely expired or
            # missing original artifacts. One canonical scan, never a retry
            # loop or security-failure laundering.
            dispatch(repo, sha)
            dispatched = True
            # Registration latency begins with this new dispatch, not with
            # the potentially lengthy audit of historical eligible artifacts.
            discovery_deadline = min(deadline, time.monotonic() + 150)
            print(f"Regenerating exact-source CodeQL original proof for {sha}",
                  flush=True)
        if time.monotonic() >= deadline:
            raise ValueError("CodeQL preflight timed out before complete verified evidence")
        if current is None and time.monotonic() >= discovery_deadline:
            raise ValueError("dispatched CodeQL run did not appear within 150 seconds")
        time.sleep(min(poll, max(0.1, deadline - time.monotonic())))


def self_test() -> None:
    sha = "a" * 40
    other = "b" * 40

    # An older protected-main candidate is valid, but not if main ancestry or
    # its remote publication tag is altered before the verdict.
    def source_git(*args):
        if args == ("rev-parse", "HEAD"):
            return sha
        if args == ("rev-parse", "refs/tags/v1.0.0^{commit}"):
            return sha
        if args[:3] == ("ls-remote", "--tags", "origin"):
            return sha + "\trefs/tags/v1.0.0"
        raise AssertionError(f"unexpected git call: {args!r}")

    with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), \
         patch(__name__ + ".git", side_effect=source_git), \
         patch(__name__ + ".subprocess.run") as source_run:
        source_run.return_value.returncode = 0
        verify_source(sha, "v1.0.0", "owner/repo")
        source_run.return_value.returncode = 1
        try:
            verify_source(sha, "v1.0.0", "owner/repo")
        except ValueError as error:
            assert "not in current protected main" in str(error)
        else:
            raise AssertionError("untrusted main ancestry accepted")

    with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), \
         patch(__name__ + ".git", side_effect=lambda *args: (
             other + "\trefs/tags/v1.0.0" if args[:3] ==
             ("ls-remote", "--tags", "origin") else sha)):
        try:
            verify_source(sha, "v1.0.0", "owner/repo")
        except ValueError as error:
            assert "remote release tag moved" in str(error)
        else:
            raise AssertionError("changed remote release tag accepted")

    modern = {"workflow_id": 2, "path": ".github/workflows/codeql.yml",
              "head_sha": other, "head_branch": "main", "event": "workflow_dispatch",
              "display_title": RUN_PREFIX + sha, "run_attempt": 1,
              "status": "completed", "conclusion": "success", "id": 17}
    legacy = dict(modern, head_sha=sha, display_title="CodeQL")
    assert verify_run(modern, sha, 2)
    assert not eligible_run(legacy, sha, 2)
    steps = [{"name": name, "conclusion": "success"} for name in
             ("Verify exact checkout", "Verify protected-main ancestry",
              "Record analyzed source", "Verify protected-main ancestry after analysis")]
    jobs = {"total_count": 3, "jobs": [
        {"name": f"CodeQL ({language})", "status": "completed",
         "conclusion": "success", "head_sha": other, "steps": steps}
        for language in LANGUAGES]}
    verify_jobs(jobs, sha, True)
    guarded_jobs = json.loads(json.dumps(jobs))
    guarded_jobs["jobs"].insert(0, {
        "name": GUARD_JOB, "status": "completed", "conclusion": "success",
        "head_sha": other, "steps": []
    })
    guarded_jobs["total_count"] = 4
    verify_jobs(guarded_jobs, sha, True)
    skipped_schedule = dict(modern, event="schedule", head_sha=sha)
    skipped_jobs = {"total_count": 4, "jobs": [{
        "name": GUARD_JOB, "status": "completed", "conclusion": "success"
    }] + [{
        "name": f"CodeQL ({language})", "status": "completed", "conclusion": "skipped"
    } for language in LANGUAGES]}
    with patch(__name__ + ".gh_json", return_value=skipped_jobs):
        assert intentional_schedule_skip("owner/repo", skipped_schedule)
    not_skipped_jobs = json.loads(json.dumps(skipped_jobs))
    not_skipped_jobs["jobs"][1]["conclusion"] = "success"
    with patch(__name__ + ".gh_json", return_value=not_skipped_jobs):
        assert not intentional_schedule_skip("owner/repo", skipped_schedule)
    artifacts = {"total_count": 6, "artifacts": [
        {"name": f"release-codeql-{sha}-{lang}" if kind == "sarif" else
         f"codeql-source-{sha}-{lang}", "expired": False, "size_in_bytes": 100,
         "workflow_run": {"id": 17}}
        for lang in LANGUAGES for kind in ("sarif", "source")]}
    verify_artifacts(artifacts, sha, 17, True)
    with tempfile.TemporaryDirectory() as directory:
        item = Path(directory) / "source-identity.json"
        doc = {"schema": 1, "candidate_sha": sha, "checkout_sha": sha,
               "event_sha": other, "run_id": 17, "run_attempt": 1, "language": "actions"}
        item.write_text(json.dumps(doc))
        verify_manifest(Path(directory), sha, modern, "actions")

        def reject(fn, fragment):
            try:
                fn()
            except (ValueError, json.JSONDecodeError) as error:
                assert fragment in str(error), (fragment, str(error))
            else:
                raise AssertionError("invalid CodeQL evidence accepted: " + fragment)

        for change, fragment in [
            ({"checkout_sha": other}, "identity mismatch"),
            ({"candidate_sha": other}, "identity mismatch"),
            ({"event_sha": sha}, "identity mismatch"),
            ({"run_attempt": 2}, "identity mismatch"),
            ({"run_id": 9}, "identity mismatch"),
            ({"language": "java-kotlin"}, "identity mismatch"),
        ]:
            item.write_text(json.dumps(dict(doc, **change)))
            reject(lambda: verify_manifest(Path(directory), sha, modern, "actions"),
                   fragment)
        item.write_text("{broken")
        reject(lambda: verify_manifest(Path(directory), sha, modern, "actions"),
               "Expecting property name")
    for change, frag in [
        ({"head_branch": "feature"}, "identity"),
        ({"event": "pull_request"}, "identity"),
        ({"display_title": "CodeQL source " + other}, "identity"),
        ({"run_attempt": 2}, "attempt"),
        ({"conclusion": "cancelled"}, "successfully"),
        ({"workflow_id": 4}, "identity"),
    ]:
        reject(lambda change=change: verify_run(dict(modern, **change), sha, 2), frag)
    bad_jobs = json.loads(json.dumps(jobs))
    bad_jobs["jobs"][0]["steps"][1]["conclusion"] = "failure"
    reject(lambda: verify_jobs(bad_jobs, sha, True), "attestation")
    bad_jobs = json.loads(json.dumps(jobs))
    bad_jobs["jobs"][0]["name"] = "CodeQL (other)"
    reject(lambda: verify_jobs(bad_jobs, sha, True), "unexpected CodeQL workflow job")
    bad_guard = json.loads(json.dumps(guarded_jobs))
    bad_guard["jobs"][0]["conclusion"] = "failure"
    reject(lambda: verify_jobs(bad_guard, sha, True), "schedule guard")
    for change, fragment in [
        ({"expired": True}, "expired or empty"),
        ({"size_in_bytes": 0}, "expired or empty"),
        ({"workflow_run": {"id": 99}}, "another run"),
    ]:
        bad = json.loads(json.dumps(artifacts))
        bad["artifacts"][0].update(change)
        reject(lambda bad=bad: verify_artifacts(bad, sha, 17, True), fragment)
    wrong_sarif_name = json.loads(json.dumps(artifacts))
    wrong_sarif_name["artifacts"][0]["name"] = f"codeql-{sha}-actions"
    reject(lambda: verify_artifacts(wrong_sarif_name, sha, 17, True),
           "missing or duplicate")
    bad = dict(artifacts, total_count=7)
    reject(lambda: verify_artifacts(bad, sha, 17, True), "incomplete")

    # Exercise the state machine, including registration latency, reuse,
    # in-progress joining, explicit failures, and timeout without sleeping.
    def exercise(sequence, expected_dispatches, failure=None):
        snapshots = iter(sequence)
        last = sequence[-1]
        scans = []
        def choose(*args, **kwargs):
            try:
                entry = next(snapshots)
            except StopIteration:
                entry = last
            scans.append(entry)
            ignored = args[3] if len(args) > 3 else kwargs.get("ignored", set())
            if entry is not None and entry.get("id") in ignored:
                return None
            return entry
        with patch(__name__ + ".verify_source"), \
             patch(__name__ + ".gh_json", return_value={"id": 2, "state": "active"}), \
             patch(__name__ + ".select_run", side_effect=choose), \
             patch(__name__ + ".dispatch") as invoke, \
             patch(__name__ + ".verify_evidence", return_value={"run_id": 17}), \
             patch(__name__ + ".time.sleep"), \
             patch(__name__ + ".time.monotonic",
                   side_effect=(x for x in range(0, 10000))):
            if failure:
                reject(lambda: run_preflight(sha, "v1.0.0", "owner/repo",
                                           timeout=50, poll=1), failure)
            else:
                result = run_preflight(sha, "v1.0.0", "owner/repo",
                                       timeout=50, poll=1)
                assert result["run_id"] == 17
            assert invoke.call_count == expected_dispatches

    # An old successful matrix with genuinely expired original archives is
    # classified as missing, but wrong source, empty, duplicate or malformed
    # present proof remains a security NO-GO.
    expired = json.loads(json.dumps(artifacts))
    expired["artifacts"][0]["expired"] = True
    assert verify_artifacts(expired, sha, 17, True, allow_missing=True) is None
    absent = json.loads(json.dumps(artifacts))
    absent["artifacts"].pop()
    absent["total_count"] -= 1
    assert verify_artifacts(absent, sha, 17, True, allow_missing=True) is None
    corrupted = json.loads(json.dumps(expired))
    corrupted["artifacts"][0]["workflow_run"]["id"] = 99
    reject(lambda: verify_artifacts(corrupted, sha, 17, True,
                                    allow_missing=True), "another run")
    empty = json.loads(json.dumps(expired))
    empty["artifacts"][0]["size_in_bytes"] = 0
    reject(lambda: verify_artifacts(empty, sha, 17, True,
                                    allow_missing=True), "expired or empty")

    with patch(__name__ + ".find_runs", return_value=[
        modern, dict(modern, id=18, conclusion="failure")]):
        reject(lambda: select_run("owner/repo", sha, 2), "ended")
    with patch(__name__ + ".find_runs", return_value=[
        modern, dict(modern, id=18, run_attempt=2)]):
        reject(lambda: select_run("owner/repo", sha, 2), "rerun")
    with patch(__name__ + ".find_runs", return_value=[
        modern, dict(modern, id=18)]):
        assert select_run("owner/repo", sha, 2, {17})["id"] == 18

    long_page = [dict(modern, id=100 + i) for i in range(100)]
    with patch(__name__ + ".gh_json", side_effect=[
        {"workflow_runs": long_page},
        {"workflow_runs": [modern]},
        {"workflow_runs": []},
    ]) as paged:
        assert len(find_runs("owner/repo", sha, 2)) == 101
        assert paged.call_count == 3

    # A concurrent second exact-candidate full scan cannot be joined
    # ambiguously, even when no historical completed result exists.
    active = dict(modern, status="in_progress", conclusion=None)
    with patch(__name__ + ".find_runs", return_value=[
        active, dict(active, id=18)]):
        reject(lambda: select_run("owner/repo", sha, 2), "multiple concurrent")
    with patch(__name__ + ".gh_json", return_value={
        "workflow_runs": long_page
    }):
        reject(lambda: find_runs("owner/repo", sha, 2), "1000-run bound")
    queued = dict(modern, status="queued", conclusion=None)
    failed = dict(modern, conclusion="failure")
    exercise([modern, modern], 0)
    # Two independently eligible complete same-SHA scans must each pass their
    # original SARIF and source-manifest verification; reuse only afterwards.
    with patch(__name__ + ".verify_source"), \
         patch(__name__ + ".gh_json", return_value={"id": 2, "state": "active"}), \
         patch(__name__ + ".select_run", side_effect=[
             modern, modern, dict(modern, id=18), None]), \
         patch(__name__ + ".dispatch") as unnecessary, \
         patch(__name__ + ".verify_evidence", side_effect=[
             {"run_id": 17}, {"run_id": 18}]) as checks, \
         patch(__name__ + ".time.sleep"), \
         patch(__name__ + ".time.monotonic",
               side_effect=(x for x in range(0, 10000))):
        reuse = run_preflight(sha, "v1.0.0", "owner/repo",
                              timeout=50, poll=1)
        assert reuse["execution"] == "reused-or-joined"
        assert checks.call_count == 2 and unnecessary.call_count == 0

    exercise([None, None, queued, active, modern], 1)
    exercise([active, active, modern], 0)
    exercise([failed, failed], 0, "ended")
    exercise([None] * 10, 1, "timed out")
    # A stale completed scanner first requires fresh canonical dispatch;
    # the complete new scanner may then authorize the unchanged source.
    with patch(__name__ + ".verify_source"), \
         patch(__name__ + ".gh_json", return_value={"id": 2, "state": "active"}), \
         patch(__name__ + ".select_run", side_effect=[
             modern, modern, None, queued, active, modern]), \
         patch(__name__ + ".dispatch") as regenerated, \
         patch(__name__ + ".verify_evidence", side_effect=[
             None, {"run_id": 17}]) as checks, \
         patch(__name__ + ".time.sleep"), \
         patch(__name__ + ".time.monotonic",
               side_effect=(x for x in range(0, 10000))):
        result = run_preflight(sha, "v1.0.0", "owner/repo",
                               timeout=50, poll=1)
        assert result["execution"] == "dispatched"
        assert checks.call_count == 2 and regenerated.call_count == 1
    print("Exact-source CodeQL reuse/dispatch/join/wait negative controls passed")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--candidate-sha", "--sha", dest="sha")
    parser.add_argument("--repository")
    parser.add_argument("--tag")
    args = parser.parse_args()
    try:
        if args.self_test:
            self_test()
            return
        if not args.sha or not args.tag or not args.repository:
            parser.error("--repository, --candidate-sha and --tag are required")
        proof = run_preflight(args.sha, args.tag, args.repository)
        print("Exact source CodeQL preflight accepted: " + json.dumps(proof, sort_keys=True))
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as out:
                out.write("### Exact-source CodeQL preflight\n\n```json\n")
                out.write(json.dumps(proof, indent=2, sort_keys=True))
                out.write("\n```\n")
    except (ValueError, OSError, subprocess.CalledProcessError, json.JSONDecodeError,
            TypeError, KeyError) as error:
        print(f"::error::Release CodeQL preflight rejected: {error}", file=sys.stderr)
        raise SystemExit(1) from error


if __name__ == "__main__":
    main()
