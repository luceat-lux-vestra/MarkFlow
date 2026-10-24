# MarkFlow #451: opt-in shared JCEF Starter experiment

**Candidate only, non-authoritative.** Required PR CI, the normal Starter shards, JCEF-disabled Heading Robot, #353 Full Differential acceptance, and PR #453 remain unchanged.

## Stage 1 evidence — one process, one combined active project

The first candidate at `a270aa5355a6262107896fbb5f1c7027e5643e58` ran successfully in temporary workflow `39938823614`.

Retained evidence:
- artifact `12495127128`;
- one actual Starter IDE launch request;
- Production Wiring proof passed;
- Differential Preview Smoke proof passed;
- lifecycle: setup→ready `105535 ms`, proof body `28142 ms`, close `18454 ms`, total `152132 ms`;
- Production Wiring body `10924 ms`;
- Preview Smoke body `17215 ms`.

The isolated baseline measurements from #459 were `104486 ms` for Preview Smoke and `92715 ms` for Production Wiring (~`197201 ms` combined). The Stage 1 total was lower, but **this is not an optimization claim**: Stage 1 collapsed both proofs into one active IntelliJ project and was measured on the former x64 derived-content runner.

Stage 1 established feasibility only: the two exact proof bodies can coexist in one JCEF-enabled IDE process.

## Stage 2 candidate — one process, two distinct active projects

The current experiment removes the combined-project shortcut.

It:

1. Creates two independent copies of the pinned fixture project.
2. Starts IDEA with the Production Wiring project as the sole active project.
3. Runs the unchanged extracted Production Wiring proof body and closes its editor.
4. Uses IntelliJ's own `ProjectUtil.openOrImport(path, projectToClose, false)` inside the same IDE process to close the Production project and open the distinct Preview Smoke project in the same frame.
5. Fails unless Preview Smoke becomes the **only** open project.
6. Runs the unchanged extracted Preview Smoke proof body in that second active project.
7. Records one IDE launch, one project switch, two active-project identities, exact fixture-byte checks, lifecycle timing, and independent proof timings.

JetBrains' Driver supports utility proxies for static/Kotlin utility methods, so the switch calls the platform project-opening authority rather than automating a fragile file chooser.

### UI-scale compatibility

Preview Smoke requires deterministic UI scale `1.0`; Production Wiring historically uses the default scale. The shared process therefore pins `1.0` globally. This is acceptable only if the **unchanged Production Wiring proof body** still passes under that scale. Stage 1 did so once; Stage 2 repeats that check with the real separate-project boundary. It is evidence for this non-visual wiring proof, not a claim that all scenarios are scale-independent.

## Execution

The temporary branch-only workflow:

`.github/workflows/starter-shared-jcef-experiment-451.yml`

runs on pushes to `ci/451-shared-jcef-lifecycle-experiment`, including while PR #461 remains Draft.

It now follows the current derived-content architecture:
- `ubuntu-24.04-arm` / ARM64;
- IDEA `2026.2.3`;
- current pinned JCEF AppArmor setup;
- matching Xvfb;
- exact-source packaged plugin with SHA-256 identity verification.

The experiment is opt-in via `MARKFLOW_SHARED_JCEF_EXPERIMENT=true`, validates that one real JUnit test executed with zero skips/failures, and uploads evidence even on failure.

## Promotion boundary

A passing Stage 2 run is still not enough to merge a production optimization automatically.

Before adoption:
- repeat the separate-project experiment enough to distinguish real savings from cache/scheduling noise;
- compare an equivalent ARM64 isolated baseline and shared candidate;
- preserve exact package identity and independent failure attribution;
- confirm no JCEF native disposal/lifecycle regression relevant to #353/#453;
- keep Heading Robot isolated because it requires JCEF off;
- keep Manifest out until #353/#453 provides a safe lifecycle basis;
- measure critical-path wall time and summed runner work, not just one test-worker timer.

If those conditions fail, record **NO-GO** rather than weakening the existing Starter evidence.
