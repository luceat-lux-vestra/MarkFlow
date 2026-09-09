# MarkFlow Engineering Instructions

## Mission

MarkFlow is an IntelliJ-platform WYSIWYG-first Markdown editor. The product goal is reliable, source-safe, responsive Markdown editing inside supported IntelliJ IDEs; preserving the current implementation is never a goal by itself.

The accepted Leap architecture is **IntelliJ-native authoritative editing with in-place source-neutral Markdown projection and isolated derived renderers**. Existing browser-editor code remains migration input until target slices replace and delete it.

## Required reading

For non-trivial work, read in this order:

1. `README.md` for current shipped/runtime behavior and target-transition notice;
2. `docs/architecture/README.md` for architecture authority and accepted target invariants;
3. ADR 0001 and ADR 0002 for native source authority and Mermaid/KaTeX continuity;
4. GitHub issue #52 for Leap execution policy and #141 for the migration map while migration is active;
5. the focused Track/Task and relevant tests/evidence.

The historical `plans/*` documents and superseded implementation-specific tests are context/evidence only. They never override #78, accepted ADRs, reconciled Tracks, or the focused target Task.

## Repository governance

Repository/delivery hardening is separate from runtime Leap work. The repository permits squash merges only. CI green is necessary but never sufficient.

A review PASS belongs only to the exact reviewed PR HEAD SHA. Any new commit invalidates the PASS and its evidence. Before merge re-read fresh `main`, merge-base, ruleset, exact HEAD, required checks, and review threads. Merge only with squash and `expected_head_sha=<reviewed SHA>`, then verify the resulting `main` SHA/tree/signature and post-main checks before closing the linked Task.

`UNKNOWN`, `UNVERIFIED`, and insufficient evidence are FAIL.

## Accepted architecture contracts

Every target change must respect or explicitly revise these contracts:

- IntelliJ `Document` is the **sole mutable live Markdown authority**.
- A native IntelliJ `Editor` edits that same `Document` directly.
- MarkFlow owns derived presentation only; projection, inlays, folds, images, Mermaid/KaTeX artifacts, and raw-HTML previews never become source authority.
- No second editable Markdown representation or correctness-critical browser mutation/ACK/recovery path exists in the target.
- Dirty/save/undo/redo and normal edit durability use intentional IntelliJ semantics, not browser flush, debounce, retry, or timer luck.
- No-edit presentation attach/refresh/reveal/dispose is source-byte-stable.
- Supported rich/native actions modify only the smallest justified lexical source region; ambiguous reconstruction degrades to exact source instead of guessing.
- Stale parse/projection/render work is inert unless it matches the current source/config identity.
- Unsupported, malformed, ambiguous, blocked, or renderer-failed content remains editable exact source.
- Optional renderer/JCEF failure never makes Markdown source editing unavailable.
- Local resources and external navigation are host-owned capabilities.
- Per-editor presentation and renderer-runtime lifecycles have explicit deterministic owners and bounded resources.

## Renderer continuity

Mermaid and KaTeX are supported product capabilities, not collateral browser-editor code.

Migration order is fixed:

1. extract/prove reusable renderer responsibilities while the current production renderer still works;
2. expose one editor-independent derived-renderer service/runtime;
3. attach the native editor/inlay consumer to that same service;
4. prove capability/settings/failure parity;
5. cut over production consumption;
6. only then delete Crepe/CodeMirror-specific renderer adapters.

Mermaid `11.17.2` and KaTeX `^0.18.5` remain the migration engines unless a separate accepted renderer decision replaces them. Do not reimplement Mermaid or TeX layout merely because the editor is native/Kotlin. Do not keep duplicate production renderer engines.

TypeScript/Vite/Node/JCEF may remain only for an actual isolated renderer consumer. JCEF below the renderer boundary never owns source editing and never gates typing/save/undo/source fallback.

## Migration rules

Already-merged Leap code receives no preservation credit. The current CodeMirror/source-native editor, Crepe/Milkdown editor, host↔web edit protocol, custom revisions, attachment/ACK/recovery, browser leases/pools, loopback editor realm, request/CSP/navigation controls, JS editor state, and browser-only settings are temporary or superseded unless #141 independently classifies a retained responsibility.

Classify by responsibility, not directory. A module can contain reusable renderer semantics and obsolete editor coupling at the same time.

Every `TEMPORARY` mechanism needs a named target owner and deletion criterion. Once replacement evidence and production ownership exist, mandatory purge is part of the migration; indefinite rollback architecture is not allowed.

