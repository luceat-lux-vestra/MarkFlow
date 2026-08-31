# Theme Application Fix Plan — Approach C (IDE Palette Sync)

## Goal
Make the Milkdown Crepe preview track the **captured IDE color scheme subset**, not just a binary
light/dark switch. This is MarkFlow-public#1: the preview should reflect the available IDE
background, foreground, selection, current-line, and border colors across diverse themes, with
legibility preserved.

We accept that Crepe's theme is a fixed set of CSS variables, so "sync" means: **read the IDE's
semantic palette and remap it into Crepe's variables** via a curated, contrast-guarded mapping.
This is a design system, not a pixel copy.

## Architecture (layered)

```
┌─ Backend (Kotlin / EDT) ─────────────────────────────────┐
│  IDE is the color source of truth.                        │
│  Read EditorColors ColorKeys → stable key → "#RRGGBB" map.│
│  Send it to the webview as part of runtime settings.      │
│  Push on IDE scheme change (listener).                    │
└───────────────────────────────────────────────────────────┘
                          │  runtimeSettings.ideColorScheme
                          ▼
┌─ Webview (TS) ───────────────────────────────────────────┐
│  Layer 0 (build-time): bundled light OR dark Crepe CSS    │
│     (toggle by dark/light). Provides shadows, structure.  │
│  Layer 1 (runtime): <style id="markflow-crepe-theme">      │
│     IDE-derived color overrides + font overrides.         │
│     - mapping table (stable IDE key → Crepe var)          │
│     - derived colors (hover, on-secondary, …)             │
│     - WCAG contrast guards                                │
│     - IDE font families                                   │
└───────────────────────────────────────────────────────────┘
```

Design rule: **the IDE is an opaque color provider; the webview owns the design system.**
This keeps the mapping/contrast/font logic next to the CSS it feeds, and keeps the backend a
simple data source.

## Backend — color source of truth

Implemented in: `MarkFlowIdeThemeService.kt`; `MarkFlowSettingsService.kt` includes the captured
palette, IDE font family, and resolved appearance in every runtime-settings payload.

Read the active scheme once per push / per scheme change:

```kotlin
val manager = EditorColorsManager.getInstance()
val scheme = manager.globalScheme
// Stable EditorColors ColorKeys are captured into our own string-keyed map.
val editorFontFamily = scheme.getFont(EditorFontType.PLAIN).family
val ideDark = manager.isDarkEditor
```

Emit a **stable-keyed** object (never platform enum names — those are an
implementation detail of the platform):

```json
"ideColorScheme": {
  "background": "#1e1e1e", "foreground": "#d4d4d4",
  "selectionBackground": "#3a3a5a", "selectionForeground": "#ffffff",
  "currentLineHighlight": "#262630", "border": "#3a3a44"
},
"ideFontFamily": "JetBrains Mono",
"ideDark": true
```

Add `ideColorScheme: Map<String,String>?` to `MarkFlowRuntimeSettings`. Send it on every
settings push and on IDE scheme change (see wiring below).

Implementation note: platform 2026.2 does not expose the planned `schemeColors` /
`SchemeColor` API in this target, so `MarkFlowIdeThemeService` reads stable `EditorColors`
`ColorKey`s and emits only values available from the active scheme. Missing keys fall back to the
bundled value for that variable.

## Webview — design system

### 1. Color math util — `webview/src/app/color.ts`
Pure, unit-testable functions:
- `parseHex("#RRGGBB") → {r,g,b}` (also accept `#RGB` and `rgba()`).
- `relativeLuminance({r,g,b})` and `contrastRatio(a,b)` (WCAG 2.x).
- `meetsAA(fg,bg, ratio=4.5)` / `meetsAALarge(...,3.0)`.
- `adjustForContrast(fg,bg,target)` → move `fg` toward black/white until `contrastRatio ≥ target`,
  clamped so we never invert the role (light text on light bg).
- `lighten(c, pct)` / `darken(c, pct)` (HSL) for derived colors.

### 2. Mapping table — `webview/src/app/crepe-theme-mapping.ts`
Data-driven, so it is readable and testable:

```ts
// source key (from backend) → Crepe variable
const DIRECT: Record<string, string> = {
  background:          "--crepe-color-background",
  foreground:          "--crepe-color-on-background",
  selectionBackground: "--crepe-color-selected",
  border:              "--crepe-color-outline",
  textLink:            "--crepe-color-primary", // optional when supplied
};
// Surfaces, code/error, secondary, inverse, current-line, and hover are derived
// from the captured palette, then passed through contrast guards.
```

### 3. Derived colors + contrast guards — `webview/src/app/crepe-theme.ts`
Build the CSS for `<style id="markflow-crepe-theme">` on `.milkdown`:

- **Direct vars:** look up the supported `DIRECT` source keys (`background`, `foreground`,
  `selectionBackground`, `border`, and an optional `textLink`) in `ideColorScheme`; if present use
  them, else fall back to the bundled/derived value for that variable.
