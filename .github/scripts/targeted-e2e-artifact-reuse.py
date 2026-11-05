#!/usr/bin/env python3
"""Fail-closed same-SHA plugin reuse for manual targeted workflows (#515)."""
import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

WORKFLOWS = {
    "starter": (".github/workflows/starter-driver-e2e.yml", "starter-driver-plugin-", "scenario_sets"),
    "differential": (".github/workflows/full-differential-acceptance.yml", "full-differential-plugin-", "scenario_set"),
}
STARTER_SETS = "canonical,core-editing,ordinary-markdown,derived-content,lifecycle,visual-acceptance,full-differential-acceptance"


def require(check, reason):
    if not check:
        raise ValueError(reason)


def api(endpoint, token):
    request = urllib.request.Request("https://api.github.com/" + endpoint,
        headers={"Accept": "application/vnd.github+json", "Authorization": "Bearer " + token,
                 "X-GitHub-Api-Version": "2022-11-28"})
    with urllib.request.urlopen(request, timeout=12) as response:
        return json.load(response)


def discover(repo, sha, current, fetch=api, token=""):
    require(re.fullmatch(r"[\w.-]+/[\w.-]+", repo) is not None, "Invalid repo")
    require(re.fullmatch(r"[0-9a-f]{40}", sha) is not None and current > 0, "Invalid run/SHA")
    endpoint = "repos/" + repo + "/actions/runs?" + urllib.parse.urlencode(
        {"head_sha": sha, "event": "workflow_dispatch", "status": "success", "per_page": 50})
    for run in fetch(endpoint, token).get("workflow_runs", [])[:50]:
        if not isinstance(run, dict):
            continue
        rid = run.get("id")
        if (not isinstance(rid, int) or rid <= 0 or rid == current
                or run.get("head_sha") != sha or run.get("event") != "workflow_dispatch"
                or run.get("status") != "completed" or run.get("conclusion") != "success"
                or (run.get("head_repository") or {}).get("full_name") != repo):
            continue
        for producer, (path, prefix, _) in WORKFLOWS.items():
            if run.get("path") != path:
                continue
            name = prefix + str(rid)
            for artifact in fetch(f"repos/{repo}/actions/runs/{rid}/artifacts?per_page=100", token).get("artifacts", []):
                if (artifact.get("name") == name and artifact.get("expired") is False
                        and isinstance(artifact.get("id"), int)
                        and isinstance(artifact.get("size_in_bytes"), int)
                        and artifact["size_in_bytes"] > 0):
                    return {"hit": "true", "run_id": str(rid), "artifact_name": name,
                            "producer": producer}
    return {"hit": "false"}


def verify(folder, output, sha, platform, producer, consumer):
    require(producer in WORKFLOWS and consumer in WORKFLOWS, "Unknown producer/consumer")
    values = {}
    for line in (folder / "manifest.txt").read_text(encoding="utf-8").splitlines():
        key, sep, value = line.partition("=")
        require(bool(sep) and key not in values and bool(key) and bool(value), "Bad manifest")
        values[key] = value
    scenario_key = WORKFLOWS[producer][2]
    require(set(values) == {"source_sha", "platform_version", "plugin_archive",
                            "plugin_sha256", scenario_key}, "Unexpected manifest keys")
    require(re.fullmatch(r"[0-9a-f]{40}", sha) is not None and values["source_sha"] == sha,
            "Source identity mismatch")
    require(values["platform_version"] == platform and
            re.fullmatch(r"[0-9]{4}\.[0-9]+\.[0-9]+", platform) is not None,
            "Pinned platform mismatch")
    require(re.fullmatch(r"[A-Za-z0-9_.-]+\.zip", values["plugin_archive"]) is not None
            and ".." not in values["plugin_archive"], "Unsafe archive name")
    require(values[scenario_key] == (STARTER_SETS if producer == "starter" else
                                      "full-differential-acceptance"), "Scenario contract mismatch")
    require(re.fullmatch(r"[0-9a-f]{64}", values["plugin_sha256"]) is not None,
            "Malformed digest")
    source = folder / "plugin.zip"
    require(source.is_file() and zipfile.is_zipfile(source), "Missing/invalid ZIP")
    with source.open("rb") as file:
        actual = hashlib.file_digest(file, "sha256").hexdigest()
    require(actual == values["plugin_sha256"], "Plugin SHA-256 mismatch")
    output.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, output / "plugin.zip")
    target_key = WORKFLOWS[consumer][2]
    target_scenarios = STARTER_SETS if consumer == "starter" else "full-differential-acceptance"
    (output / "manifest.txt").write_text(
        f"source_sha={sha}\nplugin_archive={values['plugin_archive']}\n"
        f"plugin_sha256={actual}\nplatform_version={platform}\n"
        f"{target_key}={target_scenarios}\n", encoding="utf-8")


