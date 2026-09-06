# Leap target architecture comparison

Status: architecture decision evidence for #140 / gate #139

Base audited: `main` at `93488a039845f3c601da7a557fbabf34c19a9928`

## Decision

Select **IntelliJ-native authoritative editing with an in-place, source-neutral Markdown projection layer**.

A native IntelliJ `Editor` edits the authoritative IntelliJ `Document` directly. MarkFlow owns only derived presentation. JCEF is removed from the editing correctness path; if a later derived renderer needs it, JCEF remains below a replaceable renderer boundary and its failure degrades only that preview artifact.

This intentionally makes the already-merged CodeMirror/JCEF editor, host↔web edit protocol, source-native loopback server, browser request policy/CSP, and related Leap implementation candidates for replacement/deletion. Existing-code reuse receives no architecture-selection credit.

The stable architecture boundary is **native `Editor` + the same authoritative `Document`**. Whether MarkFlow augments the platform text editor or uses a MarkFlow-owned shell around a native editor is a lower-level migration choice that must prove public-API stability, editor-state behavior and coexistence with bundled Markdown/editor features.

## Authority constraints

The target must satisfy #78/#52 regardless of implementation:

- IntelliJ `Document` is the sole live Markdown authority;
- no-edit paths are source-stable;
- supported local edits preserve unrelated lexical source;
- ambiguous/unsupported constructs preserve exact source and degrade rather than being reconstructed;
- dirty/save/command/undo behavior follows intentional IntelliJ semantics;
- stale asynchronous work cannot affect newer source/presentation;
- raw HTML/resources/render output/navigation are untrusted;
- optional rich-render failure cannot make source editing unavailable;
- split/editor/project lifecycle ownership is deterministic;
- current classes/libraries/tests are evidence only.

## Candidate A — JCEF source-native editor

Representative shape: CodeMirror/Lezer is the visible editor while IntelliJ `Document` remains host authority.

### Benefits

- source-native rather than semantic rich-document serialization;
- excellent DOM decoration/widget freedom;
- web-native image/table/Mermaid/math presentation;
- current MarkFlow experiments prove the approach can be engineered and tested.

### Costs

- two mutable Markdown representations exist across host/browser runtimes;
- editing therefore requires mutation identity/order/ACK/stale/recovery semantics;
- native save/undo/dirty behavior must be deliberately bridged;
- MarkFlow owns browser IME/composition, clipboard, keymap and accessibility compatibility;
- each editing surface owns JCEF lifecycle/fallback and browser trust boundaries;
- browser origin/network/navigation/resource/CSP controls become correctness-adjacent infrastructure;
- Node/TypeScript/Vite become production-editing dependencies;
- splits/multiple editors require explicit cross-runtime synchronization.

**Verdict: rejected as target editing architecture.** Presentation freedom does not justify putting the most correctness-sensitive operation behind an avoidable runtime boundary.

## Candidate B — native editor plus permanent split preview

### Benefits

- editing, save/undo, IME and source authority stay native;
- preview is one-way, so no web mutation protocol is required;
- complex web rendering is straightforward;
- fallback is simple.

### Cost

A permanently separate source/preview pane does not by itself satisfy MarkFlow's WYSIWYG-first primary interaction model.

**Verdict: rejected as primary surface; retained as possible fallback/secondary-view pattern.**

## Candidate C — native authoritative editor with in-place projection

### Current platform evidence

Current IntelliJ Platform sources expose maintained editor lifecycle and native presentation surfaces:

- `EditorFactoryListener` for editor create/release observation;
- `Editor` caret/selection/scrolling/markup/folding/inlay models;
- `InlayModel` inline, block and after-line-end visual additions explicitly outside `Document`;
- `EditorCustomElementRenderer` host painting for inlays;
- inlay update/lifecycle/batching behavior;
- native text-editor caret/selection/scroll state behavior.

JetBrains' current Markdown implementation itself uses native editor inlays for Markdown table presentation/editing assistance. Current JCEF APIs also expose off-screen rendering hooks, and JetBrains' Mermaid plugin uses an off-screen browser for preview, demonstrating that browser rendering can be isolated from source editing where a renderer truly needs it.

