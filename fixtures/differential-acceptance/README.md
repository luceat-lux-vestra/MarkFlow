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

During #354, `gap` rows are allowed only when their exact case IDs are pinned in the
validator's reviewed `knownGapIds` set. That makes incompleteness explicit and prevents
silent coverage shrinkage while the draft PR is being completed.

Before #354 can merge, every supported/degraded/unsupported feature family must have an
appropriate repository-owned fixture and the reviewed gap set must be empty.

The runtime differential test in #353 will consume this manifest after #354 completes.
This integrity layer does not claim rendering correctness by itself.
