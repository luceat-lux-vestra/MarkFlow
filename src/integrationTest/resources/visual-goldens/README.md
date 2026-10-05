# MarkFlow deterministic visual goldens

Issue #339 owns these baselines.

Each `.svg` golden is a text, reviewable wrapper around one exact PNG captured from the
authoritative IDEA 2026.2.3 / Xvfb visual environment. The integration test decodes the embedded
PNG, compares it with the fresh editor-only capture, and writes expected/actual/diff PNG artifacts
on failure.

Rules:

- CI never updates these files.
- A baseline update requires a reviewed user-visible explanation.
- The environment identity in `environment.txt` must match exactly before image comparison.
- Per-channel noise tolerance is narrow and the allowed changed-pixel ratio is bounded.
- Source/fidelity and semantic/runtime assertions remain separate proof layers.
- Expected/actual/diff artifacts are retained by the visual workflow for diagnosis.
