# Calculator — supported operations

What the math engine offers, per object. Source: `app/src/main/python/math_engine.py` (`_classify`
picks the buttons, `_execute` / `_execute_matrix` run them). Kotlin bridge: `math/SympyMathEngine.kt`.

Buttons are listed in display order. A row with a condition only appears when the condition holds.

All supported operations are on `app/build/python/pip/debug/common/sympy/parsing/latex/LaTeX.g4`

## Run automatically

Once a formula is understood, the panel runs one action without being asked — the first of
`solve`, `evaluate`, `simplify` that the object offers (`AUTOMATIC_MATH_ACTIONS` in `ui/NotesApp.kt`).

| Object                                              | Runs                           |
| --------------------------------------------------- | ------------------------------ |
| Equation, Relation                                  | Solve                          |
| Integral, Derivative, Unevaluated operation, Series | Evaluate                       |
| Expression                                          | Simplify                       |
| Matrix                                              | nothing — no arbitrary default |

Tapping a different action afterwards sticks; the automatic run fires once per analysis, not per tap.

## How input is classified

| Parsed as                          | Shown as                |
| ---------------------------------- | ----------------------- |
| `MatrixBase`                       | `Matrix · R × C`        |
| `Integral`                         | `Integral`              |
| `Derivative`                       | `Derivative`            |
| `Sum` over an infinite limit       | `Series`                |
| `Sum` (finite), `Product`, `Limit` | `Unevaluated operation` |
| `Equality`                         | `Equation`              |
| other `Relational`                 | `Relation`              |
| anything else                      | `Expression`            |

## Matrix

| Button            | Condition     | Result                                                            |
| ----------------- | ------------- | ----------------------------------------------------------------- |
| Transpose         | —             | `Mᵀ`                                                              |
| RREF              | —             | reduced row-echelon form                                          |
| Rank              | —             | integer                                                           |
| Normalize matrix  | —             | divided by Frobenius norm; errors on the zero matrix              |
| Normalize rows    | —             | each row divided by its own norm; errors on a zero row            |
| Convert to linear | cols ≥ 2      | system of equations — augmented if cols = rows + 1, else set to 0 |
| Determinant       | square        | factored                                                          |
| Inverse           | square        | errors if singular                                                |
| Eigenvalues       | square, ≤ 4×4 | values with multiplicities                                        |
| Null space        | —             | basis; message if only the zero vector                            |

## Expression

| Button        | Condition                      | Result                         |
| ------------- | ------------------------------ | ------------------------------ |
| Simplify      | —                              | `simplify`                     |
| Factor        | ≥ 1 symbol, polynomial in them | `factor`                       |
| Expand        | —                              | `expand`                       |
| Differentiate | exactly 1 symbol               | `d/dx`                         |
| Integrate     | exactly 1 symbol               | antiderivative, `+ C` appended |
| Graph         | exactly 1 symbol               | plot                           |
| Decimal       | no symbols                     | `N(expr, 12)`                  |

## Equation / Relation

| Button   | Condition                          | Result                                |
| -------- | ---------------------------------- | ------------------------------------- |
| Solve    | 1–3 symbols                        | solution dicts; message if none found |
| Simplify | —                                  | `simplify`                            |
| Factor   | ≥ 1 symbol, `lhs − rhs` polynomial | `factor(lhs − rhs) = 0`               |
| Graph    | 1 symbol, or `y = f(x)` form       | plot                                  |

**A quadratic's two roots print once.** SymPy returns them as two solutions differing in one
character — `x = -5 - 2\sqrt5` and `x = -5 + 2\sqrt5` — and the list of both is wider than the pane
it is drawn in. It is shown as `x = -5 \pm 2\sqrt{5}`, which is what the quadratic formula produced
before SymPy split it, and how the pair is written by hand.

The pair folds when its **centre is rational and its spread is not** — `\pm 2\sqrt5`, `\pm 2i`,
`\pm\sqrt{a}` alike, since an imaginary spread is no more rational than a surd. Everything else
prints as the list SymPy returned: rational roots such as 1 and 2, which would fold to the puzzle
`\frac{3}{2} \pm \frac{1}{2}`; a pair like 0 and `\sqrt2`, whose halves share nothing and whose
folded form invents a `\frac{\sqrt2}{2}` in neither root; a single root; three or more roots; and a
solution in more than one unknown.

**A result too wide for the pane is drawn smaller**, down to 40% of the normal size, and what still
does not fit at that floor scrolls sideways. Nothing is wrapped: a renderer breaking a formula where
it does not know the terms end reads worse than a small formula.

