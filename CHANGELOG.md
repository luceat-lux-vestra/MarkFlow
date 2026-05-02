<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# MarkFlow Changelog

## [Unreleased]

### Changed
- Preserved original Markdown formatting during save by keeping the raw source text stable.
- Performance optimizations.

### Added
- Typora-style WYSIWYG Markdown editing experience via a custom IntelliJ `FileEditor`.
- Automatic takeover of Markdown files (`.md`, `.markdown`, `.mdown`, `.mkdn`) with the MarkFlow editor.
- Two-way synchronization between IntelliJ document text and the JCEF webview editor, now gated by a source-revision protocol to reject and recover from stale updates.
- Source-preserving Markdown stringify: original formatting (bullet style, fence marker, heading style, rule style, etc.) is kept byte-stable for unchanged blocks and recovered from nested/indented structures.
- Raw-source buffer that patches only modified top-level AST nodes so the surrounding Markdown text is left untouched.
- Editor UI state persistence and restore (scroll position, cursor, and selection).
- Mermaid diagram live preview support in Markdown code blocks.
- KaTeX math rendering support for inline and block formulas.
- Markdown clipboard paste now preserves Markdown structure, while code blocks keep the default paste behavior.
- Packaged webview loading through classloader resources with shared local HTTP serving.
- Configurable MarkFlow settings panel (theme source and preview defaults).
- Extended Mermaid preview controls (size mode, zoom, error display behavior) and diagram security level configuration.
- Runtime settings synchronization from IntelliJ to webview with sequenced updates to reduce stale apply races.
- Pooled JCEF browsers with split-editor reuse and configurable idle eviction timeout.
