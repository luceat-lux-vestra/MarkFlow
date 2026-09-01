<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# MarkFlow Changelog

## [Unreleased]

### Changed
- Preserved original Markdown formatting during save by keeping the raw source text stable.
- Performance optimizations.
- Raised the minimum IDE to IntelliJ IDEA 2026.2 (`pluginSinceBuild` from `252` to `262`) and aligned the platform target and the Java toolchain (21 to 25) with it.
- IDE_SYNC now sends the captured IDE background, foreground, selection, and border palette through the runtime-settings contract; `LIGHT` and `DARK` remain explicit overrides.
- IDE_SYNC Mermaid appearance now derives arrow, node, and label colors from the captured palette and invalidates only when Mermaid-relevant palette values change.

### Fixed
- Skipped browser pre-warm and editor takeover when JCEF is unavailable in the IDE runtime instead of failing with an error.
- The body font family is now a dropdown of installed families (single value); the IDE-configured
  font is the default and is shown as `IDE Default (<actual family>)`; an explicit selection of
  the same installed family remains distinct. The webview quotes the family so a persisted value
  cannot break out of the CSS `font-family` value.
- Ensured runtime font variables override Crepe's bundled declarations so changing the body font
  takes effect in the editor.
- Propagated the configured base font size from settings into the webview and clamped it to the IntelliJ editor-supported range.
- Direct font-size input now marks the settings panel as modified so Apply becomes available.
- Unavailable persisted font families now fall back to the active IDE editor font without requiring
  a settings round-trip; text contrast selection now uses the higher WCAG black/white endpoint.

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
- Body font family (IDE default shown by its actual family name, plus installed families) and IntelliJ editor-range base font size (px)
  controls in the MarkFlow General settings, applied through Crepe's `--crepe-base-font-size`.
