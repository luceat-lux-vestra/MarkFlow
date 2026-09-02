# Leap capability and fidelity contract

Status: authoritative product contract for Leap Epic #52

This document records the approved product and source-fidelity direction from
the [#78 product / fidelity audit decision](https://github.com/luceat-lux-vestra/MarkFlow-private/issues/78)
and the [#52 fresh-main architecture audit and target design](https://github.com/luceat-lux-vestra/MarkFlow-private/issues/52).
It is product authority for downstream work under #79–#84. It does not claim
that the current MarkFlow runtime already conforms to this contract.

The shared baseline corpus is in
[`fixtures/markdown-fidelity/`](../../fixtures/markdown-fidelity/). Its
manifest is validated by
[`webview/tests/markdown-fidelity-fixtures.test.mjs`](../../webview/tests/markdown-fidelity-fixtures.test.mjs).
That validation proves fixture integrity only; it is not runtime conformance
evidence.

## Product identity and supported files

MarkFlow is a WYSIWYG-first Markdown editor for IntelliJ-based IDEs. It is not
a Markdown compiler and it is not an alternate source-of-truth store.

The supported target files are:

- `.md`
- `.markdown`
- `.mdown`
- `.mkdn`
- files that IntelliJ recognizes as Markdown where the maintained
  compatibility matrix explicitly includes them

When JCEF is available and can safely initialize, MarkFlow is the preferred
WYSIWYG surface while IntelliJ's native/source editor remains available for
the same document. MarkFlow must not remove the user's source-editing escape
hatch.

If JCEF is unavailable, disabled, or fails before MarkFlow can safely own the
surface, the native/source editor is the fallback. A blank or broken MarkFlow
surface must never trap the user away from source editing. This fallback is a
product requirement, not a claim that every current provider path implements
it yet.

## Capability and fidelity vocabulary

Every capability claim and baseline fixture declares one capability
classification:

| Classification | Product meaning |
| --- | --- |
| `supported` | The capability is part of the product contract within the stated fidelity and trust envelope. |
| `degraded` | The source remains recoverable and editable, but preview or interaction is intentionally reduced or diagnosed for this input. |
| `unsupported` | MarkFlow does not promise to interpret or edit this construct; it must prefer source preservation and a safe opaque/source presentation. |
| `intentional-normalization` | A narrowly defined operation may normalize its newly created payload, with the affected region and surrounding-source guarantee stated explicitly. |

Unknown Markdown extensions are not silently promoted to `supported`. Until an
explicit product decision exists, they are classified as `unsupported` or
`degraded` according to the safe presentation that is available.

Fidelity expectations used by the baseline manifest are:

- `byte-stable`: opening, rendering, state changes, runtime recreation,
  deactivation, and closing without a source edit leave the `Document` text
  unchanged byte-for-byte (including separators and final newline state);
- `lexically-local`: a supported local edit changes only the smallest justified
  region; unrelated source retains its whitespace, delimiters, line endings,
  and other lexical choices;
- `source-preserved-degraded`: a preview/parser/renderer limitation leaves the
  source unchanged and exposes a safe, diagnosable degraded/source view;
- `inserted-payload-only`: an operation such as paste may normalize only the
  newly inserted payload, never pre-existing surrounding source or the whole
  document.

Semantic or visual equivalence alone is not lexical-fidelity evidence.

## Source-fidelity contract

The open IntelliJ `Document` is the authoritative live Markdown source. Web
editor state is a projection of an authoritative document revision. Disk/VFS
is persistence and external-state evidence, not a second live authority.

### No-edit invariant

Opening, rendering, switching tabs, changing appearance settings, recreating
the web runtime, deactivating, or closing a document without a user source edit
must not change the Markdown bytes/text in the IntelliJ `Document`.

### Local-edit invariant

When a WYSIWYG edit can be represented within the supported fidelity envelope,
source outside the smallest justified edited region remains lexically
unchanged. This includes, when outside that region:

- whitespace and blank-line spacing;
- unordered bullet markers and ordered-list delimiters;
- heading style and closing ATX markers;
- emphasis and strong delimiter choice;
- fence marker, fence length, and indented/fenced code form;
- thematic-break style;
- inline/reference link and reference-definition style;
- raw HTML text;
- line separators and trailing-newline state.

An ambiguous source reconstruction is rejected or degraded rather than guessed.
The target architecture must not treat whole-document serialization plus
heuristic AST/LCS patching as proof of lexical locality. The existing
`source-preserving-markdown*` and `markdown-source-buffer` implementations are
historical evidence only, not this contract or architecture authority.

## Markdown and derived rendering capabilities

The baseline product scope is CommonMark-style Markdown plus the explicit
capabilities below. Parser acceptance of an arbitrary extension is not a
product support claim.

| Capability | Product contract | Fidelity / failure behavior |
| --- | --- | --- |
| Ordinary paragraphs, headings, emphasis/strong, links, images, lists, block quotes, code, fenced code, and thematic breaks | Supported within the maintained engine/evidence envelope | Preserve unrelated lexical source during local edits; no-edit paths are byte-stable. |
| Tables | Supported only when the selected editor engine and maintained evidence cover them | Otherwise classify as degraded or unsupported; do not silently normalize. |
| Mermaid fenced code | Supported as derived preview | Mermaid source remains authoritative Markdown fenced-code source. A rendering error preserves source and degrades to a diagnosable source/editor representation. |
| Inline and display math with KaTeX-compatible semantics | Supported as derived preview | Math source remains authoritative. A renderer error preserves source and degrades visibly/diagnosably. |
| Raw HTML | Supported as source-preserved content with a separately sanitized preview | Source is never rewritten to sanitize preview. Active content is blocked under the #82 trust policy; blocked preview remains recoverable/editable source. |
| Document-relative local images/resources | Supported under the #82 capability-scoped trust policy | A load failure leaves source unchanged. Opening a document never grants arbitrary filesystem access. |
| Remote resources | Not an implicit entitlement of Markdown rendering | Claim support only after #82 defines an allowed default or explicit opt-in and its privacy consequence. Until then, fail closed or classify as degraded/unsupported. |
| Markdown-aware paste outside code blocks | Supported | Prefer `text/markdown`; parse Markdown-like plain text where appropriate. BOM/line-ending cleanup and parsing normalization apply only to the inserted payload. |
| Paste inside code blocks | Literal/default paste behavior | Existing source around the insertion remains unchanged. |
| Unknown/unsupported Markdown extensions | Unsupported or degraded, explicitly classified per input | Prefer source preservation and a safe opaque/degraded presentation over destructive normalization. |

### Raw HTML and trust boundary

Raw HTML is untrusted content. Inline and block source must be preserved, while
preview sanitization is a separate concern. Script, event-handler, active
style, browser-capability, permissive navigation, and equivalent behavior fails
closed under #82. URLs in raw HTML follow the same resource/navigation policy
as Markdown links and images; raw HTML does not create a permissive side path.

### Links, images, resources, and navigation

Ordinary document-relative local resources are supported only through an
explicit, bounded capability. There is no arbitrary local-file access. An
external link must use an explicit external-navigation path owned by #82, and
navigation must not replace the MarkFlow editor realm with arbitrary content.
Source remains unchanged when a resource or navigation request is blocked or
cannot be loaded.

## IntelliJ document, save, and undo requirements

The product-visible requirements are:

- the IntelliJ `Document` is the live editing authority once the file is open;
- dirty state, save, and persistence follow IntelliJ document/save semantics,
  not a parallel web-owned delayed autosave authority;
- web-originated user edits participate intentionally in IntelliJ dirty,
  command, and undo/redo behavior;
- MarkFlow does not force-save merely because a debounce elapsed;
- deactivation, close, disposal, JCEF failure, and runtime replacement cannot
  silently lose the newest acknowledged user edit;
- stale or conflicting host/web proposals cannot silently overwrite newer
  source.

The mechanics of document revisions, write actions, conflict handling, and
undo/redo belong to #79. This document does not prescribe the current classes
or implementation strategy.

## Editor state

Caret/cursor position, selection, and scroll position are per-editor-surface
presentation state. They should be restored across editor recreation/reopen
where IntelliJ supplies/restores `FileEditor` state, on a best-effort basis.
Invalid or stale state is clamped or dropped safely. It never mutates or
overrides Markdown source.

Split editors and windows may have different caret, selection, and scroll
state while observing the same authoritative IntelliJ `Document`.

## Product settings versus implementation knobs

The following are product/user settings when supported by the final renderer:

- theme source and IDE palette integration;
- font family and base size;
- preview-only default;
- Mermaid size/zoom/error-display behavior;
- KaTeX display density;
- a security-sensitive renderer policy only when #82 can expose it safely and
  explain its effect.

Browser-pool, prewarm, cache, idle-eviction, retry, debounce, and similar
performance/lifecycle controls are implementation knobs, not product
capabilities. In particular, `idleEvictAfterMs` is not part of the Leap product
contract. The #52 target baseline is one browser/realm per live MarkFlow editor
surface; pooling, prewarming, shared realms, and delta/caching complexity need
later measured benefit plus isolation and correctness evidence.

## Compatibility and evidence policy

The maintained compatibility baseline is IntelliJ build `262+` / platform
2026.2+ with the bundled JCEF module available for the MarkFlow WYSIWYG
surface. Plugin Verifier and build success are necessary compatibility
evidence, but do not prove JCEF runtime behavior.

Public claims must name the maintained and tested IDE/JCEF matrix. Claims
beyond that exact matrix require #84 evidence. JCEF-unavailable and
initialization-failure fallback must be represented by native-editor fallback
evidence. Manual-only UI/JCEF behavior must be recorded as manual evidence,
not represented as a passing pure test.

Evidence is contract-indexed and includes, as applicable:

- pure document/revision/conflict/fidelity tests;
- protocol identity, ordering, acknowledgement, and stale-rejection tests;
- lifecycle tests for reload, replacement, disposal, split editors, and
  multiple projects;
- hostile raw HTML/resource/navigation fixtures;
- persistence, migration, reopen, split, and theme/state tests;
- the shared lexical-fidelity corpus, including repeated/ambiguous blocks;
- large-document, rapid-edit, repeated-lifecycle, compatibility, and resource
  retention evidence;
- explicit manual-only gaps.

The corpus validator only checks that the declared fixtures and their metadata
are internally consistent. Passing it must never be reported as proof that the
current editor/runtime satisfies this product contract.
