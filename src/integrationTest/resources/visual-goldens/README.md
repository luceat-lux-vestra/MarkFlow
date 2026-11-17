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
- The historical 262 identity remains in `environment.txt`; stable 263 acceptance uses the separately reviewed `environment-2026.3.txt` and must match it exactly before image comparison.
- Per-channel noise tolerance is narrow and the allowed changed-pixel ratio is bounded.
- Source/fidelity and semantic/runtime assertions remain separate proof layers.
- Expected/actual/diff artifacts are retained by the visual workflow for diagnosis.
