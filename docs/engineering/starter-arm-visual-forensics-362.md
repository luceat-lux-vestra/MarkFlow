# MarkFlow #362 — paired x64/ARM64 visual forensic candidate

**Research only — the production x64 golden test, the five reviewed goldens, pixel tolerances, and 89-case differential authority remain unchanged.**

The previous ARM64 run with native EGL failed only its derived Mermaid/KaTeX screenshot, but the x64 font-resolution and renderer intermediate data were not captured. Comparing screenshots alone cannot establish root cause.

This disposable, branch-specific GitHub Actions workflow runs automatically when `ci/362-arm-visual-root-cause` is pushed. It builds **one immutable plugin ZIP from the identical candidate HEAD**, then runs the *original* full strict visual test on both Ubuntu 24.04 x64 and ARM64, verifying actual CPU architecture and plugin SHA-256 on each runner. Only ARM installs previously missing `libegl1`. Both record resolved font file paths/hashes, JBR/CEF native file hashes, runner identity, and final screenshots/diffs.

Only this experimental job sets `MARKFLOW_VISUAL_ARM_FORENSICS=true`. The original test forwards a test-only JVM flag to the actual IDEA process, causing the real JCEF renderer to retain each source/config, Mermaid SVG, KaTeX HTML, pre-inlay PNG and dimensions under `build/visual-acceptance/derived-intermediates`, without changing the rendered content or native callback. No normal IDE or CI path activates this flag.

ARM golden mismatch remains a **real failing test**; `always()` artifact upload still preserves all evidence. The artifact validator also fails on absent intermediates. No manual user action is needed.

**Inference boundary:** renderer-returned HTML/SVG is not computed browser DOM/CSS. Differences in intermediate HTML/SVG, raster PNG and Swing screen will identify where divergence first occurs, not by themselves prove a particular font/CEF backend caused it. The follow-up checkpoint must inspect the paired artifacts, then decide whether computed CSS/DOM instrumentation is required. Do not change goldens or relax thresholds.

Remove this temporary workflow and optional capture before any ordinary production merge.

## 2026-10-23 Checkpoint 16 — controlled Arial/Liberation Sans hypothesis

The next candidate keeps the **unchanged x64 reference** and the **unchanged ARM64 baseline** and adds a **third ARM64-only font-matched worker** to the same push-triggered matrix. All three use the identical candidate source and plugin ZIP artifact, original five-case strict test, 1920x1080@96 DPI, JBR/IDE, and retained before/after raster captures.

| Variant | Host | Intended Arial resolution |
|---|---|---|
| `x64-reference` | `ubuntu-24.04` | Liberation Sans Regular, SHA-256 `4659bc0c58c5028dd488ec928d41d9265db43d9b669fc14ca8b0832daca7b144` |
| `arm-original` | `ubuntu-24.04-arm` | DejaVu Sans Book, SHA-256 `ae7b7855e115a5966d8b1b3f80f254ccc117ec86f9965e202ee2940453837280` |
| `arm-font-match` | `ubuntu-24.04-arm` | Installs only `fonts-liberation` beyond baseline EGL, refreshes fontconfig, then requires the **exact same Liberation Sans SHA-256** as x64 |

A **fail-closed font fingerprint guard** checks the selected `fc-match Arial` family and file digest in all three variants. The original ARM baseline never installs Liberation. Distinct artifact names preserve each variant's host provenance, source/config, Mermaid SVG, KaTeX HTML, raster PNG and final x64 golden comparison. An ARM visual failure is retained as a failure, not converted to PASS. If image packages change and the font-control SHA changes, the controlled experiment fails rather than making an unsupported causal claim.

**Hypothesis only:** Arial fallback may explain Mermaid's prior 656x63 vs 656x65 SVG geometry. Matching it is not expected to necessarily fix KaTeX alpha rasterization. Evaluate Mermaid DOM/SVG geometry and KaTeX alpha **separately** before deciding a next step. No production runner, golden, tolerance, Qodana/89-case gate, or PR #453/#461 change is authorized.
