# ADR 0003: Use a MarkFlow-owned native FileEditor over the authoritative IntelliJ Document

- Status: Accepted
- Date: 2026-11-14
- Decision evidence: #545, #556, PR #558
- Formalization / migration planning: #559
- Owners: @luceat-lux-vestra
- Related ADRs: ADR 0001, ADR 0002

## Context

ADR 0001 established the durable architecture boundary that still applies:

- IntelliJ `Document` is the sole mutable Markdown authority;
- a native IntelliJ `Editor` edits that `Document` directly;
- MarkFlow owns derived presentation, never a second editable Markdown model;
- JCEF/browser availability cannot decide whether source editing works.

Issue #143 then selected a lower-level shell implementation: augment the normal IntelliJ platform text editor through `EditorFactoryListener`. At that time a MarkFlow-owned native `FileEditor` added provider/state/focus/lifecycle responsibility without an independent product requirement, so the simpler platform-augmentation shell won. #153 later cut production over to that shell.

Fresh pre-1.0 owner validation recorded by Leap 2 #545 changed the premise. The bundled Markdown editor and MarkFlow presentation now need to be independently selectable surfaces for the same file, and the current augmentation shell has accumulated interaction with bundled Markdown folds/inlays/layout that materially limits product fidelity.

Issue #556 therefore re-opened only the shell decision. PR #558 proved Candidate B in real IntelliJ IDEA 2026.2.3 without changing source authority:

```text
Markdown VirtualFile
        |
        v
FileDocumentManager Document     <- sole mutable source authority
        |                  |
        v                  v
bundled IntelliJ          MarkFlow-owned FileEditor
Markdown editor           + native IntelliJ Editor
                           + MarkFlow presentation only
                           + optional derived renderer
```

The proof passed with JCEF enabled and with the JCEF plugin absent.

## Decision

MarkFlow's target editor shell is a **distinct MarkFlow-owned native `FileEditor` containing a native IntelliJ `Editor` over the exact same authoritative `FileDocumentManager Document` used by the bundled IntelliJ Markdown editor**.

This decision supersedes only the lower-level shell choice made by #143. It does not supersede ADR 0001 or ADR 0002.

### Source and editing authority

- `FileDocumentManager.getDocument(file)` remains the sole mutable Markdown authority.
- The MarkFlow editor never owns a second source model, browser document, source session, revision/ACK protocol, or whole-document synchronization path.
- Native IntelliJ command/write/undo/save, caret/selection, IME, multicaret, keymap and clipboard semantics remain authoritative.
- Dirty/save/reload/external-file behavior remains platform-owned.

### Surface ownership

The same Markdown file exposes two independently selectable editor surfaces:

1. the bundled/default IntelliJ Markdown experience; and
2. the MarkFlow native WYSIWYG-first experience.

The MarkFlow provider uses maintained `FileEditorProvider` / `FileEditor` / `TextEditor` contracts only.

The baseline provider policy proven by #556 is `PLACE_BEFORE_DEFAULT_EDITOR`:

- MarkFlow may be the first-open preferred surface;
- the bundled IntelliJ surface remains available;
- platform provider-selection persistence may remember the user's previous selection.

Do not add private/internal API hooks to force a stronger default-selection policy. A separate explicit product setting for global IntelliJ-vs-MarkFlow default selection requires its own evidence if later needed.

### Presentation ownership

MarkFlow presentation belongs only to MarkFlow-owned editor instances.

- `NativePresentationController` and its ordinary Markdown, derived, raw-HTML and host-resource presentation responsibilities remain reusable.
- Controller create/release follows the MarkFlow `FileEditor` lifecycle.
- The bundled IntelliJ editor receives zero MarkFlow presentation ownership after cutover.
- Settings-driven refresh targets the live MarkFlow-owned presentation owners rather than every eligible platform editor.
- Presentation remains source-neutral and disposable.

### State and lifecycle

The MarkFlow-owned editor must implement the public `FileEditorState` contract needed for caret/selection/scroll restoration without copying private platform implementation.

Multiple MarkFlow editor instances may share one `Document` while retaining independent:

- native `Editor` instances;
- caret/selection/scroll state;
- presentation controllers;
- lifecycle/disposal.

Closing or disposing a MarkFlow editor must release its controller and native editor without affecting the bundled editor or another MarkFlow editor over the same `Document`.

### Derived renderer boundary

ADR 0002 remains unchanged.

- Ordinary Markdown presentation does not require JCEF.
- Mermaid and KaTeX continue through the retained derived-renderer boundary.
- JCEF is optional renderer infrastructure only.
- JCEF absence/failure degrades derived presentation locally while source editing remains available.
- No browser/JCEF source authority is reintroduced.

