# MarkFlow - Seamless WYSIWYG Markdown Editor

![Build](https://github.com/luceat-lux-vestra/MarkFlow/workflows/Build/badge.svg)
[![Version](https://img.shields.io/badge/Marketplace-pending-lightgrey)](https://plugins.jetbrains.com/)

MarkFlow is an IntelliJ IDEA plugin that provides a WYSIWYG Markdown editing experience for supported Markdown files through a JCEF-based custom editor.

## Features

- Typora-style WYSIWYG Markdown editing in a custom IntelliJ `FileEditor`
- Automatic custom-editor handling for supported Markdown files (`.md`, `.markdown`, `.mdown`, `.mkdn`) when JCEF is available
- Two-way IntelliJ <-> Webview synchronization
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
(`com.intellij.modules.jcef`) for the WYSIWYG surface. When JCEF is unavailable before editor selection,
MarkFlow does not take over the file and IntelliJ's native editor remains available.

The Leap architecture tracked in #52/#78 defines a stronger target contract: WYSIWYG-first operation with native/source-editor coexistence and a safe native-editor fallback for JCEF initialization/runtime failure. Those behaviors are target requirements and are not claimed as fully implemented by the current runtime yet.
<!-- Plugin description -->
( markdown, mermaid, latex-katex, raw-html, wysiwyg )

MarkFlow is a WYSIWYG Markdown editor for IntelliJ-based IDEs.

For supported Markdown files and a supported JCEF runtime, it opens a custom editor, keeps the IntelliJ document and webview synchronized, and restores editor state such as scroll position, caret, and selection when reopening files.

MarkFlow supports Mermaid diagrams, KaTeX math, Markdown-aware paste, and raw HTML rendering with XSS sanitization, with configurable rendering options
for theme source, body font family and size, Mermaid sizing and zoom, error display, KaTeX density, and preview defaults.

The Leap product contract under #52/#78 additionally requires native/source-editor coexistence and safe fallback from JCEF failures; those requirements are not presented here as completed current-runtime behavior.
<!-- Plugin description end -->

## License

MarkFlow is licensed under the Apache License 2.0. See [LICENSE](LICENSE) for the full license text.

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
