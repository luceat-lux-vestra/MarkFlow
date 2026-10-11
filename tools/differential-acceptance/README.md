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

### Stage E2 — platform reference heading DOM (diagnostic, not parity PASS)

The bounded `md-atx-headings-h1-h6` Starter case now attempts to query
the **actual IDEA 2026.2.3 bundled Markdown Preview's JCEF DOM** using
the maintained JetBrains Driver JCEF UI surface. It identifies exactly six
ordered `h1`–`h6` elements by **exact source text**, probes each rendered
bounding rectangle and computed CSS margins, and records
`intellij-heading-dom-geometry.txt`. A second file,
`source-heading-relative-drift.tsv`, joins those anchors to the live
MarkFlow native heading inlay ledger by actual source line (0, 4, 8, 12,
16, 20). Both geometry series are normalized to the first heading; the
measured deltas are **diagnostic only**. These probes must fail rather than
fall back to a synthetic HTML preview when the genuine JCEF DOM is absent,
out of order, or source-incompatible.

The two sides use native editor pixels and preview CSS pixels, respectively;
the captured JCEF device pixel ratio and pinned UI scale must be reviewed
before any absolute error bound is adopted. No unreviewed acceptance
threshold is introduced here. The merge gate still treats source-anchor
parity, cumulative #350 heading drift, complete 89-case coverage and
#352 Gantt legibility as **unverified**. The next step is real-IDE evidence
inspection and a reviewer-approved threshold with adversarial regression
controls.

### Stage E3 — historical first known-defect repro (superseded #350 gate)

The CI at exact HEAD `bbf4f4c30e87f4b5dba6087cab8ccd1a7deb8d91`
preserved three source-identical pairs and reported two failures:

- **#350 historical candidate:** 167.031px H1-to-H6 delta between the
  **native source-line position** and the **Preview DOM heading box top**,
  against an unreviewed 44px (two native line heights) bound.
- **#352:** Gantt 284×196 decoded raster displayed at 1176×812,
  exceeding the 2× display-scale bound and visibly magnifying source pixels.

The first comparison was **not a like-for-like measurement**. A source
line's logical Y coordinate is not the native heading's rendered rectangle.
The failing HEAD is retained as immutable evidence of a flawed acceptance
criterion, but **its #350 numeric gate was withdrawn in Stage E5** rather
than treated as an authorized product-defect threshold. The original
`source-heading-relative-drift.tsv` is retained as a labeled diagnostic.
The Gantt intrinsic/display scaling gate remains active.

This distinction matters: the Preview is our primary **visual reference**,
not an absolute oracle; semantic content, editable source identity,
structural accessibility and geometric accuracy require their own
independent acceptance contracts.

### Stage E4 — bounded native Mermaid PNG enlargement (partial #352 remediation)

The production `NativeDerivedPresentationController` now limits the **total**
default Mermaid `FIT_TO_VIEWPORT` upscaling (after zoom) to **2× its decoded
PNG's intrinsic width and height**. This is deliberately bounded at UI scale
1 to prevent low-resolution PNG pixels from being magnified arbitrarily when
the editor is much wider than the rendered diagram. The 284×196 Gantt case
that previously reached 1176×812 now computes **568×392** in the host. Wide
diagrams still shrink to available width; `ACTUAL_SIZE_SCROLL` and
`SHRINK_TO_FIT` keep their existing contracts. The sizing unit test retains
the original FIT=400×200 example, exercises the actual Gantt dimensions,
wide diagrams, other modes, and explicit zoom.

The **candidate** #352 scale gate is unchanged at **2×**; it must be
evaluated in the genuine IDE capture, not assumed to pass from the unit test.
This is a **host pixel-density protection**, not a proof that the **source
Gantt PNG itself** has enough date-tick resolution or no collisions. Richer
render-artifact resolution, semantic label-overlap metrics, full diagram
review, and the independent #350 heading geometry fix remain required.
Even after #352's candidate scaling gate passes, the real #350 drift repro
is **expected to leave Starter red**. Do not mark #353 fully accepted.

### Stage E5 — distinguish native source lines, rendered inlays, and DOM boxes

The `md-atx-headings-h1-h6` real-IDE case now retains both series:

- `source-heading-relative-drift.tsv`: **legacy diagnostic** using source
  logical-line Y against the Preview DOM heading element top, **not a
  valid like-for-like layout regression gate**.
