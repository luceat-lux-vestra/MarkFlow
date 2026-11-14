# Leap 2 owned-shell production cutover inventory

Status: #559 planning authority after #556 accepted ADR 0003's MarkFlow-owned native `FileEditor` shell

- Parent: #545
- Shell proof / decision: #556
- Proof PR: #558
- Architecture: ADR 0001, ADR 0002, ADR 0003
- Historical shell provenance: #143 / `native-editor-shell-selection.md`
- Historical Leap inventory: `leap-migration-inventory.md`
- Baseline after #558: `main@f79e975e6fb5173c1cd0c023a6ab3f93eed15a2e`

This inventory is responsibility-level. It does not authorize a broad rewrite and it does not make #556 proof plumbing production architecture.

## Target end state

```text
Markdown VirtualFile
        |
        v
FileDocumentManager Document
        |                         |
        |                         +----------------------+
        v                                                v
bundled IntelliJ Markdown                    MarkFlow FileEditorProvider
FileEditor / Editor                           |
(no MarkFlow presentation)                    v
                                        MarkFlow FileEditor
                                        + native Editor
                                        + NativePresentationController
                                        + host-owned resources/navigation
                                        + optional lazy derived renderer
                                             - Mermaid
                                             - KaTeX
```

Target invariants:

1. one mutable `Document`;
2. two independently selectable native surfaces;
3. only the MarkFlow-owned surface gets MarkFlow presentation;
4. native IntelliJ edit/save/undo/input semantics remain authoritative;
5. JCEF remains optional derived-render infrastructure;
6. no browser/source synchronization architecture returns;
7. steady state has one MarkFlow production presentation-attachment mechanism.

## Classification

### RETAIN

| Responsibility / mechanism | Target disposition |
| --- | --- |
| `FileDocumentManager Document` authority | Sole mutable source authority for both surfaces |
| `MarkFlowFileSupport` | Supported-file recognition for the MarkFlow provider |
| `NativePresentationController` | Per-MarkFlow-editor presentation coordinator |
| ordinary Markdown presentation controllers/planners | Reused on the owned native editor |
| `NativeDerivedPresentationController` | Reused; renderer remains lazy |
| `NativeRawHtmlPresentationController` | Reused with existing source-preserved/fail-closed contract |
| `NativeHostResourcePresentationController` | Reused; resources/navigation stay host-owned |
| exact source/config identity + stale-result rejection | Unchanged correctness invariant |
| `DerivedRendererRuntimeFactory` / `DerivedRendererRuntimeProvider` | Optional renderer boundary |
| `markflow-jcef.xml` optional JCEF packaging boundary | Renderer-only optional plugin boundary |
| Mermaid 12.3.0 / KaTeX ^0.19.0 engines | One retained engine per capability under ADR 0002 |
| native command/write/undo/save semantics | Editing authority |
| proven native paste/keymap/clipboard/multicaret behavior | Reused where it is editor-instance agnostic |
| `MarkFlowPresentationSettingsSink` contract | Settings invalidation responsibility remains |
| product/fidelity corpus and packaged real-IDE evidence | Acceptance authority independent of shell |

### MOVE

| Responsibility / mechanism | From | To |
| --- | --- | --- |
| presentation attach/release ownership | generic eligible `EditorFactoryListener` events | MarkFlow-owned `FileEditor` create/dispose lifecycle |
| live presentation-owner enumeration | scanning/deriving from globally attached editors | explicit owned-editor/controller registry |
| settings refresh | refresh globally attached MarkFlow controllers | refresh owned MarkFlow controllers only |
| source-state restoration proof | platform shell assumptions | MarkFlow-owned `FileEditorState` implementation using public contracts |
| E2E bridges that locate the production controller | normal platform editor | selected MarkFlow-owned editor |
| real-IDE shell/projection probes | platform-augmentation production path | owned-shell production path |
| lifecycle/resource accounting | generic platform editors | owned editor + owned controller counts |

### REPLACE

| Current mechanism / assumption | Replacement |
| --- | --- |
| `NativeMarkFlowEditorFactoryListener` as production attachment owner | production MarkFlow `FileEditorProvider` / `FileEditor` lifecycle |
| plugin.xml `editorFactoryListener` production registration | production `fileEditorProvider` registration |
| #143 assumption “no target MarkFlow FileEditorProvider” | ADR 0003 owned-shell contract |
| production-cutover tests requiring no MarkFlow provider | tests requiring one maintained native provider and zero bundled-editor MarkFlow ownership |
| hardening assertions that reject any MarkFlow native provider | assertions that reject browser/source authority while allowing the ADR 0003 native provider |
| #556 diagnostic provider identity/property gating | normal production provider identity and acceptance |
| proof-only editor-state implementation if insufficient for production | minimal maintained public-`FileEditorState` production implementation |

