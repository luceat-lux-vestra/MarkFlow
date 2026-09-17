# MarkFlow Production Wiring

This fixture proves the #153 production opening path owns native presentation consumers.

Inline math: $a^2 + b^2 = c^2$.

$$
\int_0^1 x^2\,dx = \frac{1}{3}
$$

```mermaid
graph TD
    A --> B
```

Local image consumer: ![missing local proof](production-wiring-missing.png)

External navigation consumer: [example](https://example.invalid/production-wiring)

Inline <span data-kind="safe">HTML content</span> remains authoritative source.

<div class="callout">
<p>Block HTML content remains authoritative source too.</p>
</div>
