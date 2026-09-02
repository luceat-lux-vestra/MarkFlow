# MarkFlow Copilot Instructions

Follow the repository root `AGENTS.md` as the authoritative engineering and review policy.

Key constraints:

- Preserve proven product behavior, not the current implementation structure.
- Treat IntelliJ `Document`/VFS/FileEditor lifecycle, JCEF resource ownership, Markdown source fidelity, and host↔webview synchronization as correctness-critical contracts.
- Do not assume existing browser pooling, `window.*` globals, `cefQuery` message formats, source-preserving helpers, timers, boolean guards, or Milkdown/Crepe integration are permanent architecture.
- Prefer explicit ownership, a versioned/validated bridge protocol, and deterministic revision/session state over timing-dependent fixes.
- Treat Markdown, raw HTML, links/resources, and webview messages as untrusted input.
- Do not log full document content by default.
- CI green is necessary but insufficient. Changes require strict review of the exact final PR HEAD as described in `AGENTS.md`.
- Release/publication is a separate explicit gate.

For architecture work, use GitHub issue #52 and its child issues/ADRs as the current direction. Historical `plans/*` are non-authoritative context only.
