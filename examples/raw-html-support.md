# Markdown Raw HTML Support Example

This file is for testing Markdown body raw HTML round-trip behavior in MarkFlow.

## Inline HTML

This sentence has an inline <span class="highlight">HTML span</span> plus a line break<br/>right here.

You can also mix inline HTML with links like <a href="https://example.com" target="_blank">Example Site</a>.

## Block HTML

<details open>
<summary>Click to expand</summary>

<div class="callout">
  <p>This content should stay as raw HTML in the Markdown source.</p>
  <p>It can include nested <strong>formatting</strong> and <em>emphasis</em>.</p>
</div>

</details>

## HTML Inside Lists

- Normal list item
- Item with inline HTML: <kbd>Ctrl</kbd> + <kbd>S</kbd>
- Item with a wrapped HTML fragment:

  <span data-kind="note">This should remain intact when the document is saved.</span>

## Mixed Content

Paragraph before a block HTML fragment.

<section aria-label="demo section">
  <h2>Section Title</h2>
  <p>Another paragraph with <code>inline code</code> inside HTML.</p>
</section>

Paragraph after the block HTML fragment.

## Quick Checks

- Edit the surrounding Markdown and make sure the HTML blocks stay in place.
- Save and reopen the file to confirm raw HTML source is preserved.
- Try copy/paste of a raw HTML fragment into this document.
