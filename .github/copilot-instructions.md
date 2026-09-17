# MarkFlow Copilot Instructions

Follow repository root `AGENTS.md`, `docs/architecture/README.md`, accepted ADR 0001/0002, and the focused issue.

Key constraints:

- IntelliJ `Document` is the sole mutable live Markdown authority; a native IntelliJ `Editor` edits that same `Document` directly.
- MarkFlow presentation is derived and source-neutral. Projection/inlays/folds/render artifacts never become a second editable source model.
- Do not introduce or preserve host↔web edit mutation, custom source revisions, attachment/ACK/recovery, browser flush durability, JS editor state, or JCEF editor readiness as target architecture.
- Reject stale parse/projection/render work using exact current source/config identity. Do not solve correctness with debounce, retries, sleeps, boolean guards, pooling, or prewarm.
- Unsupported/ambiguous/renderer-failed content must remain editable exact source.
- Mermaid `11.17.2` and KaTeX `^0.18.7` are the retained production renderer engines behind one derived-renderer service. Native presentation consumes that service; do not recreate deleted editor adapters or introduce duplicate engines.
- TypeScript/Vite/Node are retained only for the isolated renderer build; JCEF is an optional renderer backend. None may gate source editing.
- Local images/resources and external navigation are host-owned capabilities. Treat Markdown, raw HTML, links/resources and renderer inputs as untrusted.
- Already-merged Leap code gets no preservation credit. Use #141 responsibility classifications and explicit TEMPORARY deletion criteria.
- CI green is necessary but insufficient. Review the exact final HEAD; any HEAD movement invalidates prior PASS/evidence.
- Release/publication is a separate explicit gate.

Historical `plans/*`, old browser-editor tests, and current file/package structure are evidence only, not architecture authority.
