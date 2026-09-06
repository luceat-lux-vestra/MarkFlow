# Leap capability and fidelity contract

Status: authoritative product contract for Leap Epic #52

This document records the approved product and source-fidelity direction from
[#78](https://github.com/luceat-lux-vestra/MarkFlow/issues/78) and the
[#52 Architecture Leap Epic](https://github.com/luceat-lux-vestra/MarkFlow/issues/52).
It is product authority for downstream work under #79–#84. It does not claim
that the current MarkFlow runtime already conforms to this contract.

Architecture mechanisms are intentionally not product authority. Accepted ADRs
under #139/#140 decide how this contract is implemented. In particular, a
browser/JCEF editor, native editor, renderer process, protocol, cache, or
specific editor library is not a product requirement merely because an earlier
implementation used it.

The shared baseline corpus is in
[`fixtures/markdown-fidelity/`](../../fixtures/markdown-fidelity/). Its
manifest is currently validated by
[`webview/tests/markdown-fidelity-fixtures.test.mjs`](../../webview/tests/markdown-fidelity-fixtures.test.mjs).
That path is historical implementation detail; the fixture contract survives
any test-harness/toolchain migration. Fixture validation proves corpus integrity
only, not runtime conformance.

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

The primary MarkFlow surface must provide WYSIWYG-first editing while preserving
an immediate exact-source editing escape hatch for the same authoritative
`Document`.

Optional rich-renderer failure must not make Markdown uneditable. A Mermaid,
KaTeX, raw-HTML, image, or other derived-preview failure degrades the affected
presentation to exact recoverable source rather than replacing the editor with
a blank/broken surface.

## Capability and fidelity vocabulary

Every capability claim and baseline fixture declares one capability
classification:

| Classification | Product meaning |
| --- | --- |
| `supported` | The capability is part of the product contract within the stated fidelity and trust envelope. |
| `degraded` | The source remains recoverable and editable, but preview or interaction is intentionally reduced or diagnosed for this input. |
| `unsupported` | MarkFlow does not promise to interpret or edit this construct; it must prefer source preservation and a safe opaque/source presentation. |
| `intentional-normalization` | A narrowly defined operation may normalize only its newly created payload, with the affected region and surrounding-source guarantee stated explicitly. |

Unknown Markdown extensions are not silently promoted to `supported`. Until an
explicit product decision exists, they are classified as `unsupported` or
`degraded` according to the safe presentation that is available.

Fidelity expectations used by the baseline manifest are:

- `byte-stable`: opening, rendering, state changes, presentation recreation,
  deactivation, and closing without a source edit leave the `Document` text
  unchanged byte-for-byte, including separators and final newline state;
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

The open IntelliJ `Document` is the authoritative live Markdown source. Editor
presentation, parser state, renderer state, caches, and disk/VFS state are not
competing live source authorities. Disk/VFS is persistence and external-state
evidence.

### No-edit invariant

Opening, rendering, switching tabs, changing appearance settings, recreating
presentation/renderer state, deactivating, or closing a document without a user
source edit must not change the Markdown bytes/text in the IntelliJ `Document`.

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
heuristic AST/LCS patching as proof of lexical locality. Existing
`source-preserving-markdown*` and `markdown-source-buffer` implementations are
historical evidence only, not this contract or architecture authority.

## Markdown and derived rendering capabilities

The baseline product scope is CommonMark-style Markdown plus the explicit
capabilities below. Parser acceptance of an arbitrary extension is not a
product support claim.

| Capability | Product contract | Fidelity / failure behavior |
| --- | --- | --- |
| Ordinary paragraphs, headings, emphasis/strong, links, images, lists, block quotes, code, fenced code, and thematic breaks | Supported within the maintained engine/evidence envelope | Preserve unrelated lexical source during local edits; no-edit paths are byte-stable. |
| Tables | Supported when the selected projection/editor implementation and maintained evidence cover them | Otherwise classify as degraded or unsupported; do not silently normalize. |
| Mermaid fenced code | **Supported as derived preview** | Mermaid source remains authoritative Markdown fenced-code source. A rendering error preserves source and degrades to a diagnosable source/editor representation. Mermaid support survives editor/runtime migration. |
| Inline and display math with KaTeX-compatible semantics | **Supported as derived preview** | Math source remains authoritative. A renderer error preserves source and degrades visibly/diagnosably. KaTeX-compatible support survives editor/runtime migration. |
| Raw HTML | Supported as source-preserved content with a separately sanitized/isolated preview | Source is never rewritten to sanitize preview. Active content fails closed; blocked preview remains recoverable/editable source. |
| Document-relative local images/resources | Supported under a capability-scoped host trust policy | A load failure leaves source unchanged. Opening a document never grants arbitrary filesystem access. |
| Remote resources | Not an implicit entitlement of Markdown rendering | Claim support only after #82 defines an allowed default or explicit opt-in and its privacy consequence. Until then, fail closed or classify as degraded/unsupported. |
| Markdown-aware paste outside code blocks | Supported | Prefer `text/markdown`; parse Markdown-like plain text where appropriate. BOM/line-ending cleanup and parsing normalization apply only to the inserted payload. |
| Paste inside code blocks | Literal/default paste behavior | Existing source around the insertion remains unchanged. |
| Unknown/unsupported Markdown extensions | Unsupported or degraded, explicitly classified per input | Prefer source preservation and a safe opaque/degraded presentation over destructive normalization. |

### Mermaid and KaTeX continuity

Mermaid and KaTeX are product capabilities, not properties of Crepe,
CodeMirror, JCEF, or any other editor shell.

Editor/runtime migration must therefore preserve their supported behavior. ADR
0002 defines the migration boundary: reuse/extract maintained Mermaid and KaTeX
renderer engines and replace only editor-specific adapters unless a separate,
evidence-backed renderer-replacement decision is approved.

The migration must not intentionally maintain two independent Mermaid or KaTeX
engines in steady-state production.

### Raw HTML and trust boundary

Raw HTML is untrusted content. Inline and block source must be preserved, while
preview sanitization/isolation is a separate concern. Script, event-handler,
active style, ambient resource capability, permissive navigation, and
equivalent behavior fail closed under #82. URLs in raw HTML follow the same
resource/navigation policy as Markdown links and images; raw HTML does not
create a permissive side path.

### Links, images, resources, and navigation

Ordinary document-relative local resources are supported only through an
explicit, bounded host capability. There is no arbitrary local-file access. An
external link uses an explicit host-owned navigation path under #82. Source
remains unchanged when a resource or navigation request is blocked or cannot
be loaded.

## IntelliJ document, save, and undo requirements

The product-visible requirements are:

- the IntelliJ `Document` is the live editing authority once the file is open;
- dirty state, save, and persistence follow IntelliJ document/save semantics,
  not a parallel delayed autosave authority;
- user edits participate intentionally in IntelliJ command and undo/redo
  behavior;
- MarkFlow does not force-save merely because a debounce elapsed;
- presentation/renderer failure, deactivation, close, disposal, or replacement
  cannot silently lose a user edit;
- stale asynchronous projection/render work cannot silently overwrite newer
  source or newer presentation.

The exact document write/command, conflict, and undo/redo mechanics belong to
#79 and accepted architecture ADRs. This document does not prescribe historical
session/revision/protocol classes.

## Editor state

Caret/cursor position, selection, and scroll position are per-editor-surface
presentation state. They should be restored across editor recreation/reopen
where IntelliJ supplies/restores editor state, on a best-effort basis. Invalid
or stale state is clamped or dropped safely. It never mutates or overrides
Markdown source.

Split editors and windows may have different caret, selection, scroll, and
presentation state while observing the same authoritative IntelliJ `Document`.

## Product settings versus implementation knobs

The following are product/user settings when supported by the final renderer:

- theme source and IDE palette integration;
- font family and base size;
- preview/source-reveal presentation behavior where exposed;
- Mermaid size/zoom/error-display behavior;
- KaTeX display density;
- a security-sensitive renderer policy only when #82 can expose it safely and
  explain its effect.

Browser/renderer pool size, prewarm, cache, idle eviction, retry, debounce,
worker cardinality, transport batching, and similar performance/lifecycle
controls are implementation knobs, not product capabilities. They require
measured benefit, bounded lifetime, and correctness/isolation evidence.

No product contract requires one browser per editor, a shared browser realm, a
loopback server, a host↔web protocol, or any other historical transport shape.

## Compatibility and evidence policy

The maintained IDE compatibility baseline is IntelliJ build `262+` / platform
2026.2+ until changed by an explicit compatibility decision.

The primary Markdown editing surface must not depend on optional derived
renderer availability. If a retained renderer backend uses JCEF, that backend
requires an explicitly tested IDE/JCEF matrix and real-runtime evidence, while
JCEF failure must degrade only affected derived previews and must not make
source editing unavailable.

Plugin Verifier and build success are necessary compatibility evidence but do
not prove interactive editor or renderer runtime behavior.

Evidence is contract-indexed and includes, as applicable:

- pure document/fidelity tests;
- native editor command/undo/save/state integration tests;
- stale projection/render rejection tests;
- lifecycle tests for recreation, disposal, split editors, and multiple
  projects;
- Mermaid success/error and representative diagram corpus evidence;
- KaTeX inline/display success/error evidence;
- renderer theme/settings continuity evidence;
- hostile raw HTML/resource/navigation fixtures;
- persistence, migration, reopen, split, and theme/state tests;
- the shared lexical-fidelity corpus, including repeated/ambiguous blocks;
- large-document, rapid-edit, repeated-lifecycle, compatibility, and resource
  retention evidence;
- real JCEF evidence only for retained JCEF-backed renderer responsibilities;
- explicit manual-only gaps.

Mechanism-specific protocol/JCEF/browser tests remain useful only while the
mechanism they prove is retained or temporary. They are not product evidence by
their mere existence.

The corpus validator only checks that the declared fixtures and their metadata
are internally consistent. Passing it must never be reported as proof that the
current editor/runtime satisfies this product contract.
