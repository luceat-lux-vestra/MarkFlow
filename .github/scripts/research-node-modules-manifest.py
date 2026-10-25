#!/usr/bin/env python3
import hashlib
import os
import pathlib
import sys

def load_manifest(path: pathlib.Path):
    result = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        if not raw:
            continue
        kind, rel, value = raw.split("\t", 2)
        result[rel] = (kind, value)
    return result

def snapshot(root_arg: str, output_arg: str) -> int:
    root = pathlib.Path(root_arg)
    output = pathlib.Path(output_arg)
    if not root.exists():
        raise SystemExit(f"missing tree: {root}")
    entries = []
    file_bytes = 0
    for path in sorted(root.rglob("*"), key=lambda p: p.as_posix()):
        rel = path.relative_to(root).as_posix()
        if path.is_symlink():
            entries.append(f"L\t{rel}\t{os.readlink(path)}")
        elif path.is_file():
            data = path.read_bytes()
            file_bytes += len(data)
            entries.append(f"F\t{rel}\t{hashlib.sha256(data).hexdigest()}")
    payload = "\n".join(entries)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(payload + ("\n" if payload else ""), encoding="utf-8")
    digest = hashlib.sha256(payload.encode()).hexdigest()
    print(f"{len(entries)}|{file_bytes}|{digest}")
    return 0

def diff(before_arg: str, after_arg: str, output_arg: str) -> int:
    before = load_manifest(pathlib.Path(before_arg))
    after = load_manifest(pathlib.Path(after_arg))
    output = pathlib.Path(output_arg)
    rows = []
    added = removed = changed = 0
    for rel in sorted(set(before) | set(after)):
        left = before.get(rel)
        right = after.get(rel)
        if left is None:
            added += 1
            rows.append(("ADDED", rel, "", "", right[0], right[1]))
        elif right is None:
            removed += 1
            rows.append(("REMOVED", rel, left[0], left[1], "", ""))
        elif left != right:
            changed += 1
            rows.append(("CHANGED", rel, left[0], left[1], right[0], right[1]))
    with output.open("w", encoding="utf-8") as f:
        f.write("status\tpath\tbefore_type\tbefore_value\tafter_type\tafter_value\n")
        for row in rows:
            f.write("\t".join(row) + "\n")
    print(f"{added}|{removed}|{changed}")
    return 0

def main() -> int:
    if len(sys.argv) != 4:
        raise SystemExit("usage: research-node-modules-manifest.py snapshot|diff <arg1> <arg2> [output]")
    if sys.argv[1] == "snapshot":
        return snapshot(sys.argv[2], sys.argv[3])
    if sys.argv[1] == "diff":
        raise SystemExit("diff requires: diff <before> <after> <output>")
    raise SystemExit(f"unknown command: {sys.argv[1]}")

if __name__ == "__main__":
    # diff has one extra path argument; handle separately to keep the shell interface explicit.
    if len(sys.argv) == 5 and sys.argv[1] == "diff":
        raise SystemExit(diff(sys.argv[2], sys.argv[3], sys.argv[4]))
    raise SystemExit(main())
