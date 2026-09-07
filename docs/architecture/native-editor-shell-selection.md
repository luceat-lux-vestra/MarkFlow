# Native editor shell selection

Status: target execution decision for #143 under accepted ADR 0001

- Parent Epic: #52
- Tracks: #81, #79
- Architecture gate: #139
- Migration authority: #141 / `leap-migration-inventory.md`
- Audited base: `95ae24cb6931c9618385722ed244ff2feae8448e`
- IntelliJ architecture evidence pin: `JetBrains/intellij-community@88410941a24ccd990345d917bf664382da4d255d`

## Decision

Select **augmentation of the normal IntelliJ platform text editor** as MarkFlow's target native editor integration shell.

The target does not register a replacement MarkFlow `FileEditorProvider`. MarkFlow attaches one source-neutral presentation controller to each relevant native `Editor` through the maintained `EditorFactoryListener` lifecycle. The platform text editor remains responsible for the `FileEditor`, native `Editor`, authoritative `Document`, focus/data context, input, caret/selection/multicaret, keymaps, clipboard, state, dirty/save and undo/redo semantics.

This decision does **not** cut production over. The current JCEF-backed `MarkFlowEditorProvider` and `MarkFlowEditor` remain temporary migration code until #153 performs the production cutover and #154 deletes the superseded editor/protocol/trust surface.

## Candidate comparison

### Selected: augment the platform text editor

The platform already owns the behavior MarkFlow must preserve. The #143 proof therefore needs to prove only a new presentation ownership boundary:

- one controller per native `Editor`;
- controller create/release follows `EditorFactoryListener` create/release events;
- controller attachment is source-neutral and does not replace native input wiring;
- multiple native editors share the same authoritative `Document` while keeping independent presentation/editor state;
- the native shell continues to work when the loaded JCEF runtime reports unsupported;
- exact source is always the native editor itself, not a second fallback editor.

The selected shell uses public IntelliJ contracts only. In particular, target code may depend on `EditorFactoryListener`, `Editor`, `Document`, `FileEditorProvider`/`TextEditor` contracts and other maintained APIs, but not on `com.intellij.openapi.fileEditor.impl.text.TextEditorProvider` or another platform `impl` class.

### Rejected: MarkFlow-owned `FileEditor` containing a native `Editor`

This candidate can preserve one `Document`, but it adds an ownership boundary without an independent product requirement. MarkFlow would have to re-prove or delegate all of the following merely to regain behavior the platform text editor already owns:

- provider selection/order and coexistence with bundled Markdown providers;
- `FileEditor` state serialization/restoration and scroll/caret behavior;
- focus/data-context and action routing;
- keymap, clipboard, IME/composition and multicaret behavior;
- editor creation/release and client lifecycle;
- dirty/save/undo integration and compatibility across supported IDE builds.

ADR 0001 explicitly prefers platform augmentation when it can coexist cleanly. Real-IDE evidence supports that choice; the strict merge gate requires every final-head case to pass before this decision merges. A MarkFlow-owned native `FileEditor` therefore loses on responsibility surface and compatibility proof burden unless later evidence exposes a capability the platform shell cannot provide.

## Maintained upstream evidence

The architecture evidence pin records the APIs and platform ownership used by this decision:

- `platform/editor-ui-api/src/com/intellij/openapi/editor/event/EditorFactoryListener.java` documents application-level native editor create/release notifications and public subscription through `EditorFactory`/the listener extension point.
- `platform/analysis-api/src/com/intellij/openapi/fileEditor/FileEditorProvider.java` exposes provider acceptance/policy/state contracts without requiring provider implementation classes.
- `platform/platform-impl/src/com/intellij/openapi/fileEditor/impl/text/TextEditorProvider.kt` is reviewed as **platform evidence only**, not imported by MarkFlow. At the pinned revision it owns caret/selection state, relative-caret/scroll restoration, focus component and native text-editor construction.
- `platform/core-api/src/com/intellij/openapi/command/WriteCommandAction.java` and `platform/analysis-api/src/com/intellij/openapi/command/undo/UndoManager.java` provide maintained command/write and project-scoped undo/redo behavior exercised by the runtime proof.
- JCEF's maintained registry path makes `JBCefApp.isSupported()` return false when `ide.browser.jcef.enabled=false`; #143 executes the proof under that condition rather than merely asserting that native code has no browser imports.

Importing or reflecting into the reviewed `impl` classes is not authorized by this evidence.

## IntelliJ 2026.2 JCEF packaging boundary

IntelliJ Platform 2026.2 moved JCEF out of the core platform into the separately bundled `com.intellij.modules.jcef` plugin. JCEF remains a supported JetBrains platform dependency; required and optional plugin dependencies are both normal mechanisms.