## Alternatives

### Candidate A — augment the bundled/platform editor

Previously selected by #143 and currently in production at the start of Leap 2.

It remains valid historical/control evidence and has a smaller ownership surface. It is no longer the target because the new product requirement needs an independently selectable MarkFlow surface and clean presentation ownership separate from bundled Markdown behavior.

### Candidate B — MarkFlow-owned native FileEditor

Selected.

#556 proved:

- provider coexistence;
- same `Document` identity;
- bidirectional native editing without custom synchronization;
- public provider selection and remembered alternative selection;
- native keymap/clipboard/multicaret behavior;
- Korean IME, undo/redo, save/reopen;
- `FileEditorState` round-trip;
- split/editor lifecycle isolation;
- presentation ownership isolation;
- lazy derived rendering;
- no-JCEF source editing;
- 1/5/20-file owned-editor/controller cleanup.

### Candidate C — browser/JCEF full editor

Rejected.

A visually flexible browser surface does not justify restoring browser-owned source state, source synchronization, duplicated undo/caret/input ownership, or browser readiness as an editing dependency.

## Consequences

### Positive

- MarkFlow owns the presentation surface it must make WYSIWYG-quality.
- Bundled IntelliJ Markdown remains available for direct comparison and user choice.
- Source correctness remains native by construction.
- Existing projection/rendering work is reusable instead of rewritten.
- Presentation interference with the bundled editor becomes structurally avoidable.
- Rollback does not require source migration because both shells use the same `Document`.

### Negative / trade-offs

- MarkFlow again owns a `FileEditorProvider`, `FileEditorState`, focus/lifecycle boundary and compatibility proof surface.
- Each file may have an additional native `Editor` instance when both surfaces are open.
- Provider ordering/selection behavior must remain compatible with maintained IntelliJ APIs.
- E2E/probe code that assumes presentation is attached to the normal platform editor must move to the owned surface.
- Resource measurements must be treated as evidence, not inferred from architecture.

## Compatibility and migration

Production does not switch shells merely because this ADR is accepted.

The cutover follows `leap2-owned-shell-cutover-inventory.md` and must preserve a rollback boundary until replacement evidence passes.

The migration order is:

1. prepare production-owned shell/lifecycle infrastructure without changing the normal opening path;
2. activate the production provider and move presentation ownership atomically so the bundled editor is untouched;
3. prove settings/editing/renderer/E2E behavior on the owned path;
4. delete the old generic platform-editor presentation attachment and #556 proof plumbing only after replacement evidence is green.

A rollback from the owned-shell cutover means reverting to the existing native platform-augmentation shell. It never means resurrecting the deleted browser editor.

## Security and lifecycle implications

This decision does not widen the renderer trust boundary.

- Source remains host/native and browser-free.
- Optional JCEF receives bounded derived fragments only.
- Local resources/navigation remain host-owned.
- Raw HTML remains source-preserved and fail-closed.
- Every owned editor/controller has deterministic create/dispose ownership.
- Stale parse/render results still require exact source/config identity.

## Required production-cutover evidence

Before the owned shell is called production-complete, exact-candidate evidence must prove:

- bundled IntelliJ + MarkFlow provider coexistence;
- one authoritative `Document` identity across both surfaces;
- MarkFlow-first opening policy and maintained public selection behavior;
- remembered user selection behavior without private APIs;
- typing, Korean IME, undo/redo, dirty/save/reopen and external-source stability;
- keymaps, clipboard and multicaret;
- caret/selection/scroll `FileEditorState` restoration;
- split/create/dispose/recreate lifecycle isolation;
- zero MarkFlow presentation ownership on the bundled editor;
- settings refresh reaches all and only owned MarkFlow controllers;
- ordinary Markdown creates no derived-render request;
- Mermaid/KaTeX lazy rendering succeeds when JCEF is available;
- JCEF-plugin-absent source editing remains usable and derived output degrades locally;
- 1/5/20-file lifecycle/resource evidence returns owned editor/controller counters to zero;
- packaged real-IDE, Plugin Verifier, build/test/static-analysis and merge-gate evidence all bind to the exact candidate HEAD.

UNKNOWN / UNVERIFIED / INSUFFICIENT EVIDENCE = FAIL.

## Supersession

This ADR:

- **retains** ADR 0001 source-authority/native-projection principles;
- **retains** ADR 0002 renderer continuity;
- **supersedes for future production work** the lower-level `PLATFORM_TEXT_EDITOR_AUGMENTATION` shell decision recorded by #143 and `native-editor-shell-selection.md`;
- does not rewrite #143/#153 historical evidence as if those decisions were wrong when made.
