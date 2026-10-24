#!/usr/bin/env python3
"""Trusted-base classifier for MarkFlow's Dependabot dependency fast lane.

Unsupported or ambiguous input always falls back to full CI. Production routing
may consume a positive result only from the exact trusted PR base revision.
"""
from __future__ import annotations

import argparse
import json
import sys
import tomllib
from pathlib import Path
from typing import Any

DEPENDABOT = "dependabot[bot]"
NPM_PACKAGE = "webview/package.json"
NPM_LOCK = "webview/package-lock.json"
NPM_PATHS = {NPM_PACKAGE, NPM_LOCK}
GRADLE_CATALOG = "gradle/libs.versions.toml"
DEP_SECTIONS = ("dependencies", "devDependencies", "optionalDependencies", "peerDependencies")
STATUSES = {"modified", "changed"}


def result(fast: bool, ecosystem: str, reason: str) -> tuple[bool, str, str]:
    return fast, ecosystem, reason


def parse_changed(rows: list[str], expected: str) -> tuple[bool, str, set[str]]:
    if not expected.isdigit() or expected.startswith("0") or len(rows) != int(expected):
        return False, "changed-file-count-invalid", set()

    paths: set[str] = set()
    for row in rows:
        cells = row.split("\t")
        if len(cells) != 3:
            return False, "changed-file-record-invalid", set()
        status, path, previous = cells
        if (
            status not in STATUSES
            or previous
            or not path
            or path.startswith("/")
            or any(part in ("", ".", "..") for part in path.split("/"))
            or path in paths
        ):
            return False, "unsupported-or-unsafe-change", set()
        paths.add(path)
    return (True, "changed-files-valid", paths) if paths else (False, "empty-change-set", set())


def dep_tables(obj: dict[str, Any]) -> dict[str, dict[str, Any]]:
    tables: dict[str, dict[str, Any]] = {}
    for name in DEP_SECTIONS:
        value = obj.get(name, {})
        if not isinstance(value, dict):
            raise ValueError(f"{name} must be an object")
        tables[name] = value
    return tables


def classify_npm(
    paths: set[str],
    base_manifest: dict[str, Any],
    head_manifest: dict[str, Any],
    base_lock: dict[str, Any],
    head_lock: dict[str, Any],
) -> tuple[bool, str, str]:
    if not paths.issubset(NPM_PATHS):
        return result(False, "npm", "npm-mixed-paths")

    base_deps = dep_tables(base_manifest)
    head_deps = dep_tables(head_manifest)

    base_other = {k: v for k, v in base_manifest.items() if k not in DEP_SECTIONS}
    head_other = {k: v for k, v in head_manifest.items() if k not in DEP_SECTIONS}
    if base_other != head_other:
        return result(False, "npm", "package-json-nondependency-change")

    manifest_changed = False
    for name in DEP_SECTIONS:
        if set(base_deps[name]) != set(head_deps[name]):
            return result(False, "npm", "npm-package-set-change")
        manifest_changed = manifest_changed or base_deps[name] != head_deps[name]

    if NPM_PACKAGE in paths and not manifest_changed:
        return result(False, "npm", "package-json-without-version-change")
    if NPM_PACKAGE not in paths and manifest_changed:
        return result(False, "npm", "undeclared-package-json-delta")

    for key in ("name", "version", "lockfileVersion", "requires"):
        if base_lock.get(key) != head_lock.get(key):
            return result(False, "npm", f"lock-{key}-change")
    if base_lock == head_lock:
        return result(False, "npm", "lockfile-unchanged")

    # npm ci in the eventual fast lane owns manifest/lock resolution consistency.
    return result(True, "npm", "ordinary-npm-version-or-resolution-update")


def version_refs(table: Any) -> set[str]:
    if not isinstance(table, dict):
        raise ValueError("catalog section must be a table")
    refs: set[str] = set()
    for entry in table.values():
        if not isinstance(entry, dict):
            continue
        version = entry.get("version")
        if isinstance(version, dict) and isinstance(version.get("ref"), str):
            refs.add(version["ref"])
    return refs


def classify_gradle(base: bytes, head: bytes) -> tuple[bool, str, str]:
    before = tomllib.loads(base.decode("utf-8"))
    after = tomllib.loads(head.decode("utf-8"))

    before_versions = before.get("versions")
    after_versions = after.get("versions")
    if not isinstance(before_versions, dict) or not isinstance(after_versions, dict):
        return result(False, "gradle", "catalog-versions-table-missing")
    if set(before_versions) != set(after_versions):
        return result(False, "gradle", "catalog-version-key-set-change")

    if {k: v for k, v in before.items() if k != "versions"} != {
        k: v for k, v in after.items() if k != "versions"
    }:
        return result(False, "gradle", "catalog-structure-change")

    changed = {k for k in before_versions if before_versions[k] != after_versions[k]}
    if not changed:
        return result(False, "gradle", "catalog-no-version-change")

    library_refs = version_refs(before.get("libraries", {}))
    plugin_refs = version_refs(before.get("plugins", {}))
    for key in changed:
        if key in plugin_refs:
            return result(False, "gradle", f"catalog-plugin-version:{key}")
        if key not in library_refs:
            return result(False, "gradle", f"catalog-unclassified-version:{key}")

    return result(True, "gradle", "catalog-library-version-update")


