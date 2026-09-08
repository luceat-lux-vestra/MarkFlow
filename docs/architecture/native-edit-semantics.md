# Native edit semantics

Status: #146 implementation contract under completed native shell #143 and projection foundation #145

## Decision

MarkFlow keeps the normal IntelliJ platform text editor as the owner of native input, clipboard insertion, caret/selection/multicaret, dirty/save, command/undo/redo and `FileEditor` state semantics.

#146 adds only narrowly scoped Markdown-specific behavior above that platform boundary:

```text
active single-caret Transferable                 explicit native selection(s)
           │                                               │
           ▼                                               ▼
NativePasteTransferableCapturePostProcessor   NativeMarkdownRichEdit
           │ one-shot identity bridge                       │ one local write command
           ▼                                               │
NativeMarkdownPastePreProcessor                            │
           │ payload transform only                        │
           └───────────────────────┬───────────────────────┘
                                   ▼
                       authoritative IntelliJ Document
                                   │
                                   ▼
                     platform dirty/save/undo/state

multicaret / column-mode paste ─────────► platform original paste handler
```

There is no MarkFlow-owned native editable model and no target MarkFlow `FileEditorState` format.

This is not the production editor cutover. The current browser-backed editor/provider remains temporary migration code until #153, and #154 still owns deletion of browser source synchronization/state/trust mechanisms.

## Native paste boundary

`NativeMarkdownPastePreProcessor` is registered through IntelliJ's maintained `com.intellij.copyPastePreProcessor` extension point. Where the platform invokes it, it may replace only the text payload supplied to the normal platform paste action. It never writes the `Document` itself.

`NativePasteTransferableCapturePostProcessor` is registered through the maintained `com.intellij.copyPastePostProcessor` extension point for one narrow reason: IntelliJ's `PasteHandler` has the actual active `Transferable`, but `CopyPastePreProcessor` receives only the resulting text. `PasteHandler` invokes post-processor extraction on that exact `Transferable` before the preprocessor loop. The capture post-processor therefore provides a one-shot, same-thread bridge to the preprocessor; it does not insert, rewrite, format or post-process source. Its later `processTransferableData` callback only clears any unconsumed bridge state.

The resulting responsibility split is deliberate:

- for supported single-caret paste, MarkFlow decides whether the incoming payload qualifies for Markdown-specific inserted-payload normalization;
- IntelliJ performs the actual replacement/insertion and remains responsible for command/undo, dirty state, editor input integration and save behavior;
- for multicaret or column-mode paste, MarkFlow does not install another paste-action owner and the platform's original/default paste path remains authoritative.

### IntelliJ 2026.2 multicaret/column-mode boundary

Maintained IntelliJ 2026.2 `PasteHandler` checks the editor before its code-insight paste path. When `editor.isColumnMode()` is true or the editor has more than one caret, it delegates to its original handler and returns before both the post-processor extraction and `CopyPastePreProcessor` loop used by the #146 single-caret path.

That platform behavior is an architecture constraint for #146, not something to hide behind an implementation assumption. Replacing the global paste action merely to force Markdown MIME preprocessing on multicaret would make MarkFlow re-own input, paste composition, multicaret insertion and compatibility behavior that #143 deliberately left with the platform.

Therefore #146 chooses the smaller ownership boundary:

- **single-caret** Markdown-aware preprocessing is implemented and proven here;
- **multicaret/column-mode** paste remains literal/default platform behavior here;
- full Markdown-aware multicaret/column-mode parity is deferred explicitly to #152, together with the remaining ordinary-Markdown WYSIWYG/interaction contract.

The implementation contains an explicit `caretCount != 1` delegation guard as a fail-safe even though the maintained 2026.2 `PasteHandler` already bypasses the MarkFlow single-caret extension path before that point.

### Active Transferable identity, preference and normalization

For single-caret paste outside parser-proven code blocks:

1. capture the exact active `Transferable` that the same IntelliJ `PasteHandler` invocation is processing;
2. prefer its non-blank `text/markdown` flavor only when its bounded plain-text flavor still correlates with the platform text reaching the MarkFlow preprocessor;
3. otherwise inspect the platform-provided plain text and normalize it only when it is recognizably Markdown-like;
4. otherwise return the platform-provided plain text unchanged.

