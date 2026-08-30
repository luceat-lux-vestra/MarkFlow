# Theme Application Fix Plan — Approach C (IDE Palette Sync)

## Goal
Make the Milkdown Crepe preview track the **actual IDE color scheme**, not just a binary
light/dark switch. This is MarkFlow-public#1: the preview must reflect diverse IDE themes
(default, high-contrast, Solarized, user custom themes), with legibility preserved.

We accept that Crepe's theme is a fixed set of CSS variables, so "sync" means: **read the IDE's
semantic palette and remap it into Crepe's variables** via a curated, contrast-guarded mapping.
This is a design system, not a pixel copy.

## Architecture (layered)

```
┌─ Backend (Kotlin / EDT) ─────────────────────────────────┐
│  IDE is the color source of truth.                        │
│  Read EditorColorsScheme → stable key → "#RRGGBB" map.    │
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
│     - mapping table (SchemeColor key → Crepe var)         │
│     - derived colors (hover, on-secondary, …)             │
│     - WCAG contrast guards                                │
│     - IDE font families                                   │
└───────────────────────────────────────────────────────────┘
```

Design rule: **the IDE is an opaque color provider; the webview owns the design system.**
This keeps the mapping/contrast/font logic next to the CSS it feeds, and keeps the backend a
simple data source.

## Backend — color source of truth

File: `MarkFlowSettingsService.kt` (or a small `ThemePaletteProvider` service).

Read the active scheme once per push / per scheme change:

```kotlin
val scheme = EditorColorsManager.getInstance().globalScheme
// Semantic palette (2024.3+, stable through 2026.2):
val colors: Map<SchemeColor, Color> = scheme.schemeColors
// Fonts (SchemeFontAttributes: DEFAULT, CODE, TITLE …):
val defFont  = scheme.getFont(SchemeFontAttributes.DEFAULT)
val codeFont = scheme.getFont(SchemeFontAttributes.CODE)
val titleFont= scheme.getFont(SchemeFontAttributes.TITLE)
```

Emit a **stable-keyed** object (never raw `SchemeColor` enum names — those are an
implementation detail of the platform):

```json
"ideColorScheme": {
  "background": "#1e1e1e", "foreground": "#d4d4d4",
  "selectionBackground": "#3a3a5a", "selectionForeground": "#ffffff",
  "currentLineHighlight": "#262630", "lineHighlight": "#22222a",
  "textLink": "#6ea0ff", "textReference": "#8a8a9a",
  "textCode": "#f08a3c", "comment": "#7c7c8c",
  "border": "#3a3a44", "lineSeparator": "#2a2a30",
  "runErrorLineBar": "#e06c75",
  "defaultFont": "Inter", "codeFont": "JetBrains Mono", "titleFont": "Georgia"
}
```

Add `ideColorScheme: Map<String,String>?` to `MarkFlowRuntimeSettings`. Send it on every
settings push and on IDE scheme change (see wiring below).

