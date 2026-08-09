# Calculator — supported operations

What the math engine offers, per object. Source: `app/src/main/python/math_engine.py` (`_classify`
picks the buttons, `_execute` / `_execute_matrix` run them). Kotlin bridge: `math/SympyMathEngine.kt`.

Buttons are listed in display order. A row with a condition only appears when the condition holds.

Every table below was checked against the running engine on 2026-08-09, not read off the source.

## Run automatically

Once a formula is understood, the panel runs one action without being asked — the first of
`solve`, `evaluate`, `simplify` that the object offers (`AUTOMATIC_MATH_ACTIONS` in `ui/NotesApp.kt`).

| Object | Runs |
|---|---|
| Equation, Relation | Solve |
| Integral, Derivative, Unevaluated operation | Evaluate |
| Expression | Simplify |
| Matrix | nothing — no arbitrary default |

Tapping a different action afterwards sticks; the automatic run fires once per analysis, not per tap.

## How input is classified

| Parsed as                 | Shown as                |
| ------------------------- | ----------------------- |
| `MatrixBase`              | `Matrix · R × C`        |
| `Integral`                | `Integral`              |
| `Derivative`              | `Derivative`            |
| `Sum`, `Product`, `Limit` | `Unevaluated operation` |
| `Equality`                | `Equation`              |
| other `Relational`        | `Relation`              |
| anything else             | `Expression`            |

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

## Integral · Derivative · Unevaluated operation

| Button   | Condition | Result                  |
| -------- | --------- | ----------------------- |
| Evaluate | —         | `simplify(expr.doit())` |

Covers `Sum`, `Product` and `Limit` as well as integrals and derivatives.

## Limits

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

## Input

LaTeX. Matrices accept `bmatrix`, `pmatrix`, `matrix`, `Bmatrix`, `vmatrix`, `Vmatrix`, `smallmatrix`
and `array`, optionally wrapped in `\left[…\right]`, `\left(…\right)` or `\left\{…\right\}`.
`\limits` / `\nolimits` are stripped before parsing — SymPy's grammar rejects them and they carry no
mathematics.

## Adding an operation

Two edits, and they must agree on the id string:

1. `_classify` — append `_action("id", "Label")` under the right branch and condition.
2. `_execute` or `_execute_matrix` — add the `if action_id == "id"` branch.
