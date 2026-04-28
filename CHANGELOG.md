<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# MarkFlow Changelog

## [Unreleased]

### Added
- Typora-style WYSIWYG Markdown editing experience via a custom IntelliJ `FileEditor`.
- Automatic takeover of Markdown files (`.md`, `.markdown`, `.mdown`, `.mkdn`) with the MarkFlow editor.
- Two-way synchronization between IntelliJ document text and the JCEF webview editor.
- Editor UI state persistence and restore (scroll position, cursor, and selection).
- Mermaid diagram live preview support in Markdown code blocks.
- KaTeX math rendering support for inline and block formulas.
- Markdown clipboard paste now preserves Markdown structure, while code blocks keep the default paste behavior.
- Packaged webview loading through classloader resources with shared local HTTP serving.
- Configurable MarkFlow settings panel (theme source and preview defaults).
- Extended Mermaid preview controls (size mode, zoom, error display behavior) and diagram security level configuration.
- Runtime settings synchronization from IntelliJ to webview with sequenced updates to reduce stale apply races.
- Shared JCEF browser pool with split-editor reuse, configurable max pool size, and configurable idle eviction timeout.
