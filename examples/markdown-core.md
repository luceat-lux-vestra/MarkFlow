# MarkFlow Core Markdown Manual Acceptance

This file is a focused manual acceptance surface for ordinary Markdown. It complements `eg2.md`:
`eg2.md` is a broad mixed stress document, while this file keeps representative lexical variants
close together so source reveal, editing, save/reopen, and visual projection are easier to inspect.

## Headings

# ATX H1
## ATX H2
### ATX H3
#### ATX H4
##### ATX H5
###### ATX H6

Closing ATX heading ##

Setext H1
=========

Setext H2
---------

## Inline formatting

Plain text with *emphasis*, **strong**, ***combined emphasis***, ~~strikethrough~~, and `inline code`.

Delimiter preservation probes: _underscore emphasis_, __underscore strong__, and `code with **literal markers**`.

Escapes and entities: \*literal asterisk\*, \_literal underscore\_, &amp;, &lt;, &#35;, and &#x3A;.

## Links and references

Inline link: [Markdown Guide](https://www.markdownguide.org/ "Markdown Guide").

Reference link: [JetBrains docs][jb-docs].

Collapsed reference: [MarkFlow reference][].

Autolink: <https://example.com/markflow-manual-check>.

[jb-docs]: https://plugins.jetbrains.com/docs/intellij/welcome.html "IntelliJ Platform docs"
[MarkFlow reference]: https://github.com/luceat-lux-vestra/MarkFlow

## Blockquotes

> First-level quote.
>
> > Nested quote.
> >
> > - Nested quoted list item
> > - Another quoted item
>
> Back to first level.

## Lists

- Dash item
- Dash item with continuation
  on the next indented line.
  - Nested dash
  - Nested dash 2

* Asterisk item
* Asterisk item 2

+ Plus item
+ Plus item 2

1. Ordered item
2. Ordered item
   1. Nested ordered
   2. Nested ordered 2

- [x] Completed task
- [ ] Pending task
- [ ] Another pending task with **formatting**

## Tables

| left | center | right |
| :--- | :----: | ---: |
| plain | centered | 100 |
| escaped \| pipe | *emphasis* | `code \| literal` |
| [link](https://example.com/table) | **strong** | final |

no-leading | two
--- | -----
value | spaced

## Code forms

Inline `code span` remains distinct from surrounding Markdown.

```text
three-backtick fence
**Markdown markers are literal here**
```

~~~~text
four-tilde fence
# heading marker is literal here
~~~~

````text
longer backtick fence can contain ``` safely
````

    indented code line one
    indented code line two

## Line and thematic breaks

Soft line break starts here
and continues on the next source line.

Hard line break using backslash starts here\
and this line must remain distinct.

---

***

___

## Mixed editing boundary

Before **bold** / `inline code` / [link](https://example.com/boundary) / after.

When manually validating:
- enter and leave projected constructs and confirm exact Markdown markers are recoverable;
- edit one construct and verify unrelated lexical choices above are not normalized;
- toggle a task item and verify only its checkbox marker changes;
- save/reopen and verify source remains unchanged except for intentional edits;
- verify malformed or unsupported extensions degrade to editable source rather than disappearing.

## Explicit unsupported-extension probe

Footnote-like syntax remains a source-preservation probe rather than a support claim.[^manual-note]

[^manual-note]: MarkFlow currently classifies this extension as unsupported in the differential corpus.
