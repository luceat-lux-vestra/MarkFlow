# Markdown Raw HTML Support Example

This file is the manual raw-HTML acceptance surface for MarkFlow. It intentionally contains both
supported static presentation candidates and content that must degrade to exact editable source.

The expected outcome is **not** "render every browser HTML feature." Raw HTML remains untrusted
Markdown source. MarkFlow may present only the bounded inert subset allowed by the native raw-HTML
renderer; unsafe, capability-bearing, unsupported, malformed, or ambiguous HTML must stay visible as
exact source.

When checking a release candidate, test this file in the real MarkFlow editor rather than relying
only on synthetic fixtures.

## Static Inline HTML — Rich Preview Candidates

Each marker below should remain byte-for-byte intact in the Markdown source. When the caret is away
from the fragment, the safe static subset should receive derived presentation; entering the fragment
must reveal the exact source again.

Plain span: <span>MF-SAFE-SPAN</span>

Attribute stripping probe: <span class="highlight" data-kind="safe">MF-SAFE-ATTR-SPAN</span>

Semantic inline formatting:
- <strong>MF-SAFE-STRONG</strong>
- <b>MF-SAFE-BOLD</b>
- <em>MF-SAFE-EMPHASIS</em>
- <i>MF-SAFE-ITALIC</i>
- <u>MF-SAFE-UNDERLINE</u>
- <s>MF-SAFE-STRIKE</s>
- <del>MF-SAFE-DELETE</del>
- <code>MF-SAFE-CODE</code>
- <kbd>MF-SAFE-KBD</kbd>
- H<sub>2</sub>O
- x<sup>2</sup>
- <span><strong>MF-SAFE-NESTED</strong></span>
- <a>MF-SAFE-ANCHOR-TEXT-WITHOUT-HREF</a>

## Layout-sensitive Inline HTML

A standalone top-level `<br/>` cannot be represented faithfully by the current native inline-raster
projection: rendering the tag in isolation produces no text-bearing artifact and cannot move the
following native-editor text to a new visual line. It must therefore stay as exact editable source
rather than disappearing behind a blank inlay.

MF-BR-BEFORE<br/>MF-BR-AFTER

A break nested inside one balanced HTML fragment is different: the outer fragment owns both text
runs and the break, so the inert raster can preserve that internal layout.

<span>MF-SAFE-BR-BEFORE<br/>MF-SAFE-BR-AFTER</span>

For release acceptance, verify the first case remains visibly editable source and the second case
renders as one source-neutral fragment with an internal line break.

## Static Block HTML — Rich Preview Candidates

<div class="callout">
  <p>MF-SAFE-BLOCK-PARAGRAPH</p>
  <p>Nested <strong>MF-SAFE-BLOCK-STRONG</strong> and <em>MF-SAFE-BLOCK-EMPHASIS</em>.</p>
</div>

<blockquote>
  <p>MF-SAFE-HTML-BLOCKQUOTE</p>
</blockquote>

<table>
  <caption>MF-SAFE-TABLE-CAPTION</caption>
  <thead>
    <tr><th>Key</th><th>Value</th></tr>
  </thead>
  <tbody>
    <tr><td>alpha</td><td>MF-SAFE-TABLE-VALUE</td></tr>
  </tbody>
</table>

## Capability-bearing HTML — Expected Exact-source Fallback

These cases deliberately request navigation, resources, active style, or event capability. They must
**not** become an ambient browser side path. The exact Markdown source must remain visible/editable.

<a href="https://example.com" target="_blank">MF-BLOCKED-HREF</a>

<img src="https://example.com/markflow.png" alt="MF-BLOCKED-REMOTE-IMAGE">

<div style="color:red">MF-BLOCKED-STYLE</div>

<div onclick="alert('markflow')">MF-BLOCKED-EVENT-HANDLER</div>

## Unsupported HTML Semantics — Expected Exact-source Fallback

These common browser elements are retained to make unsupported coverage visible during release
testing. Do not silently treat their presence as successful HTML rendering.

<details open>
<summary>MF-UNSUPPORTED-SUMMARY</summary>
<p>MF-UNSUPPORTED-DETAILS-BODY</p>
</details>

<section aria-label="demo section">
  <h2>MF-UNSUPPORTED-SECTION-TITLE</h2>
  <p>MF-UNSUPPORTED-SECTION-BODY</p>
</section>

<mark>MF-UNSUPPORTED-MARK</mark>

## HTML Inside Markdown Lists

- Normal list item
- Safe inline HTML in a list: <kbd>MF-SAFE-LIST-KBD</kbd>
- Safe wrapped fragment: <span data-kind="note">MF-SAFE-LIST-SPAN</span>
- Blocked navigation fragment: <a href="https://example.com/list">MF-BLOCKED-LIST-HREF</a>

## Malformed / Ambiguous Inline HTML — Expected Exact-source Fallback

Unclosed fragment: <span>MF-MALFORMED-UNCLOSED

Crossed fragment: <span><strong>MF-MALFORMED-CROSSED</span></strong>

## Save / Reveal / Round-trip Checks

- Move the caret into each safe projected fragment and confirm its exact HTML source is revealed.
- Move away and confirm the supported static preview returns without changing source bytes.
- Edit surrounding Markdown and verify unrelated HTML presentation does not oscillate or disappear.
- Save and reopen the file; compare the file bytes/source text before and after.
- Copy/paste representative safe, blocked, unsupported, and malformed fragments.
- Confirm blocked/unsupported HTML remains ordinary editable source rather than a broken blank inlay.
- Confirm no HTML case can initiate navigation, filesystem/network access, script, style, or events
  without an explicitly host-owned capability.
