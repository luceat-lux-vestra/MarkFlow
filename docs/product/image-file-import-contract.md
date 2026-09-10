# Image-file import product contract

Status: accepted by PR #187, effective on merge

This document is the normative #150 addendum to the #78 Leap capability and
fidelity contract. It resolves the image-file **import/insertion** choices
intentionally left open by #78, #99, and ADR 0001. Existing Markdown
image-reference rendering is a separate capability implemented by #147.
Implementation belongs to #151.

The historical public report only establishes that image insertion/display was a
user-visible problem; its original detail is no longer available. The gesture and
filesystem behavior below are therefore a new explicit product decision, not a
claim about what that historical report specifically requested.

## Product outcome

A user can intentionally bring local raster image content into a saved local
Markdown document and receive a normal document-relative Markdown image
reference. Import is host-owned. It never grants Markdown content ambient
filesystem authority and never requires a browser upload, loopback capability,
or JCEF editor.

The authoritative Markdown source remains the IntelliJ `Document`. File-system
side effects and the generated source edit have separate ownership and rollback
rules defined below.

## Supported entry gestures

The target supports these explicit user gestures:

1. **Insert Image action + IntelliJ file chooser** — one or more local image
   files may be selected.
2. **File drag/drop into the native editor** — one or more explicitly dropped
   local image files may be imported at the drop position.
3. **Clipboard local-file list paste** — an explicit paste whose active
   `Transferable` contains local image files uses the same import pipeline.
4. **Clipboard image-bytes paste** — one bounded raster image payload may be
   imported; MarkFlow materializes it as PNG before inserting the reference.

Plain text that merely looks like a filesystem path is never interpreted as file
authority. Ordinary text/Markdown paste remains governed by #146/#152.

Image import is single-caret/single-drop-position behavior. Multiple source
carets do not multiply one import gesture; the operation is rejected/delegated
without file creation until a separate product decision explicitly defines that
semantics.

## Accepted media and resource bounds

Imported file media intentionally matches the #147 native local-image
presentation envelope so a successful import cannot create a reference MarkFlow
itself claims to support but cannot present:

- PNG (`.png`)
- JPEG (`.jpg`, `.jpeg`)
- GIF (`.gif`)
- BMP (`.bmp`)

SVG, WebP, remote URLs, arbitrary `image/*`, and extension/content mismatches are
not accepted by this contract. GIF animation is not a target product guarantee;
the native image capability is a bounded raster presentation contract.

The implementation must enforce the same upper presentation bounds as #147 at
the import boundary:

- encoded file size: at most 64 MiB;
- width/height: each at most 8192 pixels;
- decoded pixel count: at most 16 Mi pixels;
- actual decoded media must match the admitted extension/format.

A clipboard image is encoded to PNG first and the resulting artifact must satisfy
the same size/dimension/pixel envelope.

## Destination policy

The default managed import directory is `assets/` directly beneath the
Markdown document's parent directory.

A successful import must always produce a path resolvable by the #147
document-root and target-syntax policy.

- If a selected/dropped/pasted local file already exists as a safe regular file
  beneath the current Markdown document's real parent directory, satisfies the
  admitted media/bounds, **and its contained relative target is accepted by the
  #147 target decoder**, MarkFlow links it in place instead of copying it.
- A contained file whose existing relative path cannot be represented by #147
  is not linked in place; it follows the normal sanitized copy path below.
- Otherwise MarkFlow copies/materializes the image into the document-local
  `assets/` directory.
- MarkFlow does not search the wider project for a "better" asset location and
  does not gain authority to arbitrary files merely because a project is open.

The destination filename preserves the source basename where safely portable.
Characters unsafe for portable filenames **or rejected after decoding by the
#147 target policy** are replaced deterministically before Markdown-path
encoding. In particular, copied names must not retain path separators, control
characters, `?`, `#`, traversal segments, or any syntax that #147 would reject.
An empty result falls back to `image`. Clipboard image bytes use basename
`image.png`.

