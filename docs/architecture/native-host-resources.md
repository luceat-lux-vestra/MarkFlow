# Native host resources and navigation

Status: #147 target implementation/evidence boundary for Architecture Leap #52.

## Decision

Ordinary Markdown local images and external links are host responsibilities in the native target architecture. They are not browser capabilities and do not grant document content access to JCEF, loopback HTTP, `file:` URLs, filesystem tokens, or a browser navigation realm.

The open IntelliJ `Document` remains authoritative Markdown source. `NativeHostResourceProjectionPlanner` derives immutable resource intents from the exact #145 projection generation. `NativeHostResourcePresentationController` owns only per-editor presentation/listener state; it cannot write source.

Production editor selection remains owned by #153. This task makes the host-resource consumer independently usable and evidence-backed before that cutover.

## Local image trust boundary

`NativeLocalImageResolver` accepts only document-relative raster targets and resolves them against the real parent directory of the real Markdown document path. It performs one bounded percent decode while preserving literal `+`, then fails closed for:

- absolute, scheme-bearing, query/fragment, mixed-separator and control-character targets;
- raw or encoded traversal and second-layer encoded path escapes;
- missing/non-regular paths and real-path/symlink escape;
- unapproved or extension/content-mismatched media;
- encoded files above the bounded byte limit;
- image dimensions/pixel counts above the bounded decode envelope.

The approved native target formats are PNG, JPEG, GIF and BMP because the maintained JVM ImageIO path can identify and decode them without a browser. A file extension alone is not evidence: the actual decoder format must match the approved extension family. Other image formats degrade to exact source until a separately maintained decoder/contract is approved.

Successful resolution returns only an inert `BufferedImage` plus media type. The resource target, local path and file bytes are never converted into a document-visible capability URL.

Image I/O and decode run off the EDT. An artifact may be installed only if its captured projection identity and request generation are still exact-current. Stale async results are discarded. Missing, blocked, invalid, oversized or undecodable images do not fold source.

When an image is inactive, the per-editor controller may fold its exact Markdown range and place an inert block inlay. A caret or selection intersecting that range removes the owned fold/inlay and reveals exact source. Screen-reader/accessibility fallback keeps source visible instead of installing rich image presentation.

## External navigation

`ExternalNavigationPolicy` is the single host allowlist for this migration state. Only absolute HTTP(S) URIs with a host, no credentials, and no whitespace/control characters are accepted. `javascript:`, `file:`, relative URLs and other schemes are rejected.

The native controller requires an explicit modifier-left-click over the derived link label before invoking the host navigator. It revalidates the URI immediately before the host action. Rejection or navigator failure does not touch Markdown source.

The temporary source-native browser bridge delegates its HTTP(S) authorization to the same policy only to prevent migration drift. That bridge remains temporary and is deleted by #154 after #153 production cutover; it is not part of the target host-resource architecture.

## Ownership and non-goals

#147 owns existing Markdown local-image projection and external navigation only.

- #150/#151 own drag/drop, chooser and clipboard image-file import plus Markdown insertion.
- #149 owns raw-HTML source preservation and isolated/sanitized derived preview.
- #152 owns remaining ordinary Markdown WYSIWYG parity.
- #153 owns production `FileEditorProvider` cutover and fallback/recovery policy.
- #154 owns removal of the temporary browser editor/protocol/loopback migration surface.

No code in #147 changes the production editor provider.

## Required evidence

The dedicated real-IDE probe runs with `com.intellij.modules.jcef` disabled and records explicit cases for:

- native plugin/classloading and platform text-editor selection without JCEF;
- exact host-resource projection classification;
- successful PNG/JPEG host decode and native fold/inlay ownership;
- missing/unsupported resource source fallback;
- caret/selection reveal and split-editor isolation;
- explicit HTTP(S) navigation and denied implicit/unsafe/relative activation;
- traversal, encoded traversal, mixed separators, symlink escape, media mismatch and encoded-size denial;
- document-relative context revocation/re-resolution after move and native owner recreation;
- stale async image result rejection;
- repeated create/dispose ownership and final source/dirty/undo stability.

Unit tests additionally exercise reference-style links/images, code-range exclusion, empty image alt text, URL policy, percent decoding, format sniffing and dimension bounds.
