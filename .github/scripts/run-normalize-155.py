from pathlib import Path
import runpy

EXPECTED_COMPAT_EXIT = "forbidden target residue under webview/src: previewOnlyByDefault"

try:
    runpy.run_path(".github/scripts/normalize-155.py", run_name="__main__")
except SystemExit as failure:
    if str(failure) != EXPECTED_COMPAT_EXIT:
        raise

# The compatibility parser may name removed wire keys solely to delete them. No other retained
# production TypeScript may expose or consume those settings.
web_root = Path("webview/src")
files = [p for p in web_root.rglob("*") if p.is_file() and p.as_posix() != "webview/src/app/runtime-settings.ts"]
corpus = "\n".join(p.read_text(encoding="utf-8", errors="ignore") for p in files)
for needle in ["previewOnlyByDefault", "diagramSecurityLevel", '"LOOSE"']:
    if needle in corpus:
        raise SystemExit(f"legacy renderer setting remains outside compatibility stripping: {needle}")

runtime = Path("webview/src/app/runtime-settings.ts").read_text(encoding="utf-8")
for required in [
    "delete current.diagramSecurityLevel;",
    "delete current.previewOnlyByDefault;",
    'securityLevel: "strict" as const',
]:
    if required not in runtime:
        raise SystemExit(f"runtime compatibility/fail-closed invariant missing: {required}")
if '"LOOSE"' in runtime or "resolveDiagramSecurityLevel" in runtime:
    raise SystemExit("weaker Mermaid security selection remains in renderer runtime")

current = "\n".join(
    candidate.read_text(encoding="utf-8", errors="ignore")
    for root in [Path("src/main/kotlin"), Path("src/main/resources/META-INF")]
    for candidate in root.rglob("*") if candidate.is_file()
)
for needle in ["MarkFlowRuntimeSettingsSink", "MarkFlowRuntimeSettingsNotifier", "NativeMarkFlowRuntimeSettingsSink", "runtimeSettingsSink"]:
    if needle in current:
        raise SystemExit(f"old settings invalidation name remains: {needle}")

print("#155 compatibility-aware convergence guard passed")
