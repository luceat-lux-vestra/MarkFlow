# Native raw-HTML derived rendering

Status: production native derived-rendering contract from #149; #153 cutover is complete and #154 removed the superseded browser editor path.

## Authority

The IntelliJ `Document` remains the only mutable Markdown source authority. Raw HTML is supported as source-preserved content under the #78 contract, but its preview is untrusted derived presentation. Sanitization or rendering never rewrites Markdown source.

## Selected execution boundary

#149 uses a browser-free host renderer rather than extending the Mermaid/KaTeX runtime selected by #144.

```text
authoritative Document snapshot
        |
JetBrains Markdown parser ranges
        |
NativeRawHtmlProjection
        |
fail-closed sanitizer
        |
capability-free static HTML
        |
off-screen Swing HTML renderer
        |
bounded inert BufferedImage
        |
per-editor native fold/inlay
```

This is not a second Mermaid/KaTeX renderer and does not reopen #144. Mermaid and KaTeX continue to use their single extracted derived-renderer runtime. Raw HTML has different trust requirements and does not need JavaScript or browser layout authority for the accepted static preview subset.

No JCEF, browser navigation, filesystem resolver, remote resource loader, script engine, source serializer, host-to-web mutation protocol or editable HTML model participates in this path.

## Parser and source ranges

Raw-HTML ranges are never discovered by scanning arbitrary Markdown with an HTML regex.

- block HTML comes only from JetBrains Markdown `HTML_BLOCK` parser nodes;
- inline HTML starts from parser-proven `HTML_TAG` tokens inside one paragraph;
- inline opening/closing tokens must form an unambiguous balanced fragment;
- crossed, unclosed or otherwise ambiguous tags remain exact source;
- any parser failure leaves raw HTML as source.

A narrow tag-name classifier is applied only after the parser has established an exact HTML-tag token range. It is not a Markdown parser or source reconstruction mechanism.

## Sanitization policy

The sanitizer is deliberately stricter than general-purpose browser HTML sanitization because #82 does not grant raw HTML an ambient capability side path.

Accepted preview output contains only static formatting/table elements. Source attributes are not copied into the preview. The complete preview fails closed when input contains capability-bearing or active constructs, including:

- script/style/iframe/object/embed and equivalent active content;
- image/audio/video/source and other resource-loading elements;
- form controls/submission elements;
- SVG/canvas/MathML execution or rendering surfaces outside this contract;
- any `on*` event attribute;
- `style`;
- `href`, `src`, `srcset`, `action`, `poster`, `background`, `xlink:href` and equivalent navigation/resource attributes.

This means even an otherwise benign raw-HTML `href`/`src` is preview-blocked until an explicit host-mediated policy is implemented. Markdown links and document-relative Markdown images continue to use the dedicated #147 host-owned navigation/resource path. Raw HTML cannot bypass it.

Blocked preview is not an editor error: exact Markdown source remains visible and editable.

## Bounds

Sanitizer input and output are bounded to 64 KiB each and node processing is bounded. Native raster dimensions and pixel count are bounded before an artifact can be installed. Over-limit, malformed, unsupported or failed rendering degrades to exact source.

## Per-editor lifecycle and staleness

`NativeRawHtmlPresentationController` owns only its editor's derived presentation:

- one exact projection identity is retained at a time;
- a `Document` mutation immediately invalidates pending/installed raw-HTML presentation;
- late results from an invalidated generation cannot install;
- caret or selection touching a projected range removes its owned fold/inlay and reveals exact source;
- moving away may restore an already-current inert artifact;
- disposal removes owned folds/inlays and invalidates pending work;
- presentation install checks that source text and modification stamp remain unchanged.

## Production cutover boundary

#149 proves the target raw-HTML planner, trust boundary, renderer and per-editor presentation owner. It does **not** change the normal production editor provider. #153 must attach this owner to the selected production native presentation path and run the retained full-product acceptance/regression matrix before production cutover can complete.
