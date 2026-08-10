"""Bounded SymPy operations exposed to viveNotes through a JSON-only API.

The Kotlin side never evaluates Python source. It supplies editable LaTeX, receives a structural
classification, and may invoke only the operation identifiers returned by ``analyze``.
"""

import json
import math
import re
from typing import Any

import sympy as sp
from sympy.core.relational import Relational
from sympy.matrices.matrixbase import MatrixBase
from sympy.parsing.latex import parse_latex


#: What every operation hands back to Kotlin before it is serialized: ``title``, ``latex``, and
#: optionally ``message`` or ``graph``. Values are mixed, so ``Any`` is honest rather than lazy here.
ResultPayload = dict[str, Any]

#: One entry in the action list ``analyze`` returns — ``{"id": ..., "label": ...}``.
Action = dict[str, str]

#: What ``_parse`` can hand back. A matrix is **not** an ``Expr`` in SymPy — ``MatrixBase`` sits
#: outside that hierarchy — which is exactly why so many helpers below branch on ``isinstance``.
#: Spelling the union out makes those branches look deliberate instead of defensive.
ParsedInput = sp.Expr | MatrixBase

MAX_SOURCE_CHARS = 1200
MAX_EXPRESSION_NODES = 700
MAX_MATRIX_EDGE = 8
MAX_PLOT_ABS_Y = 1_000_000.0
PLOT_POINT_COUNT = 321


def _action(action_id: str, label: str) -> Action:
    return {"id": action_id, "label": label}


def _json(payload: dict[str, Any]) -> str:
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def _friendly_error(error: BaseException) -> str:
    if isinstance(error, MathInputError):
        return str(error)
    return "SymPy could not interpret this LaTeX. Check the recognized source and try again."


class MathInputError(ValueError):
    pass


def analyze(source: str) -> str:
    """Return normalized LaTeX and only the actions which make sense for this expression."""
    try:
        expression = _parse(source)
        summary, actions = _classify(expression)
        return _json(
            {
                "normalizedLatex": sp.latex(expression),
                "summary": summary,
                "variables": _variables(expression),
                "actions": actions,
            }
        )
    except Exception as error:  # Expected parse failures are a normal editable-UI state.
        return _json({"error": _friendly_error(error)})


def execute(source: str, action_id: str) -> str:
    """Execute one allow-listed operation and return renderable LaTeX or sampled graph points."""
    try:
        expression = _parse(source)
        available = {item["id"] for item in _classify(expression)[1]}
        if action_id not in available:
            raise MathInputError("That operation does not apply to this expression.")
        return _json(_execute(expression, action_id))
    except Exception as error:
        return _json({"error": _friendly_error(error)})


def _parse(source: str) -> ParsedInput:
    if not isinstance(source, str):
        raise MathInputError("The recognized formula is not text.")
    source = source.strip()
    if not source:
        raise MathInputError("Enter LaTeX to see math actions.")
    if len(source) > MAX_SOURCE_CHARS:
        raise MathInputError("This formula is too long to evaluate safely on this device.")
    source = _normalize_latex(source)

    expression = _parse_matrix(source)
    if expression is None:
        # strict=True prevents the ANTLR backend from accepting only a valid prefix such as `x -`.
        expression = parse_latex(source, backend="antlr", strict=True)
    expression = _bind_constants(expression)
    _check_complexity(expression)
    return expression


#: LaTeX names the ANTLR backend hands back as ordinary symbols rather than the constants they
#: denote. ``i`` is deliberately absent: it is a summation or matrix index far more often than it is
#: the imaginary unit, and binding it would silently rewrite ``\sum_{i=1}^{n}``.
_CONSTANT_SYMBOLS: dict[str, sp.Expr] = {"e": sp.E, "pi": sp.pi}


