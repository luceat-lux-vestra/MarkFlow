<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# MarkFlow Changelog

## [Unreleased]

### Changed
- Preserved original Markdown formatting during save by keeping the raw source text stable.
- Performance optimizations.
- Bumped `pluginSinceBuild` from `252` to `253` in `gradle.properties`.
- Removed the invalid JCEF module dependency from `plugin.xml` and aligned the platform target with IntelliJ IDEA 2025.3.

### Added
- Typora-style WYSIWYG Markdown editing experience via a custom IntelliJ `FileEditor`.
- Automatic takeover of Markdown files (`.md`, `.markdown`, `.mdown`, `.mkdn`) with the MarkFlow editor.
- Two-way synchronization between IntelliJ document text and the JCEF webview editor.
- Editor UI state persistence and restore (scroll position, cursor, and selection).
- Mermaid diagram live preview support in Markdown code blocks.
- KaTeX math rendering support for inline and block formulas.
- Markdown clipboard paste now preserves Markdown structure, while code blocks keep the default paste behavior.
- Raw HTML support for inline and block HTML with XSS sanitization via Milkdown integration.
- Packaged webview loading through classloader resources with shared local HTTP serving.
- Configurable MarkFlow settings panel (theme source and preview defaults).
- Extended Mermaid preview controls (size mode, zoom, error display behavior) and diagram security level configuration.
- Runtime settings synchronization from IntelliJ to webview with sequenced updates to reduce stale apply races.
- Pooled JCEF browsers with split-editor reuse and configurable idle eviction timeout.