- `source-heading-rendered-box-drift.tsv`: measured **installed native
  heading inlay rectangle top/height** versus measured **actual bundled
  Preview JCEF heading DOM element rectangle top/height**. Both top series
  are relative to H1 and tied to source line identity. They are better
  counterparts than source lines but still do not share a certified text
  baseline, margin collapse or font metrics contract.

The independently inspected pre-E5 real-IDE artifact
`11676606958` supplies these native inlay top positions (pixels):
**0, 136, 265, 389, 509, 625**, versus JCEF DOM heading top offsets
**0, 117.375, 213.172, 293, 363.984, 434.969 CSS px** at DPR 1.
Thus the H6 relative **block-box top delta is 190.031px**. This is
diagnostic evidence, **not a proven 190px text-baseline regression**.
The analogous source-line/DOM number was 167.031px, demonstrating
why anchor choice matters. E5 records the box-delta directly rather
than inferring it from source-line advance.

Until the correct shared rendered-text baseline and box semantics are
reviewed, the #350 case explicitly records
`HEADING_LAYOUT_ANCHOR_CONTRACT_UNREVIEWED` and remains **FAIL-CLOSED**,
rather than applying an arbitrary 44px numerical acceptance threshold
to incomparable objects. Adversarial fixtures reject wrong source lines,
unavailable/mismatched inlay bounds, collapsed DOM boxes and fake Preview
documents. Candidate failure reports remain machine-readable and all three
selected image pairs remain in CI artifacts. Only after the reference
anchor is proven should a reviewed numerical drift bound be set and
used for product spacing changes.

#352 still has its independent 2× raster upscaling guard; future evidence
must show the installed Gantt raster reaches 568×392 under the pinned
UI scale and that labels remain readable. Full 89-expanded-case coverage,
visual acceptance, clipping/scroll reachability and #350 baseline parity
are **not certified**; PR #364 remains unmergeable.

### Stage E6 — same-source following paragraph text checkpoints

The H1–H6 case additionally compares **the six exact follow-up paragraphs**
from `fixtures/differential-acceptance/markdown/headings-atx-h1-h6.md`.
Each source line (2, 6, 10, 14, 18, 22) must equal
`Paragraph after Hn.` and be matched to an identically named, ordered
`<p>` in the **real bundled Markdown Preview**. Using JCEF
`document.createRange().selectNodeContents(p).getBoundingClientRect()`,
the test records a **text range top and height**, rather than treating the
paragraph's outer CSS margin box as text. Its two artifacts are
`intellij-following-paragraph-dom-geometry.txt` and
`source-following-text-line-drift.tsv`. Native position comes from the
installed editor's same source-line checkpoint, *not* from guessed
viewport scroll offsets; both sides are normalized to the first paragraph.

This is materially closer to comparable **visible text** geometry than
native Markdown heading syntax lines versus DOM heading boxes. However
native logical line top and browser inline Range top do not necessarily
share baseline/font ascent. Consequently it is labeled **diagnostic only**,
and the known `HEADING_LAYOUT_ANCHOR_CONTRACT_UNREVIEWED` blocker
persists. Negative controls reject wrong paragraph source lines,
out-of-order/collapsed text ranges and a non-preview document.

The next acceptance step is to calibrate the native text baseline and the
browser's actual rendered text line with a reviewed font/layout contract,
then measure cumulative paragraph drift and adjust heading block allocation
only if that measurement justifies it. Do not resurrect the superseded
44px source-line/heading-box numeric threshold merely to unblock CI.

### Stage E7 — Mermaid Gantt drawing width independent of hidden JCEF DOM

At exact HEAD `e2bacac0c3b38f65729d070e95733194ea0fd39b`,
all three selected cases captured. The real Starter artifact
`11677482656` confirmed that #352's **native host** now correctly
shows the 284×196 PNG at 568×392 (2×). The derived-content shard
still fails solely because #350's common text-baseline contract is
unreviewed. More importantly, independent review of its
`mermaid-gantt/side-by-side.png` showed date ticks and task labels
**still visibly overlapping** in MarkFlow. Host scale correction does
not solve poor intrinsic layout.

Mermaid 12 Gantt's drawing code reads `elem.parentElement.offsetWidth`
to choose its timeline range and SVG `viewBox` width, unless
`gantt.useWidth` is explicitly set. Our retained hidden JCEF renderer
had produced only a **284px** wide chart. To remove this unintentional
layout dependency without changing Mermaid source bytes, the derived
renderer now supplies `gantt.useWidth=960`, a finite diagram layout
width; other Mermaid kinds retain automatic layout. Existing size/zoom
settings remain in force in the native host and the maximum 2× PNG
host-scale guard remains intact. Runtime unit tests preserve this setting
across all size modes and reject accidental overrides for flowchart/class.

