# ADR 0001: Use IntelliJ-native authoritative editing with source-neutral projection

- Status: Accepted
- Date: 2026-09-07
- Accepted by: PR #142 (effective on merge)
- Owners: @luceat-lux-vestra
- Related issues/PRs: #52, #78, #79, #80, #81, #82, #83, #84, #99, #139, #140, #141, PR #142

## Context

Leap exists to satisfy MarkFlow's product and source-fidelity contract without preserving an implementation merely because it exists.

The repository currently contains multiple generations of editor architecture:

- historical Crepe/Milkdown rich-document editing with source reconstruction/preservation machinery;
- already-merged Leap CodeMirror/JCEF source-native editing with a host↔web mutation protocol, per-surface browser runtime, local-resource capability serving, navigation handling and browser-request containment;
- IntelliJ `Document`/VFS/editor infrastructure that remains the product's source and persistence environment.

The source-native browser work materially improved fidelity over the historical rich-model architecture, but it still places the visible editable Markdown document in a second runtime. That makes MarkFlow prove synchronization, acknowledgement, stale rejection, recovery, IME/input compatibility, browser lifecycle and browser trust around ordinary editing.

The strengthened #52/#139 policy requires asking whether that boundary should exist at all.

Current IntelliJ Platform evidence shows maintained native editor lifecycle and presentation surfaces, including editor create/release notifications, markup/folding, and inline/block inlays whose visual content is not part of the `Document`. JetBrains' own current Markdown implementation uses native editor inlays for Markdown table presentation. A native in-place projection is therefore a credible target rather than a theoretical fallback.

The upstream IntelliJ Platform evidence reviewed for this decision was pinned to `JetBrains/intellij-community@88410941a24ccd990345d917bf664382da4d255d` (2026-09-06), including `EditorFactoryListener`, `InlayModel`, `EditorCustomElementRenderer`, `FoldingModel`, the Markdown table-inlay implementation, and JCEF off-screen rendering APIs. This pin records the architecture evidence snapshot; supported MarkFlow releases still require their own compatibility matrix and Plugin Verifier/runtime evidence.

## Decision

MarkFlow's target architecture is **IntelliJ-native authoritative editing with an in-place, source-neutral Markdown projection layer**.

### Source and editing authority

- The IntelliJ `Document` is the sole mutable live Markdown authority.
- A native IntelliJ `Editor` edits that same `Document` directly.
- MarkFlow does not maintain a second editable Markdown document in JCEF, JavaScript, a rich-text model, a persistence cache, or a session object.
- User text editing, paste, supported rich-edit actions and generated Markdown insertion use IntelliJ command/write/document semantics.
- Native editor/platform behavior remains responsible for dirty/save integration, undo/redo, caret/selection, IME, multicaret, keymaps and accessibility of source editing.

### Native editor integration shell

The architecture fixes **native `Editor` + authoritative `Document`**, not a particular `FileEditor` wrapper.

Prefer augmenting the normal platform text editor when a proof slice shows that MarkFlow presentation can coexist cleanly with bundled Markdown/editor behavior. A MarkFlow-owned `FileEditor` shell is acceptable only if it still contains a native editor bound directly to the same `Document` and independently proves equivalent editor-state/input/save/undo behavior using maintained APIs.

The shell decision must not:

- introduce a second editable source model;
- require JCEF/browser readiness for editing;
- depend on unstable/internal platform APIs without an explicit compatibility decision;
- silently duplicate/conflict with bundled Markdown inlays/actions;
- remove the user's exact-source escape hatch.

### Presentation authority

MarkFlow may own only derived presentation state:

- parsing/classification of immutable source snapshots;
- source-range presentation plans;
- syntax styling/highlighting;
- source-neutral folding/marker presentation where safe;
- inline/block inlays;
- caret/selection-aware exact-source reveal;
- bounded immutable derived-render artifacts;
- per-editor presentation state.

Presentation never becomes source authority and never mutates `Document` merely because it is created, refreshed, disabled, failed or disposed.

### Projection model

A Markdown projection responsibility consumes an immutable source snapshot plus the corresponding document generation/modification identity and returns an immutable projection plan.

A per-editor presentation controller applies a plan only when it still represents the current source/configuration. Older asynchronous work is discarded. Do not retain or invent custom revision/epoch state unless a later proof shows the maintained IntelliJ document identity plus task-generation identity cannot represent a required ordering fact.

