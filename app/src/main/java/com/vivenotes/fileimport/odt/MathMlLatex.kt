package com.vivenotes.fileimport.odt

import com.vivenotes.fileimport.docx.childElements
import org.w3c.dom.Element

/**
 * OpenDocument's equations as LaTeX, which is what the canvas already knows how to draw and edit.
 *
 * LibreOffice stores a formula as MathML next to the document. Anything not recognised falls through
 * to its children, so an unknown wrapper loses its decoration and keeps its contents.
 */
internal fun mathMlToLatex(math: Element): String = convertChildren(math).trim()

/** The formula's characters with no structure, for when even LaTeX cannot draw it. */
internal fun mathMlPlainText(math: Element): String {
    val out = StringBuilder()
    fun walk(element: Element) {
        if (element.localName == "annotation") return
        val children = element.childElements
        if (children.isEmpty()) out.append(element.textContent.orEmpty().trim()) else children.forEach(::walk)
    }
    walk(math)
    return out.toString()
}

private fun convertChildren(element: Element): String = element.childElements.joinToString("", transform = ::convert)

private fun group(element: Element?): String = "{" + (element?.let(::convert).orEmpty()) + "}"

private fun convert(element: Element): String {
    val children = element.childElements
    return when (element.localName) {
        "annotation", "annotation-xml" -> ""
        "mi", "mn", "mo" -> tokenOf(element.textContent.orEmpty().trim())
        "mtext" -> "\\text{" + element.textContent.orEmpty().trim() + "}"
        "mspace" -> "\\ "
        "mfrac" -> "\\frac" + group(children.getOrNull(0)) + group(children.getOrNull(1))
        "msqrt" -> "\\sqrt{" + convertChildren(element) + "}"
        "mroot" -> "\\sqrt[" + children.getOrNull(1)?.let(::convert).orEmpty() + "]" + group(children.getOrNull(0))
        "msup", "mover" -> group(children.getOrNull(0)) + "^" + group(children.getOrNull(1))
        "msub", "munder" -> group(children.getOrNull(0)) + "_" + group(children.getOrNull(1))
        "msubsup", "munderover" -> group(children.getOrNull(0)) + "_" + group(children.getOrNull(1)) + "^" + group(children.getOrNull(2))
        "mfenced" -> {
            val open = element.getAttribute("open").ifEmpty { "(" }
            val close = element.getAttribute("close").ifEmpty { ")" }
            "\\left" + delimiter(open) + children.joinToString(",", transform = ::convert) + "\\right" + delimiter(close)
        }
        "mtable" -> "\\begin{matrix}" + children.joinToString(" \\\\ ", transform = ::convert) + "\\end{matrix}"
        "mtr" -> children.joinToString(" & ", transform = ::convert)
        else -> convertChildren(element)
    }
}

/** LaTeX needs `.` where a side has no delimiter at all, and a backslash before a brace. */
private fun delimiter(value: String): String = when (value) {
    "" -> "."
    "{", "}" -> "\\$value"
    else -> value
}

private fun tokenOf(text: String): String = when {
    text in FUNCTIONS -> "\\$text "
    text.length > 1 && text.all(Char::isLetter) -> "\\mathrm{$text}"
    else -> text.map { char -> SYMBOLS[char] ?: if (char == '{' || char == '}' || char == '%' || char == '#' || char == '&') "\\$char" else char.toString() }.joinToString("")
}

private val FUNCTIONS = setOf("sin", "cos", "tan", "cot", "sec", "csc", "log", "ln", "exp", "lim", "max", "min", "det", "sinh", "cosh", "tanh")

private val SYMBOLS: Map<Char, String> = mapOf(
    'α' to "\\alpha ", 'β' to "\\beta ", 'γ' to "\\gamma ", 'δ' to "\\delta ", 'ε' to "\\epsilon ", 'ζ' to "\\zeta ",
    'η' to "\\eta ", 'θ' to "\\theta ", 'ι' to "\\iota ", 'κ' to "\\kappa ", 'λ' to "\\lambda ", 'μ' to "\\mu ",
    'ν' to "\\nu ", 'ξ' to "\\xi ", 'π' to "\\pi ", 'ρ' to "\\rho ", 'σ' to "\\sigma ", 'τ' to "\\tau ",
    'υ' to "\\upsilon ", 'φ' to "\\phi ", 'χ' to "\\chi ", 'ψ' to "\\psi ", 'ω' to "\\omega ",
    'Γ' to "\\Gamma ", 'Δ' to "\\Delta ", 'Θ' to "\\Theta ", 'Λ' to "\\Lambda ", 'Ξ' to "\\Xi ", 'Π' to "\\Pi ",
    'Σ' to "\\Sigma ", 'Φ' to "\\Phi ", 'Ψ' to "\\Psi ", 'Ω' to "\\Omega ",
    '∑' to "\\sum ", '∏' to "\\prod ", '∫' to "\\int ", '∞' to "\\infty ", '≤' to "\\leq ", '≥' to "\\geq ",
    '≠' to "\\neq ", '±' to "\\pm ", '∓' to "\\mp ", '×' to "\\times ", '÷' to "\\div ", '·' to "\\cdot ", '⋅' to "\\cdot ",
    '→' to "\\to ", '←' to "\\leftarrow ", '⇒' to "\\Rightarrow ", '⇔' to "\\Leftrightarrow ", '∈' to "\\in ", '∉' to "\\notin ",
    '⊂' to "\\subset ", '⊆' to "\\subseteq ", '∪' to "\\cup ", '∩' to "\\cap ", '∀' to "\\forall ", '∃' to "\\exists ",
    '∂' to "\\partial ", '∇' to "\\nabla ", '≈' to "\\approx ", '≡' to "\\equiv ", '∅' to "\\emptyset ", '∧' to "\\wedge ",
    '∨' to "\\vee ", '¬' to "\\neg ", '−' to "-",
)