def _bind_constants(expression: ParsedInput) -> ParsedInput:
    """Bind ``e`` and ``\\pi`` to Euler's number and π.

    ``parse_latex`` returns ``Symbol('e')`` and ``Symbol('pi')``, so without this every constant is
    a free variable: ``\\sin(\\pi)`` stays ``sin(pi)`` instead of ``0``, and ``\\sum e^{-7n}`` looks
    like a two-variable series, which is what made ``is_convergent`` refuse it outright.

    A formula that genuinely uses ``e`` as a variable is therefore read as Euler's number. That is
    the deliberate trade: in recognized handwriting ``e`` is the constant far more often than it is
    an unknown, and the alternative — guessing from context — would make the same formula mean
    different things on different pages.
    """
    substitutions: list[tuple[sp.Symbol, sp.Expr]] = [
        (symbol, _CONSTANT_SYMBOLS[str(symbol)])
        for symbol in _symbols(expression)
        if str(symbol) in _CONSTANT_SYMBOLS
    ]
    return expression.subs(substitutions) if substitutions else expression


_LIMIT_PLACEMENT_DIRECTIVE = re.compile(r"\\(?:no)?limits(?![A-Za-z])\s*")


def _normalize_latex(source: str) -> str:
    """Remove TeX-only layout directives which do not change the represented mathematics."""
    # FormulaNet can emit ``\int\limits_0^1``. ``\limits`` and ``\nolimits`` only control where
    # TeX draws an operator's bounds, but SymPy's ANTLR grammar rejects them. Keeping the integral,
    # sum, or other operator and its following bounds is therefore a semantics-preserving adapter.
    return _LIMIT_PLACEMENT_DIRECTIVE.sub("", source)


_MATRIX_ENVIRONMENT = re.compile(
    r"\\begin\{(?P<env>bmatrix|pmatrix|matrix|Bmatrix|vmatrix|Vmatrix|smallmatrix)\}"
    r"(?P<body>.*?)\\end\{(?P=env)\}",
    re.DOTALL,
)
_ARRAY_ENVIRONMENT = re.compile(
    r"\\begin\{array\}(?:\{[^{}]*\})?(?P<body>.*?)\\end\{array\}",
    re.DOTALL,
)


def _parse_matrix(source: str) -> MatrixBase | None:
    """Parse the matrix environments emitted by FormulaNet but unsupported by parse_latex."""
    cleaned: str = source.strip()
    wrappers: tuple[tuple[str, str], ...] = (
        (r"\left[", r"\right]"),
        (r"\left(", r"\right)"),
        (r"\left\{", r"\right\}"),
    )
    for prefix, suffix in wrappers:
        if cleaned.startswith(prefix) and cleaned.endswith(suffix):
            cleaned = cleaned[len(prefix) : -len(suffix)].strip()
            break

    match = _MATRIX_ENVIRONMENT.fullmatch(cleaned) or _ARRAY_ENVIRONMENT.fullmatch(cleaned)
    if match is None:
        return None

    row_sources: list[str] = _split_matrix_rows(match.group("body"))
    if not row_sources:
        raise MathInputError("The recognized matrix has no rows.")
    rows: list[list[sp.Expr]] = []
    width: int | None = None
    for row_source in row_sources:
        row_source = row_source.replace(r"\hline", "").strip()
        cells: list[str] = [cell.strip() for cell in _split_top_level(row_source, "&")]
        if any(not cell for cell in cells):
            raise MathInputError("The recognized matrix contains an empty cell.")
        if width is None:
            width = len(cells)
        if len(cells) != width:
            raise MathInputError("Every recognized matrix row must have the same number of cells.")
        rows.append([parse_latex(cell, backend="antlr", strict=True) for cell in cells])

    if len(rows) > MAX_MATRIX_EDGE or (width or 0) > MAX_MATRIX_EDGE:
        raise MathInputError(f"Matrices are limited to {MAX_MATRIX_EDGE} × {MAX_MATRIX_EDGE}.")
    return sp.Matrix(rows)


