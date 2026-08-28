"""Degree marks rewritten into the radian multiples SymPy's trigonometry actually takes.

SymPy has no degree mode. Every trig function reads radians and there is no switch to change that,
so a degree mark has to become an explicit factor before the formula reaches the parser.

Leaving it alone is not a safe default, because the mark is outside ``parse_latex``'s grammar and
the grammar has no way to say so. ``\\sin(30^\\circ)`` does not fail: ``\\circ`` falls through as an
ordinary symbol and the caret becomes exponentiation, leaving ``sin(30**circ)`` — a two-variable
expression which ``analyze`` cheerfully offers to differentiate and graph. The bare ``°`` character
is the kinder case, failing to lex at all.

Rewriting to a ``\\frac{\\pi}{180}`` factor also keeps the exactness the rest of the calculator is
built on: ``\\sin(30^\\circ)`` simplifies to ``\\frac{1}{2}``, not to ``0.4999999999``.

Only a base the mark can attach to unambiguously is rewritten — a number, a variable or single
macro, or a bracketed group. Anything else keeps its mark, and ``has_stray_mark`` reports it so the
caller can refuse the formula instead of letting a symbol named ``circ`` reach the parser.
"""

import re

#: How the mark is written: ``^\\circ``, ``^{\\circ}``, the ``\\degree`` of ``gensymb`` and
#: ``siunitx``, or the bare character a recognizer emits when it reads the ring as one glyph.
_DEGREE_MARK = r"(?:\^\s*\{?\s*(?:\\circ|\\degree|°)\s*\}?|°)"

#: What the mark may attach to: a number, a variable or macro such as ``\\theta``, or a group which
#: brackets its own contents. Each alternative refuses a base which is only part of a token, because
#: a partial base does not merely miss — it rewrites the formula into a different one. Without the
#: lookbehinds, ``\\theta_{2}^\\circ`` matches on ``{2}`` alone and the π/180 factor lands *inside*
#: the subscript, yielding ``theta_{pi/180} * 2``: no error, no degree mark, wrong answer.
#:
#: So a digit run may not start mid-number, a letter may not start mid-word, and no base may follow
#: ``_`` or ``^`` (it would be swallowed by the script) or attach to a macro's own argument, which
#: is what rules out ``\\frac{1}{2}^\\circ`` and ``\\sqrt{2}^\\circ``. A base needing balanced-delimiter
#: counting to find is deliberately unreachable here: matching it with a regex would be guesswork,
#: and ``has_stray_mark`` turns every one of these misses into an error rather than a wrong answer.
_DEGREE_BASE = (
    r"(?:"
    r"(?<![_^\d.])\d+(?:\.\d+)?"
    r"|(?<![_^A-Za-z])\\[A-Za-z]+"
    r"|(?<![_^A-Za-z\\])[A-Za-z]"
    r"|(?<![_^A-Za-z}\\])\{[^{}]*\}"
    r"|(?<![_^A-Za-z])\\left\([^()]*\\right\)"
    r"|(?<![_^A-Za-z])\([^()]*\)"
    r")"
)

_DEGREES = re.compile(r"(?P<base>" + _DEGREE_BASE + r")\s*" + _DEGREE_MARK)

_STRAY_MARK = re.compile(r"\\circ(?![A-Za-z])|\\degree(?![A-Za-z])|°")

#: FormulaNet emits one token at a time, so ``36`` arrives as ``3 6`` and ``\\sin(36^\\circ)`` as
#: ``\\sin ( 3 6 ^ { \\circ } )``. The ANTLR lexer already spans that spacing — it reads ``3 6`` as
#: the single number 36 — so collapsing it changes nothing about how the formula parses, and the
#: rewrite below has to see the same number the parser will. Skipping this step made the mark attach
#: to the last digit alone: ``\\sin ( 3 6 ^ { \\circ } )`` became ``sin(3 * 6°)``, which is sin(18°)
#: = 0.309 where sin(36°) = 0.588 was written. Wrong, quietly, in the one place a degree mark is
#: most likely to appear.
_SPACED_DECIMAL = re.compile(r"(?<=\d)\s*\.\s*(?=\d)")
_SPACED_DIGITS = re.compile(r"(?<=\d)\s+(?=\d)")

#: Recognizer spacing also separates a base from the script or bracket which binds it, and the
#: lookbehinds above read a single character. ``\\theta _ { 2 } ^ { \\circ }`` would show a space
#: before ``{2}`` rather than the ``_`` that is supposed to disqualify it, so the subscript trap
#: reopens. Whitespace is meaningless to the grammar next to these characters, unlike between a
#: macro name and a following letter, which is why only these are closed up.
_SPACED_STRUCTURE = re.compile(r"\s*([_^{}()])\s*")


def _canonicalize_spacing(source: str) -> str:
    source = _SPACED_DECIMAL.sub(".", source)
    source = _SPACED_DIGITS.sub("", source)
    return _SPACED_STRUCTURE.sub(r"\1", source)


def _to_radian_factor(match: re.Match[str]) -> str:
    # \cdot rather than implicit multiplication: the grammar accepts both, but an explicit product
    # cannot be re-read as function application when the base is a macro like \theta.
    return r"\frac{\pi}{180}\cdot\left(" + match.group("base") + r"\right)"


def to_radians(source: str) -> str:
    """Rewrite every degree mark on a recognizable base as an explicit π/180 factor.

    A formula carrying no mark is returned untouched rather than merely unchanged in meaning: the
    spacing this has to canonicalize is invisible to the parser but not to a reader comparing the
    recognized source against what they wrote, so nothing pays for a rewrite it did not need.
    """
    if not _STRAY_MARK.search(source):
        return source
    return _DEGREES.sub(_to_radian_factor, _canonicalize_spacing(source))


def has_stray_mark(source: str) -> bool:
    """Report a degree mark ``to_radians`` could not attach to a base.

    Also true of ``\\circ`` written as function composition, which the grammar cannot represent
    either — both belong in the same "this formula was not understood" answer rather than in a
    silent reinterpretation.
    """
    return _STRAY_MARK.search(source) is not None
