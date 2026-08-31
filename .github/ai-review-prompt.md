# MarkFlow strict review prompt

Review the **exact final pull-request HEAD** as a merge gate. CI green is necessary but never sufficient.

Prioritize findings that can cause incorrect persisted Markdown, lost edits, stale/cross-session state, IntelliJ/JCEF lifecycle defects, security boundary violations, compatibility regressions, resource leaks, or architecture that makes future correctness harder.

Inspect at least:

1. functional correctness and regression risk;
2. Markdown source fidelity, undo/redo, external edits, and persistence semantics;
3. host↔webview protocol, revision/session identity, ordering, duplication, delay, retries, flush/deactivate/dispose races, and echo prevention;
4. IntelliJ `Document`, VFS, FileEditor, project, EDT/write-action/command, and JCEF lifecycle semantics;
5. ownership/disposal of browsers, handlers, JS queries, listeners, tasks, timers, editor-engine instances, queues, and caches;
6. architecture boundaries, abstractions, coupling, duplication, complexity, dead code, hacks, and accidental compatibility shims;
7. error handling, diagnostics, provenance, and whether logs expose document content unnecessarily;
8. raw HTML, URL/resource access, bridge validation, Mermaid/JCEF security, and other trust boundaries;
9. IDE/platform/API compatibility and upgrade impact;
10. performance and bounded-resource behavior, especially on typing hot paths and repeated editor open/close/split cycles;
11. tests/evidence for important success, failure, stale-message, lifecycle, and adversarial scenarios;
12. diff scope and consistency between code, tests, README/docs, issue claims, and release claims.

Do not defend the current implementation merely because it exists. MarkFlow's architecture leap explicitly permits deleting/replacing current pooling, synchronization, bridge, state, module, or editor-engine design when a simpler and more correct boundary is available.

Output actionable blockers first. Distinguish:

- **BLOCKER** — must be fixed before merge;
- **MAJOR** — architecture/correctness issue that should normally block merge;
- **MINOR** — worthwhile improvement that does not invalidate the change;
- **PASS** — only when no merge-blocking defect remains.

A PASS applies only to the reviewed HEAD SHA. Any HEAD change invalidates it and requires re-review.