### Benefits

- visible editing modifies the authoritative `Document` directly;
- no host↔web edit protocol or second editable Markdown model;
- native undo/save/dirty/IME/clipboard/multicaret/keymaps/source accessibility by construction;
- split editors naturally share source while retaining independent presentation state;
- ordinary editing/local raster images need no browser, origin, loopback server, CSP or request interceptor;
- parser/renderer choices remain replaceable because neither owns source;
- disabling/failing presentation reveals the same exact source rather than switching authorities.

### Costs

- native inlays/folding/painting are less flexible than arbitrary DOM widgets;
- syntax reveal/hide must be proven carefully around caret/selection/folding;
- complex derived blocks need a separate inert-artifact rendering strategy;
- custom rich inlay accessibility requires explicit evidence/fallback;
- the integration shell must prove coexistence with bundled Markdown/editor behavior.

**Verdict: selected.** It removes the largest correctness boundary while retaining a credible WYSIWYG-first path through maintained native editor APIs.

## Candidate D — semantic rich document plus Markdown reconstruction

Representative shape: Crepe/Milkdown/ProseMirror-like rich state is edited and serialized/patched back to Markdown.

**Verdict: rejected.** Semantic equivalence cannot prove #78's lexically-local fidelity for arbitrary lexical choices and repeated/ambiguous source. Whole-document serialization, AST/LCS pairing or style metadata does not change that structural mismatch.

## Decision matrix

Scale: 5 = strongest architecture fit / lowest structural risk, 1 = weakest. The score does not reward existing MarkFlow implementation maturity.

| Criterion | A: JCEF source-native | B: native + split preview | C: native in-place projection | D: rich reconstruction |
| --- | ---: | ---: | ---: | ---: |
| Authoritative-source simplicity | 3 | 5 | **5** | 1 |
| Lexical fidelity by construction | 4 | 5 | **5** | 1 |
| IntelliJ undo/save/dirty integration | 2 | 5 | **5** | 2 |
| IME/keymap/multicaret/source accessibility | 2 | 5 | **5** | 2 |
| WYSIWYG-first primary UX potential | 5 | 2 | **4** | 5 |
| Rich block rendering freedom | 5 | 5 | 3 | 5 |
| Lifecycle/isolation simplicity | 2 | 4 | **5** | 2 |
| Trust/network attack-surface minimization | 2 | 4 | **5** | 2 |
| Split/multi-project semantics | 2 | 5 | **5** | 2 |
| Platform-native state restoration | 2 | 5 | **5** | 2 |
| Testability without real browser | 3 | 4 | **5** | 3 |
| Dependency/toolchain minimization | 2 | 4 | **5** | 2 |
| **Total** | **36/60** | **53/60** | **57/60** | **29/60** |

The decisive reason is not the total. Candidate C makes source editing, undo/save and native input cease to be cross-runtime synchronization problems.

## Target component model

```text
VirtualFile / VFS / disk
        │ persistence + external-change evidence
        ▼
IntelliJ Document  ─────────── sole mutable Markdown authority
        │
        ├── native IntelliJ Editor(s) ── direct native user edits
        │          │
        │          └── MarkFlowPresentationController (per editor)
        │                    ├── markup / safe folding
        │                    ├── inline/block inlays
        │                    └── caret/selection-aware exact-source reveal
        │
        └── MarkdownProjectionEngine
                  │ immutable snapshot + document generation
                  ▼
             ProjectionPlan
                  ├── native presentation intents
                  ├── LocalImageResolver -> inert raster artifact
                  └── DerivedRenderer -> inert artifact / typed failure
                         ├── Mermaid backend
                         ├── math backend
                         └── sanitized raw-HTML backend
```

Names describe responsibilities, not mandatory classes.

## Dependency and ownership rules