Current MarkFlow migration code still declares `com.intellij.modules.jcef` as a required plugin dependency and directly references `com.intellij.ui.jcef.*`/`org.cef.*` classes. Therefore the #143 runtime proof has a deliberately narrower meaning:

- it proves the **native shell/runtime path** works while `JBCefApp.isSupported()` is false after MarkFlow and the JCEF plugin have already loaded;
- it does **not** prove the current MarkFlow package loads when the `com.intellij.modules.jcef` plugin itself is absent or disabled;
- it must not be cited as evidence that Mermaid/KaTeX renderer packaging is JCEF-independent.

Renderer execution/dependency isolation is owned by #144. Before production cutover #153, native source-editing correctness must no longer depend on renderer/JCEF availability. If #144 retains JCEF as the renderer substrate, JCEF-specific code and dependency ownership must be isolated below the derived-renderer boundary so renderer disablement/failure cannot disable native Markdown editing.

The JetBrains `com.intellij.mermaid` plugin is also a valid platform-dependency candidate for #144, but exact 2026.2 source review found no maintained public render-to-SVG service: its Markdown preview/export implementation is JCEF-backed and internal/private. #144 must not depend on those internals; it may reuse maintained Mermaid language services or promote the plugin to renderer dependency if a supported artifact API becomes available.

## Executable proof contract

`.github/workflows/native-editor-shell-evidence.yml` launches the real supported IntelliJ runtime under Xvfb with JCEF runtime support explicitly disabled and requires the diagnostic probe to produce a complete PASS evidence document.

Required cases:

1. `jcef-disabled-runtime` — `JBCefApp.isSupported()` is actually false in the proof process; this is runtime-disabled evidence, not plugin-absence evidence.
2. `authoritative-document-fixture` — an actual VFS file maps to one clean IntelliJ `Document`.
3. `provider-and-bundled-markdown-coexistence` — the public provider EP contains at least one bundled Markdown integration that accepts the fixture, the platform text provider accepts the file with `NONE` policy, and the temporary JCEF-gated MarkFlow provider does not take over while JCEF runtime support is disabled.
4. `editor-factory-presentation-lifecycle` — two source editors create exactly two per-editor presentation owners without source/input mutation.
5. `shared-document-splits` — two native editor surfaces share the exact `Document` while caret/selection/multicaret state remains per editor.
6. `platform-keyboard-and-keymap` — real platform typing reaches the authoritative `Document` and active keymap bindings remain present.
7. `platform-clipboard` — real platform copy/paste handlers round-trip selected source through the authoritative `Document`.
8. `platform-ime-composition` — real native input-method events exercise composition and committed Korean text without a browser editor.
9. `caret-selection-scroll-file-editor-state` — native caret/selection state round-trips and opaque platform `FileEditorState` observes scroll state while the preferred focus component remains native.
10. `dirty-save-undo-redo` — one native command changes the authoritative `Document`, marks it dirty, is undoable/redoable through the correct real-project/platform undo context, is visible through both editor surfaces and saves through `FileDocumentManager` to the backing file.
11. `exact-source-fallback` — the native editor exposes the current authoritative source directly; no second editable model or browser fallback is involved.
12. `create-recreate-dispose` — editor release disposes the matching controller, recreation binds the same `Document`, and no source editor/controller remains retained afterward.
13. `owned-file-editor-rejection-boundary` — the platform text provider already owns the required `FileEditor` boundary and the temporary browser provider is not required for native editing.

Any missing, duplicate, failed, incomplete or differently classified case is FAIL. GitHub CI green outside this workflow is not a substitute for this evidence.

## State/input interpretation

#143 is a shell proof, not the final native presentation implementation. It deliberately does not add key, clipboard or input-method handlers. The proof exercises platform-owned input behavior and verifies that shell attachment does not replace it. Later projection/rich-action Tasks must separately prove any behavior they actually add.

The runtime proof directly checks native caret/selection/multicaret isolation. For scroll, the proof uses the public opaque `FileEditorState` contract rather than asserting a private synchronous pixel-restoration implementation: with caret/selection held stable, a changed native scroll position must be represented by a changed platform state. #146 owns the target user-visible restoration behavior.

## Non-goals / migration boundary

#143 does not:

- change the production `MarkFlowEditorProvider` policy or registration;
- make native presentation the normal file-opening path;
- split or remove the current required JCEF plugin dependency;
- delete JCEF, CodeMirror, Crepe, sync/session/revision, loopback or browser trust code;
- select or extract Mermaid/KaTeX renderer execution (#144);
- depend on JetBrains Mermaid plugin internals;
- implement projection semantics (#145/#152);
- migrate editor settings/state (#146);
- change local resource/navigation behavior (#147);
- perform the production cutover (#153) or purge (#154).

After #143, the shell choice is no longer `UNRESOLVED`: downstream native work targets platform text-editor augmentation. Production still remains on the temporary browser editor until the later cutover gate is satisfied.