The bridge deliberately does **not** query `CopyPasteManager` or the global clipboard from the preprocessor. That distinction is correctness-critical: paste history or another producer can supply a different active `Transferable`, and two different producers can even have identical plain text with different Markdown flavors. Plain-text equality against a later/global clipboard cannot prove producer identity. Capturing the actual `Transferable` from `PasteHandler` removes that ambiguity; the subsequent plain-text comparison is only a guard against an earlier preprocessor having changed the text in the same paste chain.

The bridge stores only a weak one-shot reference on the current thread. The MarkFlow preprocessor consumes and removes it before source-context parsing, so cancellation cannot leave an active payload retained by the MarkFlow path. If the preprocessor does not consume the bridge, the capture post-processor's later callback clears it. A future paste also replaces the weak reference before any MarkFlow preprocessing.

The active Transferable's auxiliary plain/Markdown flavors are streamed with the same character-count ceiling used by IntelliJ's `BasePasteHandler` (`FileSizeLimit.getDefaultContentLoadLimit()`). Reading stops and the Markdown-specific path is abandoned as soon as that ceiling would be exceeded. A zero-length bulk read is forced through one-character progress so an unusual `Reader` cannot spin the editor thread indefinitely. This prevents the optional MIME lookup from creating an unbounded or non-progressing second payload after IntelliJ has already size-checked its authoritative plain paste text.

The only normalization owned by #146 is:

- remove one leading U+FEFF BOM from the inserted payload;
- convert CRLF or CR line separators in that inserted payload to LF.

Pre-existing source before or after the paste range is never normalized by this mechanism.

Markdown-like detection carries forward product-relevant lessons from the temporary browser implementation: representative headings, fenced blocks, display-math markers, block quotes, lists, thematic breaks, links/images, raw HTML tags and table structure. This heuristic classifies only the incoming payload; it is not a Markdown source parser and never rewrites the existing document.

### Code-block fail-safe

Before applying single-caret Markdown-specific payload normalization, #146 parses an immutable exact source string with the same bundled JetBrains Markdown parser dependency established by #145. If the active native caret or selection is inside or intersects a parser-proven fenced or indented code block, the preprocessor returns the original platform paste text unchanged.

If source context parsing fails or produces invalid ranges, the operation also returns the original platform paste text unchanged. `ProcessCanceledException` is propagated rather than converted into a product result.

This implements the product contract that paste inside code blocks is literal/default and avoids guessing from raw delimiters around the caret.

## Native rich local edits

#146 establishes only three representative source-local primitives needed to prove that WYSIWYG-style actions can use native `Document` command semantics without a serializer:

- strong: wrap the selected source in `**...**`;
- emphasis: wrap the selected source in `*...*`;
- inline code: wrap one safe single-line selection in `` `...` ``.

The primitives require explicit non-empty native selections. Every active caret must have a selection. Non-overlapping multicaret selections are applied in reverse source-offset order inside one IntelliJ write command, preserving unrelated source bytes. Selection ranges are then restored around the original inner text.

Overlapping ranges are ambiguous and rejected before any write. Adjacent non-overlapping selections are valid. Every selected range is also checked with the public `Document.getRangeGuard()` API before the command starts; if any caret selection intersects a guarded/read-only fragment, the entire rich action is rejected before any source mutation, preventing a reverse-order multicaret edit from becoming partially applied. Inline-code wrapping is rejected when the selected source contains a line break or backtick, because #146 has no product contract for selecting a larger delimiter or transforming multiline code.

No-selection convenience behavior, headings, lists, links, tables, user-facing rich-action UX mapping and full ordinary-Markdown WYSIWYG parity remain #152 responsibilities unless a later contract explicitly moves them.

The #146 primitives are an implementation boundary, not a claim that the current temporary browser toolbar is product authority.

## Editor state authority

Native caret, selection, multicaret and scroll state remain per-editor presentation state owned by the platform `TextEditor`/`FileEditor` implementation.

#146 deliberately does not introduce a replacement native state DTO. Runtime evidence uses only public `FileEditor.getState(FileEditorStateLevel.FULL)` / `setState` behavior and verifies:

- caret and selection restore on the same native editor surface;
- scroll changes are represented by the opaque platform state;
- split editors can retain different caret/selection state while observing the same authoritative `Document`;
- a shared-Document source edit may legitimately shift another editor's caret by the source delta; this is normal range/caret tracking, not state leakage;
- state capture/restore does not mutate source;
- the returned target state is not the historical browser-shaped `MarkFlowEditorState`.