- Projection depends on source snapshots, never mutable Swing/JCEF presentation state.
- Presentation depends on immutable projection output.
- Derived renderers receive bounded source/configuration and cannot mutate `Document`.
- JCEF, if selected for a renderer backend, remains below `DerivedRenderer`; document/editor domain types do not depend on it.
- Local-resource resolution is host-owned and explicit.
- Settings affect presentation/configuration only and never source authority.
- One presentation controller owns its editor listeners/folds/inlays/jobs/artifacts and dies with that editor.
- Project caches, if any, contain bounded immutable artifacts and cannot retain disposed editor/project objects.

Forbidden target dependencies include:

- `Document` correctness -> browser editor;
- correctness-critical host↔web mutation/ACK/recovery;
- browser readiness -> ability to edit;
- loopback HTTP -> ordinary local-image access;
- browser pool/shared realm -> editor ownership;
- rich-model serialization/reconstruction -> source correctness;
- persisted browser/capability identity -> document state.

## Source/edit/undo/save model

1. User edits change IntelliJ `Document` through normal IntelliJ command/write semantics.
2. That `Document` change is the edit; there is no second-runtime proposal step.
3. Projection reads a source snapshot plus its document generation/modification identity.
4. A completed plan applies only if that identity and relevant configuration still match current state.
5. Stale projection/render output is discarded.
6. Applying presentation does not enter source undo history.
7. Save/dirty/persistence remain IntelliJ behavior.
8. Multiple editors may have distinct caret/selection/scroll/presentation state while observing the same `Document`.

A custom source revision/epoch exists only if later evidence proves IntelliJ's document identity plus task generation cannot express a required independent fact.

## WYSIWYG-first presentation contract

The target is not merely a text editor with nicer colors.

- inactive headings/inline constructs receive rich typography/presentation;
- syntax markers may be visually reduced only through source-neutral mechanisms with deterministic exact-source reveal;
- caret/selection entering a construct reveals the Markdown required to edit it;
- list/quote/fence/table presentation consumes parser-proven ranges only;
- images render as inlays while Markdown remains source;
- Mermaid/math/sanitized raw HTML may render as block inlays from inert artifacts;
- malformed/unsupported/renderer-failed constructs remain exact visible source with a degraded indication.

Presentation never rewrites source merely to look richer.

## Derived renderer boundary

A derived renderer receives only a renderer type, bounded source fragment, non-secret theme/configuration and explicit approved resource inputs. It returns an inert host-presentable artifact or typed/redacted failure.

Prefer raster or host-sanitized vector artifacts that cannot execute script, navigate, fetch resources or mutate source. Renderer backends are replaceable. A JVM/native backend is valid; an isolated JCEF off-screen backend is also possible if later evidence selects it. JCEF is never required for editing.

## Image requirements

### Existing Markdown image references

Document-relative local images are already a #78-supported product capability. The target resolves them on the host, enforces path/media/size policy, and paints inert image presentation. A missing/moved/unsupported image leaves exact source visible/recoverable.

### Historical image insertion/import report

#99 preserves the former public report that image-file insertion/display did not work. The architecture must carry that requirement without pretending the already-built loopback image mechanism solves it.

#78 has not yet fully specified the exact image-import gesture set or filesystem side effects. Therefore #140 does **not** silently promote every chooser/drop/clipboard behavior into product authority. It establishes the safe target shape if import is ratified:

1. explicit user-supported gesture supplies a file/image;
2. host/VFS-aware logic validates it and chooses an explicit relative destination policy;
3. file creation/copy occurs through the host;
4. one normal IntelliJ command inserts the Markdown image reference;
5. source undo and physical-file cleanup semantics are specified explicitly.

#99/#78 reconciliation under #141/#139 must decide the supported import capability and gestures before product-complete claims.

## Trust consequences

Native editing removes browser trust surfaces from ordinary editing:

- no content-originated browser navigation in the editor;
- no editor-origin remote fetch;
- no source-native loopback page/assets;
- no editor CSP/request interceptor;
- no browser-held local filesystem capability for ordinary images.

Raw HTML remains source-preserved untrusted input. Any sanitized preview lives behind the derived-render boundary and only inert output reaches editor presentation. Remote resources remain denied by default unless #78/#82 explicitly approves a host-mediated privacy policy.

## Performance model

