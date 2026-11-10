# MarkFlow examples

This directory is the repository-owned manual product-validation surface. These files are examples,
but they also exist so a release candidate can be exercised in a real IntelliJ IDE without inventing
a new ad-hoc document.

## Recommended release sweep

1. **`markdown-core.md`** — focused ordinary Markdown lexical/presentation checks.
2. **`katex-math.md`** — KaTeX-compatible inline/display formulas plus intentional renderer errors.
3. **`raw-html-support.md`** — safe raw-HTML presentation, fail-closed capability cases,
   unsupported browser semantics, malformed fragments, and source round-trip.
4. **`mermaid-minimal.md`** — quickest Mermaid success sanity check.
5. **`mermaid-error.md`** — failure isolation between valid and invalid Mermaid blocks.
6. **`eg2.md`** — broad mixed Markdown/KaTeX/Mermaid stress document.
7. The remaining **`mermaid-*.md`** files — diagram-family compatibility sweep.

`eg1.md` is retained as an additional styled/subgraph Mermaid stress example.

## What a manual PASS means

A real-IDE PASS is more than “the file opens”:

- exact Markdown source remains the sole mutable authority;
- rich presentation appears for the supported subset;
- entering a projected construct reveals exact editable source;
- leaving it restores current presentation without duplicate/stale artifacts;
- save/reopen preserves unrelated source bytes and lexical choices;
- one malformed/unsupported renderer input is contained locally—source fallback or a bounded fail-soft error artifact, according to that renderer's contract—instead of blanking the document;
- Mermaid/KaTeX/raw-HTML failure does not prevent ordinary Markdown editing;
- theme/config changes reject stale derived artifacts;
- blocked raw HTML remains editable source and does not gain ambient script/network/navigation power.

## Coverage notes

The many Mermaid files intentionally cover successful diagram-family breadth. `mermaid-error.md`
covers the complementary failure-isolation case.

The focused Markdown and KaTeX files do not replace the automated differential/fixture corpus under
`fixtures/`; they provide a human-readable, repository-owned surface for final product smoke tests.
Automated corpus PASS and manual product PASS are separate evidence.
