# MarkFlow - Seamless WYSIWYG Markdown Editor

![Build](https://github.com/luceat-lux-vestra/MarkFlow/workflows/Build/badge.svg)
[![Version](https://img.shields.io/badge/Marketplace-pending-lightgrey)](https://plugins.jetbrains.com/)

MarkFlow is an IntelliJ IDEA plugin that provides a WYSIWYG-first Markdown editing experience when its supported JCEF surface is available, while preserving access to IntelliJ's native/source editor.

## Features

- Typora-style WYSIWYG Markdown editing in a custom IntelliJ `FileEditor`
- Preferred WYSIWYG editing for supported Markdown files (`.md`, `.markdown`, `.mdown`, `.mkdn`) with native/source editor coexistence
- Two-way IntelliJ <-> Webview synchronization with document/source fidelity requirements
- Editor UI state restore (scroll position, cursor, selection)
- Mermaid live preview in code blocks
- KaTeX rendering for inline and block math expressions
- Markdown-aware clipboard paste (with safe fallback in code blocks)
- Raw HTML support for inline and block HTML with XSS sanitization
- JCEF-based webview integration via IntelliJ's bundled browser APIs
- Frontend build integrated into Gradle plugin tasks

## Options

You can configure these in `Settings > Tools > MarkFlow`.

- **General:** Theme source (`IDE_SYNC` follows the active IDE palette; `LIGHT`/`DARK` force a theme), body font family (`IDE Default (<actual IDE font>)` or an installed family), base font size (IntelliJ editor-supported range), Preview only by default
- **Mermaid:** Diagram size mode, Diagram zoom (%), Error display behavior
- **KaTeX:** Display density
- **Advanced:** Diagram security level (STRICT/LOOSE)

MarkFlow targets IntelliJ IDEA 2026.2+ (build 262+) with the bundled **Web Browser (JCEF)** plugin
(`com.intellij.modules.jcef`) for the WYSIWYG surface. If JCEF is unavailable or fails to initialize safely,
the native/source editor remains the fallback.
<!-- Plugin description -->
( markdown, mermaid, latex-katex, raw-html, wysiwyg )

MarkFlow is a WYSIWYG-first Markdown editor for IntelliJ-based IDEs.

It offers a preferred WYSIWYG surface for supported Markdown files while retaining access to IntelliJ's native/source editor,
keeps the authoritative document and webview synchronized, and restores editor state such as scroll position, caret, and selection when reopening files.

MarkFlow supports Mermaid diagrams, KaTeX math, Markdown-aware paste, and raw HTML rendering with XSS sanitization, with configurable rendering options
for theme source, body font family and size, Mermaid sizing and zoom, error display, KaTeX density, and preview defaults.
<!-- Plugin description end -->

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
