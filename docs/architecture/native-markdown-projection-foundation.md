# Native Markdown projection foundation

Status: #145 native projection foundation, expanded by #152 ordinary-Markdown/table parity under ADR 0001

## Decision

MarkFlow augments the normal IntelliJ platform text editor selected by #143. The open IntelliJ `Document` remains the sole live mutable Markdown authority. Presentation is derived from immutable exact snapshots and may never write source merely because it is created, refreshed, revealed, degraded or disposed.

The target flow is:

```text
IntelliJ Document
      │ immutable exact snapshot under read action
      ▼
ProjectionSourceIdentity(source + modificationStamp + configGeneration)
      │
      ▼
bundled JetBrains Markdown/GFM parser
      │ parser-proven ranges only
      ▼
NativeProjectionPlan
      │ exact-current identity gate
      ▼
NativePresentationController (one per Editor)
      ├── source-neutral ordinary Markdown folds/highlighters
      ├── NativeTablePresentationController
      ├── #147 host-resource presentation
      └── #148 derived renderer presentation
```

There is no second editable Markdown representation, browser/JS authority, serializer write-back or AST/LCS source reconstruction in this architecture.

## Parser boundary

`org.intellij.plugins.markdown` is an explicit bundled-plugin dependency. MarkFlow uses `MarkdownParserManager.createMarkdownParser(MarkdownParserManager.FLAVOUR)` with inline parsing enabled against an immutable source string.

The dependency is a maintained parser/range dependency. MarkFlow does **not** call JetBrains' internal live-preview reconciler/spec APIs. IntelliJ's bundled Markdown live preview may coexist on the same platform editor, but MarkFlow's correctness cannot require `@ApiStatus.Internal` implementation contracts or a user setting being enabled.

Parser/tree ranges are validated against the exact snapshot before becoming projection intent. `ProcessCanceledException` propagates. Other parser failures become typed `DEGRADED_TO_SOURCE` plans with no projected ranges; only the failure class name is retained.

## Immutable source identity and stale rejection

Every plan carries:

- exact snapshot source;
- the `Document.modificationStamp` captured with it;
- projection configuration generation.

Before presentation changes, the controller captures the current identity again. Any source, stamp or configuration mismatch returns `STALE_REJECTED` before current MarkFlow-owned presentation is touched.

Document changes schedule an EDT refresh with controller-local generation coalescing. Disposal invalidates pending callbacks. A later valid plan may recover from `DEGRADED_TO_SOURCE` without any source mutation.

## Ordinary Markdown projection set (#152)

The #145 representative slice has been expanded to parser-proven projections for:

- paragraphs and ATX/Setext headings;
- emphasis and strong;
- inline/reference/shortcut/autolinks where a stable visible label/range is parser-proven;
- unordered/ordered lists and list-item markers;
- block quotes;
- inline code, fenced code and indented code;
- thematic breaks;
- GFM tables, headers, rows and cell ranges.

Image `IMAGE` subtrees are deliberately not reinterpreted as ordinary links: #147 owns local-image/resource presentation. Raw HTML remains owned by #149. Mermaid/KaTeX remain #148 responsibilities.

Marker syntax is never reconstructed from semantic text. List and block-quote marker ranges originate in parser tokens; when JetBrains' token includes following separator whitespace, MarkFlow excludes only that trailing whitespace from the presentation-owned marker range so the original separator bytes remain visible/preserved.

Link projection similarly uses parser-proven link text/label children. If a stable visible content range cannot be proven, MarkFlow does not invent one.

## Source-neutral ordinary presentation

`NativePresentationController` owns only presentation it creates. It never calls a `Document` mutation API.

Inactive supported constructs may use public editor highlighters and folding to conceal parser-proven syntax while leaving the authoritative source unchanged. Current #152 presentation includes:

- heading/emphasis/strong/inline-code/fence syntax concealment;
- link syntax concealment around a parser-proven visible label;
- source-neutral list marker placeholders;
- source-neutral block-quote marker placeholders;
- thematic-break presentation;
- syntax-aware highlighting for supported ordinary constructs.

