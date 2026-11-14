#!/usr/bin/env python3
"""Fail-closed PR compatibility adapter for the trusted Gradle dependency publisher."""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

SHA = re.compile(r"[0-9a-f]{40}\Z")
JOB = re.compile(r"^  ([A-Za-z0-9_-]+):\s*$", re.MULTILINE)
VERSION = re.compile(r'^intelliJPlatform\s*=\s*"([^"\s]+)"\s*$', re.MULTILINE)
COORDINATE_KEY = "org.jetbrains.intellij.platform:intellij-platform-gradle-plugin"
PIN_ACTIONS = ("actions/checkout", "actions/setup-java", "gradle/actions/dependency-submission")
RELEVANT_PATHS = frozenset({
    ".github/workflows/dependency-submission.yml",
    ".github/workflows/build.yml",
    ".github/scripts/trusted-dependency-submission-validation.py",
    ".github/scripts/check-dependency-submission.sh",
    "gradle/libs.versions.toml",
})
COORDINATE_LINE = re.compile(
    r'required = \{"(?P<coordinate>[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[^"\s]+)"\}\Z'
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def expected_coordinate(catalog: Path) -> str:
    matches = VERSION.findall(catalog.read_text(encoding="utf-8"))
    require(len(matches) == 1, "version catalog must declare exactly one intelliJPlatform version")
    version = matches[0]
    require(re.fullmatch(r"[0-9A-Za-z.+-]+", version) is not None,
            "unsupported intelliJPlatform version syntax")
    return f"{COORDINATE_KEY}:{version}"


def safe_path(value: object) -> str:
    require(isinstance(value, str) and value, "missing changed-file path")
    require(not value.startswith("/") and ".." not in value.split("/"),
            "unsafe changed-file path")
    return value


def require_supported_publisher_patch(entry: dict, coordinate: str) -> None:
    require(entry.get("status") == "modified",
            "unsupported dependency-submission workflow change type")
    patch = entry.get("patch")
    require(isinstance(patch, str) and patch,
            "dependency-submission change has no reviewable patch")
    changed = [
        (line[0], line[1:].strip())
        for line in patch.splitlines()
        if line.startswith(("+", "-")) and not line.startswith(("+++", "---"))
    ]
    require(changed, "dependency-submission change has no changed lines")
    allowed_action = re.compile(
        r"uses:\s*(?:actions/checkout|actions/setup-java|gradle/actions/dependency-submission)"
        r"@[0-9a-f]{40}(?:\s+#.*)?\Z"
    )
    if all(allowed_action.fullmatch(line) for _, line in changed):
        return

    removed = [line for sign, line in changed if sign == "-"]
    added = [line for sign, line in changed if sign == "+"]
    dynamic_verifier = [
        "python3 .github/scripts/trusted-dependency-submission-validation.py verify-main \\",
        "--dir dependency-graph-reports \\",
        '--head "$GITHUB_SHA" \\',
        "--version-catalog gradle/libs.versions.toml",
    ]
    legacy_coordinate = re.compile(
        rf'required = \{{"{re.escape(COORDINATE_KEY)}:[^"\s]+"\}}\Z'
    )
    if added == dynamic_verifier:
        require("python3 - <<'PY'" in removed and "PY" in removed,
                "dynamic verifier migration did not remove the legacy inline Python block")
        require(any(legacy_coordinate.fullmatch(line) for line in removed),
                "dynamic verifier migration did not remove the legacy hardcoded coordinate")
        forbidden_prefixes = (
            "uses:", "permissions:", "contents:", "dependency-graph:",
            "cache-read-only:", "dependency-graph-continue-on-failure:",
        )
        require(not any(line.startswith(forbidden_prefixes) for line in removed),
                "dynamic verifier migration touched publisher authority or execution policy")
        return

    raise ValueError("dependency-submission workflow mutation exceeds supported adapters")


def classify(pull: dict, pages: list, expected_head: str, coordinate: str) -> bool:
    require(SHA.fullmatch(expected_head or "") is not None, "invalid expected PR HEAD")
    require(isinstance(pull, dict) and isinstance(pull.get("head"), dict), "missing PR metadata")
    require(pull["head"].get("sha") == expected_head, "PR HEAD changed during classification")
    expected_count = pull.get("changed_files")
    require(type(expected_count) is int and 0 < expected_count <= 3000,
            "invalid or unsupported PR changed-file count")
    require(isinstance(pages, list) and pages and all(isinstance(page, list) for page in pages),
            "invalid paginated changed-file listing")
    entries = [entry for page in pages for entry in page]
    require(len(entries) == expected_count, "incomplete PR changed-file listing")
    seen: set[str] = set()
    paths: set[str] = set()
    for entry in entries:
        require(isinstance(entry, dict), "malformed changed-file entry")
        name = safe_path(entry.get("filename"))
        require(name not in seen, "duplicate changed-file entry")
        seen.add(name)
        status = entry.get("status")
        require(status in {"added","modified","removed","renamed","copied","changed","unchanged"},
                "unknown changed-file status")
        paths.add(name)
        if status == "renamed":
            paths.add(safe_path(entry.get("previous_filename")))
        if name == ".github/workflows/dependency-submission.yml":
            require_supported_publisher_patch(entry, coordinate)
    return bool(paths & RELEVANT_PATHS)


def extract_job(workflow: str, job_id: str) -> str:
    marker = re.search(rf"^  {re.escape(job_id)}:\s*$", workflow, re.MULTILINE)
    require(marker is not None, f"missing PR validation job {job_id}")
    tail = workflow[marker.end():]
    end = JOB.search(tail)
    return tail[:end.start()] if end else tail


def action_pin(text: str, action: str) -> str:
    pins = re.findall(
        rf"uses:\s*{re.escape(action)}@([0-9a-f]{{40}})(?:\s+#.*)?$",
        text, re.MULTILINE,
    )
    require(len(pins) == 1, f"expected exactly one {action} pin")
    return pins[0]


def check_contract(production: str, pr_workflow: str, coordinate: str) -> None:
    require("  pull_request:" not in production and "  pull_request_target:" not in production,
            "production dependency publisher must not run on PR events")
    require("      contents: write" in production,
            "production publisher lost contents:write authority")
    require("dependency-graph: generate-and-submit" in production,
            "production publisher is not in generate-and-submit mode")
    require("cache-read-only: true" in production,
            "production publisher may write Gradle cache")
    require("dependency-graph-continue-on-failure: false" in production,
            "production publisher no longer fails closed")
    dynamic_verifier = (
        "python3 .github/scripts/trusted-dependency-submission-validation.py verify-main \\" in production
        and "--dir dependency-graph-reports \\" in production
        and '--head "$GITHUB_SHA" \\' in production
        and "--version-catalog gradle/libs.versions.toml" in production
    )
    require(dynamic_verifier,
            "production publisher must verify graph coverage from the version catalog")
    require("python3 - <<'PY'" not in production,
            "production publisher must not duplicate dependency graph verification inline")

    job = extract_job(pr_workflow, "dependencySubmissionValidation")
    require("    permissions:\n      contents: read\n      pull-requests: read\n" in job,
            "PR dependency adapter permissions must be explicitly read-only")
    require(re.search(r"^\s+\S+:\s*write(?:\s+#.*)?$", job, re.MULTILINE) is None,
            "PR dependency adapter gained write authority")
    require("dependency-graph: generate-and-upload" in job,
            "PR dependency adapter must use generate-and-upload")
    require("dependency-graph: generate-and-submit" not in job,
            "PR dependency adapter must never submit")
    require("cache-read-only: true" in job,
            "PR dependency adapter may write Gradle cache")
    require("dependency-graph-continue-on-failure: false" in job,
            "PR dependency adapter no longer fails closed")
    require("dependency-graph-report-dir: dependency-graph-reports-pr" in job,
            "PR dependency adapter report directory drifted")
    require(
        "repository: ${{ github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name || github.repository }}" in job
        and "ref: ${{ github.event_name == 'pull_request' && github.event.pull_request.head.sha || inputs.target_sha || github.sha }}" in job,
        "PR dependency adapter must checkout the exact requested source",
    )
    require("persist-credentials: false" in job,
            "PR dependency adapter checkout credentials must not persist")
    for action in PIN_ACTIONS:
        require(action_pin(production, action) == action_pin(job, action),
                f"PR adapter {action} pin does not match production publisher")


def production_eligible(graph: dict, source_sha: str) -> bool:
    return graph.get("sha") == source_sha and graph.get("ref") == "refs/heads/main"


def verify_main_graph(directory: Path, expected_head: str, coordinate: str) -> dict:
    require(SHA.fullmatch(expected_head or "") is not None, "invalid protected-main HEAD")
    files = sorted(directory.rglob("*.json"))
    require(len(files) == 1, f"expected exactly one dependency graph report, got {len(files)}")
    data = json.loads(files[0].read_text(encoding="utf-8"))
    require(isinstance(data, dict), "dependency graph report is not an object")
    require(data.get("sha") == expected_head, "dependency graph source SHA is not exact main HEAD")
    require(data.get("ref") == "refs/heads/main", "dependency graph ref is not protected main")
    manifests = data.get("manifests")
    require(isinstance(manifests, dict) and manifests, "dependency graph has no manifests")
    resolved: dict[str, dict] = {}
    for manifest in manifests.values():
        require(isinstance(manifest, dict), "malformed dependency graph manifest")
        nodes = manifest.get("resolved")
        require(isinstance(nodes, dict), "dependency graph manifest lacks resolved dependencies")
        resolved.update(nodes)
    require(len(resolved) >= 450,
            f"dependency graph lost required coverage: {len(resolved)} < 450")
    require(coordinate in resolved, f"dependency graph lost required coordinate: {coordinate}")
    direct = sum(isinstance(node, dict) and node.get("relationship") == "direct"
                 for node in resolved.values())
    require(direct >= 40, f"dependency graph lost direct coverage: {direct} < 40")
    return {
        "source_sha": expected_head,
        "event_ref": "refs/heads/main",
        "manifests": len(manifests),
        "resolved": len(resolved),
        "direct": direct,
        "production_evidence": True,
    }


def verify_graph(directory: Path, expected_head: str, event_ref: str, coordinate: str) -> dict:
    require(SHA.fullmatch(expected_head or "") is not None, "invalid expected PR HEAD")
    require(isinstance(event_ref, str) and event_ref.startswith("refs/pull/"),
            "dependency fixture did not run in pull_request ref context")
    require(event_ref != "refs/heads/main",
            "PR dependency fixture cannot use protected-main provenance")
    files = sorted(directory.rglob("*.json"))
    require(len(files) == 1, f"expected exactly one dependency graph report, got {len(files)}")
    data = json.loads(files[0].read_text(encoding="utf-8"))
    require(isinstance(data, dict), "dependency graph report is not an object")
    require(data.get("sha") == expected_head, "dependency graph source SHA is not exact PR HEAD")
    require(data.get("ref") == event_ref, "dependency graph ref is not the PR event ref")
    require(not production_eligible(data, expected_head),
            "PR dependency graph was admitted as production evidence")
    manifests = data.get("manifests")
    require(isinstance(manifests, dict) and manifests, "dependency graph has no manifests")
    resolved: dict[str, dict] = {}
    for manifest in manifests.values():
        require(isinstance(manifest, dict), "malformed dependency graph manifest")
        nodes = manifest.get("resolved")
        require(isinstance(nodes, dict), "dependency graph manifest lacks resolved dependencies")
        resolved.update(nodes)
    require(len(resolved) >= 450,
            f"dependency graph lost required coverage: {len(resolved)} < 450")
    require(coordinate in resolved, f"dependency graph lost required coordinate: {coordinate}")
    direct = sum(isinstance(node, dict) and node.get("relationship") == "direct"
                 for node in resolved.values())
    require(direct >= 40, f"dependency graph lost direct coverage: {direct} < 40")
    return {
        "source_sha": expected_head,
        "event_ref": event_ref,
        "manifests": len(manifests),
        "resolved": len(resolved),
        "direct": direct,
        "production_evidence": False,
    }


def self_test() -> None:
    import tempfile
    sha = "a" * 40
    coordinate = COORDINATE_KEY + ":fixture-current"
    pull = {"head": {"sha": sha}, "changed_files": 1}
    assert classify(pull, [[{
        "filename": ".github/scripts/trusted-dependency-submission-validation.py",
        "status": "modified",
    }]], sha, coordinate)
    assert not classify(pull, [[{"filename": "README.md", "status": "modified"}]], sha, coordinate)

    pin_patch = (
        "@@ -1 +1 @@\n"
        "-        uses: gradle/actions/dependency-submission@" + "b" * 40 + " # v6.3.0\n"
        "+        uses: gradle/actions/dependency-submission@" + "c" * 40 + " # v6.4.1"
    )
    assert classify(pull, [[{
        "filename": ".github/workflows/dependency-submission.yml",
        "status": "modified",
        "patch": pin_patch,
    }]], sha, coordinate)

    migration_patch = (
        "@@ -1,3 +1,4 @@\n"
        "-          python3 - <<'PY'\n"
        '-          required = {"' + COORDINATE_KEY + ':fixture-previous"}\n'
        "-          PY\n"
        "+          python3 .github/scripts/trusted-dependency-submission-validation.py verify-main \\\n"
        "+            --dir dependency-graph-reports \\\n"
        '+            --head "$GITHUB_SHA" \\\n'
        "+            --version-catalog gradle/libs.versions.toml"
    )
    assert classify(pull, [[{
        "filename": ".github/workflows/dependency-submission.yml",
        "status": "modified",
        "patch": migration_patch,
    }]], sha, coordinate)

    bad_cases = [
        ({"head": {"sha": sha}, "changed_files": 2},
         [[{"filename": "README.md", "status": "modified"}]], sha, coordinate),
        (pull, [[{"filename": "../bad", "status": "modified"}]], sha, coordinate),
        (pull, [[{"filename": "new", "status": "renamed"}]], sha, coordinate),
        (pull, [[{
            "filename": ".github/workflows/dependency-submission.yml",
            "status": "modified",
            "patch": "@@ -1 +1 @@\n-permissions: {}\n+permissions: write-all",
        }]], sha, coordinate),
        (pull, [[{
            "filename": ".github/workflows/dependency-submission.yml",
            "status": "modified",
            "patch": (
                "@@ -1 +1 @@\n"
                '-          required = {"' + COORDINATE_KEY + ':fixture-previous"}\n'
                '+          required = {"' + coordinate + '"}'
            ),
        }]], sha, coordinate),
        (pull, [[{
            "filename": ".github/workflows/dependency-submission.yml",
            "status": "removed",
            "patch": pin_patch,
        }]], sha, coordinate),
        (pull, [[{"filename": "README.md", "status": "modified"}]], "d" * 40, coordinate),
    ]
    for bad_pull, bad_pages, bad_sha, bad_coordinate in bad_cases:
        try:
            classify(bad_pull, bad_pages, bad_sha, bad_coordinate)
        except ValueError:
            pass
        else:
            raise AssertionError("unsafe changed-file classification was accepted")

    prod = (
        "on:\n  push:\npermissions: {}\njobs:\n  submitGraph:\n"
        "    permissions:\n      contents: write\n    steps:\n"
        "      - uses: actions/checkout@" + "1" * 40 + "\n"
        "      - uses: actions/setup-java@" + "2" * 40 + "\n"
        "      - uses: gradle/actions/dependency-submission@" + "3" * 40 + "\n"
        "        with:\n          cache-read-only: true\n"
        "          dependency-graph: generate-and-submit\n"
        "          dependency-graph-continue-on-failure: false\n"
        "      - name: Verify submitted graph coverage and provenance\n"
        "        run: |\n"
        "          set -euo pipefail\n"
        "          python3 .github/scripts/trusted-dependency-submission-validation.py verify-main \\\n"
        "            --dir dependency-graph-reports \\\n"
        "            --head \"$GITHUB_SHA\" \\\n"
        "            --version-catalog gradle/libs.versions.toml\n"
    )
    pr = (
        "jobs:\n  dependencySubmissionValidation:\n"
        "    permissions:\n      contents: read\n      pull-requests: read\n"
        "    steps:\n"
        "      - uses: actions/checkout@" + "1" * 40 + "\n"
        "        with:\n"
        "          repository: ${{ github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name || github.repository }}\n"
        "          ref: ${{ github.event_name == 'pull_request' && github.event.pull_request.head.sha || inputs.target_sha || github.sha }}\n"
        "          persist-credentials: false\n"
        "      - uses: actions/setup-java@" + "2" * 40 + "\n"
        "      - uses: gradle/actions/dependency-submission@" + "3" * 40 + "\n"
        "        with:\n          cache-read-only: true\n"
        "          dependency-graph: generate-and-upload\n"
        "          dependency-graph-report-dir: dependency-graph-reports-pr\n"
        "          dependency-graph-continue-on-failure: false\n"
    )
    check_contract(prod, pr, coordinate)
    for mutated in (
        pr.replace("dependency-graph: generate-and-upload", "dependency-graph: generate-and-submit"),
        pr.replace("contents: read", "contents: write", 1),
        pr.replace("cache-read-only: true", "cache-read-only: false"),
        pr.replace("gradle/actions/dependency-submission@" + "3" * 40,
                   "gradle/actions/dependency-submission@" + "4" * 40),
    ):
        try:
            check_contract(prod, mutated, coordinate)
        except ValueError:
            pass
        else:
            raise AssertionError("unsafe PR dependency adapter contract was accepted")

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        catalog = root / "libs.versions.toml"
        catalog.write_text('intelliJPlatform = "fixture-current"\n', encoding="utf-8")
        assert expected_coordinate(catalog) == coordinate
        resolved = {f"example:indirect:{i}": {"relationship": "indirect"} for i in range(410)}
        for i in range(39):
            resolved[f"example:direct:{i}"] = {"relationship": "direct"}
        resolved[coordinate] = {"relationship": "direct"}
        graph = {
            "sha": sha,
            "ref": "refs/pull/7/merge",
            "manifests": {"root": {"resolved": resolved}},
        }
        (root / "graph.json").write_text(json.dumps(graph), encoding="utf-8")
        result = verify_graph(root, sha, "refs/pull/7/merge", coordinate)
        assert result["resolved"] == 450 and result["direct"] == 40
        assert result["production_evidence"] is False
        graph["ref"] = "refs/heads/main"
        (root / "graph.json").write_text(json.dumps(graph), encoding="utf-8")
        main_result = verify_main_graph(root, sha, coordinate)
        assert main_result["resolved"] == 450 and main_result["direct"] == 40
        assert main_result["production_evidence"] is True
        graph["ref"] = "refs/pull/7/merge"
        (root / "graph.json").write_text(json.dumps(graph), encoding="utf-8")
        try:
            verify_graph(root, sha, "refs/heads/main", coordinate)
        except ValueError:
            pass
        else:
            raise AssertionError("protected-main provenance was accepted for PR fixture")
        graph["sha"] = "b" * 40
        (root / "graph.json").write_text(json.dumps(graph), encoding="utf-8")
        try:
            verify_graph(root, sha, "refs/pull/7/merge", coordinate)
        except ValueError:
            pass
        else:
            raise AssertionError("wrong source SHA was accepted")

    print("trusted dependency-submission PR adapter self-tests passed")


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    classify_p = sub.add_parser("classify")
    classify_p.add_argument("--pull", type=Path, required=True)
    classify_p.add_argument("--pages", type=Path, required=True)
    classify_p.add_argument("--head", required=True)
    classify_p.add_argument("--version-catalog", type=Path, required=True)
    contract_p = sub.add_parser("check-contract")
    contract_p.add_argument("--production", type=Path, required=True)
    contract_p.add_argument("--pr-workflow", type=Path, required=True)
    contract_p.add_argument("--version-catalog", type=Path, required=True)
    verify_p = sub.add_parser("verify")
    verify_p.add_argument("--dir", type=Path, required=True)
    verify_p.add_argument("--head", required=True)
    verify_p.add_argument("--event-ref", required=True)
    verify_p.add_argument("--version-catalog", type=Path, required=True)
    verify_main_p = sub.add_parser("verify-main")
    verify_main_p.add_argument("--dir", type=Path, required=True)
    verify_main_p.add_argument("--head", required=True)
    verify_main_p.add_argument("--version-catalog", type=Path, required=True)
    sub.add_parser("self-test")
    args = parser.parse_args()
    try:
        if args.command == "classify":
            coordinate = expected_coordinate(args.version_catalog)
            required = classify(
                json.loads(args.pull.read_text(encoding="utf-8")),
                json.loads(args.pages.read_text(encoding="utf-8")),
                args.head, coordinate,
            )
            print("required=" + str(required).lower())
        elif args.command == "check-contract":
            coordinate = expected_coordinate(args.version_catalog)
            check_contract(
                args.production.read_text(encoding="utf-8"),
                args.pr_workflow.read_text(encoding="utf-8"),
                coordinate,
            )
        elif args.command == "verify":
            coordinate = expected_coordinate(args.version_catalog)
            print(json.dumps(
                verify_graph(args.dir, args.head, args.event_ref, coordinate),
                sort_keys=True,
            ))
        elif args.command == "verify-main":
            coordinate = expected_coordinate(args.version_catalog)
            print(json.dumps(
                verify_main_graph(args.dir, args.head, coordinate),
                sort_keys=True,
            ))
        else:
            self_test()
    except (ValueError, OSError, KeyError, TypeError, json.JSONDecodeError) as exc:
        print(f"::error::Trusted dependency-submission PR validation rejected: {exc}",
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