**The 960px setting is a candidate correction**, not a proof of Gantt
legibility. The next authoritative Starter run must show an enlarged
*intrinsic* Gantt PNG, unchanged exact source bytes, acceptable actual
task-label and date-tick spacing, and no clipping or reachability loss.
Do not claim #352 resolved without inspecting that real capture; the
full #353 corpus and independent #350 heading text-drift obligation
remain release blocking.

### Stage E8 — measured Gantt restoration and custom-fold heading geometry pilot

The **actual** IntelliJ 2026.2.3 Starter run of E7 at
`df1b06e7367b71097e13607ec18c8518d14f9a5a` finished with Build,
CodeQL, Hardening, all other Starter shards, and deterministic visual
acceptance **SUCCESS**. The derived-content shard remained **FAIL** only
for the intentionally unreviewed #350 heading anchor contract, after all
three source-identical cases were captured. Artifact
`11679309097` contains the actual Gantt raster:

- intrinsic **960 × 196** pixels (previously 284 × 196);
- native installed **1176 × 240** pixels (about **1.225×** wide);
- no candidate 2× over-enlargement violation; Gantt source unmodified;
- directly reviewed pair shows the earlier severe overlapping timeline and
  task labels relieved. This is **a bounded visual improvement**, not a
  semantic collision-free claim for the entire diagram corpus.

The real selected Gantt case now additionally records a *candidate*
`CANDIDATE_GANTT_MIN_INTRINSIC_WIDTH_800` failure if the four-day fixture
at the pinned 1200px editor viewport regresses below **800px intrinsic**
width. Adversarial examples 799 (FAIL), 800 (PASS), 960 (PASS) protect
the boundary. This is NOT a universal Gantt width for other sources,
viewports, or layouts. We retain the independent ≤2× display-scale gate.

For #350, six identical-style following paragraphs show **206.031px**
H1-to-H6-relative native-minus-reference accumulated drift. Exactly
**162px** of native extra allocation equals the measured five H2–H6
**block inlay heights** (41+36+32+28+25), while the source-line/Preview
spacing accounts for approximately **44px** more. This exposes the
source-line-plus-block-inlay double-geometry mechanism, not a vetted
replacement height or proof that every preview spacing difference
is incorrect.

Before rewriting the production heading controller, we added an isolated
`NativeOrdinaryPresentationTest` probe using the JetBrains
`CustomFoldRegionRenderer` and `addCustomLinesFolding(0,0,...)`.
The probe asserts that the following text moves by **custom fold height
minus editor line height** (instead of custom fold height in addition to
the line). It checks document bytes, modification stamp, caret position,
source restoration by removing the always-collapsed custom region, reinstalling it,
and final cleanup geometry. The experimental
renderer draws no rich content, so passing this test is **not** sufficient
to switch production yet: existing platform parser folds, source clicks,
active-caret revelation, rich heading paint, undo/save/reopen and
Starter screenshot semantics require further verified integration.

PR #364 remains fail-closed and unmergeable until the #350 replacement,
full 89 expanded case coverage, reviewed semantic/perceptual acceptance
and exact-final-HEAD evidence are complete.


### Stage E9 — platform heading syntax fold coexistence and two-line geometry pilot

The E8 single-line custom-fold proof at exact HEAD
`c3f0f441bea3cfa0e8c4750698a3b24946d70552` passed the
IDE platform unit-test suite. Build (including Qodana, Plugin Verifier,
Merge Gate), CodeQL, and Hardening all succeeded. The real Starter
derived-content shard stayed red solely due to the explicit
`HEADING_LAYOUT_ANCHOR_CONTRACT_UNREVIEWED` blocker; all other stable
Starter shards succeeded.

Two additional **isolated test-only** platform probes now validate:

- **Foreign parser syntax fold:** a collapsed, platform-owned fold on
  the ATX marker (`# `) must remain valid, owned by the platform and
  collapsed when the custom heading line fold is installed and removed;
  exact source and document stamp must not change, and caret navigation
  back into the heading after removal must be possible
- **Heading + verified blank line:** a custom fold spanning a heading
  line and its immediately following blank line must move the next
  paragraph by `custom_height - 2 × editor_line_height`, rather than
  allocating both original lines and an extra block inlay; source,
  caret, undo-sensitive modification stamp and cleanup geometry
  must remain intact