- **on-surface:** seed = foreground. If `contrast(foreground, surface) < 4.5`, adjust `surface`
  (darken if text is light, lighten if dark) until ≥ 4.5. This keeps body text readable on any theme.
- **on-secondary:** pick black/white that yields ≥ 4.5 against `secondary`; if neither, nudge
  `secondary` toward the chosen role. Never emit unreadable secondary text.
- **on-inverse / inverse:** inverse = background, on-inverse = foreground (already a safe pair).
- **hover:** `lighten(background, ~10%)`, then guarantee ≥ 3:1 against background (large-text
  threshold); clamp lightness so it stays a subtle hover.
- **inline-area:** reuse `selectionBackground`.
- **Fonts:** a selected installed `fontFamily` sets `--crepe-font-default` and
  `--crepe-font-title`; an empty selection resolves to `ideFontFamily`, the active IDE editor
  family. The code font remains Crepe's bundled code family. `baseFontSizePx` comes from MarkFlow's
  10–32 px setting and is applied to body text and headings. If the IDE family is unavailable,
  keep the bundled fallback.

Guards are the safety net that makes arbitrary themes usable: **a mapped pair that cannot reach
its contrast floor falls back to the bundled value for that pair only.** We never ship illegible
text; worst case a weird theme degrades to the bundled look for one or two variables.

### 4. Injection — same mechanism as the earlier light/dark toggle
- Keep the bundled light/dark Crepe CSS toggle (Layer 0, from shadows/structure needs).
- Replace/insert `<style id="markflow-crepe-theme">` with the IDE-derived block on every apply.
- Idempotent: re-running apply rewrites the same style element. Safe before `.milkdown` exists.

## Wiring

- `webview/src/app/crepe-theme.ts` exports `applyRuntimeAppearance(settings)`.
- Call it from `mermaid-renderer.ts:applyRuntimeSettingsFromHost()` (where settings already flow)
  and from `editor-session.ts` after the first Crepe instance starts.
- `MarkFlowIdeThemeService` owns the `EditorColorsListener`; on scheme change it refreshes the
  captured palette/font and pushes updated runtime settings with a revision bump through
  `MarkFlowSharedBrowserService.notifyRuntimeSettingsChanged(forceReload = false)`.
  This makes IDE_SYNC follow live theme switches.

## Edge cases & fallbacks
- **Missing/null color** in the map → bundled value for that variable.
- **Contrast floor not reachable** → bundled value for that pair.
- **Empty `ideColorScheme`** (headless/test) → bundled light/dark shell only, no palette override.
- **Foreground ≈ background** theme → guards force readable text (degrades to bundled for text
  pairs). Documented, not a bug.
- **Custom font missing** → CSS/browser fallback stack.

## Verification
- `runIde` with a **non-default theme** (Solarized / High Contrast / a user custom theme). Open a
  Markdown file with links, inline code, lists, selection, and error text. Verify the supported
  background, foreground, selection, current-line, and border colors track the captured IDE
  palette; derived link/code/error colors remain readable under the contrast guards.
- **Contrast guard:** apply a theme whose foreground≈background; confirm body text stays readable
  (guard kicked in).
- **Font mapping:** confirm the settings default shows the actual IDE family name, the empty
  selection uses that family in the editor, and an installed selection changes body/title text.
- **Font-size input:** type a valid size directly into the field and confirm Apply becomes enabled
  and the editor updates after applying.
- **Live switch:** change IDE theme while the editor is open → preview updates without reload.
- **Unit test** `color.ts`: contrast thresholds, `adjustForContrast` direction/clamp, `lighten`
  monotonicity. Deterministic, no IDE needed.
- `./gradlew check` and `npm run build` inside `webview/`.

## Scope decisions (for the reader)
- **In scope:** palette sync for colors + fonts, with contrast guards.
- **Deliberately out of scope:** matching IDE *text attributes* (bold/italic/underline effects),
  cursor blink, and per-language highlight semantics beyond the semantic palette. Those require
  deeper ProseMirror node styling work and are a separate effort.
- **Font sizes** come from MarkFlow's configured base size (one value, 10–32 px), not per-heading
  settings or the IDE editor size.

## Assumptions
- Platform is 2026.2 (sinceBuild 262); the implementation uses `EditorColors` ColorKeys and
  `EditorFontType.PLAIN` available in the resolved target.
- `globalScheme` reflects the active theme (matches `isDarkEditor`); local-only scheme overrides
  are rare and intentionally not targeted here.
- Sending raw ARGB hex is cheap; the mapping/contrast work happens once per theme change.

## Resolved implementation notes

1. The active `globalScheme` is the source for the palette and editor font.
2. The webview receives `ideColorScheme`, `ideFontFamily`, `ideDark`, and the persisted
   `baseFontSizePx` as separate runtime-settings fields.
3. IDE scheme changes use a non-reloading runtime-settings push; the webview replaces the same
   style element so open editors update in place.