The existing `MarkFlowEditorState(scrollTop, cursorOffset, selectionStart, selectionEnd)` remains `REPLACE + TEMPORARY` migration state for the browser editor. It is not extended as target state authority. Invalid or unmappable historical state may be safely dropped/clamped rather than reconstructed through JS or source mutation.

## Failure and ownership behavior

The target editing path fails safe by declining the MarkFlow-specific operation rather than reconstructing source:

- unknown/non-Markdown plain text paste: delegate unchanged;
- code-block or ambiguous single-caret paste context: delegate unchanged;
- multicaret or column-mode paste: platform literal/default path; no #146 Markdown-MIME claim;
- missing/expired active-Transferable bridge: ignore Markdown MIME and retain the platform payload;
- active Transferable plain text no longer matching the platform text after earlier preprocessors: ignore Markdown MIME and retain the platform payload;
- auxiliary active-Transferable plain/Markdown flavor over the platform content-load limit: ignore the Markdown-specific MIME path rather than reading it without bound;
- parser/runtime context failure: delegate unchanged, except platform cancellation propagates;
- missing rich selection: no source write;
- overlapping rich selections: no source write;
- guarded rich-edit selection: reject the entire action before the command; no partial multicaret mutation;
- unsupported inline-code selection: no source write;
- read-only document: no source write.

There is no global-clipboard identity guess, browser ACK/revision/recovery, debounce/flush, whole-document serialization, delayed autosave or second editable source representation in this boundary.

## Runtime evidence

`.github/workflows/native-editing-evidence.yml` launches a real supported IntelliJ runtime under Xvfb with JCEF runtime support disabled and requires a complete PASS evidence document.

The proof exercises:

1. JCEF-disabled native editing;
2. a real project/VFS/authoritative-Document fixture;
3. public platform text-provider ownership while the temporary browser provider does not take over;
4. the MarkFlow native paste extension path;
5. actual single-caret platform paste with the active `Transferable`'s `text/markdown` preference, inserted-payload-only BOM/line-ending normalization, dirty state and undo/redo;
6. Markdown-like single-caret plain-text normalization through the actual platform paste path;
7. literal/default paste inside a parser-proven fenced code block;
8. actual two-caret platform paste delegation, proving the literal/default payload reaches both native carets, MarkFlow Markdown MIME does not take over, and undo/redo remains platform-owned;
9. unchanged ordinary plain-text paste;
10. source-local strong edit plus platform undo/redo;
11. deterministic non-overlapping multicaret emphasis with independent split-editor state and expected shared-source caret-delta tracking;
12. unsupported inline-code edit rejection with source stability;
13. public platform state round-trip, split-state isolation and source stability;
14. final zero retained native editors for the proof fixture.

Unit tests separately cover payload classification, exact active-Transferable selection against a conflicting global clipboard with identical plain text, bounded/forward-progress-safe auxiliary flavor reads, fenced/indented code context, a selection crossing a code block, explicit multicaret paste-preprocessor delegation, source-local wrapping, multicaret offset ordering, guarded/read-only whole-action rejection, explicit overlap rejection/adjacent-range acceptance and unsupported selection behavior.

Normal Build/Test/Inspect code/Verify plugin, repository Hardening, Native Editor Shell Evidence and Native Projection Evidence remain independent regression evidence when selected by the final diff. JCEF Transport Evidence remains evidence for temporary code when its workflow path filter selects the same change; it is not part of the native editing correctness path.

## Migration ownership

- #143 platform editor shell: completed and retained;
- #145 projection plan/controller: completed and retained;
- #146 single-caret Markdown-aware paste, platform state ownership and representative local edit semantics: established by this candidate only after its exact-final-HEAD and post-main gates pass; it is not production cutover;
- #152 full ordinary-Markdown/table capability parity, including the explicitly deferred Markdown-aware multicaret/column-mode paste contract: still required;
- #153 production native cutover: still required;
- #154 browser editor/protocol/state/trust purge: still required;
- #155 final dependency/toolchain/JCEF/settings convergence: still required.

No part of #146 authorizes deleting the current browser editor before #153/#154 or treating browser-shaped state as permanent target compatibility surface.