Caret/selection activity reveals the exact Markdown source needed to edit an active construct. Unsupported, ambiguous or unsafe presentation degrades to exact visible source rather than guessed structure.

### Derived renderers

Mermaid, math, sanitized raw-HTML preview and similar complex presentation are behind a replaceable derived-renderer responsibility.

A renderer:

- receives only a bounded source fragment, renderer type and non-secret render configuration;
- has no authority to edit the document;
- has no ambient navigation/filesystem/network capability;
- returns an inert host-presentable artifact or a typed/redacted failure;
- may be unavailable without making Markdown editing unavailable.

The target does **not** require JCEF for editing. A derived-render backend may use a maintained native/JVM library or an isolated JCEF off-screen worker if later evidence selects it. If JCEF is used, JCEF types stay below the renderer boundary and cannot leak into document/editing authority.

### Local resources

Document-relative local image/resource handling is host-owned.

For ordinary supported local raster images, the host resolves paths against explicit document/project context, enforces path/media/size/trust policy, decodes the resource, and presents an inert artifact. Loopback HTTP, browser `file:` access and browser-held filesystem capability tokens are not baseline architecture.

### Historical image insertion/import requirement

#99 preserves a historical public report that image file insertion/display did not work. #78 already makes document-relative local-resource rendering a product capability; it does **not yet fully specify every image-import gesture or filesystem side effect**.

The architecture therefore guarantees that any accepted image-import workflow is host-owned and does not require a browser bridge:

1. an explicitly supported user gesture supplies an image/file;
2. host logic validates media and chooses an explicit project/document-relative destination policy;
3. file creation/copy uses IntelliJ/VFS-aware host behavior;
4. one IntelliJ command inserts the resulting Markdown image reference into `Document`;
5. source undo and physical-file cleanup semantics are decided explicitly rather than guessed.

#99/#78 reconciliation must ratify the exact supported import capability and gesture set before implementation is claimed as product-complete.

### External navigation

Approved Markdown link activation is handled by a host-owned navigation action with explicit scheme policy. Document content never navigates/replaces an editor browser realm because the target has no browser editor realm.

## Dependency direction

Allowed direction:

```text
IntelliJ Document
    ↑ direct native edits
native IntelliJ Editor
    ← MarkFlow Presentation Controller
          ← immutable Projection Plan
              ← Markdown Projection Engine

Presentation Controller
    ← inert Local Resource Artifacts
    ← inert Derived Render Artifacts
          ← replaceable renderer backend (optional JCEF below this line)
```

Forbidden direction:

- document/editing domain -> JCEF/browser;
- document/editing domain -> JavaScript editor;
- projection engine -> mutable editor presentation state;
- derived renderer -> document mutation;
- security capability/token -> persisted editor/source state;
- optional renderer availability -> editor availability.

## Alternatives considered

### JCEF source-native CodeMirror editor

Rejected as the target editing architecture.

It preserves Markdown source better than a rich semantic model, but ordinary editing still crosses a host↔web correctness boundary. MarkFlow would continue to own mutation protocol, browser lifecycle, browser trust, browser input/IME compatibility and recovery that the native editor can avoid.

Current source-native implementation remains migration evidence only.

### Native editor plus permanent split preview

Rejected as the primary product surface because it does not by itself satisfy WYSIWYG-first interaction. The one-way derived-render principle is retained as a fallback/secondary-view option.

### Rich document model plus Markdown reconstruction

Rejected because semantic equivalence is insufficient for #78's lexically-local contract. Whole-document serialization or heuristic patch/reconstruction cannot prove preservation of ambiguous/repeated lexical Markdown outside an edit.

## Consequences

### Positive

- Source authority is true by construction rather than synchronized across runtimes.
- Native dirty/save/undo/IME/keymap/multicaret/source-accessibility behavior is retained.
- Split editors naturally share the same source while retaining independent presentation state.
- Ordinary editing no longer requires JCEF, page readiness, JS query ownership, loopback HTTP, CSP or browser request containment.
- Local image rendering can avoid browser filesystem/network authority entirely.
- Optional rich renderers can fail independently and degrade to source.
- The Node/TypeScript/Vite production editing toolchain may become removable if no retained renderer backend needs it.
- Tests can target source/presentation invariants without requiring real JCEF for ordinary editing.

