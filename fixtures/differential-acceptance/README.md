# Differential acceptance corpus

This directory is the repository-owned coverage index for #354 and the later #353
MarkFlow-vs-IntelliJ Markdown Preview differential harness.

`corpus.tsv` is intentionally broader than `examples/`:

- it maps Markdown visual/semantic feature families to existing or missing fixtures;
- it snapshots the Mermaid 12.1.0 detector surface that MarkFlow currently exposes through
  the retained production renderer (the upstream diagram-orchestration.ts registry is byte-identical
  between Mermaid 12.0.0 and 12.1.0; render validation must still be repeated for 12.1.0);
- it records product classification (`supported`, `degraded`, `unsupported`);
- it distinguishes a real fixture (`covered`) from an explicit audited hole (`gap`).

All 88 reviewed corpus rows currently have fixtures; `knownGapIds` is intentionally
empty. `differential-acceptance-corpus.test.mjs` pins the full reviewed `corpus.tsv`
Git blob hash (0f93154d639a0a1901fe9bb94a3dd1e3ad196b1b) and exact row count. This protects against
silent deletion, support-level reclassification, fixture substitution, and changes to
interaction/preview metadata, not just feature-name drift. Regression tests confirm
that deleted rows, reclassification and fixture substitution invalidate the snapshot.

A legitimate fixture/corpus change requires an explicit review of the complete manifest
diff followed by an intentional hash/row-count rebaseline. The pinned digest detects
accidental drift; it is not a substitute for human review and does not prevent someone
from editing both the manifest and the test in the same change.

The runtime differential test in #353 will consume this manifest after #354 completes.
This integrity layer does not claim rendering correctness by itself.

## Mermaid 12.1.0 runtime sizing/configuration audit (#354 → #352)

`mermaid-runtime-config-matrix.tsv` is the reviewed **37-detector** source-level
join of the Mermaid detector inventory in `corpus.tsv` (classification and
canonical fixture) and the explicit per-diagram `useMaxWidth` fields in
`webview/src/app/runtime-settings.ts`. Supplemental flowchart examples remain
in the 88-row corpus, not additional detectors.

The `config_status` column has four meanings:

- `explicit-mode`: MarkFlow passes a per-diagram `useMaxWidth` value derived
  from the selected size mode to the listed configuration keys.
- `fixed-true`: `xychart` / `xyChart` explicitly use `useMaxWidth: true`,
  independent of the selected size mode.
- `shared-unverified`: `flowchart-elk` has **no dedicated** key;
  `flowchart` is a potentially shared configuration key, but the actual
  Mermaid ELK consumer/config behavior is **not proven** by that source mapping.
- `no-per-type-key`: there is no dedicated per-diagram entry in the MarkFlow
  preview configuration. This does **not** imply that Mermaid cannot render
  the detector; the global `useMaxWidth` setting and upstream renderer
  behavior must not be conflated with an explicit per-diagram guarantee.

The missing explicit detector keys are:
agentflow, cynefin, eventmodeling, info, ishikawa, radar, railroad,
railroad-abnf, railroad-ebnf, railroad-peg, swimlanes, treeview, treemap,
usecase, wardley. The ELK-specific path is audited separately as
`shared-unverified`.

The corpus test checks this matrix against all 37 detector IDs, manifest
classifications/fixtures, and *every* explicit per-diagram `useMaxWidth`
key in the checked-in MarkFlow config source. A new detector or changed config
requires re-audit instead of silently drifting. These tests do **not** prove
that upstream Mermaid honors every key or that the rendered SVG responds
correctly to all three MarkFlow size modes. That runtime visual/behavioral
verification belongs to #352 and the differential harness in #353.
Do not add guessed Mermaid config keys or declare layout parity solely
from this source audit.
