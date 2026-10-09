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