def self_test():
    sha = "a" * 40
    repo = "example/MarkFlow"
    good = {"id": 3, "head_sha": sha, "event": "workflow_dispatch", "status": "completed",
            "conclusion": "success", "head_repository": {"full_name": repo},
            "path": WORKFLOWS["starter"][0]}
    artifact = {"name": "starter-driver-plugin-3", "id": 42, "size_in_bytes": 42, "expired": False}
    def fetch(_endpoint, _token):
        return {"artifacts": [artifact]} if "/artifacts?" in _endpoint else {"workflow_runs": [good]}
    assert discover(repo, sha, 4, fetch)["hit"] == "true"
    assert discover(repo, sha, 3, fetch)["hit"] == "false"
    for key, value in [("head_sha", "b" * 40), ("event", "push"),
                       ("status", "in_progress"), ("conclusion", "failure"),
                       ("path", "untrusted.yml")]:
        def wrong(endpoint, token):
            return {"artifacts": [artifact]} if "/artifacts?" in endpoint else {
                "workflow_runs": [{**good, key: value}]}
        assert discover(repo, sha, 4, wrong)["hit"] == "false"
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        src, dest = root / "source", root / "output"
        src.mkdir()
        with zipfile.ZipFile(src / "plugin.zip", "w") as z:
            z.writestr("plugin.xml", "test")
        digest = hashlib.sha256((src / "plugin.zip").read_bytes()).hexdigest()
        manifest = (f"source_sha={sha}\nplugin_archive=MarkFlow.zip\n"
                    f"plugin_sha256={digest}\nplatform_version=2026.2.3\n"
                    f"scenario_sets={STARTER_SETS}\n")
        (src / "manifest.txt").write_text(manifest)
        verify(src, dest, sha, "2026.2.3", "starter", "differential")
        assert "scenario_set=full-differential-acceptance" in (dest / "manifest.txt").read_text()
        for bad in (manifest.replace(sha, "b" * 40), manifest.replace(digest, "0" * 64),
                    manifest.replace("2026.2.3", "2026.2.2"), manifest + "source_sha=x\n"):
            (src / "manifest.txt").write_text(bad)
            try:
                verify(src, root / "invalid", sha, "2026.2.3", "starter", "differential")
            except ValueError:
                pass
            else:
                raise AssertionError("Invalid artifact accepted")
        assert not (root / "invalid").exists()
    print("Same-SHA plugin reuse negative controls: PASS")


def main():
    p = argparse.ArgumentParser()
    mode = p.add_mutually_exclusive_group(required=True)
    mode.add_argument("--self-test", action="store_true")
    mode.add_argument("--discover", action="store_true")
    mode.add_argument("--verify", action="store_true")
    for name in ("repository", "source-sha", "platform-version", "producer", "consumer"):
        p.add_argument("--" + name, default="")
    p.add_argument("--run-id", type=int, default=0)
    p.add_argument("--input-dir", type=Path)
    p.add_argument("--output-dir", type=Path)
    args = p.parse_args()
    if args.self_test:
        self_test()
    elif args.discover:
        try:
            result = discover(args.repository, args.source_sha, args.run_id,
                              token=os.environ.get("GH_TOKEN", ""))
        except (OSError, ValueError) as exc:
            print("::notice::Artifact lookup unavailable, build fresh: " + type(exc).__name__, file=sys.stderr)
            result = {"hit": "false"}
        for key, value in result.items():
            print(f"{key}={value}")
    else:
        try:
            verify(args.input_dir, args.output_dir, args.source_sha,
                   args.platform_version, args.producer, args.consumer)
        except (OSError, ValueError) as exc:
            print("::warning::Artifact identity rejected, build fresh: " + str(exc), file=sys.stderr)
            print("reused=false")
        else:
            print("reused=true")


if __name__ == "__main__":
    main()
