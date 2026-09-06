# MarkFlow - Seamless WYSIWYG Markdown Editor

![Build](https://github.com/luceat-lux-vestra/MarkFlow/workflows/Build/badge.svg)
[![Version](https://img.shields.io/badge/Marketplace-pending-lightgrey)](https://plugins.jetbrains.com/)

MarkFlow is an IntelliJ IDEA plugin for WYSIWYG-first Markdown editing.

## Current runtime

The current production implementation still uses a JCEF-based custom editor for supported Markdown files. That implementation remains functional migration input while the accepted Leap target is implemented; it is **not** the target architecture.

Current behavior includes:

- Typora-style WYSIWYG Markdown editing in a custom IntelliJ `FileEditor`
- Automatic custom-editor handling for supported Markdown files (`.md`, `.markdown`, `.mdown`, `.mkdn`) when JCEF is available
- Two-way IntelliJ <-> Webview synchronization in the current runtime
- Editor UI state restore (scroll position, cursor, selection)
- Mermaid live preview in code blocks
- KaTeX rendering for inline and block math expressions
- Markdown-aware clipboard paste (with safe fallback in code blocks)
- Raw HTML support for inline and block HTML with XSS sanitization
- Frontend build integrated into Gradle plugin tasks

When JCEF is unavailable before current editor selection, MarkFlow does not take over the file and IntelliJ's native editor remains available.

## Accepted Leap target

ADR 0001 / PR #142 selected **IntelliJ-native authoritative editing + in-place source-neutral Markdown projection + isolated derived renderers**.

The target has these non-negotiable properties:

- IntelliJ `Document` is the sole mutable live Markdown authority;
- a native IntelliJ `Editor` edits that same `Document` directly;
- MarkFlow owns derived presentation only, never a second editable Markdown model;
- browser/JCEF/JavaScript are removed from ordinary editing correctness;
- unsupported/ambiguous/renderer-failed content degrades to exact source;
- optional renderer/JCEF failure cannot make source editing unavailable;
- local resources and external navigation are host-owned capabilities.

ADR 0002 preserves renderer continuity during migration: Mermaid `11.17.2` and KaTeX `^0.18.4` are extracted from editor-specific integration, reused behind one derived-renderer service, and then consumed by native inlays. They are not deleted and recreated as collateral editor work.

JCEF/TypeScript/Vite/Node may remain only for actual isolated renderer consumers after editor migration. They do not become editor authority again.

See `docs/architecture/README.md` and `docs/architecture/leap-migration-inventory.md`.

## Options

You can configure these in `Settings > Tools > MarkFlow`.

Current runtime options include:

- **General:** Theme source (`IDE_SYNC` follows the active IDE palette; `LIGHT`/`DARK` force a theme), body font family (`IDE Default (<actual IDE font>)` or an installed family), base font size, Preview only by default
- **Mermaid:** Diagram size mode, Diagram zoom (%), Error display behavior
- **KaTeX:** Display density
- **Advanced:** Diagram security level (STRICT/LOOSE)

Some browser-runtime settings/meanings are migration inputs rather than target product contracts. #83/#155 own their final native/renderer interpretation and persistence migration.

MarkFlow targets IntelliJ IDEA 2026.2+ (build 262+). The current production editor uses the bundled **Web Browser (JCEF)** plugin (`com.intellij.modules.jcef`); the accepted target does not permit JCEF availability to gate Markdown source editing.

<!-- Plugin description -->
( markdown, mermaid, latex-katex, raw-html, wysiwyg )

MarkFlow is a WYSIWYG-first Markdown editor for IntelliJ-based IDEs.

The current runtime uses a JCEF-backed custom editor and supports Mermaid diagrams, KaTeX math, Markdown-aware paste, raw HTML rendering with sanitization, configurable theme/font settings, Mermaid sizing/zoom/error display, and KaTeX density.

The accepted Leap architecture is migrating editing authority to the native IntelliJ `Document`/`Editor` with source-neutral in-place projection. Mermaid and KaTeX remain supported through one extracted derived-renderer service; optional renderer/JCEF failure will not gate source editing.
<!-- Plugin description end -->

## License

MarkFlow is licensed under the Apache License 2.0. See [LICENSE](LICENSE) for the full license text.

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
