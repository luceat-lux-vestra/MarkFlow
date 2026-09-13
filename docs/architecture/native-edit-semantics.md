# Native edit semantics

Status: #146 native editing foundation, expanded by #152 ordinary-Markdown parity

## Decision

MarkFlow keeps the normal IntelliJ platform text editor and authoritative `Document` in charge of text insertion, caret/selection/multicaret, dirty/save, command/undo/redo, IME/keymaps and `FileEditor` state semantics.

MarkFlow may transform a bounded incoming Markdown payload or invoke a source-local Markdown action, but it does not own a second editable Markdown model and does not insert a whole-document serialization result.

The current paste ownership is:

```text
single-caret paste
active Transferable
      │
      ▼
NativePasteTransferableCapturePostProcessor
      │ exact one-shot Transferable identity
      ▼
NativeMarkdownPastePreProcessor
      │ optional string payload transform
      ▼
IntelliJ PasteHandler
      │
      ▼
authoritative Document

multicaret / column-mode paste
active Transferable via PasteAction.TRANSFERABLE_PROVIDER
      │
      ▼
NativeMarkdownDeferredPasteHandler
      │ classify + optionally replace only DataFlavor.stringFlavor
      ▼
existing EditorPaste handler chain
      │ platform distribution / guarded handling / command / undo / caret semantics
      ▼
authoritative Document
```

This is still not the production editor cutover. #153 owns production opening; #154 owns deletion of the temporary browser editor/protocol.

## Single-caret paste boundary (#146)

`NativeMarkdownPastePreProcessor` is registered through IntelliJ's maintained `com.intellij.copyPastePreProcessor` extension point. `NativePasteTransferableCapturePostProcessor` provides a weak, same-thread, one-shot identity bridge from the exact active `Transferable` that IntelliJ's `PasteHandler` is already processing. MarkFlow never queries the global clipboard to infer identity.

For supported single-caret paste outside parser-proven code:

1. capture the exact active `Transferable`;
2. prefer a bounded, non-blank `text/markdown` flavor only while its plain flavor still correlates with the platform text for that same paste;
3. otherwise normalize only recognizably Markdown-like platform text;
4. otherwise delegate the platform text unchanged.

The only inserted-payload normalization owned here is removing one leading U+FEFF BOM and converting CRLF/CR within the incoming payload to LF. Existing document bytes outside the insertion/replacement range are never normalized.

Auxiliary flavors are bounded by IntelliJ's content-load limit and use progress-safe reads. Missing/expired capture state, mismatched producer content, oversized auxiliary data or parser failure all fail closed to the platform payload. `ProcessCanceledException` propagates.

## Multicaret and column-mode paste boundary (#152)

IntelliJ 2026.2 intentionally bypasses `CopyPastePreProcessor` when column mode is active or the editor already has multiple carets. #152 closes only that payload-selection gap with `NativeMarkdownDeferredPasteHandler`, registered on `EditorPaste` before the bundled Markdown table reformat hook.

The handler is deliberately not an insertion owner. It captures the exact `Transferable` from the maintained `PasteAction.TRANSFERABLE_PROVIDER`, computes a disposition, optionally wraps the transferable so only `DataFlavor.stringFlavor` changes, then delegates to the existing editor action-handler chain.

The safe cases are constrained by IntelliJ's maintained clipboard distribution semantics:

- destination multicaret + **one source-caret metadata entry**: the whole chosen string may be transformed because IntelliJ duplicates that string to every destination caret;
- destination multicaret + **missing source-caret metadata**: delegate the exact original transferable because IntelliJ may newline-split it across carets;
- destination multicaret + **multiple source-caret metadata entries**: delegate the exact original transferable because recorded offsets index the original string flavor;
- column mode: a transformed Markdown payload is permitted only when every prospective platform clone destination passes the same parser/locality gate;
- any destination in or intersecting parser-proven fenced/indented code: delegate the exact original transferable.

MarkFlow therefore does not reproduce `ClipboardTextPerCaretSplitter`, column cloning, selection replacement, guarded-fragment handling, command grouping, undo or caret movement. Those remain IntelliJ behavior.

## Parser/locality fail-safe

Paste context is classified from an immutable exact source string using the same bundled JetBrains Markdown parser boundary established by #145. If a destination or selection is inside/intersects parser-proven fenced or indented code, Markdown-specific transformation is declined.

If parsing fails or ranges are invalid, the operation also delegates unchanged rather than guessing from raw delimiters. This rule applies to both the single-caret preprocessor path and #152's deferred multicaret/column path.

## Source-local rich actions

#146 established representative local strong/emphasis/inline-code primitives over explicit native selections. They validate every caret and guarded range before one reverse-offset write command, reject overlap/ambiguous inline-code cases, and preserve unrelated source bytes.

#152 additionally proves that the bundled JetBrains Markdown strong, emphasis and code-span actions remain available on the selected native editor and perform source-local edits with platform undo/redo. MarkFlow does not duplicate maintained action IDs merely to claim parity.

No action in this boundary authorizes whole-document reconstruction. Ambiguous rich editing must reveal/degrade to source rather than infer a new lexical representation.

## Editor state authority

Caret, selection, multicaret and scroll state remain per-editor platform state owned by `TextEditor`/`FileEditor`. Split editors may maintain different presentation state over one shared authoritative `Document`; normal source edits may move another caret through platform range tracking.

The historical browser-shaped `MarkFlowEditorState` remains migration state only. It is not target native state authority and is not extended by #152.

## Failure and ownership behavior

The native editing boundary fails safe:

- unknown/non-Markdown payload: delegate unchanged;
- parser/runtime context failure: delegate unchanged, except cancellation propagates;
- code destination: delegate unchanged;
- ambiguous multicaret source segmentation: delegate exact original transferable;
- unsupported rich selection: no source write;
- overlapping or guarded rich selections: reject the whole MarkFlow-specific action before mutation;
- read-only document: no MarkFlow source write.

There is no browser ACK/revision/recovery, debounce/flush, delayed autosave, global-clipboard identity guess, whole-document serializer or second editable source authority in this path.

## Runtime evidence

`.github/workflows/native-editing-evidence.yml` launches a real supported IntelliJ runtime and retains #146 evidence for:

- authoritative project/VFS/Document ownership;
- single-caret active-Transferable Markdown preference and bounded normalization;
- literal code-context fallback;
- platform dirty/save/undo and state semantics;
- split-editor state behavior;
- source-local rich edits and rejection paths;
- lifecycle cleanup.

#152 adds a supplemental real-IDE parity document that must prove through the runtime `EditorPaste` dynamic handler chain:

- one-source-caret Markdown transformed once and duplicated by IntelliJ to multiple destination carets;
- missing/multiple source-caret metadata delegated unchanged with platform segmentation preserved;
- safe column-mode Markdown distributed by the platform;
- prospective column destinations that cross parser-proven code delegated unchanged;
- bundled Markdown strong/emphasis/code actions remain registered, source-local and undoable.

Unit tests separately prove the disposition matrix, transferable flavor preservation, parser code gates and multicaret/column destination calculations.

## Migration ownership

- #143: platform editor shell, retained;
- #145: immutable projection plan/controller, retained;
- #146: single-caret paste, platform state authority and representative local edit semantics, retained;
- #152: ordinary Markdown/table parity plus the deferred multicaret/column paste boundary described here;
- #153: production native cutover;
- #154: browser editor/protocol/state/trust purge;
- #155: final dependency/toolchain/JCEF/settings convergence.

No part of #146 or #152 authorizes deleting the temporary production editor before #153/#154.