These are actual IntelliJ platform API checks, not simulated layout
math. They are **not** proof that switching the production heading
controller is safe: rich text painting, source-click reveal via the
custom region, caret activity, existing source syntax folding, and
real-IDE screenshot parity must still be implemented and proven.
Two-line folding is valid only when the second source line is actually
empty; it must never swallow a nonempty paragraph. On unsupported
overlap or an unavailable custom region, the production controller
must retain exact source with a fail-closed fallback.

All numeric #350 Preview comparisons remain diagnostic until a
reviewed same-text anchor/font contract exists. Full #353
89-expanded-case acceptance is also outstanding.


### Stage E10 — custom heading click ownership and safe source reveal proof

At E9 candidate head `c5b3899dea24fa279c90135c9f0a9fff44ca718c`,
all Build jobs (including unit tests, Plugin Verifier, Qodana, Merge Gate),
CodeQL and Hardening passed. The real Starter derived-content job
failed only for the **known, unreviewed #350 anchor contract**, while
the ordinary Markdown, canonical, lifecycle, core, and deterministic
visual suites passed. Artifact `11685712264` was independently
inspected: 3/3 selected sources captured, 86 not executed, sole
`HEADING_LAYOUT_ANCHOR_CONTRACT_UNREVIEWED` failure, Mermaid Gantt
intrinsic 960×196 and native 1176×240 preserved.

The next `NativeOrdinaryPresentationTest` pilot exercises a
**two-source-line** custom fold (heading + verified blank line) while
retaining an independent JetBrains heading syntax fold. It constructs
`EditorMouseEvent` instances with custom/foreign collapsed-fold
identity and verifies the future handler's ownership behavior:
left-click on the **currently owned** custom heading region removes
only that region, restores editable source and moves the caret to the
heading's parser content offset. A foreign fold, a right-click,
wrong-area event or stale previously removed region cannot trigger
reveal; document source and modification stamp are unchanged.

This is an **isolated, synthetic EditorMouseEvent ownership test**.
It proves neither real mouse hit testing nor production wiring. JetBrains
`EditorImpl.createEditorMouseEvent` supplies actual custom fold identity
through `getCollapsedFoldRegion()` within the visible placeholder.
Before switching the product controller, implement the production
ownership/lifetime mapping and separately prove real UI click/reveal,
rendered rich heading font geometry, platform-fold conflicts,
caret interactions, and true Starter screenshot changes. A disabled or
unsupported custom fold must leave exact source visible, not fabricate
successful rich projection.


### Stage E11 — production-capable custom heading renderer and fail-closed line planner

PR #364 was rebased by the user onto `main`
`8b228a50f451826a0fc7f9798ae6c31bbd437544`. Exact rebased
HEAD `b2dacadde85219b05d41f62fa2be40fe6f7d59a0` had 71
replayed commits, 0 behind, and preserved #366/#368/#370 optimizations.
Hardening, CodeQL and eight native Evidence runs passed before the
subsequent feature commits superseded/cancelled its remaining Build/Starter
runs; their incomplete gates **must not be counted as PASS**.

Rather than immediately switch the production heading controller and
invalidate its existing IDE and evidence consumers, the next slice
implements an actual `NativeHeadingCustomFoldRenderer` using the
JetBrains `CustomFoldRegionRenderer` interface, drawing bold H1–H6
glyphs at the established font scale and padding with ellipsis on narrow
views. The isolated pinned-IDE test checks the existing H2 native
block-inlay's width/height against the new custom region, verifies
that nontransparent glyph pixels are painted, and proves the subsequent
source line moves by `headingHeight - 2*editorLineHeight`
when a verified adjacent blank line is included. Source bytes,
modification stamp, caret and cleanup geometry must remain untouched.

`NativeHeadingCustomFoldLinePlanner` denies unsafe whole-line
replacement: multiline Setext headings, ranges that do not occupy
exactly one physical source line, and nonblank or whitespace-only
following lines never get hidden. A blank second line is included
only if it is exactly empty **and** followed by a nonblank document line.
Adversarial tests cover no blank, whitespace-only blank, trailing EOF
blank and Setext multiline syntax. If no custom region can be installed
because of platform folding overlap, the future controller must fail
closed to exact editable Markdown source.

