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
