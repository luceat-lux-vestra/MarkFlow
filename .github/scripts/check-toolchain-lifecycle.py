#!/usr/bin/env python3
"""Fail-closed local consistency and scheduled authoritative upstream drift check."""
import argparse
import json
import re
from pathlib import Path
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[2]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def parse_node_lts(data):
    require(isinstance(data, list), "invalid Node release index")
    versions = [tuple(map(int, row["version"][1:].split(".")))
                for row in data if isinstance(row.get("lts"), str)
                and row["lts"] and re.fullmatch(r"v\d+\.\d+\.\d+", row.get("version", ""))]
    require(versions, "no LTS release found")
    return ".".join(map(str, max(versions)))


def parse_runner_catalog(data):
    versions = []
    for line in data.splitlines():
        if not line.startswith("| Ubuntu ") or "| arm64 |" not in line:
            continue
        if re.search(r"\b(?:preview|beta|deprecated)\b", line, re.I):
            continue
        label = re.search(r"\| arm64 \| `ubuntu-(\d+)\.(\d+)-arm` \|", line)
        if label:
            versions.append(tuple(map(int, label.groups())))
    require(versions, "no GA Ubuntu ARM64 label found in provider catalog")
    major, minor = max(versions)
    return f"ubuntu-{major}.{minor:02}-arm"


def local():
    entries = {e["id"]: e for e in json.loads(read(".github/dependency-update-policy.json"))["entries"]}
    for entry in entries.values():
        require(all((ROOT / p).is_file() or (ROOT / p).is_dir() for p in entry["source_paths"]),
                f"{entry['id']}: missing source")
    node = read(".nvmrc").strip()
    require(node == "lts/*" and entries["renderer-node"]["strategy"] == "CHANNEL"
            and entries["renderer-node"]["provenance"]["selector"] == node,
            "renderer Node must use newest-LTS channel")
    pkg = json.loads(read("webview/package.json"))
    lock = json.loads(read("webview/package-lock.json"))["packages"][""]
    require(re.fullmatch(r">=\d+", pkg["engines"]["node"]), "Node engine must allow future LTS")
    require(pkg["engines"] == lock["engines"] and pkg["devDependencies"] == lock["devDependencies"],
            "renderer lock root mismatch")
    runner = entries["github-hosted-arm64"]["provenance"]["label"]
    require(re.fullmatch(r"ubuntu-\d+\.\d+-arm", runner), "invalid ARM64 runner label")
    require(re.findall(r"^    - (ubuntu-\d+\.\d+-arm)$", read(".github/actionlint.yaml"), re.M) == [runner],
            "actionlint compatibility label mismatch")
    java_match = re.findall(r"jvmToolchain\((\d+)\)", read("build.gradle.kts"))
    require(len(java_match) == 1, "expected one JVM toolchain")
    java = int(java_match[0])
    runner_paths = set(entries["github-hosted-arm64"]["source_paths"])
    java_paths = set(entries["java-build-lts"]["source_paths"])
    for path in sorted((ROOT / ".github/workflows").glob("*.yml")):
        p = str(path.relative_to(ROOT))
        text = read(p)
        runners = re.findall(r"^\s*runs-on:\s*(ubuntu-[\d.]+-arm)\s*$", text, re.M)
        if runners:
            require(p in runner_paths and set(runners) == {runner}, f"runner ownership drift: {p}")
        versions = re.findall(r"^([ \t]*)java-version: ['\"]?(\d+)['\"]?\s*$", text, re.M)
        fresh = re.findall(r"^([ \t]*)java-version: ['\"]?(\d+)['\"]?\n\1check-latest: true$", text, re.M)
        if versions:
            require(p in java_paths and versions == fresh and all(int(v) == java for _, v in versions),
                    f"Java selector drift: {p}")
    for p in runner_paths:
        labels = re.findall(r"ubuntu-\d+\.\d+-arm", read(p))
        require(labels and set(labels) == {runner}, f"ARM64 declaration/fixture mismatch: {p}")
    require('package-ecosystem: "gomod"' in read(".github/dependabot.yml")
            and 'directory: "/.github/tools/actionlint"' in read(".github/dependabot.yml"),
            "actionlint native updater absent")
    print(json.dumps({"node": node, "runner": runner, "java": java}, sort_keys=True))
    return node, runner, java


def fetch(url):
    with urlopen(url, timeout=25) as response:
        return response.read().decode("utf-8")


def upstream(current):
    actual = (
        parse_node_lts(json.loads(fetch("https://nodejs.org/dist/index.json"))),
        parse_runner_catalog(fetch("https://raw.githubusercontent.com/actions/runner-images/main/README.md")),
        json.loads(fetch("https://api.adoptium.net/v3/info/available_releases"))["most_recent_lts"],
    )
    print(json.dumps({"observed": current, "upstream_targets": actual}))
    node, runner, java = actual
    pkg = json.loads(read("webview/package.json"))
    major = int(node.split(".")[0])
    require(major >= int(pkg["engines"]["node"][2:]), "Node LTS below supported floor")
    require(pkg["devDependencies"]["@types/node"].startswith(f"^{major}."),
            "Node LTS types major needs ordinary Dependabot/compatibility review")
    require(current[0] == "lts/*" and current[1:] == (runner, java),
            "runner or Java LTS drift: ordinary reviewed migration required")


def self_test():
    assert parse_node_lts([{"version":"v27.0.0","lts":False},
                           {"version":"v26.13.1","lts":"LTS"},
                           {"version":"v24.99.0","lts":"older"}]) == "26.13.1"
    assert parse_runner_catalog("| Ubuntu 26.04 Arm64 | arm64 | `ubuntu-26.04-arm` | image |\n"
                                "| Ubuntu 28.04 Arm64 beta | arm64 | `ubuntu-28.04-arm` | image |") == "ubuntu-26.04-arm"
    for fn, value in ((parse_node_lts, []), (parse_runner_catalog, "missing")):
        try:
            fn(value)
        except ValueError:
            pass
        else:
            raise AssertionError("missing upstream metadata accepted")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--upstream", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
    current = local()
    if args.upstream:
        upstream(current)
    print("Toolchain lifecycle contract: PASS")
