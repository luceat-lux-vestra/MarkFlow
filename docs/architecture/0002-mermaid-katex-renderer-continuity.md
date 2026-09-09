# ADR 0002: Preserve Mermaid and KaTeX renderer continuity across native-editor migration

- Status: Accepted
- Date: 2026-09-07
- Accepted by: PR #142 (effective on merge)
- Owners: @luceat-lux-vestra
- Related issues/PRs: #52, #78, #81, #82, #83, #84, #99, #139, #140, #141, PR #142

## Context

ADR 0001 removes JCEF/JavaScript from the **editing correctness path** by selecting an IntelliJ-native editor over the authoritative `Document`.

That decision does **not** remove MarkFlow's supported derived-rendering capabilities. The Leap product contract explicitly requires:

- Mermaid fenced code as a supported derived preview;
- inline and display math with KaTeX-compatible semantics as a supported derived preview;
- source-preserved degradation when either renderer fails.

Current `main` already carries maintained JavaScript renderer dependencies:

- `mermaid` `11.17.2`;
- `katex` `^0.18.5`.

The current Mermaid implementation also contains MarkFlow-specific renderer behavior worth preserving independently of the editor shell: Mermaid configuration, IDE theme/palette mapping, security level, size/zoom semantics, cache identity, stale-render protection, error behavior, and fidelity fixtures. However `MarkFlowMermaidRenderer` currently mixes those concerns with Crepe/CodeMirror preview callbacks, `crepeSessionId`, DOM preview registries, visibility observers, editor telemetry, and editor-session lifecycle.

KaTeX is currently less isolated: Crepe's `Latex` feature owns the actual rendering integration, while MarkFlow provides KaTeX CSS/settings and product behavior. Replacing Crepe therefore requires a thin direct KaTeX adapter, but **not** a new math-rendering implementation.

Both upstream libraries expose renderer APIs independent of an editor engine: Mermaid renders a diagram definition to SVG, and KaTeX renders TeX to HTML/string output. Therefore editor migration and renderer replacement are separate decisions.

## Decision

Mermaid and KaTeX remain **mandatory supported target capabilities** through the native-editor migration.

The migration must preserve the existing renderer engines and extract them from editor-specific integration before deleting the old editor path.

### Renderer continuity invariant

The native-editor migration must not introduce a second Mermaid or KaTeX implementation merely because the presentation surface changes.

During migration:

- Mermaid rendering continues to use the maintained Mermaid JavaScript package unless a separate renderer-replacement ADR proves a replacement is superior and capability-compatible;
- math rendering continues to use KaTeX unless a separate renderer-replacement ADR proves otherwise;
- current MarkFlow Mermaid theme/security/size/error/cache semantics are retained or deliberately changed through an explicit product decision;
- fidelity fixtures for Mermaid success/error and math success/error remain target evidence;
- no production state should render the same Mermaid/math block through two independent rendering engines.

Temporary A/B or differential rendering is allowed only as test/evidence instrumentation and must not become a permanent production path.

### Target renderer decomposition

The target separates renderer **core**, renderer **execution**, and editor **presentation**.

```text
MarkdownProjectionEngine
        │ bounded Mermaid/math source + source/config identity
        ▼
DerivedRendererService
        │
        ├── MermaidRendererCore
        │      └── Mermaid JS -> SVG or typed failure
        │
        └── KatexRendererCore
               └── KaTeX -> HTML/render tree or typed failure

Renderer execution adapter
        │ isolated runtime if DOM/layout is required
        ▼
InertRenderArtifact
        │
        ▼
Native editor block/inline inlay
```

Responsibilities:

- `MermaidRendererCore` consumes diagram source + resolved render settings and produces SVG or a typed failure. It has no Crepe/CodeMirror/editor-session dependency.
- `KatexRendererCore` consumes TeX source + display/settings and calls KaTeX directly. It has no Crepe dependency.
- a renderer execution adapter provides DOM/layout only when the upstream renderer requires it;
- the editor receives only an inert artifact or typed failure and never exposes a general web application as the editing surface.

Names above describe responsibilities, not mandatory class/file names.

### Initial execution strategy

The migration baseline should **reuse the existing TypeScript/Vite renderer stack** and, where browser DOM/layout is required, run it in an isolated renderer runtime rather than rewrite Mermaid/KaTeX in Kotlin/JVM.

An isolated JCEF off-screen renderer is an acceptable initial execution adapter because:

- JCEF is already a supported dependency in the current plugin;
- IntelliJ exposes maintained off-screen rendering APIs;
- Mermaid is browser/DOM-oriented and returns SVG;
- KaTeX can produce HTML/string output but its visual result depends on KaTeX CSS/fonts;
- this keeps renderer compatibility work below the `DerivedRendererService` boundary.

This does **not** make JCEF part of editor correctness. Renderer startup/failure may delay or degrade a specific rich preview, but it cannot block typing, save, undo, caret/selection, or exact-source editing.