Caret/selection source reveal is boundary-inclusive for presentation purposes: a caret touching either edge of a projected construct reveals its exact syntax so a hidden fold never owns the caret boundary. Parser containment itself remains half-open `[start,end)`.

If another owner, including bundled Markdown live preview, already owns the exact same fold range, MarkFlow does not create a conflicting duplicate. MarkFlow still relies only on public platform folding/markup APIs and can provide its own fallback presentation when that platform-owned fold is absent.

When rich presentation is disabled for accessibility/screen-reader disposition, ordinary MarkFlow folds/highlighters are not installed and exact Markdown source remains visible.

## Table presentation

Supported GFM table structure is derived only from parser-proven TABLE/HEADER/ROW/CELL ranges. There is no pipe-splitting source parser and no table serializer.

An inactive supported table is represented by a native block inlay while its exact source range is source-neutrally folded. Any caret or selection touching the table reveals exact source. A left click is handled from IntelliJ's public `EditorMouseEvent.inlay` identity and moves the primary caret to the first parser-proven cell after removing MarkFlow's table presentation.

Screen-reader/accessibility disposition installs no table fold/inlay. Failed or unproven table models remain exact source.

The table inlay proves supported table structure and source/reveal ownership; #152 does not introduce an independent general-purpose Markdown renderer engine inside table cells.

## Fidelity and ownership invariants

For ordinary Markdown projection:

- attach/open/refresh/reveal/recreate/dispose without a source edit must leave source and modification stamp unchanged;
- projected ranges come from the exact immutable source generation;
- unrelated whitespace, delimiter choice, fences, list markers, line endings and final newline are not normalized by presentation;
- stale work cannot replace newer presentation or source;
- malformed/unsupported/ambiguous constructs remain exact source;
- split editors own independent presentation over one shared authoritative `Document`;
- MarkFlow removes only folds/highlighters/inlays it owns.

These are lexical-fidelity constraints, not merely visual-equivalence claims.

## Runtime evidence

`Native Projection Evidence` launches a real supported IntelliJ runtime under Xvfb with JCEF runtime support disabled. The base #145 proof covers:

- real project/VFS/Document authority;
- platform text editor + bundled Markdown coexistence;
- no-edit attachment stability;
- parser-proven plan/ranges;
- native inline/block presentation and multicaret source reveal;
- source/config stale-plan rejection;
- document refresh and degraded-plan recovery;
- split-editor isolation;
- malformed/unsupported fallback;
- lifecycle cleanup.

#152 adds mandatory parity evidence for:

- the actual #78 ordinary fidelity corpus files mapped through the runtime parser/projection path with exact source identity;
- the expanded ordinary projection kinds and parser ranges;
- source-neutral list/quote/link/thematic ordinary presentation plus boundary reveal;
- exact-source accessibility fallback;
- maintained-API native table presentation and mouse/caret/selection reveal;
- table accessibility fallback;
- rapid source edits/caret movement with exact-current regeneration;
- a large representative document baseline.

The workflow validator fails closed on missing/unexpected cases or missing detail markers. Changes to `fixtures/markdown-fidelity/**` select this workflow because those files are runtime evidence inputs.

Normal Build/Test/Inspect code/Verify plugin, repository Hardening, Native Editing Evidence and the retained shell/resource/derived evidence workflows remain independent gates when selected by the diff.

## Migration ownership

- #143: platform text-editor shell, retained;
- #145: immutable native projection authority and lifecycle foundation, retained;
- #146: native editing/paste/state foundation, retained;
- #147: host local-image/navigation projection;
- #148: Mermaid/KaTeX derived presentation;
- #149: sanitized raw-HTML presentation;
- #152: ordinary Markdown/table fidelity parity described here;
- #153: production native cutover;
- #154: mandatory browser editor/protocol/trust purge;
- #155: final dependency/toolchain/JCEF/settings convergence.

No part of #145/#152 authorizes production cutover or browser deletion before #153/#154.
