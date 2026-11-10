# MarkFlow KaTeX / LaTeX-style Math Manual Acceptance

MarkFlow supports **KaTeX-compatible inline and display math** as derived presentation. The Markdown
source remains authoritative. Production KaTeX uses fail-soft rendering (`throwOnError: false`):
malformed formulas may produce an inert `katex-error` presentation rather than a renderer-status
failure. Entering the projected formula must still reveal the exact source, and a malformed formula
must not break unrelated formulas or ordinary Markdown editing.

## Inline math

Basic: $a^2 + b^2 = c^2$.

Fraction and root: $x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}$.

Greek letters: $\alpha + \beta + \gamma = \pi$.

Operators: $\sin^2 x + \cos^2 x = 1$ and $\log_b x = \frac{\ln x}{\ln b}$.

Subscript/superscript: $a_{n+1} = r a_n$.

Vector/accent: $\vec{v} = (v_1, v_2)$ and $\overline{AB}$.

Text inside math: $\text{rate} = \frac{\text{requests}}{\text{second}}$.

## Display math

Integral:

$$
\int_0^1 x^2 \, dx = \frac{1}{3}
$$

Summation:

$$
\sum_{k=1}^{n} k = \frac{n(n+1)}{2}
$$

Aligned equations:

$$
\begin{aligned}
f(x) &= x^2 + 2x + 1 \\
     &= (x + 1)^2
\end{aligned}
$$

Matrix:

$$
A =
\begin{bmatrix}
1 & 2 \\
3 & 4
\end{bmatrix}
$$

Cases:

$$
f(x) =
\begin{cases}
x^2, & x \ge 0 \\
-x,  & x < 0
\end{cases}
$$

Large delimiters:

$$
\left( \frac{a+b}{c+d} \right)^2
$$

Maxwell-style multiline expression:

$$
\begin{aligned}
\nabla \cdot \mathbf{E} &= \frac{\rho}{\varepsilon_0} \\
\nabla \cdot \mathbf{B} &= 0
\end{aligned}
$$

## Mixed prose and multiple formulas

The energy relation $E = mc^2$ appears inline before a display equation:

$$
E_k = \frac{1}{2}mv^2
$$

and prose continues immediately afterward with another inline expression, $p = mv$.

## Intentional renderer-error probes

The following inputs are intentionally malformed. Under the current production contract they should
produce a bounded fail-soft `katex-error` presentation. The exact malformed source must remain
recoverable on reveal/edit, and one malformed formula must not hide unrelated Markdown or later
valid formulas.

Malformed inline math: $\frac{1}{$.

Malformed display math:

$$
\frac{1}{2
$$

A valid formula after the failures must still render: $\sqrt{16} = 4$.

## Manual checks

- Click/enter every rendered formula and confirm the exact `$...$` or `$$...$$` source is revealed.
- Move away and confirm valid formulas render again without changing source bytes.
- Confirm malformed formulas show the fail-soft error presentation, reveal exact editable source on entry, and do not suppress later valid formulas.
- Switch Light → Dark → Light and confirm formulas re-render without stale/duplicate inlays.
- Save/reopen after editing one formula and verify unrelated math source remains lexically unchanged.
