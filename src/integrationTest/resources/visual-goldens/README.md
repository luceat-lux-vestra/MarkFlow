# MarkFlow deterministic visual goldens

Issue #339 owns these baselines.

Each `.svg` golden is a text, reviewable wrapper around one exact PNG originally captured from the
reviewed IDEA 2026.2.3 / Xvfb visual environment. Those pixels remain a historical reference and are
not relabeled as 263. Stable IDEA 2026.3 acceptance records and reviews its own exact environment
identity, then compares its fresh editor-only capture with the retained reference; any material drift
requires a fresh reviewed 2026.3 baseline. Failures retain expected/actual/diff artifacts.

Rules:

- CI never updates these files.
- A baseline update requires a reviewed user-visible explanation.
- The historical 262 identity remains in `environment.txt`; stable 263 acceptance uses the separately reviewed `environment-2026.3.txt` captured from Starter Driver E2E run `44093118250` on actual `IU-263.7307.200`, and must match it exactly before image comparison.
- Two independent stable-263 runs (`44093118250` and `44096108201`) reproduced the environment identity and structure-list geometry byte-for-byte. Comparing their actual 263 captures with the unchanged strict comparator produced 134 differing pixels for typography, 143 for structure, and 143 for code (all <= the 183-pixel allowance); the capture is therefore reproducible enough to become the reviewed 263 authority.
- Materially changed 263 baselines live under `2026.3/<case>.svg`; typography, structure, and code use those versioned rasters. Derived/resources remain on the retained root reference only because both real-263 runs stayed within the existing strict tolerance. This fallback is explicitly allowlisted by the test and is not a generic compatibility escape hatch.
- The original 2026.3 typography/code raster import comes from exact visual artifact `14332904986` / run `44096108201`, source SHA `a70a70ee2e3c1854ab9e7d599d1781fd3929e4f2`, and actual `IU-263.7307.200`; do not overwrite or relabel the retained 262 goldens.\n- The 2026.3 **structure** raster was intentionally re-baselined for #628 from exact visual artifact `14742194357` / run `44992286652`, source SHA `78f21f42646500ef6caa7eeddb2788af3d1c51de`, actual `IU-263.7307.200`. Review confirmed the new pixels reflect the intended compact list rhythm, current-LAF task checkbox treatment, and editor-relative thematic rule; structure-list geometry remained valid, allocated, and lexically ordered, with no Trial/Got-It overlay in the captured editor raster.
- Per-channel noise tolerance is narrow and the allowed changed-pixel ratio is bounded.
- Source/fidelity and semantic/runtime assertions remain separate proof layers.
- Expected/actual/diff artifacts are retained by the visual workflow for diagnosis.