**Important boundary:** the new actual renderer/planner are in
production source but are **not yet installed** by
`NativeHeadingPresentationController`. The shipping heading path
continues to use the old native inlays. A follow-up integration must
replace that path as one atomic lifecycle change, update
`headingInlays`-based probe and E2E contracts, and add real IDE
mouse-hit, source reveal, keyboard/caret, font geometry, native-vs-bundled
Preview, undo/reopen and foreign-fold tests before accepting #350.
The known `HEADING_LAYOUT_ANCHOR_CONTRACT_UNREVIEWED` and 86
not-yet-executed corpus cases continue to fail-close PR #364.


### Stage E12 — guarded native custom-fold controller lifecycle

The custom line renderer and fail-closed line planner are now wired
into the **real** `NativeHeadingPresentationController` under the
explicit constructor-only pilot parameter
`sourceLineReplacement = true`. The shipping default remains
`false`, with all existing block-inlay behavior unchanged until a
separately accepted production migration.

When enabled, an eligible full-source-line heading installs a
`CustomFoldRegion` over the heading and, if independently verified
safe, its immediately following blank line. A failed custom fold
installation is **not** silently replaced by a double-height inlay:
the original authoritative Markdown stays editable. Setext and
unreviewed multi-line headings remain on the prior presentation path.

The controller independently owns the custom fold and never claims
ownership of a JetBrains Markdown syntax fold. Source-identity and
actual fold object identity are checked before left-click reveal;
wrong-button/foreign/stale fold events cannot remove the heading.
Removing only the owned region exposes the original source, moves the
caret to the parser heading content offset, and a subsequent inactive
refresh reinstalls the region. Evidence distinguishes old
`ownedInlays` from the new `ownedCustomFolds`, without falsely
reporting a custom fold as an inlay.

A pinned IntelliJ platform test exercises this **actual controller**
mode, unlike E10's synthetic standalone listener pilot. It asserts
the two-line vertical-advance delta, foreign fold survival, source
bytes, stamp, caret, reveal count, activity-driven reinstallation
and disposal cleanup. The test constructs an EditorMouseEvent
for deterministic ownership checks; physical pointer hit-testing
is still outstanding. All exact-final-HEAD CI and Starter evidence
remain mandatory.

There is intentionally **no change to production call sites or
ordinary Markdown heading E2E contracts** in this stage. Once the
guarded controller, glyph-paint and line-planner tests pass, the next
bounded integration step is an explicitly real IDE screenshot and
mouse-flow pilot before making this mode the default, updating all
legacy `headingInlays`-based consumers, and solving the #350
reference-anchored cumulative-layout contract. #353 full corpus
remains 86 cases short and this PR must not be merged.


### Stage E13 — EditorImpl-owned mouse hit-testing integration pilot

At exact prior HEAD `b2ee1db4e33f4f90dd9c959e80081dc0b32ef4e5`
the actual Gradle `check` and webview-source Test job completed
**SUCCESS**: guarded custom-fold renderer, planner, synthetic
mouse ownership and controller lifecycle tests passed. All eight
Native Evidence, CodeQL and Hardening runs had also succeeded.
The Starter canonical/core-editing and deterministic visual jobs
were successful in that run; the remaining jobs were still running
when subsequent commits superseded the HEAD, so a *complete*
Starter gate was not claimed.

The next in-process **real IntelliJ editor event routing** test
`testSourceReplacingHeadingUsesRealEditorMouseHitTestingForSourceReveal`
gets the custom region's actual editor coordinates through
`CustomFoldRegion.getLocation()`, sends an AWT
`MouseEvent.MOUSE_CLICKED` into `editor.contentComponent.dispatchEvent`,
and requires the real `EditorImpl.createEditorMouseEvent` hit-testing
path to resolve the owned fold. The production
`NativeHeadingPresentationController` in explicit pilot mode must
consume the click, remove only its own custom fold, move the caret to
parser-proven heading content, preserve source bytes and modification
stamp, and reinstall upon inactive refresh. An absent visible region
location is a hard test failure, never silently replaced with a
fabricated `EditorMouseEvent`.

This test is **real EditorImpl mouse-event hit-testing within a
BasePlatformTestCase UI**, not a full OS Robot / Starter Driver
pointer-capture test. The shipping controller remains
`sourceLineReplacement=false`. Passing it is necessary but not
sufficient for enabling the production migration: the real Starter
UI, existing E2E contracts and source-anchored six-heading Preview
parity must still be verified against exact final HEAD, and all
89 expanded manifest cases must satisfy reviewed acceptance before
PR #364 can merge.