def _split_matrix_rows(body: str) -> list[str]:
    parts: list[str] = []
    start: int = 0
    depth: int = 0
    index: int = 0
    while index < len(body):
        character: str = body[index]
        if character == "{":
            depth += 1
        elif character == "}":
            depth = max(0, depth - 1)
        elif character == "\\" and index + 1 < len(body) and body[index + 1] == "\\" and depth == 0:
            parts.append(body[start:index].strip())
            index += 2
            # TeX permits optional row spacing after `\\`, for example `\\[3pt]`.
            spacing = re.match(r"\s*\[[^]]*\]", body[index:])
            if spacing:
                index += spacing.end()
            start = index
            continue
        index += 1
    parts.append(body[start:].strip())
    return [part for part in parts if part]


def _split_top_level(source: str, delimiter: str) -> list[str]:
    parts: list[str] = []
    start: int = 0
    depth: int = 0
    for index, character in enumerate(source):
        if character == "{":
            depth += 1
        elif character == "}":
            depth = max(0, depth - 1)
        elif character == delimiter and depth == 0:
            parts.append(source[start:index])
            start = index + 1
    parts.append(source[start:])
    return parts


def _check_complexity(expression: ParsedInput) -> None:
    values: list[sp.Expr] = list(expression) if isinstance(expression, MatrixBase) else [expression]
    nodes: int = sum(1 for value in values for _ in sp.preorder_traversal(value))
    if nodes > MAX_EXPRESSION_NODES:
        raise MathInputError("This formula is too complex to evaluate safely on this device.")


def _variables(expression: ParsedInput) -> list[str]:
    symbols: set[sp.Symbol] = set()
    if isinstance(expression, MatrixBase):
        for value in expression:
            symbols.update(value.free_symbols)
    else:
        symbols.update(expression.free_symbols)
    return [str(symbol) for symbol in sorted(symbols, key=sp.default_sort_key)]


def _symbols(expression: ParsedInput) -> list[sp.Symbol]:
    symbols: set[sp.Symbol] = set()
    if isinstance(expression, MatrixBase):
        for value in expression:
            symbols.update(value.free_symbols)
    else:
        symbols.update(expression.free_symbols)
    return sorted(symbols, key=sp.default_sort_key)


def _classify(expression: ParsedInput) -> tuple[str, list[Action]]:
    if isinstance(expression, MatrixBase):
        rows: int
        columns: int
        rows, columns = expression.shape
        actions: list[Action] = [
            _action("matrix_transpose", "Transpose"),
            _action("matrix_rref", "RREF"),
            _action("matrix_rank", "Rank"),
            _action("matrix_normalize", "Normalize matrix"),
            _action("matrix_normalize_rows", "Normalize rows"),
        ]
        if columns >= 2:
            actions.append(_action("matrix_linear_system", "Convert to linear"))
        if rows == columns:
            actions.extend(
                [
                    _action("matrix_determinant", "Determinant"),
                    _action("matrix_inverse", "Inverse"),
                ]
            )
            if rows <= 4:
                actions.append(_action("matrix_eigenvalues", "Eigenvalues"))
        actions.append(_action("matrix_nullspace", "Null space"))
        return f"Matrix · {rows} × {columns}", actions

    symbols: list[sp.Symbol] = _symbols(expression)
    if isinstance(expression, sp.Integral):
        return "Integral", [_action("evaluate", "Evaluate")]
    if isinstance(expression, sp.Derivative):
        return "Derivative", [_action("evaluate", "Evaluate")]
    if isinstance(expression, sp.Sum):
        actions = [_action("evaluate", "Evaluate")]
        if _infinite_series_index(expression) is not None:
            actions.append(_action("series_convergence", "Convergence"))
            return "Series", actions
        return "Unevaluated operation", actions
    if isinstance(expression, (sp.Product, sp.Limit)):
        return "Unevaluated operation", [_action("evaluate", "Evaluate")]

    if isinstance(expression, Relational):
        actions = [_action("simplify", "Simplify")]
        if symbols and len(symbols) <= 3:
            actions.insert(0, _action("solve", "Solve"))
        polynomial: sp.Expr = sp.expand(expression.lhs - expression.rhs)
        if symbols and polynomial.is_polynomial(*symbols):
            actions.append(_action("factor", "Factor"))
        if _graph_expression(expression) is not None:
            actions.append(_action("graph", "Graph"))
        relation: str = "Equation" if isinstance(expression, sp.Equality) else "Relation"
        return relation, actions

    actions = [_action("simplify", "Simplify"), _action("expand", "Expand")]
    if symbols and expression.is_polynomial(*symbols):
        actions.insert(1, _action("factor", "Factor"))
    if len(symbols) == 1:
        actions.extend(
            [
                _action("differentiate", "Differentiate"),
                _action("integrate", "Integrate"),
                _action("graph", "Graph"),
            ]
        )
    if not symbols:
        actions.append(_action("decimal", "Decimal"))
    return "Expression", actions