The first available filename wins. Existing files are never overwritten by
default. Collisions use a deterministic numeric suffix before the extension,
for example `diagram.png`, `diagram-2.png`, `diagram-3.png`.

## Generated Markdown

Generated image references use normal inline Markdown image syntax:

```text
![alt](relative/path.png)
```

Path rules:

- relative to the Markdown document's parent directory;
- `/` separators regardless of host platform;
- no `..` segments;
- URL-style UTF-8 percent encoding for path-segment characters that are safe for
  #147 after decoding but would otherwise be ambiguous in Markdown/URLs;
- no encoded escape intended to reintroduce a character or segment rejected by
  #147;
- no absolute path, `file:` URL, capability token, query, or fragment.

For a copied image the normal form is `assets/<name>`. A safely linkable image
already below the document parent keeps its shortest contained relative path.
Before source insertion, the final generated target must round-trip through the
same #147 target-decoding/containment acceptance used by presentation.

Default alt text is the original source filename stem, with Markdown-significant
characters escaped and line breaks removed. Clipboard image bytes use `image`.
If the derived alt text is empty after normalization, use `image`.

For chooser/drop/file-list multi-image import, references are emitted in the
user gesture's stable input order, one image reference per line. A chooser or
clipboard import replaces the active single selection using normal editor
insertion semantics; otherwise it inserts at the active caret. A drop inserts at
the resolved drop position. Source outside that explicit replacement/insertion
region is unchanged.

## Host/VFS and Document transaction boundary

The product contract requires this ordering; exact API calls belong to #151:

1. resolve the current saved local document context and explicit user-supplied
   image authority;
2. validate every input and compute all final destination names/Markdown paths
   without mutating source;
3. create/copy only the required new assets through maintained host/VFS write
   semantics;
4. verify the created/linkable `VirtualFile` state and final #147-resolvable
   relative target needed by the operation;
5. perform **one IntelliJ command** that replaces/inserts the complete generated
   Markdown payload into the authoritative `Document`;
6. refresh normal #147 host-owned image presentation from the resulting source.

IntelliJ 2026.2 may defer physical persistence of `VirtualFile` content. The
product contract therefore depends on successful VFS-visible creation/write,
not on a synchronous raw-disk flush completing before the source command.

## Failure and rollback

Before the source command succeeds, every newly created asset remains owned by
the in-flight import operation.

- Input validation or destination-preflight failure creates no files and edits no
  source.
- If asset creation/copy fails, the operation removes only assets created by this
  attempt and leaves source unchanged.
- If the source command cannot be committed after assets were created, MarkFlow
  removes only those newly created assets and leaves source unchanged.
- A pre-existing/link-in-place file is never deleted by rollback.
- If cleanup of a newly created asset itself fails, source still remains
  unchanged and the user receives an explicit diagnostic identifying the safe
  relative orphan path; the inconsistency is never silent.

Partial success that silently leaves some inserted references or some unreported
copied files is not accepted.

## Undo / redo semantics

After the source command succeeds, newly created assets become ordinary project
files and **are not deleted by editor Undo**.

Undo/redo applies only to the Markdown `Document` edit:

- Undo removes/restores only the generated Markdown reference payload.
- Undo never deletes a pre-existing file and never tries to infer whether a new
  asset is still referenced elsewhere.
- Redo reinserts the same Markdown payload and does not recopy the file.
- If the user independently removes/moves the asset before redo/reopen, the
  reference remains exact source and #147 degrades presentation rather than
  inventing or recreating filesystem state.

This deliberately prefers non-destructive filesystem semantics over attempting
to make editor text undo own physical-file lifetime.

## Unsupported document contexts

Image import is unavailable, with no file/source mutation, when the current
Markdown target does not provide the deterministic local context required by
this contract, including:

- unsaved/in-memory documents without a stable local path;
- non-local/remote virtual files;
- read-only documents;
- a document parent/destination that cannot be written safely;
- invalid or stale document/file context discovered before commit.