### Negative / trade-offs

- Native editor inlays/folding/painting are less flexible than arbitrary DOM widgets.
- MarkFlow must implement and prove a high-quality native live-preview presentation layer.
- Rich preview accessibility for custom inlay artifacts needs separate evidence; native source-editing accessibility alone does not prove derived-artifact accessibility.
- Complex Mermaid/math/raw-HTML rendering still needs a renderer backend and an inert-artifact presentation strategy.
- The editor integration shell must prove coexistence with platform/bundled Markdown behavior.
- Already-merged Leap implementation will intentionally become dead code where #141 classifies it as superseded.
- Temporary migration coexistence may be necessary, but it must be short-lived and explicitly owned.

## Compatibility and migration

#141 owns the exact repository inventory and task graph. Directionally:

- replace browser/custom editing with a native IntelliJ editor integration shell and MarkFlow presentation controller;
- replace CodeMirror/Lezer presentation implementation with host projection/presentation;
- delete correctness-critical host↔web editing protocol/query/readiness/recovery after no production consumer remains;
- delete source-native loopback page/assets/request/CSP machinery after no retained renderer consumer needs it;
- replace browser local-image capability with host path/media resolver and native image presentation;
- replace browser external-navigation bridge with host editor actions;
- delete Crepe/Milkdown and source-reconstruction architecture;
- revalidate Node/TypeScript/Vite, JCEF and Markdown-plugin dependencies after renderer/integration-shell decisions;
- preserve product/fidelity corpus and invariant-relevant hostile/lifecycle evidence while rewriting mechanism-specific tests.

Migration proceeds through coherent PR-sized slices. Two permanent editor architectures are not a target.

## Security and lifecycle implications

The architecture removes browser trust from ordinary editing rather than adding more controls around it.

Remaining trust boundaries are explicit:

- Markdown parsing/projection treats source as untrusted data;
- local resource resolution is host-owned and bounded to approved scope;
- raw HTML is source-preserved and only separately rendered through a sanitizing/isolating renderer;
- remote resource loading remains denied by default unless explicitly approved later;
- external navigation is host-owned and scheme-bounded;
- optional renderer backends cannot navigate, fetch or mutate by ambient authority;
- every presentation controller and renderer job has one owner and deterministic cancellation/disposal;
- stale async projection/render results are discarded using source/config generation identity.

If a JCEF renderer backend is selected, it needs independent #82 containment evidence. That evidence proves the renderer backend, not the editor architecture.

## Required evidence

Before production migration is complete, prove at minimum:

- one native integration shell can attach/detach MarkFlow presentation using maintained APIs without bundled Markdown/editor conflicts;
- the selected shell retains expected editor-state/input/save/undo semantics;
- no-edit projection/reveal/refresh/recreate paths leave `Document` unchanged;
- supported native edits participate in expected IntelliJ undo/redo/dirty/save behavior;
- caret/selection entering projected syntax reveals exact source without offset corruption;
- multi-editor/split views share source and isolate presentation state;
- stale projection/render tasks cannot apply to newer document/config state;
- local images render under bounded host path/media/size policy with traversal/symlink hostile cases;
- any accepted image-import workflow has deterministic copy/path/source-insertion/failure/undo semantics;
- Mermaid/math/raw-HTML renderer failures degrade to exact source and cannot mutate/navigate/fetch outside policy;
- custom rich inlay artifacts have an explicit accessibility/fallback disposition;
- repeated editor/project create/dispose does not retain controllers/jobs/artifacts;
- supported IntelliJ versions pass Plugin Verifier and runtime evidence;
- WYSIWYG-first interaction is validated on the #78 representative capability corpus rather than inferred from API availability alone.

## Supersession

This ADR supersedes architecture-authority assumptions embedded in earlier Leap tasks that treated:

- CodeMirror 6 as the approved target editor;
- one JCEF browser/realm per MarkFlow editor as the target baseline;
- a correctness-critical host↔web protocol as inherently required;
- loopback HTTP/resource capability/CSP/browser-request controls as permanent target trust mechanisms;
- Milkdown/Crepe as a replaceable web-editor adapter inside an otherwise web-editor-based target.

Those implementations remain historical evidence until #141 classifies and migration tasks remove or explicitly retain individual pieces.
