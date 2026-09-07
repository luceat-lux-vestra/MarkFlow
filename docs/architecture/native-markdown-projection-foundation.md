# Native Markdown projection foundation

Status: #145 implementation contract under accepted ADR 0001 and completed shell proof #143

## Decision

The first native projection foundation uses the **normal IntelliJ platform text editor** selected by #143, the authoritative IntelliJ `Document`, the bundled JetBrains Markdown parser, and per-editor native presentation ownership.

The data flow is:

```text
IntelliJ Document
      │ immutable exact snapshot captured under read action
      ▼
ProjectionSourceIdentity(source + modificationStamp + configGeneration)
      │
      ▼
JetBrains Markdown parser
      │ parser-proven ranges only
      ▼
NativeProjectionPlan
      │ exact-current identity gate
      ▼
NativePresentationController (one per Editor)
      ├── native markup for inline derived presentation
      └── native folding for a parser-proven heading syntax marker
```

This foundation does **not** register a new production editor provider and does not cut normal MarkFlow opening over from the temporary browser editor. #153 owns production cutover; #154 owns browser editor/protocol deletion.

## Parser boundary

#145 declares `org.intellij.plugins.markdown` as an explicit bundled-plugin dependency and uses `MarkdownParserManager.createMarkdownParser(MarkdownParserManager.FLAVOUR)` with inline parsing enabled.

Reasons:

- #143 already proved the bundled Markdown plugin coexists with the selected platform text editor;
- the parser is maintained by JetBrains and exposes exact source offsets;
- the default JetBrains Markdown flavour avoids inventing a second MarkFlow grammar at this boundary;
- parsing an immutable string snapshot keeps live PSI/editor state out of projection ownership;
- a custom parser or semantic rich-document serializer would add correctness surface without product value.

The Markdown dependency is a **parser/range dependency**, not a second source authority and not a renderer dependency. JCEF remains a temporary packaging/runtime dependency because the current production browser editor still exists; #155 owns final dependency convergence after cutover/purge and retained renderer consumers are known.

## Immutable source identity

Every plan carries all of:

- the exact snapshot source string;
- the IntelliJ `Document.modificationStamp` captured with that source;
- the relevant projection configuration generation.

The source string and modification stamp are captured together under an IntelliJ read action. Before any plan may replace current presentation, the controller captures the current exact snapshot identity again and compares it with the plan identity. A source, modification-stamp, or configuration-generation mismatch returns `STALE_REJECTED` **before** any MarkFlow-owned highlighter or fold is removed or added.

The initial planner is synchronous. This is intentional: #145 has no measured need for incremental parsing, background work, cache, pooling or debounce. Keeping the identity gate even in the synchronous baseline makes later asynchronous optimization unable to bypass the stale-result contract accidentally.

## Initial projection slice

The planner recognizes only the representative #145 foundation set:

- ATX headings;
- emphasis;
- strong emphasis;
- inline code;
- fenced code classification.

Other constructs are not guessed into this plan. Raw HTML, unknown extensions and malformed/unsupported structures remain exact source unless a later capability Task adds a parser-proven presentation contract.

`NativeProjectionPlan` is immutable derived intent. It contains source ranges and, where needed, parser-proven syntax-marker ranges. It contains no mutable rich Markdown document and has no source write API.

## Native presentation ownership

One `NativePresentationController` owns one native IntelliJ `Editor` presentation lifecycle.

It owns only:

- listeners registered against that controller disposable;
- MarkFlow-created `RangeHighlighter`s;
- MarkFlow-created `FoldRegion`s;
- its current immutable plan and refresh scheduling generation.

It never calls a `Document` mutation API.

Inline emphasis/strong/code presentation uses editor markup with `TextAttributesKey` fallbacks so the platform color scheme remains authoritative. The minimal block proof folds only the parser-proven ATX heading marker to a zero-width placeholder. Caret or selection entering the heading expands that fold; leaving the active construct may collapse it again. Active-state reads honor IntelliJ's read-action contract and inspect every caret, including secondary-caret selections. Inline derived styling is suppressed while its construct is active, leaving exact Markdown unadorned for editing.

The controller removes only the highlighters/folds it created. It never calls global `removeAllHighlighters` or changes unrelated folding ownership.

## Refresh and stale behavior

A real `Document` change schedules a projection refresh on the IDE event queue. Multiple pending changes coalesce by controller-local request generation. Disposal invalidates queued callbacks.

The required order is:

1. document change occurs through normal IntelliJ editing/command semantics;
2. controller schedules a derived refresh;
3. planner captures the new immutable exact snapshot under read action;
4. the resulting plan is accepted only if its exact source/modification/config identity is still current;
5. source-stale or config-stale plans are discarded before presentation mutation;
6. current presentation is rebuilt without changing source or source undo history.

No browser ACK/revision/recovery concept participates.

## Failure and recovery behavior

Parser failure is typed as `DEGRADED_TO_SOURCE` with no projected ranges. Only the failure class name is retained; source text is not copied into the diagnostic reason.

Applying a current-identity degraded plan removes only MarkFlow-owned derived presentation, leaves exact source visible/editable, and does not dirty or mutate the `Document`. A later successful refresh may replace that degraded plan with a current `READY` plan without source changes. The runtime evidence explicitly proves both the degraded fallback and recovery path.

Malformed, unsupported, out-of-bounds or unproven ranges do not receive derived presentation. Parser tree ranges are validated against the immutable snapshot before they become projection intent.

Optional JCEF state is not consulted anywhere in the planner/controller. The dedicated runtime probe runs with `JBCefApp.isSupported=false`. Because the current package still declares the JCEF bundled plugin for temporary browser code, this proves **runtime-disabled projection independence**, not package load with the JCEF plugin physically absent.

## Evidence

The dedicated `Native Projection Evidence` workflow runs a real IntelliJ 2026.2 platform editor inside a non-default opened project with JCEF runtime support disabled. It fails closed unless all required cases are present and PASS:

- JCEF-disabled projection path;
- authoritative document fixture;
- platform text editor + bundled Markdown coexistence with `FileEditorProvider.acceptRequiresReadAction()` respected;
- attach/no-edit source, modification-stamp, dirty and undo-availability stability;
- representative parser-proven plan and exact heading syntax range;
- both native inline markup and native block folding;
- primary caret, primary selection, and secondary-caret selection exact-source reveal;
- deliberate source-stale plan rejection before apply;
- automatic refresh after a real `Document` change;
- deliberate config-stale plan rejection before apply plus recovery to the new generation;
- typed `DEGRADED_TO_SOURCE` exact-source fallback plus successful recovery;
- split-editor presentation isolation over one shared `Document`;
- malformed/unsupported source-safe degradation;
- repeated refresh/create/dispose source stability;
- final zero owned controller/editor lifecycle state.

Normal Build/Test/Inspect code/Verify plugin, repository Hardening, and the already-retained #143 Native Editor Shell Evidence remain independent required review evidence when their path filters select this change.

## Migration ownership

- #143 shell proof: completed, retained as evidence.
- #145 projection plan/controller: target responsibility established here but not yet normal production opening.
- #146: native paste/actions/editor-state semantics.
- #147: host local-image/navigation projection.
- #148: Mermaid/KaTeX native consumers over #144's shared renderer service.
- #149: sanitized raw-HTML derived rendering.
- #152: ordinary Markdown/table capability and fidelity parity.
- #153: production native cutover.
- #154: mandatory old browser editor/protocol/trust purge.
- #155: dependency/toolchain/JCEF/settings convergence based on actual retained consumers.

No part of this foundation authorizes skipping those Tasks.