A later implementation may replace the execution adapter with a non-JCEF host renderer if that is measurably simpler/better, but editor migration must not require that replacement.

### Existing Mermaid code classification direction

Before #141's exact file/function inventory, the architectural classification is:

#### RETAIN / EXTRACT

- Mermaid package/version compatibility and supported diagram behavior;
- `createMermaidPreviewConfig`-class configuration;
- IDE theme/palette derivation relevant to Mermaid output;
- diagram security configuration;
- size/zoom/error-display product semantics;
- cache-key inputs such as diagram content + theme/palette/security/size identity;
- stale-result rejection as an invariant;
- Mermaid success/error fixtures and representative diagram corpus.

#### REPLACE / DELETE after extraction

- `createCodeMirrorFeatureConfig` and other Crepe/CodeMirror adapter entry points;
- `crepeSessionId` as renderer correctness identity;
- DOM preview callback registries owned by editor widgets;
- IntersectionObserver/visibility scheduling that only exists for the old editor DOM;
- browser editor telemetry/lifecycle coupling;
- mutation/readiness protocol used to keep a web editor synchronized with IntelliJ `Document`.

Renderer caching/scheduling may be reimplemented only where the new renderer service needs different ownership semantics; the old implementation is evidence, not an obligation to preserve its shape.

### Existing KaTeX code classification direction

KaTeX support must not disappear with Crepe.

#### RETAIN / EXTRACT

- KaTeX package and supported KaTeX-compatible semantics;
- KaTeX CSS/font assets required for faithful rendering;
- display-density setting and relevant theme/font integration;
- math success/error fidelity fixtures.

#### REPLACE

- `Crepe.Feature.Latex` integration is replaced by a direct KaTeX renderer adapter (`render`/`renderToString`-class API) behind the derived-render boundary.

This adapter is glue around KaTeX, not a reimplementation of TeX layout.

## No-double-work migration order

The production migration order must be:

1. **extract/prove renderer cores while the current editor still works;**
2. prove Mermaid and KaTeX outputs/failure semantics against the existing fidelity corpus and representative examples;
3. introduce the native projection/inlay consumer of the same renderer service;
4. cut the production consumer from Crepe/CodeMirror to native inlays;
5. only then delete old editor-specific renderer adapters and editor runtime;
6. remove Node/TypeScript/Vite/JCEF pieces only if no retained renderer consumer requires them.

Do **not** implement a native Mermaid engine or a native TeX engine as part of editor migration.

Do **not** delete the current renderer implementation first and recreate its behavior later.

## Security and lifecycle

The extracted renderer boundary is narrower than the current browser editor:

- renderer input is a bounded Mermaid/math fragment plus non-secret render settings;
- renderer output must be converted to an inert host-presentable artifact before entering native editor presentation;
- generated Mermaid interaction hooks are not automatically activated;
- renderer execution has no document mutation authority;
- renderer execution has no ambient external navigation or arbitrary filesystem capability;
- network access is denied by default;
- stale source/config generation results are discarded;
- renderer runtime ownership/cancellation is independent of editor source ownership.

KaTeX error messages can include source content; logging remains redacted/bounded and rendered errors must escape untrusted source correctly.

## Consequences

### Positive

- Native editor migration does not throw away working Mermaid/KaTeX capability.
- We remove the editing browser boundary without paying for a renderer-engine rewrite.
- Mermaid and KaTeX stay on their maintained upstream implementations.
- Renderer behavior can be tested independently of editor lifecycle.
- The old editor can be deleted after a consumer cutover instead of after a feature rewrite.

### Negative / trade-offs

- TypeScript/Vite and possibly JCEF remain as renderer infrastructure even after browser editing disappears.
- Existing Mermaid code requires extraction because renderer and editor concerns are currently mixed.
- KaTeX needs a new thin direct adapter because current rendering is provided by Crepe.
- Native inlays need an inert-artifact presentation/capture strategy and accessibility fallback.

These costs are intentionally smaller than reimplementing the renderer engines.

## Required evidence

Before the old editor renderer path is deleted, prove:

- Mermaid representative corpus renders with no unapproved capability regression;
- Mermaid syntax failures preserve exact source and degrade deterministically;
- KaTeX inline/display success cases render with KaTeX-compatible output;
- malformed math preserves exact source and degrades deterministically;
- IDE theme/palette and Mermaid size/zoom/error-display settings preserve approved behavior;
- KaTeX display density preserves approved behavior;
- stale renderer results cannot overwrite newer source/config presentation;
- renderer runtime failure leaves native source editing fully usable;
- no renderer request can mutate the `Document`;
- renderer runtime cannot navigate/fetch/access files outside explicit policy;
- repeated renderer/runtime disposal does not leak JCEF/browser/resources if JCEF remains the execution adapter.

## Supersession

This ADR corrects any reading of ADR 0001 or #140 that would treat Mermaid/KaTeX support, their maintained renderer engines, or the complete TypeScript renderer stack as collateral deletion targets of the browser-editor migration.

Editor architecture and derived-renderer implementation are separate replacement decisions.