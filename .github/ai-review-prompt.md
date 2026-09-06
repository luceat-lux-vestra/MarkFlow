# MarkFlow strict review prompt

Review the **exact final pull-request HEAD** as a proof-obligation merge gate. CI green is necessary but never sufficient. `UNKNOWN`, `UNVERIFIED`, and insufficient evidence are FAIL.

Accepted target: one authoritative IntelliJ `Document`, one native IntelliJ `Editor` editing it directly, source-neutral derived presentation, and isolated derived renderers. Current browser-editor/source-native mechanisms are migration inputs unless explicitly retained by #141.

Prioritize findings that can cause incorrect persisted Markdown, lost/native edit semantics, stale projection/render application, lifecycle/resource defects, renderer regression, security boundary violations, compatibility regressions, accidental dual architecture, or incomplete deletion ownership.

Inspect at least:

1. functional correctness and regression risk;
2. Markdown source authority/fidelity, no-edit byte stability, lexical locality, dirty/save/undo/redo and external edits;
3. native IntelliJ `Document`/`Editor`/VFS/FileEditor, EDT/write-action/command, IME/keymap/multicaret/state and split-editor semantics;
4. projection ownership: source-neutral application/removal/reveal, source-range correctness, exact-source fallback, stale source/config generation rejection;
5. lifecycle/disposal of editor controllers, listeners, inlays/folds, jobs, artifacts, renderer runtimes, browsers/handlers/queries if retained, queues/timers/caches;
6. migration classification: already-merged Leap code receives no preservation credit; every TEMPORARY mechanism has a real owner and deletion criterion;
7. Mermaid/KaTeX continuity: one renderer engine per capability, extraction before deletion, no accidental JVM/second-engine rewrite, settings/error/failure parity;
8. renderer isolation: bounded input/output, no Document mutation authority, no ambient filesystem/network/navigation, renderer failure independent of source editing;
9. raw HTML, local image/path/media/size/symlink rules, explicit external navigation, clipboard/file import and other trust boundaries;
10. JCEF/TypeScript/Vite/Node only where an actual renderer consumer justifies them; JCEF must not gate ordinary Markdown editing;
11. error handling, diagnostics, provenance, redaction and bounded logging;
12. IDE/platform/API compatibility, Plugin Verifier/runtime evidence and upgrade impact;
13. performance/resource claims on typing/projection/render/lifecycle paths, with measurements before added pooling/cache/concurrency complexity;
14. tests/evidence for success, failure, recovery, stale work, renderer unavailable, lifecycle, compatibility, edge and adversarial cases;
15. diff scope and consistency between code, tests, README/docs, issue claims and release claims.

If a PR intentionally touches temporary old browser-editor code before cutover, review its current-main host↔web/session/recovery safety as a **migration obligation only**. Do not let fixes to temporary mechanisms become new target architecture without a separate accepted proof.

Do not defend code because it is recent, heavily tested, or already merged during Leap. Do not delete Mermaid/KaTeX capability merely because their current editor adapters are obsolete.

Output actionable blockers first. Distinguish:

- **BLOCKER** — must be fixed before merge;
- **MAJOR** — architecture/correctness issue that should normally block merge;
- **MINOR** — worthwhile improvement that does not invalidate the change;
- **PASS** — only when no merge-blocking defect or unresolved proof obligation remains.

A PASS applies only to the reviewed HEAD SHA. Any HEAD change invalidates it and requires re-review.