Opening such a document still permits ordinary source editing according to the
platform; only the import capability is unavailable.

## Trust and explicit authority

- Import begins only from an explicit chooser/drop/paste user gesture.
- Markdown source cannot cause a file to be copied merely by being opened or
  rendered.
- Plain text paths do not grant authority.
- All source files and destinations are revalidated as local regular files and
  through the #147 media/size/containment envelope before source insertion.
- Symlink/path traversal or a destination that escapes the document parent fails
  closed.
- No browser/JCEF file picker, loopback upload, arbitrary filesystem token, or
  ambient network access is introduced.
- Normal diagnostics prefer filename/safe relative path and do not expose
  unnecessary absolute local paths or clipboard image bytes.

## Reopen, rename, and move expectations

- Reopen at the same document path must preserve the generated relative
  reference and #147 rendering behavior.
- Renaming the Markdown file within the same parent directory does not require
  rewriting its `assets/` references.
- MarkFlow does not silently move assets or rewrite Markdown when the document is
  moved to a different directory. If a relative asset no longer resolves,
  source remains unchanged and presentation degrades visibly.
- External asset rename/move/delete likewise never triggers guessed source
  reconstruction.

A future explicit refactoring/move feature may define stronger behavior, but it
is not part of image import.

## User-visible failure behavior

Failures are actionable and source-safe. The implementation should report a
concise reason such as unsupported format, oversize image, read-only context,
copy failure, or unavailable local document context. Successful imports need no
success notification beyond the inserted source/presentation.

Diagnostics must not dump image bytes, full clipboard contents, or unrelated
absolute filesystem paths.

## Maintained-platform implementation basis

This product contract is compatible with maintained platform responsibilities
already used by the Leap target:

- IntelliJ `FileChooser` / `FileChooserDescriptor` for explicit file selection;
- the IntelliJ file-drop extension surface for explicit dropped files;
- the active paste `Transferable`/clipboard integration boundary already proven
  by #146 rather than later global-clipboard guessing;
- `VirtualFile`/VFS write semantics for project-visible asset creation/copy;
- the native IntelliJ `Document`, command, write, dirty, undo and redo semantics
  already selected by ADR 0001 and #146.

These APIs are implementation evidence, not product authority. #151 must still
prove the exact supported IDE matrix and runtime behavior rather than infer it
from API availability.

## #151 deterministic acceptance matrix

The implementation Task must prove at minimum:

- chooser single/multi import;
- file drop single/multi import at the intended drop position;
- clipboard local-file list import;
- clipboard image-byte -> PNG import;
- stable multi-image ordering;
- already-contained **#147-representable** image links in place without copying;
- contained but #147-unrepresentable relative path copies to a sanitized
  `assets/` target rather than emitting an unloadable reference;
- external image copy to `assets/`;
- deterministic collision suffixing and no overwrite;
- safe filename/path generation, final #147 target round-trip, and reopen;
- PNG/JPEG/GIF/BMP valid cases;
- unsupported extension, media mismatch, oversize, excessive dimensions/pixels;
- read-only, unsaved and non-local targets;
- VFS/create/write failure before source edit;
- source-command failure after file creation with new-file rollback;
- rollback cleanup failure surfaced as an explicit orphan diagnostic;
- undo/redo changes Markdown only and never deletes imported/pre-existing files;
- source outside the insertion/replacement range remains lexically identical;
- multi-caret import is not silently multiplied;
- traversal/symlink/encoded-target/authority hostile cases fail closed;
- no browser upload/loopback/file token path is introduced;
- JCEF-unavailable native editing/import behavior where the gesture does not
  require a renderer;
- imported references feed #147 host-owned presentation without a second source
  authority.

## Non-goals

- no generic asset manager;
- no automatic orphan cleanup;
- no automatic cross-directory document/asset refactoring;
- no configurable asset-root UX in this contract;
- no remote image download;
- no SVG/WebP support claim;
- no browser upload/file picker;
- no production implementation in #150.
