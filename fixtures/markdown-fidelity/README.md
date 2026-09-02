# Markdown fidelity fixture corpus

This is the shared baseline corpus for Leap product/fidelity work under #78
and #52. It is intentionally independent of the current editor engine and
runtime implementation.

`manifest.tsv` is the machine-readable index. Each row declares:

1. a unique fixture ID;
2. the repository-relative fixture path;
3. one capability classification;
4. one fidelity expectation;
5. expected line-ending style;
6. expected trailing-newline presence; and
7. comma-separated coverage categories.

Fixture bytes are evidence. In particular, the CRLF and no-trailing-newline
fixtures must not be rewritten by a platform-default text helper.

The validator in
`webview/tests/markdown-fidelity-fixtures.test.mjs` checks manifest shape,
unique IDs/paths, declared-file completeness, allowed enums, file existence,
line endings, and trailing-newline metadata. It validates the corpus only; it
does not run MarkFlow or claim runtime fidelity conformance.

Downstream Kotlin or webview tests may load the files directly from this
directory and apply the product contract's own operation-specific assertions.
