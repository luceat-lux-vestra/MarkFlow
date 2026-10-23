# MarkFlow #362 — paired x64/ARM64 visual forensic candidate

**Research only — the production x64 golden test, the five reviewed goldens, pixel tolerances, and 89-case differential authority remain unchanged.**

The previous ARM64 run with native EGL failed only its derived Mermaid/KaTeX screenshot, but the x64 font-resolution and renderer intermediate data were not captured. Comparing screenshots alone cannot establish root cause.

This disposable, branch-specific GitHub Actions workflow runs automatically when `ci/362-arm-visual-root-cause` is pushed. It builds **one immutable plugin ZIP from the identical candidate HEAD**, then runs the *original* full strict visual test on both Ubuntu 24.04 x64 and ARM64, verifying actual CPU architecture and plugin SHA-256 on each runner. Only ARM installs previously missing `libegl1`. Both record resolved font file paths/hashes, JBR/CEF native file hashes, runner identity, and final screenshots/diffs.

Only this experimental job sets `MARKFLOW_VISUAL_ARM_FORENSICS=true`. The original test forwards a test-only JVM flag to the actual IDEA process, causing the real JCEF renderer to retain each source/config, Mermaid SVG, KaTeX HTML, pre-inlay PNG and dimensions under `build/visual-acceptance/derived-intermediates`, without changing the rendered content or native callback. No normal IDE or CI path activates this flag.

ARM golden mismatch remains a **real failing test**; `always()` artifact upload still preserves all evidence. The artifact validator also fails on absent intermediates. No manual user action is needed.

**Inference boundary:** renderer-returned HTML/SVG is not computed browser DOM/CSS. Differences in intermediate HTML/SVG, raster PNG and Swing screen will identify where divergence first occurs, not by themselves prove a particular font/CEF backend caused it. The follow-up checkpoint must inspect the paired artifacts, then decide whether computed CSS/DOM instrumentation is required. Do not change goldens or relax thresholds.

Remove this temporary workflow and optional capture before any ordinary production merge.
