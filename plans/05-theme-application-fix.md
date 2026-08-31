# Theme Application Fix Plan — Approach C (IDE Palette Sync)

## Goal
Make the Milkdown Crepe preview track the **captured IDE color scheme subset**, not just a binary
light/dark switch. This is MarkFlow-public#1: the preview should reflect the available IDE
background, foreground, selection, and border colors across diverse themes, with
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
  "border": "#3a3a44"
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
- `adjustForContrast(fg,bg,target)` → compare black/white contrast, move `fg` toward the better
  endpoint with a binary search, and verify the rounded CSS result. If the requested ratio is
  impossible for the background, return the highest-contrast endpoint.
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
};
// Surfaces, links, code/error, secondary, inverse, and hover are derived
// from the captured palette, then passed through contrast guards.
```

### 3. Derived colors + contrast guards — `webview/src/app/crepe-theme.ts`
Build the CSS for `<style id="markflow-crepe-theme">` on `.milkdown`:

- **Direct vars:** look up the supported `DIRECT` source keys (`background`, `foreground`,
  `selectionBackground`, and `border`) in `ideColorScheme`; if present use them, else fall back to
  the bundled/derived value for that variable. The body foreground is contrast-guarded against the
  background. `selectionForeground` is applied to the browser text selection pseudo-element and is
  contrast-guarded against `selectionBackground`.
- **Surfaces:** derive `surface`, `surface-low`, hover, and inline-area by a small light/dark shift
  from the captured background. Derive on-surface and inline-code text from the captured foreground
  with contrast guards.
- **on-secondary:** derive the secondary accent from the captured foreground/background, then pick
  the better black/white endpoint and verify the requested ratio.
- **on-inverse / inverse:** use the captured foreground as the inverse surface and adjust the
  captured background for readable inverse text.
- **Fonts:** a selected installed `fontFamily` sets `--crepe-font-default` and
  `--crepe-font-title`; an empty selection resolves to `ideFontFamily`, the active IDE editor
  family. The code font remains Crepe's bundled code family. `baseFontSizePx` comes from MarkFlow's
  IntelliJ editor-supported setting and is applied to body text and headings. If the IDE family is
  unavailable, keep the bundled fallback.

Contrast guards are the safety net that makes arbitrary themes usable. A pair that already meets
the requested ratio keeps the captured value; otherwise the foreground moves toward the endpoint
with the greatest WCAG contrast. When the requested ratio is impossible, the best endpoint is used
and the limitation is explicit rather than silently claiming an invariant that cannot hold.

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
- **Contrast floor not reachable** → highest-contrast black/white endpoint for that pair.
- **Empty or invalid `ideColorScheme`** (headless/test) → bundled light/dark shell only, no palette override.
- **Foreground ≈ background** theme → guards move text toward the higher-contrast endpoint; if the
  requested floor is impossible, the best available endpoint is used. Documented, not a bug.
- **Custom font missing** → the backend omits the custom override and the webview uses the active IDE
  font; if that is unavailable, use the CSS/browser fallback stack.

## Verification
- `runIde` with a **non-default theme** (Solarized / High Contrast / a user custom theme). Open a
  Markdown file with links, inline code, lists, selection, and error text. Verify the supported
  background, foreground, selection, and border colors track the captured IDE
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
- `./gradlew check`, `npm run test:source`, and `npm run build` inside `webview/`.

## Scope decisions (for the reader)
- **In scope:** palette sync for colors + fonts, with contrast guards.
- **Deliberately out of scope:** matching IDE *text attributes* (bold/italic/underline effects),
  cursor blink, and per-language highlight semantics beyond the semantic palette. Those require
  deeper ProseMirror node styling work and are a separate effort.
- **Font sizes** use one logical base value constrained by IntelliJ's `EditorFontsConstants` range;
  they are not per-heading settings. The existing persisted default remains compatible with older
  MarkFlow settings, while normalization and the settings UI use the platform range.

## Assumptions
- Platform is 2026.2 (sinceBuild 262); the implementation uses `EditorColors` ColorKeys,
  `EditorFontType.PLAIN`, and `EditorFontsConstants` available in the resolved target.
- `globalScheme` reflects the active theme (matches `isDarkEditor`); local-only scheme overrides
  are rare and intentionally not targeted here.
- Sending raw ARGB hex is cheap; the mapping/contrast work happens once per theme change.

## Resolved implementation notes

1. The active `globalScheme` is the source for the palette and editor font.
2. The webview receives `ideColorScheme`, `ideFontFamily`, `ideDark`, and the persisted
   `baseFontSizePx` as separate runtime-settings fields. Persisted custom families are resolved
   against the installed-family list before they are sent; an unavailable value becomes empty and
   therefore inherits the IDE family.
3. IDE scheme changes use a non-reloading runtime-settings push; the webview replaces the same
   style element so open editors update in place.