### DELETE after replacement evidence passes

| Mechanism | Deletion criterion |
| --- | --- |
| generic MarkFlow presentation attachment to bundled/platform Markdown editors | exact production owned-shell evidence proves zero bundled-editor ownership |
| `NativeMarkFlowEditorFactoryListener` production registration | owned provider lifecycle is exact-HEAD green |
| obsolete “platform augmentation is the target” assertions | ADR 0003/current architecture checks replace them |
| proof-only startup activity | production provider/probes no longer use it |
| proof-only enable/output properties that exist only for #556 shell selection | production-owned evidence has equivalent dedicated test entrypoints |
| proof-only counters/helpers with no ongoing diagnostic value | production lifecycle accounting/evidence replaces them |
| mechanism-specific #556 tests that only prove dormant-provider gating | production provider tests cover the same invariant |

Do not delete reusable #556 real-IDE evidence cases merely because the proof task closed. Migrate invariant-level tests first; delete only mechanism-specific scaffolding.

### TEMPORARY

| Temporary item | Owner | Deletion criterion |
| --- | --- | --- |
| #556 dormant provider/activity/property scaffolding on main | Leap 2 shell migration | production provider and migrated exact-HEAD owned-shell evidence pass |
| old generic attachment code kept unregistered after cutover, if needed for one cleanup PR | cutover cleanup task only | post-cutover exact-HEAD + post-main evidence passes; then delete |
| compatibility assertions supporting both old and new native shells during one bounded migration slice | owning cutover task | cannot survive the cleanup task |
| dual E2E lookup support during bridge migration | E2E migration task | all production bridges resolve the owned editor |

No TEMPORARY item is a runtime fallback architecture. Rollback is source-control rollback to the previous native shell, not permanent dual-path selection.

## Ordered production cutover plan

### Slice 1 — production-ready owned-shell infrastructure, no opening-path change

Goal: prepare the real implementation while current production remains platform augmentation.

Bounded changes:

- promote the #556 proof editor/provider concepts into production-named types;
- introduce explicit owned-editor/controller registration needed for lifecycle and settings refresh;
- make `NativeMarkFlowProductionLifecycle` attach/release callable from the owned `FileEditor` without depending on global editor creation;
- retain the current production listener registration for this slice;
- keep the production provider dormant/unregistered;
- add unit/real-IDE evidence for create/dispose/state/registry behavior.

STOP: no user-visible provider activation and no bundled-editor ownership change.

### Slice 2 — atomic provider activation and presentation ownership cutover

Goal: make the MarkFlow-owned editor the real production surface without a dual presentation owner.

Bounded changes:

- register the production `FileEditorProvider`;
- use the proven maintained provider policy baseline (`PLACE_BEFORE_DEFAULT_EDITOR`);
- activate provider acceptance for supported Markdown files;
- attach MarkFlow presentation from the owned `FileEditor`;
- remove/disable plugin.xml registration of generic production `NativeMarkFlowEditorFactoryListener`;
- require the bundled IntelliJ editor to have zero MarkFlow controller ownership;
- preserve the listener implementation temporarily only if rollback review needs one cleanup boundary; it must be unreachable in normal production.

Required replacement evidence in the same exact candidate:

- provider coexistence/selection;
- same `Document`;
- native edit/input/state/save behavior;
- settings refresh;
- JCEF-enabled and plugin-absent paths;
- packaged real-IDE acceptance.

STOP: do not mix Markdown/KaTeX/Mermaid fidelity fixes into this slice.

### Slice 3 — migrate production evidence and settings/controller lookup assumptions

Goal: make tests and runtime diagnostics observe the selected production shell rather than historical platform augmentation.

Bounded changes:

- update E2E bridges to select/resolve the MarkFlow provider/editor explicitly;
- update native projection/editing/lifecycle probes where their production-path assumption changed;
- keep invariant-level platform-editor control probes only where they still provide comparative value;
- verify settings invalidation reaches every owned controller and never the bundled editor;
- preserve exact-source, stale-result and renderer-boundary assertions.

STOP: no product-fidelity repair.

### Slice 4 — delete superseded augmentation/proof scaffolding

Precondition: Slice 2/3 exact-HEAD and post-main replacement evidence are green.

Delete/replace:

