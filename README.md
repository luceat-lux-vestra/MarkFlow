# MarkFlow - Seamless WYSIWYG Markdown Editor

![Build](https://github.com/luceat-lux-vestra/MarkFlow/workflows/Build/badge.svg)
[![Version](https://img.shields.io/badge/Marketplace-pending-lightgrey)](https://plugins.jetbrains.com/)

MarkFlow is an IntelliJ IDEA plugin that provides a Typora-style Markdown editing experience using a JCEF webview and Milkdown.

## Features

- Typora-style WYSIWYG Markdown editing in a custom IntelliJ `FileEditor`
- Automatic takeover for Markdown files (`.md`, `.markdown`, `.mdown`, `.mkdn`)
- Two-way IntelliJ <-> Webview synchronization via `JBCefJSQuery`
- Editor UI state restore (scroll position, cursor, selection)
- Mermaid live preview in code blocks with rendering controls
- KaTeX rendering for inline and block math expressions
- Markdown-aware clipboard paste (with safe fallback in code blocks)
- Force re-render action/shortcut (`Cmd/Ctrl+Alt+Shift+R`) for Mermaid and KaTeX previews
- Frontend build integrated into Gradle plugin tasks

## Options

You can configure these in `Settings > Tools > MarkFlow`.

- **General:** Theme source, Render trigger (LIVE/DEBOUNCED/MANUAL_REFRESH), Render debounce (ms), Preview only by default, Force Re-render shortcut on/off
- **Mermaid:** Diagram size mode, Diagram zoom (%), Error display behavior
- **KaTeX:** Display density
- **Advanced:** Diagram security level (STRICT/LOOSE)

## Operational tips

- If you often use split editors or open many Markdown files at once, increase **Browser pool size** slightly so a spare JCEF instance is available without waiting for a full recreate.
- If MarkFlow feels too heavy when many tabs sit idle, reduce **Idle browser eviction delay (ms)** so unused pooled browsers are cleaned up sooner.
- For the smoothest split-editor switching, keep the pool size at least as large as the number of concurrently visible Markdown panes you use most often.
- The first Markdown tab still pre-warms one browser on startup, so a pool size of `1` is the lightest configuration and works well for single-editor workflows.

<!-- Plugin description -->
( markdown, mermaid, latex-katex, wysiwyg )
MarkFlow is a lightweight WYSIWYG Markdown editor for IntelliJ-based IDEs.
It uses a hybrid architecture: Kotlin + IntelliJ Platform on the backend, and TypeScript + Milkdown in a JCEF webview on the frontend.
It provides two-way IntelliJ/Webview synchronization, editor state restoration, Mermaid and KaTeX preview support, and configurable rendering options for fast IDE-native Markdown workflows.
<!-- Plugin description end -->

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
