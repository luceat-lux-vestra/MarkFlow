# MarkFlow - Seamless WYSIWYG Markdown Editor

[![Build](https://github.com/luceat-lux-vestra/MarkFlow/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/luceat-lux-vestra/MarkFlow/actions/workflows/build.yml)
[![Marketplace Version](https://img.shields.io/jetbrains/plugin/v/com.algorist.markflow)](https://plugins.jetbrains.com/plugin/index?xmlId=com.algorist.markflow)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/com.algorist.markflow)](https://plugins.jetbrains.com/plugin/index?xmlId=com.algorist.markflow)
[![License](https://img.shields.io/github/license/luceat-lux-vestra/MarkFlow)](LICENSE)

MarkFlow is an IntelliJ IDEA plugin for WYSIWYG-first Markdown editing.

## Current runtime

Production editing uses IntelliJ's platform text editor. The IntelliJ `Document` is the sole mutable Markdown authority, and MarkFlow augments that native editor with source-neutral presentation rather than maintaining a second browser editor.

Current behavior includes:

- Native IntelliJ editing for supported Markdown files (`.md`, `.markdown`, `.mdown`, `.mkdn`)
- In-place source-neutral Markdown projection with exact-source reveal/degradation
- Mermaid derived previews through the retained Mermaid engine
- KaTeX rendering for inline and block math through the retained KaTeX engine
- Markdown-aware native paste with safe fallback in parser-proven code blocks
- Host-owned local-image and external-navigation handling
- Source-preserved raw HTML with isolated/sanitized derived presentation
- Renderer-only TypeScript/Vite build integrated into Gradle plugin tasks

JCEF is an optional derived-renderer backend behind `markflow-jcef.xml`. If it is unavailable or disabled, rich renderer output degrades while native source editing, save, undo/redo, settings, and exact-source access remain available.

## Accepted Leap architecture

ADR 0001 / PR #142 selected **IntelliJ-native authoritative editing + in-place source-neutral Markdown projection + isolated derived renderers**.

The target has these non-negotiable properties:

- IntelliJ `Document` is the sole mutable live Markdown authority;
- a native IntelliJ `Editor` edits that same `Document` directly;
- MarkFlow owns derived presentation only, never a second editable Markdown model;
- browser/JCEF/JavaScript are removed from ordinary editing correctness;
- unsupported/ambiguous/renderer-failed content degrades to exact source;
- optional renderer/JCEF failure cannot make source editing unavailable;
- local resources and external navigation are host-owned capabilities.

ADR 0002 preserves renderer continuity during migration: Mermaid `11.17.2` and KaTeX `^0.18.7` are extracted from editor-specific integration, reused behind one derived-renderer service, and then consumed by native inlays. They are not deleted and recreated as collateral editor work.

JCEF/TypeScript/Vite/Node may remain only for actual isolated renderer consumers after editor migration. They do not become editor authority again.

See `docs/architecture/README.md` and `docs/architecture/leap-migration-inventory.md`.

## Options

You can configure these in `Settings > Tools > MarkFlow`.

Current options include:

- **Appearance:** Theme source (`IDE_SYNC` follows the active IDE palette; `LIGHT`/`DARK` force a theme), body font family (`IDE Default (<actual IDE font>)` or an installed family), base font size
- **Mermaid:** Diagram size mode, Diagram zoom (%), Error display behavior
- **KaTeX:** Display density

Browser-era `previewOnlyByDefault`, `idleEvictAfterMs`, and user-selectable Mermaid security-level settings are removed. Legacy persisted values are ignored and are not written again; Mermaid rendering is fail-closed at `securityLevel: strict`.

MarkFlow targets IntelliJ IDEA 2026.2+ (build 262+). The bundled **Web Browser (JCEF)** plugin (`com.intellij.modules.jcef`) is optional renderer infrastructure and never gates Markdown source editing.

<!-- Plugin description -->
( markdown, mermaid, latex-katex, raw-html, wysiwyg )

MarkFlow is a WYSIWYG-first Markdown editor for IntelliJ-based IDEs.

MarkFlow uses the native IntelliJ `Document`/`Editor` as the authoritative Markdown editing surface and adds source-neutral in-place presentation, Markdown-aware paste, host-owned resources/navigation, and source-preserved raw HTML presentation.

Mermaid and KaTeX remain supported through one isolated derived-renderer service. JCEF is optional renderer infrastructure; renderer failure does not gate source editing, save, undo/redo, or exact-source access.
<!-- Plugin description end -->

## License

MarkFlow is licensed under the Apache License 2.0. See [LICENSE](LICENSE) for the full license text.

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