- `NativeMarkFlowEditorFactoryListener` if no retained diagnostic consumer exists;
- #556 proof startup activity and enable properties;
- obsolete dormant-provider checks;
- stale hardening/architecture assertions;
- temporary dual lookup/test compatibility;
- unused proof diagnostics.

Run repository search/hardening to prove the old production attachment path is unreachable/absent.

### Slice 5 — post-cutover product verification resumes

This is outside the shell cutover implementation itself.

Resume #545 product verification in the established order:

`Markdown core -> KaTeX -> Mermaid -> Raw HTML -> mixed/lifecycle acceptance`.

New fidelity defects remain children of #546/#547/#548/#549 as appropriate.

## Provider/default-selection policy

The accepted baseline is deliberately narrow:

- provider policy: `PLACE_BEFORE_DEFAULT_EDITOR`;
- MarkFlow is eligible to be the initial preferred surface;
- bundled IntelliJ Markdown remains selectable;
- platform remembered provider selection is retained when supported by maintained APIs;
- no private/internal API is authorized to force IntelliJ-first or custom per-file selection.

A new explicit global “default editor = IntelliJ / MarkFlow / remember last” product setting is not part of this cutover. Add it only through a separate product/API proof if required.

## Rollback boundary

Before Slice 2, rollback is trivial because production behavior is unchanged.

For Slice 2:

- the rollback unit is the complete provider-activation/presentation-ownership cutover;
- reverting it restores the previous native platform-augmentation shell;
- no source conversion, migration or browser state recovery is required because both shells use the same `Document`;
- do not retain a hidden browser editor or permanent dual attachment for rollback;
- do not delete the old attachment implementation until the replacement exact-HEAD and post-main evidence required by Slice 4 is available.

After Slice 4, rollback requires reverting the owned-shell cutover commits; it never authorizes recreating browser source authority.

## Production acceptance contract

The production cutover is incomplete unless the exact candidate proves all of the following.

### Provider / authority

- MarkFlow and bundled IntelliJ Markdown coexist for the same supported file.
- MarkFlow can be selected through maintained public APIs.
- Bundled IntelliJ can be selected through maintained public APIs.
- first-open/default ordering matches the selected provider policy.
- remembered selection behavior is observed and recorded.
- both surfaces use the exact same `FileDocumentManager Document`.
- no custom source synchronization protocol exists.

### Native editing / state

- typing and Korean IME/composition;
- undo/redo and dirty/save/reopen;
- caret/selection;
- multicaret;
- keymaps;
- copy/paste;
- applicable list indentation/edit actions;
- `FileEditorState` caret/selection/scroll restoration;
- external VFS/source change behavior;
- split/create/dispose/recreate.

### Presentation isolation

- one MarkFlow presentation owner per MarkFlow editor instance;
- zero MarkFlow presentation owners on bundled IntelliJ editors;
- disposing one owned editor does not affect another editor over the same `Document`;
- stale presentation cannot apply after source/config identity changes.

### Renderer boundary

- ordinary Markdown creates no derived-render request;
- Mermaid/KaTeX lazily request the retained renderer;
- renderer failure leaves source editing usable;
- JCEF plugin absent => MarkFlow source editing still works and derived output degrades locally;
- no JCEF/browser source authority is loaded through the base editing path.

### Resource / lifecycle

Record, do not infer:

- baseline;
- bundled IntelliJ editor only;
- MarkFlow owned editor;
- both surfaces together where relevant;
- 1 / 5 / 20 representative Markdown files;
- live native editor count;
- live MarkFlow controller count;
- renderer/browser process/runtime count;
- heap/RSS where meaningful;
- repeated open/close;
- post-disposal idle cleanup.

Resource snapshots are comparative evidence, not universal fixed thresholds unless a later performance task defines such thresholds.

### Repository / release evidence

- unit/build/static-analysis gates;
- Plugin Verifier;
- native runtime evidence;
- packaged real-IDE Starter/E2E evidence;
- exact-HEAD merge gate;
- post-main verification where required by repository policy;
- unresolved review threads = 0.

UNKNOWN / UNVERIFIED / INSUFFICIENT EVIDENCE = FAIL.

## Non-goals

This cutover plan does not:

- repair #550–#555 fidelity defects;
- change Mermaid/KaTeX engines;
- expand raw-HTML capability;
- add browser editing;
- add a second mutable Markdown model;
- add a custom provider-selection preference unless separately justified;
- authorize v1.0.0.

## First implementation task after #559

The next bounded task should be **Slice 1 only**: prepare production-named owned-shell lifecycle/registry/provider infrastructure while leaving the current production opening path unchanged.

Do not combine Slice 1 with provider activation, old-listener deletion, or fidelity repair.