def _execute(expression: ParsedInput, action_id: str) -> ResultPayload:
    if isinstance(expression, MatrixBase):
        return _execute_matrix(expression, action_id)

    symbols: list[sp.Symbol] = _symbols(expression)
    if action_id == "solve":
        result = sp.solve(expression, symbols, dict=True)
        return _latex_result("Solutions", result, "No symbolic solution was found." if not result else None)
    if action_id == "simplify":
        result = sp.simplify(expression)
        return _latex_result("Simplified", result)
    if action_id == "factor":
        if isinstance(expression, Relational):
            result = sp.Eq(sp.factor(expression.lhs - expression.rhs), 0)
        else:
            result = sp.factor(expression)
        return _latex_result("Factored", result)
    if action_id == "expand":
        return _latex_result("Expanded", sp.expand(expression))
    if action_id == "differentiate":
        variable = symbols[0]
        return _latex_result(f"Derivative with respect to {variable}", sp.diff(expression, variable))
    if action_id == "integrate":
        variable = symbols[0]
        result = sp.integrate(expression, variable)
        payload = _latex_result(f"Antiderivative with respect to {variable}", result)
        payload["latex"] += r" + C"
        return payload
    if action_id == "evaluate":
        return _latex_result("Evaluated", sp.simplify(expression.doit()))
    if action_id == "series_convergence":
        return _series_convergence(expression)
    if action_id == "decimal":
        return _latex_result("Decimal approximation", sp.N(expression, 12))
    if action_id == "graph":
        return _graph_result(expression)
    raise MathInputError("That operation is not supported.")


def _execute_matrix(matrix: MatrixBase, action_id: str) -> ResultPayload:
    if action_id == "matrix_transpose":
        return _latex_result("Transpose", matrix.T)
    if action_id == "matrix_rref":
        return _latex_result("Reduced row-echelon form", matrix.rref()[0])
    if action_id == "matrix_rank":
        return _latex_result("Rank", matrix.rank())
    if action_id == "matrix_determinant":
        return _latex_result("Determinant", sp.factor(matrix.det()))
    if action_id == "matrix_inverse":
        if matrix.det().equals(0) is True:
            raise MathInputError("This matrix is singular, so it has no inverse.")
        return _latex_result("Inverse", matrix.inv())
    if action_id == "matrix_eigenvalues":
        return _latex_result("Eigenvalues and multiplicities", matrix.eigenvals())
    if action_id == "matrix_nullspace":
        result = matrix.nullspace()
        return _latex_result("Null-space basis", result, "The null space contains only the zero vector." if not result else None)
    if action_id == "matrix_normalize":
        norm = sp.simplify(matrix.norm())
        if norm.equals(0) is True:
            raise MathInputError("The zero matrix cannot be normalized.")
        # `applyfunc` wants a callable, which a named `def` satisfies as well as a lambda would.
        def scaled(value: sp.Expr) -> sp.Expr:
            return sp.simplify(value / norm)

        return _latex_result("Normalized by the Frobenius norm", matrix.applyfunc(scaled))
    if action_id == "matrix_linear_system":
        return _linear_system(matrix)
    if action_id == "matrix_normalize_rows":
        normalized_rows: list[list[sp.Expr]] = []
        for row_index in range(matrix.rows):
            row = matrix.row(row_index)
            norm = sp.simplify(row.norm())
            if norm.equals(0) is True:
                raise MathInputError(f"Row {row_index + 1} is zero and cannot be normalized.")
            normalized_rows.append([sp.simplify(value / norm) for value in row])
        return _latex_result("Rows normalized independently", sp.Matrix(normalized_rows))
    raise MathInputError("That matrix operation is not supported.")


