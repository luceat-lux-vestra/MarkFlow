# MarkFlow - Seamless WYSIWYG Markdown Editor

![Build](https://github.com/luceat-lux-vestra/MarkFlow/workflows/Build/badge.svg)
[![Version](https://img.shields.io/badge/Marketplace-pending-lightgrey)](https://plugins.jetbrains.com/)

MarkFlow is an IntelliJ IDEA plugin that provides a Typora-style Markdown editing experience using a JCEF webview and Milkdown.

## Features

- Typora-style WYSIWYG Markdown editing in a custom IntelliJ `FileEditor`
- Automatic takeover for Markdown files (`.md`, `.markdown`, `.mdown`, `.mkdn`)
- Two-way IntelliJ <-> Webview synchronization via `JBCefJSQuery`
- Editor UI state restore (scroll position, cursor, selection)
- Mermaid live preview in code blocks
- KaTeX rendering for inline and block math expressions
- Markdown-aware clipboard paste (with safe fallback in code blocks)
- Raw HTML support for inline and block HTML with XSS sanitization
- JCEF-based webview integration via IntelliJ's bundled browser APIs
- Frontend build integrated into Gradle plugin tasks

## Options

You can configure these in `Settings > Tools > MarkFlow`.

- **General:** Theme source (`IDE_SYNC` follows the active IDE palette; `LIGHT`/`DARK` force a theme), body font family (IDE font by default or an installed family), base font size (IntelliJ editor-supported range), Preview only by default
- **Mermaid:** Diagram size mode, Diagram zoom (%), Error display behavior
- **KaTeX:** Display density
- **Advanced:** Diagram security level (STRICT/LOOSE)

## Operational tips

- If MarkFlow feels too heavy when many tabs sit idle, reduce **Idle browser eviction delay (ms)** so unused pooled browsers are cleaned up sooner.
- Split editors now allocate browsers on demand, so the first open on a new pane may incur a one-time JCEF startup cost.
- The first Markdown tab still pre-warms one browser on startup, which keeps the single-editor workflow responsive.

MarkFlow requires IntelliJ IDEA 2026.2+ (build 262+) with the bundled **Web Browser (JCEF)** plugin
(`com.intellij.modules.jcef`) enabled, which is where the JCEF runtime lives since 2026.2.
<!-- Plugin description -->
( markdown, mermaid, latex-katex, raw-html, wysiwyg )

MarkFlow is a Typora-style WYSIWYG Markdown editor for IntelliJ-based IDEs.

It opens supported Markdown files in a custom IntelliJ editor, keeps the document and JCEF webview synchronized in both
directions, and restores editor state such as scroll position, caret, and selection when reopening files.

MarkFlow supports Mermaid diagrams, KaTeX math, Markdown-aware paste, and raw HTML rendering with XSS sanitization, with configurable rendering options
for theme source, body font family and size, Mermaid sizing and zoom, error display, KaTeX density, and preview defaults.
<!-- Plugin description end -->

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
