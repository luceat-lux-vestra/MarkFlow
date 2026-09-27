<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# MarkFlow Changelog

## [Unreleased]

The unreleased line describes the current native-editor implementation. Browser-editor-only implementation bullets superseded by the completed Architecture Leap are not presented as current product behavior.

### Changed
- Completed the Architecture Leap to IntelliJ-native authoritative `Document`/`Editor` editing with source-neutral projection; the superseded browser editor/session/protocol stack is removed.
- Preserved original Markdown formatting during save by keeping the raw source text stable.
- Performance optimizations.
- Raised the minimum IDE to IntelliJ IDEA 2026.2 (`pluginSinceBuild` from `252` to `262`) and aligned the platform target and the Java toolchain (21 to 25) with it.
- IDE_SYNC now sends the captured IDE background, foreground, selection, and border palette through the runtime-settings contract; `LIGHT` and `DARK` remain explicit overrides.
- IDE_SYNC Mermaid appearance now derives arrow, node, and label colors from the captured palette and invalidates only when Mermaid-relevant palette values change.

### Fixed
- Native Markdown source editing, save, undo/redo, settings, and exact-source access remain available when optional JCEF renderer infrastructure is unavailable.
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
- WYSIWYG-first Markdown presentation on the normal IntelliJ platform text editor for `.md`, `.markdown`, `.mdown`, and `.mkdn` files while the IntelliJ `Document` remains authoritative.
- Native caret, selection, scroll, dirty/save, and undo/redo behavior through IntelliJ editor semantics.
- Mermaid diagram live preview support in Markdown code blocks.
- KaTeX math rendering support for inline and block formulas.
- Markdown clipboard paste now preserves Markdown structure, while code blocks keep the default paste behavior.
- Source-preserved raw HTML with isolated/sanitized derived presentation.
- Configurable MarkFlow settings for theme source, typography, Mermaid size/zoom/error behavior, and KaTeX density.
- Isolated renderer settings synchronization with stale-result rejection; Mermaid security is fixed fail-closed at `securityLevel: strict`.
- Host-owned local-image presentation and external navigation without granting the renderer ambient filesystem/navigation authority.
- Body font family (IDE default shown by its actual family name, plus installed families) and IntelliJ editor-range base font size (px)
  controls in the MarkFlow General settings, applied through Crepe's `--crepe-base-font-size`.