Do not revive custom source revisions, epochs, ACKs, browser flush, whole-content reconstruction, retry loops, or bridge versioning unless a new proof shows an independent requirement that native `Document`/task identity cannot represent.

## Native IntelliJ discipline

- Use maintained IntelliJ Platform APIs; experimental/private APIs need an explicit compatibility decision and evidence.
- Make EDT/write-action/command requirements explicit.
- Preserve IntelliJ dirty/save/undo/redo behavior intentionally.
- Treat IME/composition, keymaps, multicaret, clipboard, accessibility, caret/selection/scroll, split editors, external document changes, project close, editor recreate/dispose, and JCEF-unavailable operation as ordinary scenarios.
- One per-editor presentation controller owns its listeners, inlays/folds, tasks and artifacts and disposes deterministically.
- Avoid detached background work and process-global mutable state.
- Derived work must carry enough source/config identity to reject stale results deterministically without becoming a second source revision protocol.

## Renderer / TypeScript discipline

When working on retained renderer infrastructure:

- keep editor-independent renderer contracts free of `Document` mutation authority and browser-editor session identity;
- use bounded source/config input and inert artifact or typed/redacted failure output;
- keep Mermaid/KaTeX engine semantics, palette/theme, size/zoom/error/density behavior, and failure corpus explicit;
- treat raw HTML as source-preserved data and sanitize/isolate only the derived preview;
- give JCEF/browser handlers, requests, listeners, timers, caches and processes explicit bounded owners when they actually remain;
- do not expose arbitrary filesystem/network/navigation authority;
- do not log full document or renderer source by default.

Do not create a browser editor adapter merely because renderer code is already TypeScript.

## Security

Treat Markdown, raw HTML, links, local-resource references, Mermaid input/configuration, image bytes, renderer input/output, and any retained renderer-runtime message boundary as untrusted.

Review changed trust boundaries for:

- raw HTML/script/event/style/active-content containment;
- local path normalization, traversal, symlink escape, media and size bounds;
- explicit external-navigation schemes and user action;
- renderer network/filesystem/navigation isolation;
- Mermaid security configuration;
- renderer/JCEF origin/request/CSP policy only when a retained renderer actually uses JCEF;
- clipboard/file import authority and rollback semantics;
- diagnostics redaction and bounded payloads.

Source-native editor CSP/request/loopback machinery is historical migration code, not target trust authority.

## Testing and evidence

Test the changed target contract at the narrowest level that can falsify it. Depending on risk, cover:

- source byte stability and lexical locality;
- native dirty/save/undo/redo/external-change behavior;
- projection attach/reveal/rebuild/dispose without source mutation;
- stale projection/render rejection;
- IME/keymap/multicaret/paste/caret/selection/scroll and split-editor behavior;
- Mermaid/KaTeX success/error/settings parity through the single renderer service;
- local resource/navigation and raw-HTML hostile cases;
- renderer unavailable/crash/restart with source editing still available;
- repeated editor/project/renderer lifecycle and bounded resources;
- large documents and rapid edits;
- supported IntelliJ versions and Plugin Verifier.

If a PR touches a temporary old browser-editor mechanism before its deletion, test the legacy failure/recovery contract needed to keep current `main` safe, but do not encode that mechanism as target authority. Mechanism-only tests are deleted/replaced with their superseded responsibility.

## Strict merge gate

Review the exact final PR HEAD for:

- functional correctness and regressions;
- source authority/fidelity and native IntelliJ semantics;
- target architecture and ownership boundaries;
- migration classification and deletion criteria;
- lifecycle, concurrency, stale-work and resource bounds;
- renderer continuity and accidental duplicate engines;
- error handling, diagnostics and provenance;
- security/trust boundaries;
- API/platform compatibility;
- performance claims and evidence;
- duplication, complexity, dead code, hacks and accidental compatibility shims;
- failure/recovery/edge/adversarial cases;
- tests/evidence and documentation consistency.

A PASS is valid only for the reviewed HEAD SHA. Any HEAD movement invalidates it.

## Work discipline

- Keep each PR to one coherent independently reviewable purpose.
- Preserve unrelated user changes.
- Do not combine production cutover, broad purge, dependency convergence and final quality proof into one rewrite PR; use the dependency graph from #141/#143–#156.
- Do not claim benchmark, compatibility, security, leak, lifecycle or runtime evidence that was not actually demonstrated.
- When a lower-level target choice remains `UNRESOLVED`, stop at the dedicated proof/ADR Task instead of silently preserving current mechanism.