## Integral · Derivative · Unevaluated operation

| Button   | Condition | Result                  |
| -------- | --------- | ----------------------- |
| Evaluate | —         | `simplify(expr.doit())` |

Covers finite `Sum`, `Product` and `Limit` as well as integrals and derivatives.

## Series $\sum$

A `Sum` with one index running to infinity. Evaluate still applies and still runs automatically;
Convergence is the added button.

| Button      | Condition | Result                                                                               |
| ----------- | --------- | ------------------------------------------------------------------------------------ |
| Evaluate    | —         | `simplify(expr.doit())` — the closed form, where one exists                          |
| Convergence | —         | Diverges · Converges · Converges absolutely · Converges conditionally · Undetermined |

**SymPy exposes no individually addressable convergence test.** `Sum.is_convergent()` walks a fixed
cascade internally and returns one boolean without saying which test decided it. The order, read out
of the 1.14.0 source, is:

> divergence → p-series → comparison → limit comparison → ratio → Raabe → root → alternating series
> → integral → Dirichlet → bounded-times-convergent

The first branch that reaches a verdict wins and the rest never run. There is no `ratio_test()` or
`root_test()` to call, so the panel reports a verdict and never names a test — naming one would be a
guess. "Converges conditionally" is derived: convergent, not absolutely so.

**A divergence verdict is withheld for trigonometric summands.** Traced through 1.14.0: the p-series
branch _discards the bounded factor_ and decides on the `1/n^p` remainder alone, and it sits ahead of
Dirichlet in the cascade, so Dirichlet never runs. For p > 1 that is harmless — Σsin(n)/n² returns
`True` correctly. For p ≤ 1 it is wrong on exactly the interesting cases: Σsin(n)/n and Σcos(n)/n
both converge and both return `False`, identically to the genuinely divergent Σsin(n). Those report
`Undetermined` rather than stating a false theorem. A `True` is unaffected, because the discarded
factor only ever helps convergence.

Absolute convergence for those summands is decided by bounding `|sin|` and `|cos|` by 1 and testing
what remains, which is a valid comparison and avoids the ~5 s `is_absolutely_convergent` spends on
Σcos(n)/n² before raising. Only the positive answer is a proof; an inconclusive comparison adds
nothing rather than downgrading the verdict.

Measured on the bundled corpus, desktop CPython: every case above lands under 80 ms.

## Limits $\lim_{x\to\infty}$

| Limit           | Value                                        |
| --------------- | -------------------------------------------- |
| Source length   | 1200 chars                                   |
| Expression size | 700 nodes                                    |
| Matrix size     | 8 × 8                                        |
| Solve           | ≤ 3 symbols                                  |
| Eigenvalues     | ≤ 4 × 4                                      |
| Graph domain    | x ∈ [−10, 10], 321 samples                   |
| Graph range     | \|y\| ≤ 1 000 000; non-finite points dropped |

Exceeding one is an error message in the panel, not a crash.

# Defaults

Trigonometric functions `Decimal` always assume **Radians** . To calculate Degrees use $^{\circ}$ for example
$\sin(32^{\circ})$

## Supported functions

| Trigger  | function |
| -------- | -------- |
| $\Gamma$ | Gamma    |
| $\Beta$  | Beta     |

## Input

LaTeX. Matrices accept `bmatrix`, `pmatrix`, `matrix`, `Bmatrix`, `vmatrix`, `Vmatrix`, `smallmatrix`
and `array`, optionally wrapped in `\left[…\right]`, `\left(…\right)` or `\left\{…\right\}`.
`\limits` / `\nolimits` are stripped before parsing — SymPy's grammar rejects them and they carry no
mathematics.

**`e` and `\pi` are bound to Euler's number and π after parsing.** `parse_latex` returns them as
ordinary symbols, so before this every constant was a free variable: `\sin(\pi)` stayed `sin(pi)`
instead of `0`, and `\sum e^{-7n}` looked like a two-variable series, which made `is_convergent`
refuse it outright. A formula that genuinely uses `e` as an unknown is therefore read as the
constant — the deliberate trade, since in recognized handwriting `e` is the constant far more often,
and inferring it from context would make the same formula mean different things on different pages.

`i` is deliberately **not** bound. It is a summation or matrix index far more often than it is the
imaginary unit, and binding it would silently rewrite `\sum_{i=1}^{n}`.

## Adding an operation

Two edits, and they must agree on the id string:

1. `_classify` — append `_action("id", "Label")` under the right branch and condition.
2. `_execute` or `_execute_matrix` — add the `if action_id == "id"` branch.
