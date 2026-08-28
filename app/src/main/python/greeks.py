"""Greek-named LaTeX functions bound to the SymPy functions they denote.

``parse_latex`` recognizes only the macros declared in SymPy's ``LaTeX.g4`` — some forty names such
as ``\\sin``, ``\\log`` and ``\\binom``. Every other name falls through to
``sympy.Function(name)(*args)``: an *undefined* function carrying no evaluation rules.

Binding is keyed on the argument count as well as the name, because the same Greek letter denotes a
function at one arity and an ordinary variable at another. ``\\beta(x, y)`` is the Beta function,
while ``\\beta`` and ``\\beta(t)`` are a coefficient and some function of ``t`` — those are left
alone rather than silently reinterpreted.
"""

import sympy as sp
from sympy.core.function import AppliedUndef
from sympy.matrices.matrixbase import MatrixBase

#: Mirrors ``math_engine.ParsedInput``. Spelled out again rather than imported so this module stays
ParsedInput = sp.Expr | MatrixBase

_BOUND_FUNCTIONS: dict[tuple[str, int], sp.FunctionClass] = {
    ("Gamma", 1): sp.gamma,
    ("Gamma", 2): sp.uppergamma,
    ("gamma", 2): sp.lowergamma,
    ("beta", 2): sp.beta,
    ("Beta", 2): sp.beta,
}


def _binding_key(node: sp.Basic) -> tuple[str, int]:
    return type(node).__name__, len(node.args)


def bind_functions(expression: ParsedInput) -> ParsedInput:
    """Rewrite undefined ``\\Gamma`` and ``\\beta`` applications as the SymPy functions they mean.

    ``replace`` rebuilds bottom-up, so a binding inside a binding — ``\\Gamma(\\beta(2, 3))`` — is
    resolved innermost first and both letters land. It also applies entry-wise to a matrix, which
    keeps the one call in ``_parse`` covering both branches of ``ParsedInput``.
    """

    def is_bound_notation(node: sp.Basic) -> bool:
        return isinstance(node, AppliedUndef) and _binding_key(node) in _BOUND_FUNCTIONS

    def to_sympy_function(node: sp.Basic) -> sp.Expr:
        return _BOUND_FUNCTIONS[_binding_key(node)](*node.args)

    return expression.replace(is_bound_notation, to_sympy_function)
