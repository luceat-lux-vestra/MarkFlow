# #353 Differential capture plan — Stage A

This is a **manifest-driven execution inventory**, not a substitute for real IDE
paired capture. Generate it from the exact checked-out source commit:

```sh
node tools/differential-acceptance/plan.mjs --head "$(git rev-parse HEAD)" --output build/differential-capture-plan.json
```

The output retains each of the 88 reviewed corpus case IDs, exact source file
SHA-256, manifest SHA-256, classification, preview expectation and interaction
contract. Mermaid entries expand **all** fenced blocks with independently
identified source-line anchors and block hashes (including both blocks of
`examples/mermaid-large-flowchart.md`). It never guesses Markdown
visual anchors from filenames. Unsupported/degraded cases stay in the plan,
rather than silently disappearing.

`capture_status=not-captured` and `execution_status=not-captured`
are mandatory Stage A outputs. A generated plan must never be reported as
visual PASS. The next bounded slice must exercise the actual IDEA 2026.2.3
bundled Markdown preview and MarkFlow native editor against the **same bytes**,
then retain source-bound identity, screenshots and structural metrics. Missing
captures, unsupported platform bridges or uncertain layout must fail closed.

CI executes `webview/tests/differential-capture-plan.test.mjs` through
the existing Gradle-managed Node `testWebviewSource` task. Negative
cases cover duplicate IDs, path traversal, missing fixtures, uncovered rows,
missing/unclosed Mermaid blocks, exact HEAD and source-byte drift.

## Stage B/C — one genuine reference smoke pair (not corpus acceptance)

The Starter `derived-content` shard (same packaged candidate ZIP) captures
`VISUAL-DERIVED.md` in MarkFlow's native editor and the **actual** bundled
IDEA 2026.2.3 Markdown Preview. The source bytes, SHA-256, source-line anchor,
JVM/AppArmor identity and captured viewport sizes are retained. JCEF must show
nonblank document content and the original source/stamp must remain unchanged.

A bounded diagnostic step emits `side-by-side.png` and `diff.png` alongside
`markflow.png`, `intellij-preview.png`, `source.md`, `identity.txt` and
`metrics.json`. The diff rescales the reference to the candidate screenshot's
pixel dimensions **without semantic registration**. Its mean RGB delta is
**diagnostic only**: fonts, intrinsic Mermaid scale, and differing viewports
make raw pixel comparison unsuitable as an acceptance gate. The metadata
explicitly records `visual_parity=unverified`, `anchor_alignment=unverified`,
`hard_structural_gate=not-implemented`, and `differential_pass=false`.

This single fixture does **not** satisfy #353 acceptance. Future slices must
execute every manifest entry and Mermaid block, prove source-anchored scroll
alignment and dimensional invariants, and apply reviewed per-class visual
thresholds; no auto-approval from a raw-pixel diff.

## Stage D — bounded manifest-to-real-IDE capture (NOT differential acceptance)

`MarkFlowStarterDifferentialManifestTest` uses the 88-row corpus manifest, expands
every Mermaid fenced block as a distinct capture identity, and opens the *same*
source bytes in MarkFlow's native editor and IDEA **2026.2.3** bundled Markdown
Preview. It retains per-case `source.md`, `identity.txt`, `markflow.png`,
`intellij-preview.png`, `side-by-side.png`, `diff.png`, `metrics.json` and
JCEF runtime evidence. `differential-manifest/summary.json` keeps **all**
expanded cases, including those not run. The current bounded CI slice attempts three real source-identical pairs:
`md-atx-headings-h1-h6`, `mermaid-flowchart-minimal`, and `mermaid-gantt`.
The Gantt case is diagnostic until inspected and a reviewed legibility gate is added.

To select additional *supported and platform-comparable* cases in a separately
authorized Starter run:

```sh
./gradlew integrationTest -x buildPlugin -PplatformVersion=2026.2.3 \
  -PdifferentialCaseIds=md-atx-headings-h1-h6,mermaid-flowchart-minimal,mermaid-gantt \
  --tests com.algorist.markflow.e2e.MarkFlowStarterDifferentialManifestTest
```

The regular CI intentionally remains bounded to three cases (not 89). Its statuses are
`CAPTURED_UNVERIFIED`, `CAPTURE_FAILED` and `NOT_EXECUTED`, with
`full_differential_acceptance=false` **even when the capture suite passes**.
The original manifest's `supported`/`degraded`/`unsupported` classifications
are retained rather than silently dropping missing cases. The raw-pixel MAE
in `metrics.json` is an **unregistered diagnostic**, not a quality score.

This slice does not yet prove cumulative heading/source-anchor geometry,
clipping, non-uniform scaling, Gantt legibility, renderer equivalence, or
complete 88-row/89-expanded-case real coverage. Some corpus sources reference
relative resources; preserve those paths before extending execution to them.
All missing or unvalidated #353 criteria are **FAIL** for the release/merge gate.

### Stage D geometry instrumentation — bounded

The real-IDE manifest capture now retains `native-raster-geometry.txt` for each
selected case. It records decoded PNG intrinsic width/height, actual IntelliJ
block-inlay rendered width/height and bounds, inlay source line/offset and the
native visible-area rectangle. A deterministic negative-control probe checks
that material aspect-ratio distortion and collapsed dimensions are rejected
before any live capture. A tall raster which extends beyond a **single**
viewport may still be reachable by scrolling; the first screenshots alone do
not establish intrinsic clipping.

This is a **native-raster aspect invariant only**, not final #353 geometry
acceptance. Registered platform DOM source-anchor locations, cumulative
heading/scroll drift, per-class Gantt legibility, and full corpus execution
remain unverified and release blocking. The optional Gradle case selector
does not alter the default bounded CI selection.

### Stage E1 — native heading source geometry (NOT reference parity)

The bounded real-IDE manifest capture additionally writes
`native-heading-source-geometry.txt` from actual installed
`NativeHeadingInlayRenderer` instances, with ATX heading source line/offset,
next nonblank source checkpoint, logical-to-editor pixel Y coordinates,
plain-line baseline, measured vertical span, per-heading and cumulative
source-normalized extra pixels, worst absolute cumulative excess, inlay height
and available inlay bounds. H1–H6 case requires six measured
heading inlays. Deterministic negative controls distinguish ordinary line
advance, materially inflated advance and collapsed advance.

This is **native-only diagnostic geometry**, not yet a reviewed drift
threshold or source-anchored IntelliJ Preview DOM comparison. In particular,
a plain-line baseline is **not** a surrogate for preview layout or a
license to reject intentional heading spacing. `geometry_gate` remains
`NOT_IMPLEMENTED` and `full_differential_acceptance=false`. The next
slice must measure the same source anchors in the real reference DOM, resolve
the #350 cumulative drift on representative long documents, and implement
reviewed thresholds capable of failing the actual regression.
