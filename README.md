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
  - Raw HTML support (inline and block) with XSS-safe sanitization
  - Markdown-aware clipboard paste (with safe fallback in code blocks)

## Options

You can configure these in `Settings > Tools > MarkFlow`.

- **General:** Theme source, Preview only by default
- **Mermaid:** Diagram size mode, Diagram zoom (%), Error display behavior
- **KaTeX:** Display density
- **Advanced:** Diagram security level (STRICT/LOOSE)

## Operational tips

- If MarkFlow feels too heavy when many tabs sit idle, reduce **Idle browser eviction delay (ms)** so unused pooled browsers are cleaned up sooner.
- Split editors now allocate browsers on demand, so the first open on a new pane may incur a one-time JCEF startup cost.
- The first Markdown tab still pre-warms one browser on startup, which keeps the single-editor workflow responsive.

<!-- Plugin description -->
( markdown, mermaid, latex-katex, wysiwyg, raw-html )

MarkFlow is a Typora-style WYSIWYG Markdown editor for IntelliJ-based IDEs.

It opens supported Markdown files in a custom IntelliJ editor, keeps the document and JCEF webview synchronized in both
directions, and restores editor state such as scroll position, caret, and selection when reopening files.

MarkFlow supports Mermaid diagrams, KaTeX math, raw HTML, and Markdown-aware paste handling, with configurable rendering options
for theme source, Mermaid sizing and zoom, error display, KaTeX density, and preview defaults.
<!-- Plugin description end -->

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