- parse/project snapshots off EDT where maintained APIs permit;
- apply only current-generation presentation on EDT;
- use incremental/changed/visible-range work only after evidence selects the mechanism;
- do not rebuild the full visual document on ordinary caret movement;
- bound render dimensions/bytes;
- add caches/reuse only after measured benefit;
- no browser pooling/prewarm in the baseline.

## Evidence architecture

| Invariant | Primary evidence |
| --- | --- |
| no-edit source stability | pure + IntelliJ document integration |
| source-local edits | command/editor tests + fidelity corpus |
| undo/redo/dirty/save | IntelliJ integration |
| caret/selection reveal | native editor presentation tests |
| stale projection rejection | deterministic generation tests |
| split editors | IntelliJ multi-editor integration |
| local image containment | pure path/media policy + host integration |
| ratified image import | VFS/command/failure/undo evidence |
| rich renderer failure/trust | renderer contract + hostile fixtures |
| optional JCEF renderer | real JCEF only if selected |
| disposal/resource retention | repeated lifecycle tests |
| compatibility | Plugin Verifier + maintained IDE matrix + targeted manual evidence |

## Directional migration map

#141 performs exact repository inventory. #140 establishes only direction.

### RETAIN as invariant/evidence

- #78 product/fidelity contract and corpus;
- IntelliJ `Document` authority semantics;
- stale-work/failure lessons from #80;
- parser-proven source-range concepts independent of CodeMirror;
- hostile resource/path evidence that remains applicable;
- real-JCEF evidence lessons for any retained renderer backend;
- historical public requirements in #99.

### REPLACE

- browser/custom editing surface -> native editor integration shell + presentation controller;
- CodeMirror/Lezer presentation -> host projection/presentation;
- browser local-image capability -> host image resolver/inlay;
- web external-navigation bridge -> host editor action;
- browser-owned presentation state -> native editor/FileEditor state where needed.

### DELETE when no consumer remains

- host↔web edit protocol/attachment/ACK/recovery;
- JCEF editing transport/query/readiness machinery;
- source-native loopback page/assets/request/CSP controls;
- CodeMirror editor/bootstrap/sync/live-preview code;
- Crepe/Milkdown rich-editor/reconstruction path;
- browser editing pools/prewarm/retry/debounce;
- Node/TypeScript/Vite production-editing pieces with no retained renderer consumer;
- tests that assert deleted mechanism shape rather than retained invariants.

### TEMPORARY

Only migration coexistence necessary to keep `main` usable, with an explicit owner and deletion criterion. A permanent dual-editor architecture is forbidden.

## Open implementation choices below the architecture boundary

- augment platform text editor vs MarkFlow-owned native-editor shell;
- parser/library and incremental parsing strategy;
- per-construct reveal technique (folding, attributes, inlays);
- Mermaid/KaTeX renderer backend and raster/vector host painting;
- image destination naming/collision policy;
- final image-import gesture set after product reconciliation;
- measured cache policy.

These choices may change without reopening ADR 0001 as long as they keep native `Editor` + authoritative `Document`, source-neutral projection, and isolated derived rendering.

## Evidence references reviewed for #140

- IntelliJ Platform current `EditorFactoryListener`, `InlayModel`, and `EditorCustomElementRenderer` sources.
- Current JetBrains Markdown `MarkdownTableInlayProvider` using native editor inlays.
- Current IntelliJ text-editor state implementation for caret/selection/scroll semantics.
- Current `JBCefBrowserBuilder` / `JBCefOSRHandlerFactory` off-screen hooks.
- Current JetBrains Mermaid preview implementation using off-screen JCEF.
- IntelliJ IDEA 2026.2 Markdown documentation for native Markdown editing/table inlays/live preview.
- Maintained Mermaid and KaTeX rendering APIs as backend evidence only.

## #140 conclusion

Architecture-selection PASS is justified for **native authoritative editing + in-place projection + isolated derived renderers**.

The reason is elimination of an avoidable correctness authority and browser trust boundary from Markdown editing, not reuse or rewrite cost. #141 must now classify current `main` against this target and rebuild the migration backlog. #139 remains open until that inventory, ADR acceptance and Track reconciliation are complete.