def _linear_system(matrix: MatrixBase) -> ResultPayload:
    """Write a matrix out as the system of linear equations it stands for.

    **Which reading is used depends on the shape, and the result says which.** An ``n x (n+1)``
    matrix is the classic augmented form, so the last column is the constants on the right. Anything
    else is read as a plain coefficient matrix and every equation is set to zero, which is the same
    system ``matrix_nullspace`` already answers for. Guessing silently between the two would be the
    real trap here, so the reading is named in the returned message.
    """
    rows: int
    columns: int
    rows, columns = matrix.shape
    augmented: bool = columns == rows + 1
    unknown_count: int = columns - 1 if augmented else columns
    unknowns: list[sp.Symbol] = [sp.Symbol(name) for name in _unknown_names(unknown_count, matrix)]

    equations: list[sp.Equality] = []
    for row_index in range(rows):
        row = matrix.row(row_index)
        left = sp.Add(*[row[index] * unknowns[index] for index in range(unknown_count)])
        right = row[columns - 1] if augmented else sp.Integer(0)
        # ``evaluate=False`` only on the equality, never on the sum. A row of zeros makes ``Eq(0, 0)``,
        # which SymPy folds to the boolean ``True`` and prints as the literal text "True"; an
        # inconsistent row makes ``Eq(0, 5)`` and prints "False". Both are useless as equations, and a
        # zero row is not exotic — RREF, sitting in this same menu, produces them constantly. Keeping
        # the sum evaluated is what still drops zero terms, so `[1, 0, 3]` stays `x = 3`.
        equations.append(sp.Eq(left, right, evaluate=False))

    return {
        "title": "Linear system",
        "latex": _stacked_latex(equations),
        "message": (
            "The last column is read as the constants on the right."
            if augmented
            else "Read as a coefficient matrix, so every equation is set to zero."
        ),
    }


def _unknown_names(count: int, matrix: MatrixBase) -> list[str]:
    """Name the unknowns without colliding with symbols the matrix already contains.

    ``[[a, 2], [3, b]]`` must not produce equations in an ``x`` that means something else on the page,
    so each naming scheme is tried in turn and the first clash-free one wins.
    """
    taken: set[str] = {str(symbol) for symbol in _symbols(matrix)}
    schemes: list[list[str] | None] = [
        ["x", "y", "z"][:count] if count <= 3 else None,
        [f"x_{index + 1}" for index in range(count)],
        [f"u_{index + 1}" for index in range(count)],
    ]
    for names in schemes:
        if names and not taken.intersection(names):
            return names
    # Every scheme collided, which takes a deliberately hostile matrix. Fall back to something no
    # ordinary formula contains rather than raising: the operation still has a correct answer.
    return [f"v_{{{index + 1}}}" for index in range(count)]


def _stacked_latex(equations: list[sp.Equality]) -> str:
    """One equation per line.

    ``\\begin{matrix}`` rather than ``cases`` or ``aligned`` on purpose: it is the one multi-line
    environment this app is *known* to render, because SymPy emits it for every matrix result the
    panel already shows. An environment RaTeX did not support would fail only on the device.
    """
    body: str = r" \\ ".join(sp.latex(equation) for equation in equations)
    return r"\begin{matrix}" + body + r"\end{matrix}"


def _latex_result(title: str, result: Any, message: str | None = None) -> ResultPayload:
    return {"title": title, "latex": sp.latex(result), "message": message}