**Open item (confirm at impl):** exact `SchemeColor` enum member spelling and the Kotlin
property name for `getSchemeColors()` in the resolved platform build. The mapping above uses
described members (`Foreground`, `Background`, `SelectionBackground`, `CurrentLineHighlight`,
`LineHighlight`, `TextLink`, `TextReference`, `TextCode`, `Comment`, `Border`,
`LineSeparator`, `RunErrorLineBarColor`). If a key is absent for a given theme, treat it as
"fall back to bundled value".

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
  background:        "--crepe-color-background",
  foreground:        "--crepe-color-on-background",
  currentLineHighlight: "--crepe-color-surface",
  lineHighlight:     "--crepe-color-surface-low",
  comment:           "--crepe-color-on-surface-variant",
  border:            "--crepe-color-outline",
  textLink:          "--crepe-color-primary",
  textReference:     "--crepe-color-secondary",
  textCode:          "--crepe-color-inline-code",
  runErrorLineBar:   "--crepe-color-error",
  selectionBackground: "--crepe-color-selected",
};
// on-surface / on-secondary / inverse pairs use foreground as seed, then guard.
// hover is derived from background.
```

### 3. Derived colors + contrast guards — `webview/src/app/crepe-theme.ts`
Build the CSS for `<style id="markflow-crepe-theme">` on `.milkdown`:

- **Direct vars:** look up each `DIRECT` source key in `ideColorScheme`; if present use it,
  else fall back to the bundled theme's value for that variable.
- **on-surface:** seed = foreground. If `contrast(foreground, surface) < 4.5`, adjust `surface`
  (darken if text is light, lighten if dark) until ≥ 4.5. This keeps body text readable on any theme.
- **on-secondary:** pick black/white that yields ≥ 4.5 against `secondary`; if neither, nudge
  `secondary` toward the chosen role. Never emit unreadable secondary text.
- **on-inverse / inverse:** inverse = background, on-inverse = foreground (already a safe pair).
- **hover:** `lighten(background, ~10%)`, then guarantee ≥ 3:1 against background (large-text
  threshold); clamp lightness so it stays a subtle hover.
- **inline-area:** reuse `selectionBackground`.
- **Fonts:** set `--crepe-font-default / -code / -title` from IDE font families; set root
  `font-size` from IDE default size. If an IDE font is unavailable, keep the bundled fallback.

Guards are the safety net that makes arbitrary themes usable: **a mapped pair that cannot reach
its contrast floor falls back to the bundled value for that pair only.** We never ship illegible
text; worst case a weird theme degrades to the bundled look for one or two variables.

### 4. Injection — same mechanism as the earlier light/dark toggle
- Keep the bundled light/dark Crepe CSS toggle (Layer 0, from shadows/structure needs).
- Replace/insert `<style id="markflow-crepe-theme">` with the IDE-derived block on every apply.
- Idempotent: re-running apply rewrites the same style element. Safe before `.milkdown` exists.

## Wiring

- `webview/src/app/crepe-theme.ts` exports `applyIdeTheme(shell: "light"|"dark",
  ideColors: Record<string,string>|null)`.
- Call it from `mermaid-renderer.ts:applyRuntimeSettingsFromHost()` (where settings already flow)
  and from `editor-session.ts:createCrepeInstance()` for the first instance.
- Backend: add `EditorColorsListener` (or `SchemeListener`) in `MarkFlowSettingsService`; on
  scheme change, push updated runtime settings (`ideColorScheme` + revision bump) to open
  webviews via `MarkFlowSharedBrowserService.notifyRuntimeSettingsChanged(forceReload = true)`.
  This makes IDE_SYNC follow live theme switches.

## Edge cases & fallbacks
- **Missing/null color** in the map → bundled value for that variable.
- **Contrast floor not reachable** → bundled value for that pair.
- **Empty `schemeColors`** (headless/test) → bundled light/dark shell only, no override.
- **Foreground ≈ background** theme → guards force readable text (degrades to bundled for text
  pairs). Documented, not a bug.
- **Custom font missing** → bundled fallback.

## Verification
- `runIde` with a **non-default theme** (Solarized / High Contrast / a user custom theme). Open a
  Markdown file with links, inline code, lists, selection, error text. Verify each tracks the IDE
  palette (link = `TextLink`, code accent = `TextCode`, selection = `SelectionBackground`, error
  = `RunErrorLineBarColor`).
- **Contrast guard:** apply a theme whose foreground≈background; confirm body text stays readable
  (guard kicked in).
- **Font mapping:** confirm default/code/title families track the IDE fonts.
- **Live switch:** change IDE theme while the editor is open → preview updates without reload.
- **Unit test** `color.ts`: contrast thresholds, `adjustForContrast` direction/clamp, `lighten`
  monotonicity. Deterministic, no IDE needed.
- `./gradlew check` and `npm run build` inside `webview/`.

## Scope decisions (for the reader)
- **In scope:** palette sync for colors + fonts, with contrast guards.
- **Deliberately out of scope:** matching IDE *text attributes* (bold/italic/underline effects),
  cursor blink, and per-language highlight semantics beyond the semantic palette. Those require
  deeper ProseMirror node styling work and are a separate effort.
- **Font sizes** come from the IDE default size (one value), not per-heading sizes.

## Assumptions
- Platform is 2026.2 (sinceBuild 262) — `schemeColors`, `schemeColor(SchemeColor)`,
  `getFont(SchemeFontAttributes)` are available.
- `globalScheme` reflects the active theme (matches `isDarkEditor`); local-only scheme overrides
  are rare and intentionally not targeted here.
- Sending raw ARGB hex is cheap; the mapping/contrast work happens once per theme change.

## Open items (confirm at implementation)
1. Exact `SchemeColor` enum member names in the resolved platform build.
2. Kotlin property name for `getSchemeColors()` (likely `schemeColors`).
3. `SchemeFontAttributes` member names (`DEFAULT`, `CODE`, `TITLE`).
4. Whether `globalScheme` vs `localScheme` matters for the target user base.