def classify(
    actor: str,
    rows: list[str],
    expected: str,
    *,
    npm: tuple[dict[str, Any], dict[str, Any], dict[str, Any], dict[str, Any]] | None = None,
    gradle: tuple[bytes, bytes] | None = None,
) -> tuple[bool, str, str]:
    if actor != DEPENDABOT:
        return result(False, "none", "actor-not-dependabot")

    ok, reason, paths = parse_changed(rows, expected)
    if not ok:
        return result(False, "none", reason)

    if paths.issubset(NPM_PATHS):
        if npm is None:
            return result(False, "npm", "missing-npm-input")
        return classify_npm(paths, *npm)

    if paths == {GRADLE_CATALOG}:
        if gradle is None:
            return result(False, "gradle", "missing-gradle-input")
        return classify_gradle(*gradle)

    return result(False, "none", "mixed-high-risk-or-unknown-path")


def fixture_manifest(**versions: str) -> dict[str, Any]:
    return {
        "name": "webview",
        "private": True,
        "scripts": {"build": "tsc && vite build"},
        "dependencies": {"mermaid": versions.get("mermaid", "12.2.0")},
        "devDependencies": {"vite": versions.get("vite", "^8.3.4")},
    }


def fixture_lock(marker: str) -> dict[str, Any]:
    return {
        "name": "webview",
        "version": "0.0.0",
        "lockfileVersion": 3,
        "requires": True,
        "packages": {"node_modules/example": {"version": marker}},
    }


def fixture_catalog(junit: str = "6.1.3", kotlin: str = "2.4.21") -> bytes:
    return f"""
[versions]
junit = "{junit}"
kotlin = "{kotlin}"

[libraries]
junit = {{ group = "org.junit.jupiter", name = "junit-jupiter", version.ref = "junit" }}

[plugins]
kotlin = {{ id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }}
""".strip().encode()


def self_test() -> None:
    current_manifest = fixture_manifest()
    current_lock = fixture_lock("2")
    npm_rows = [f"modified\t{NPM_PACKAGE}\t", f"modified\t{NPM_LOCK}\t"]

    old_mermaid = fixture_manifest(mermaid="12.1.0")
    assert classify(
        DEPENDABOT,
        npm_rows,
        "2",
        npm=(old_mermaid, current_manifest, fixture_lock("1"), current_lock),
    )[0], "Mermaid/Vite/direct npm version updates are scope-eligible"

    assert classify(
        DEPENDABOT,
        [f"modified\t{NPM_LOCK}\t"],
        "1",
        npm=(current_manifest, current_manifest, fixture_lock("1"), current_lock),
    )[0], "transitive npm lock resolution is scope-eligible"

    old_junit = fixture_catalog(junit="5.14.4")
    assert classify(
        DEPENDABOT,
        [f"modified\t{GRADLE_CATALOG}\t"],
        "1",
        gradle=(old_junit, fixture_catalog()),
    )[0], "ordinary Gradle library update is scope-eligible"

    old_kotlin = fixture_catalog(kotlin="2.4.20")
    assert not classify(
        DEPENDABOT,
        [f"modified\t{GRADLE_CATALOG}\t"],
        "1",
        gradle=(old_kotlin, fixture_catalog()),
    )[0], "Gradle plugin update must use full CI"

    for path in (
        "gradle/wrapper/gradle-wrapper.properties",
        ".github/workflows/build.yml",
        "build.gradle.kts",
        "settings.gradle.kts",
        "src/main/kotlin/App.kt",
    ):
        assert not classify(DEPENDABOT, [f"modified\t{path}\t"], "1")[0]

    assert not classify(
        "human-maintainer",
        [f"modified\t{NPM_LOCK}\t"],
        "1",
        npm=(current_manifest, current_manifest, fixture_lock("1"), current_lock),
    )[0], "initial pilot is Dependabot-only"

    changed_script = fixture_manifest()
    changed_script["scripts"] = {"build": "tsc && vite build && echo changed"}
    assert not classify(
        DEPENDABOT,
        npm_rows,
        "2",
        npm=(current_manifest, changed_script, fixture_lock("1"), current_lock),
    )[0]

    assert not classify(
        DEPENDABOT,
        [f"modified\t{NPM_LOCK}\t"],
        "2",
        npm=(current_manifest, current_manifest, fixture_lock("1"), current_lock),
    )[0]


def load_json(path: str) -> dict[str, Any]:
    value = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("JSON root must be an object")
    return value


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--actor")
    parser.add_argument("--expected-count")
    parser.add_argument("--base-package-json")
    parser.add_argument("--head-package-json")
    parser.add_argument("--base-package-lock")
    parser.add_argument("--head-package-lock")
    parser.add_argument("--base-gradle-catalog")
    parser.add_argument("--head-gradle-catalog")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        print("dependency fast-lane shadow fixtures: PASS")
        return 0

    if not args.actor or not args.expected_count:
        selected = result(False, "none", "missing-core-input")
    else:
        rows = sys.stdin.read().splitlines()
        ok, _, paths = parse_changed(rows, args.expected_count)
        try:
            if ok and paths.issubset(NPM_PATHS):
                required = (
                    args.base_package_json,
                    args.head_package_json,
                    args.base_package_lock,
                    args.head_package_lock,
                )
                npm = tuple(load_json(p) for p in required) if all(required) else None
                selected = classify(args.actor, rows, args.expected_count, npm=npm)
            elif ok and paths == {GRADLE_CATALOG}:
                required = (args.base_gradle_catalog, args.head_gradle_catalog)
                gradle = tuple(Path(p).read_bytes() for p in required) if all(required) else None
                selected = classify(args.actor, rows, args.expected_count, gradle=gradle)
            else:
                selected = classify(args.actor, rows, args.expected_count)
        except (OSError, UnicodeDecodeError, ValueError, json.JSONDecodeError, tomllib.TOMLDecodeError) as exc:
            selected = result(False, "none", f"input-error:{type(exc).__name__}")

    fast, ecosystem, reason = selected
    print(f"fast_lane={str(fast).lower()}")
    print(f"ecosystem={ecosystem}")
    print(f"reason={reason}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