def _verdict_result(verdict: str, message: str | None = None) -> ResultPayload:
    """A convergence answer, whose result is a word rather than an expression."""
    return {"title": "Convergence", "latex": rf"\text{{{verdict}}}", "message": message}


def _infinite_series_index(series: sp.Sum) -> sp.Symbol | None:
    """The summation index of a single-index series running to infinity, else ``None``.

    Multi-index sums are excluded because SymPy's convergence machinery refuses them anyway, and a
    finite sum has nothing to decide — it is arithmetic, which ``evaluate`` already does.
    """
    if len(series.limits) != 1:
        return None
    index, lower, upper = series.limits[0]
    return index if bool(upper.is_infinite) or bool(lower.is_infinite) else None


def _tribool(value: Any) -> bool | None:
    """Normalize a SymPy answer to ``True`` / ``False`` / unknown.

    ``is_convergent`` returns ``BooleanTrue``/``BooleanFalse``, not Python ``bool``, so ``value is
    True`` is *always* false against it and an identity check silently reports every convergent
    series as divergent. Anything that is neither boolean is treated as unknown rather than coerced.
    """
    if value is True or value is sp.true:
        return True
    if value is False or value is sp.false:
        return False
    return None


def _oscillates_with_index(term: sp.Expr, index: sp.Symbol) -> bool:
    """Whether the summand carries a trigonometric factor in the summation index."""
    oscillators = (sp.sin, sp.cos, sp.tan, sp.cot, sp.sec, sp.csc)
    return any(index in node.free_symbols for node in term.atoms(*oscillators))


def _absolutely_convergent_by_comparison(series: sp.Sum, index: sp.Symbol) -> bool | None:
    """Test absolute convergence of an oscillating summand by bounding its trig factors by 1.

    |sin| and |cos| never exceed 1, so if the summand with those factors removed converges
    absolutely then the original does too. Returns ``None`` when the comparison is inconclusive,
    which is not evidence of anything — only the positive answer is a proof.
    """
    oscillators = (sp.sin, sp.cos)
    bounded: sp.Expr = series.function
    for node in bounded.atoms(*oscillators):
        if index in node.free_symbols:
            bounded = bounded.subs(node, sp.Integer(1))
    if index in bounded.atoms(sp.tan, sp.cot, sp.sec, sp.csc):
        return None  # Unbounded on the reals; comparison says nothing.
    try:
        comparison = sp.Sum(sp.Abs(bounded), series.limits[0])
        return True if _tribool(comparison.is_convergent()) is True else None
    except (NotImplementedError, RecursionError, ValueError, TypeError):
        return None


