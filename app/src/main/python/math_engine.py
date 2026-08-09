"""Bounded SymPy operations exposed to viveNotes through a JSON-only API.

The Kotlin side never evaluates Python source. It supplies editable LaTeX, receives a structural
classification, and may invoke only the operation identifiers returned by ``analyze``.
"""

import json
import math
import re

import sympy as sp
from sympy.core.relational import Relational
from sympy.matrices.matrixbase import MatrixBase
from sympy.parsing.latex import parse_latex


MAX_SOURCE_CHARS = 1200
MAX_EXPRESSION_NODES = 700
MAX_MATRIX_EDGE = 8
MAX_PLOT_ABS_Y = 1_000_000.0
PLOT_POINT_COUNT = 321


def _action(action_id, label):
    return {"id": action_id, "label": label}


def _json(payload):
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def _friendly_error(error):
    if isinstance(error, MathInputError):
        return str(error)
    return "SymPy could not interpret this LaTeX. Check the recognized source and try again."


class MathInputError(ValueError):
    pass


def analyze(source):
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


def execute(source, action_id):
    """Execute one allow-listed operation and return renderable LaTeX or sampled graph points."""
    try:
        expression = _parse(source)
        available = {item["id"] for item in _classify(expression)[1]}
        if action_id not in available:
            raise MathInputError("That operation does not apply to this expression.")
        return _json(_execute(expression, action_id))
    except Exception as error:
        return _json({"error": _friendly_error(error)})


def _parse(source):
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
    _check_complexity(expression)
    return expression


_LIMIT_PLACEMENT_DIRECTIVE = re.compile(r"\\(?:no)?limits(?![A-Za-z])\s*")


def _normalize_latex(source):
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


def _parse_matrix(source):
    """Parse the matrix environments emitted by FormulaNet but unsupported by parse_latex."""
    cleaned = source.strip()
    wrappers = (
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

    row_sources = _split_matrix_rows(match.group("body"))
    if not row_sources:
        raise MathInputError("The recognized matrix has no rows.")
    rows = []
    width = None
    for row_source in row_sources:
        row_source = row_source.replace(r"\hline", "").strip()
        cells = [cell.strip() for cell in _split_top_level(row_source, "&")]
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


def _split_matrix_rows(body):
    parts = []
    start = 0
    depth = 0
    index = 0
    while index < len(body):
        character = body[index]
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


def _split_top_level(source, delimiter):
    parts = []
    start = 0
    depth = 0
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


def _check_complexity(expression):
    values = list(expression) if isinstance(expression, MatrixBase) else [expression]
    nodes = sum(1 for value in values for _ in sp.preorder_traversal(value))
    if nodes > MAX_EXPRESSION_NODES:
        raise MathInputError("This formula is too complex to evaluate safely on this device.")


def _variables(expression):
    symbols = set()
    if isinstance(expression, MatrixBase):
        for value in expression:
            symbols.update(value.free_symbols)
    else:
        symbols.update(expression.free_symbols)
    return [str(symbol) for symbol in sorted(symbols, key=sp.default_sort_key)]


def _symbols(expression):
    symbols = set()
    if isinstance(expression, MatrixBase):
        for value in expression:
            symbols.update(value.free_symbols)
    else:
        symbols.update(expression.free_symbols)
    return sorted(symbols, key=sp.default_sort_key)


def _classify(expression):
    if isinstance(expression, MatrixBase):
        rows, columns = expression.shape
        actions = [
            _action("matrix_transpose", "Transpose"),
            _action("matrix_rref", "RREF"),
            _action("matrix_rank", "Rank"),
            _action("matrix_normalize", "Normalize matrix"),
            _action("matrix_normalize_rows", "Normalize rows"),
        ]
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

    symbols = _symbols(expression)
    if isinstance(expression, sp.Integral):
        return "Integral", [_action("evaluate", "Evaluate")]
    if isinstance(expression, sp.Derivative):
        return "Derivative", [_action("evaluate", "Evaluate")]
    if isinstance(expression, (sp.Sum, sp.Product, sp.Limit)):
        return "Unevaluated operation", [_action("evaluate", "Evaluate")]

    if isinstance(expression, Relational):
        actions = [_action("simplify", "Simplify")]
        if symbols and len(symbols) <= 3:
            actions.insert(0, _action("solve", "Solve"))
        polynomial = sp.expand(expression.lhs - expression.rhs)
        if symbols and polynomial.is_polynomial(*symbols):
            actions.append(_action("factor", "Factor"))
        if _graph_expression(expression) is not None:
            actions.append(_action("graph", "Graph"))
        relation = "Equation" if isinstance(expression, sp.Equality) else "Relation"
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


def _execute(expression, action_id):
    if isinstance(expression, MatrixBase):
        return _execute_matrix(expression, action_id)

    symbols = _symbols(expression)
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
    if action_id == "decimal":
        return _latex_result("Decimal approximation", sp.N(expression, 12))
    if action_id == "graph":
        return _graph_result(expression)
    raise MathInputError("That operation is not supported.")


def _execute_matrix(matrix, action_id):
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
        return _latex_result("Normalized by the Frobenius norm", matrix.applyfunc(lambda value: sp.simplify(value / norm)))
    if action_id == "matrix_normalize_rows":
        normalized_rows = []
        for row_index in range(matrix.rows):
            row = matrix.row(row_index)
            norm = sp.simplify(row.norm())
            if norm.equals(0) is True:
                raise MathInputError(f"Row {row_index + 1} is zero and cannot be normalized.")
            normalized_rows.append([sp.simplify(value / norm) for value in row])
        return _latex_result("Rows normalized independently", sp.Matrix(normalized_rows))
    raise MathInputError("That matrix operation is not supported.")


def _latex_result(title, result, message=None):
    return {"title": title, "latex": sp.latex(result), "message": message}


def _graph_expression(expression):
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


def _graph_result(expression):
    graph = _graph_expression(expression)
    if graph is None:
        raise MathInputError("This expression cannot be represented as a one-variable graph yet.")
    variable, graph_expression, dependent_label = graph
    function = sp.lambdify(variable, graph_expression, modules=["math"])
    x_min = -10.0
    x_max = 10.0
    x_values = []
    y_values = []
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