def _series_convergence(series: sp.Sum) -> ResultPayload:
    """Decide convergence, and refuse to answer where SymPy is known to answer wrongly.

    SymPy exposes no individually addressable test — ``is_convergent`` runs the divergence,
    p-series, comparison, ratio, Raabe, root, alternating-series, integral and Dirichlet tests
    internally and returns only a boolean, never which one decided. So this reports the verdict, not
    a test name, because naming a test here would be a guess.

    **A ``False`` from a summand with a trigonometric factor in the index carries no information.**
    Traced through SymPy 1.14.0: the p-series branch discards the bounded factor and decides on the
    ``1/n**p`` remainder alone, and it sits *ahead* of the Dirichlet branch in the cascade, so
    Dirichlet never runs. That is right whenever the remainder converges absolutely — Σsin(n)/n²
    returns ``True`` correctly — and wrong for every conditionally convergent case: Σsin(n)/n and
    Σcos(n)/n both converge and both come back ``False``, identically to the genuinely divergent
    Σsin(n). Reporting that as "diverges" would state a false theorem, so p ≤ 1 is undetermined
    instead. A ``True`` is unaffected, since the discarded factor only ever helps convergence.
    """
    index: sp.Symbol | None = _infinite_series_index(series)
    if index is None:
        raise MathInputError("Convergence applies to a series with one index running to infinity.")

    try:
        converges: bool | None = _tribool(series.is_convergent())
    except NotImplementedError:
        return _verdict_result("Undetermined", "SymPy could not decide this series.")

    if converges is None:
        return _verdict_result("Undetermined", "SymPy could not decide this series.")

    if converges is False:
        if _oscillates_with_index(series.function, index):
            return _verdict_result(
                "Undetermined",
                "SymPy reports divergence for every oscillating summand of this shape, including "
                "ones that converge, so the answer is withheld rather than guessed.",
            )
        return _verdict_result("Diverges")

    # Only asked once convergence is established, because it is the expensive half.
    absolutely: bool | None
    if _oscillates_with_index(series.function, index):
        # `is_absolutely_convergent` spends ~5 s on these before raising — measured on Σcos(n)/n²,
        # which is in the bundled corpus — so bound |sin| and |cos| by 1 and test what is left
        # instead. Convergence of the bound implies absolute convergence by comparison; failure to
        # decide the bound implies nothing, so it only ever adds the qualifier, never removes it.
        absolutely = _absolutely_convergent_by_comparison(series, index)
    else:
        try:
            absolutely = _tribool(series.is_absolutely_convergent())
        except (NotImplementedError, RecursionError, ValueError, TypeError):
            absolutely = None

    if absolutely is True:
        return _verdict_result("Converges absolutely")
    if absolutely is False:
        return _verdict_result("Converges conditionally")
    return _verdict_result("Converges", "Absolute convergence could not be determined.")


def _graph_expression(expression: ParsedInput) -> tuple[sp.Symbol, sp.Expr, str] | None:
    if not isinstance(expression, Relational):
        symbols = _symbols(expression)
        return (symbols[0], expression, "y") if len(symbols) == 1 else None

    symbols = _symbols(expression)
    if len(symbols) == 1:
        return symbols[0], sp.simplify(expression.lhs - expression.rhs), "y"

    # Recognize ordinary explicit functions such as y = x^2 without attempting a costly solve.
    if len(symbols) == 2 and isinstance(expression, sp.Equality):
        if expression.lhs.is_Symbol:
            other = list(expression.rhs.free_symbols)
            if len(other) == 1 and expression.lhs not in expression.rhs.free_symbols:
                return other[0], expression.rhs, str(expression.lhs)
        if expression.rhs.is_Symbol:
            other = list(expression.lhs.free_symbols)
            if len(other) == 1 and expression.rhs not in expression.lhs.free_symbols:
                return other[0], expression.lhs, str(expression.rhs)
    return None


def _graph_result(expression: ParsedInput) -> ResultPayload:
    graph = _graph_expression(expression)
    if graph is None:
        raise MathInputError("This expression cannot be represented as a one-variable graph yet.")
    variable, graph_expression, dependent_label = graph
    function = sp.lambdify(variable, graph_expression, modules=["math"])
    x_min: float = -10.0
    x_max: float = 10.0
    x_values: list[float] = []
    y_values: list[float | None] = []
    for index in range(PLOT_POINT_COUNT):
        x_value = x_min + (x_max - x_min) * index / (PLOT_POINT_COUNT - 1)
        x_values.append(x_value)
        try:
            raw = function(x_value)
            if isinstance(raw, complex):
                y_value = raw.real if abs(raw.imag) < 1e-9 else None
            else:
                y_value = float(raw)
            if y_value is not None and (not math.isfinite(y_value) or abs(y_value) > MAX_PLOT_ABS_Y):
                y_value = None
        except (ArithmeticError, TypeError, ValueError, OverflowError):
            y_value = None
        y_values.append(y_value)

    if not any(value is not None for value in y_values):
        raise MathInputError("No real graph values were found between −10 and 10.")
    return {
        "title": "Graph",
        "latex": sp.latex(graph_expression),
        "graph": {
            "xLabel": str(variable),
            "yLabel": dependent_label,
            "xValues": x_values,
            "yValues": y_values,
        },
    }
