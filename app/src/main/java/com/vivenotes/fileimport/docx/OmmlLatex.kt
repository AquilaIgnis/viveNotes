package com.vivenotes.fileimport.docx

import org.w3c.dom.Element

/**
 * Word's equations as LaTeX, which is what the canvas already knows how to draw and edit.
 *
 * Covers what Word's equation editor makes: fractions, scripts, radicals, n-ary operators, delimiters,
 * matrices, equation arrays, accents and functions. Anything else falls through to its children, so an
 * unknown wrapper loses its decoration and keeps its contents rather than losing the formula.
 */
internal fun ommlToLatex(math: Element): String = OmmlConverter().children(math).trim()

/** The formula's characters with no structure, for when even LaTeX cannot draw it. */
internal fun ommlPlainText(math: Element): String = math.allText(NS_M, "t")

private class OmmlConverter {
    private var depth = 0

    fun children(element: Element): String = buildString {
        element.childElements.forEach { append(convert(it)) }
    }

    private fun arg(parent: Element, name: String): String =
        parent.child(NS_M, name)?.let(::children).orEmpty()

    private fun braced(parent: Element, name: String): String = "{${arg(parent, name)}}"

    private fun convert(element: Element): String {
        if (element.namespaceURI != NS_M) return ""
        if (depth >= MAX_DEPTH) return ""
        depth++
        try {
            return when (element.localName) {
                "r" -> run(element)
                "f" -> fraction(element)
                "sSup" -> "${arg(element, "e").asBase()}^${braced(element, "sup")}"
                "sSub" -> "${arg(element, "e").asBase()}_${braced(element, "sub")}"
                "sSubSup" -> "${arg(element, "e").asBase()}_${braced(element, "sub")}^${braced(element, "sup")}"
                "sPre" -> "{}_${braced(element, "sub")}^${braced(element, "sup")}${arg(element, "e")}"
                "rad" -> radical(element)
                "nary" -> nary(element)
                "d" -> delimiter(element)
                "func" -> "${functionName(element.child(NS_M, "fName"))} ${arg(element, "e")}"
                "limLow" -> limit(element, under = true)
                "limUpp" -> limit(element, under = false)
                "acc" -> accent(element)
                "bar" -> if (element.child(NS_M, "barPr")?.child(NS_M, "pos")?.mVal() == "bot") {
                    "\\underline${braced(element, "e")}"
                } else {
                    "\\overline${braced(element, "e")}"
                }
                "groupChr" -> groupCharacter(element)
                "borderBox" -> "\\boxed${braced(element, "e")}"
                "eqArr" -> "\\begin{aligned}${rows(element.children(NS_M, "e"))}\\end{aligned}"
                "m" -> matrix(element)
                "phant" -> arg(element, "e")
                // Properties say how to draw their parent and hold no content of their own.
                "ctrlPr", "rPr", "sPrePr", "sSupPr", "sSubPr", "sSubSupPr", "fPr", "radPr", "naryPr",
                "dPr", "funcPr", "limLowPr", "limUppPr", "accPr", "barPr", "groupChrPr", "boxPr",
                "borderBoxPr", "eqArrPr", "mPr", "phantPr", -> ""
                else -> children(element)
            }
        } finally {
            depth--
        }
    }

    private fun String.asBase(): String = if (length > 1) "{$this}" else this

    private fun Element.mVal(): String? = attr(NS_M, "val")

    private fun run(run: Element): String {
        val properties = run.child(NS_M, "rPr")
        val text = run.children(NS_M, "t").joinToString("") { it.textContent.orEmpty() }
        if (text.isEmpty()) return ""
        val literal = properties?.child(NS_M, "nor") != null
        if (literal) return "\\text{${text.textEscaped()}}"
        val body = symbols(text)
        val font = properties?.child(NS_M, "scr")?.mVal()
        val style = properties?.child(NS_M, "sty")?.mVal()
        return when {
            font == "double-struck" -> "\\mathbb{$body}"
            font == "fraktur" -> "\\mathfrak{$body}"
            font == "script" -> "\\mathcal{$body}"
            font == "monospace" -> "\\mathtt{$body}"
            font == "sans-serif" -> "\\mathsf{$body}"
            style == "b" || style == "bi" -> "\\mathbf{$body}"
            style == "p" && text.any(Char::isLetter) && symbols(text) == text -> "\\mathrm{$body}"
            else -> body
        }
    }

    private fun fraction(fraction: Element): String {
        val numerator = braced(fraction, "num")
        val denominator = braced(fraction, "den")
        return when (fraction.child(NS_M, "fPr")?.child(NS_M, "type")?.mVal()) {
            "lin" -> "$numerator/$denominator"
            "noBar" -> "\\genfrac{}{}{0pt}{}$numerator$denominator"
            "skw" -> "{}^$numerator/_$denominator"
            else -> "\\frac$numerator$denominator"
        }
    }

    private fun radical(radical: Element): String {
        val hidden = radical.child(NS_M, "radPr")?.child(NS_M, "degHide")?.isMathOn() == true
        val degree = arg(radical, "deg")
        return if (hidden || degree.isEmpty()) "\\sqrt${braced(radical, "e")}" else "\\sqrt[$degree]${braced(radical, "e")}"
    }

    private fun Element.isMathOn(): Boolean = when (mVal()?.lowercase()) {
        "0", "false", "off" -> false
        else -> true
    }

    private fun nary(nary: Element): String {
        val properties = nary.child(NS_M, "naryPr")
        val char = properties?.child(NS_M, "chr")?.mVal() ?: "∫"
        val operator = NARY_OPERATORS[char] ?: symbols(char)
        val placement = when (properties?.child(NS_M, "limLoc")?.mVal()) {
            "undOvr" -> "\\limits"
            "subSup" -> "\\nolimits"
            else -> ""
        }
        val sub = if (properties?.child(NS_M, "subHide")?.isMathOn() == true) "" else arg(nary, "sub")
        val sup = if (properties?.child(NS_M, "supHide")?.isMathOn() == true) "" else arg(nary, "sup")
        return buildString {
            append(operator).append(placement)
            if (sub.isNotEmpty()) append("_{").append(sub).append('}')
            if (sup.isNotEmpty()) append("^{").append(sup).append('}')
            append(' ')
            append(arg(nary, "e"))
        }
    }

    private fun delimiter(delimiter: Element): String {
        val properties = delimiter.child(NS_M, "dPr")
        val open = properties?.child(NS_M, "begChr")?.mVal() ?: "("
        val close = properties?.child(NS_M, "endChr")?.mVal() ?: ")"
        val separator = properties?.child(NS_M, "sepChr")?.mVal() ?: "|"
        val parts = delimiter.children(NS_M, "e")
        // A brace with one side and stacked equations is `cases`, which is how Word writes a piecewise.
        val onlyArray = parts.singleOrNull()?.childElements
            ?.filter { it.namespaceURI == NS_M && !it.localName.endsWith("Pr") }
            ?.singleOrNull()
            ?.takeIf { it.localName == "eqArr" }
        if (open == "{" && close.isEmpty() && onlyArray != null) {
            return "\\begin{cases}${rows(onlyArray.children(NS_M, "e"))}\\end{cases}"
        }
        val body = parts.joinToString(symbols(separator)) { children(it) }
        return "\\left${fence(open)} $body \\right${fence(close)}"
    }

    private fun fence(char: String): String = when (char) {
        "" -> "."
        "{" -> "\\{"
        "}" -> "\\}"
        "(", ")", "[", "]", "|", "/" -> char
        "‖" -> "\\|"
        "⟨", "〈" -> "\\langle"
        "⟩", "〉" -> "\\rangle"
        "⌈" -> "\\lceil"
        "⌉" -> "\\rceil"
        "⌊" -> "\\lfloor"
        "⌋" -> "\\rfloor"
        else -> symbols(char).ifBlank { "." }
    }

    /** A `\sin`-style command for a function Word sets upright, or an operator name for any other. */
    private fun functionName(name: Element?): String {
        name ?: return ""
        val structured = name.childElements.filterNot { it.localName.endsWith("Pr") }.singleOrNull()
        if (structured != null && structured.localName != "r") return convert(structured)
        val text = name.allText(NS_M, "t").trim()
        return when {
            text.isEmpty() -> children(name)
            text in KNOWN_FUNCTIONS -> "\\$text"
            else -> "\\operatorname{${text.textEscaped()}}"
        }
    }

    private fun limit(limit: Element, under: Boolean): String {
        val base = limit.child(NS_M, "e")
        val text = base?.allText(NS_M, "t")?.trim().orEmpty()
        val bound = braced(limit, "lim")
        return when {
            text in KNOWN_FUNCTIONS -> "\\$text${if (under) "_" else "^"}$bound"
            under -> "\\underset$bound{${base?.let(::children).orEmpty()}}"
            else -> "\\overset$bound{${base?.let(::children).orEmpty()}}"
        }
    }

    private fun accent(accent: Element): String {
        val char = accent.child(NS_M, "accPr")?.child(NS_M, "chr")?.mVal() ?: "̂"
        val command = when (char) {
            "̂" -> "\\hat"
            "̃" -> "\\tilde"
            "̄", "¯" -> "\\bar"
            "̅" -> "\\overline"
            "̇" -> "\\dot"
            "̈" -> "\\ddot"
            "⃗", "→" -> "\\vec"
            "̆" -> "\\breve"
            "̌" -> "\\check"
            else -> "\\hat"
        }
        return "$command${braced(accent, "e")}"
    }

    private fun groupCharacter(group: Element): String {
        val properties = group.child(NS_M, "groupChrPr")
        val char = properties?.child(NS_M, "chr")?.mVal() ?: "⏟"
        return when {
            char == "⏟" -> "\\underbrace${braced(group, "e")}"
            char == "⏞" -> "\\overbrace${braced(group, "e")}"
            properties?.child(NS_M, "pos")?.mVal() == "top" -> "\\overset{${symbols(char)}}${braced(group, "e")}"
            else -> "\\underset{${symbols(char)}}${braced(group, "e")}"
        }
    }

    private fun rows(rows: List<Element>): String = rows.joinToString(" \\\\ ") { children(it) }

    private fun matrix(matrix: Element): String {
        val rows = matrix.children(NS_M, "mr").joinToString(" \\\\ ") { row ->
            row.children(NS_M, "e").joinToString(" & ") { children(it) }
        }
        return "\\begin{matrix}$rows\\end{matrix}"
    }

    private fun String.textEscaped(): String = buildString {
        for (char in this@textEscaped) {
            when (char) {
                '{', '}', '%', '#', '$', '_', '&' -> append('\\').append(char)
                '\\' -> append("\\backslash ")
                else -> append(char)
            }
        }
    }

    /** Unicode maths to LaTeX commands. Word's `&` is the alignment mark inside `eqArr` and stays. */
    private fun symbols(text: String): String = buildString {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            val ascii = mathematicalLetter(codePoint)
            when {
                ascii != null -> append(ascii)
                codePoint < 0x10000 -> {
                    val char = codePoint.toChar()
                    when {
                        char == '{' || char == '}' || char == '%' || char == '#' || char == '$' || char == '_' ->
                            append('\\').append(char)
                        char == '\\' -> append("\\backslash ")
                        else -> {
                            val command = MATH_SYMBOLS[char]
                            if (command != null) append(command).append(' ') else append(char)
                        }
                    }
                }
                else -> appendCodePoint(codePoint)
            }
        }
    }

    /** The Mathematical Alphanumeric block is a styled alphabet: the letter is what is wanted. */
    private fun mathematicalLetter(codePoint: Int): Char? {
        val first = 0x1D400
        val last = 0x1D6A3
        if (codePoint !in first..last) return null
        val offset = (codePoint - first) % 52
        return if (offset < 26) 'A' + offset else 'a' + (offset - 26)
    }

    private companion object {
        /** A formula nested past this is not a formula; it keeps a hostile file off the stack. */
        const val MAX_DEPTH = 64

        val KNOWN_FUNCTIONS = setOf(
            "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh",
            "coth", "log", "ln", "lg", "exp", "lim", "max", "min", "sup", "inf", "det", "gcd", "arg", "deg",
            "dim", "hom", "ker", "Pr",
        )

        val NARY_OPERATORS = mapOf(
            "∑" to "\\sum", "∏" to "\\prod", "∐" to "\\coprod", "∫" to "\\int", "∬" to "\\iint",
            "∭" to "\\iiint", "∮" to "\\oint", "⋃" to "\\bigcup", "⋂" to "\\bigcap", "⋁" to "\\bigvee",
            "⋀" to "\\bigwedge", "⨁" to "\\bigoplus", "⨂" to "\\bigotimes",
        )

        val MATH_SYMBOLS: Map<Char, String> = buildMap {
            """
            α alpha β beta γ gamma δ delta ε epsilon ζ zeta η eta θ theta ι iota κ kappa λ lambda μ mu
            ν nu ξ xi π pi ρ rho σ sigma ς varsigma τ tau υ upsilon φ phi χ chi ψ psi ω omega ϕ phi
            ϵ epsilon ϑ vartheta ϱ varrho ϖ varpi Γ Gamma Δ Delta Θ Theta Λ Lambda Ξ Xi Π Pi Σ Sigma
            Υ Upsilon Φ Phi Ψ Psi Ω Omega ∞ infty ± pm ∓ mp × times ÷ div · cdot ⋅ cdot ∘ circ ∙ bullet
            ≤ le ≥ ge ≠ ne ≈ approx ≡ equiv ∼ sim ≃ simeq ≅ cong ∝ propto ≪ ll ≫ gg ∈ in ∉ notin ∋ ni
            ⊂ subset ⊃ supset ⊆ subseteq ⊇ supseteq ∪ cup ∩ cap ∅ emptyset ∀ forall ∃ exists ¬ neg
            ∧ wedge ∨ vee → rightarrow ← leftarrow ↔ leftrightarrow ⇒ Rightarrow ⇐ Leftarrow ⇔ Leftrightarrow
            ↦ mapsto ↑ uparrow ↓ downarrow ∂ partial ∇ nabla ℏ hbar ℓ ell ℜ Re ℑ Im ℵ aleph … ldots ⋯ cdots
            ⋮ vdots ⋱ ddots ∠ angle ⊥ perp ∥ parallel ⊕ oplus ⊗ otimes ⊙ odot ∑ sum ∏ prod ∫ int ∮ oint
            √ surd ⟨ langle ⟩ rangle ⌈ lceil ⌉ rceil ⌊ lfloor ⌋ rfloor
            """.trim().split(Regex("\\s+")).chunked(2).forEach { (symbol, command) -> put(symbol.single(), "\\$command") }
            put('−', "-")
            put('∗', "*")
            put('′', "'")
            put('″', "''")
            put('°', "^{\\circ}")
            put('ℝ', "\\mathbb{R}")
            put('ℕ', "\\mathbb{N}")
            put('ℤ', "\\mathbb{Z}")
            put('ℚ', "\\mathbb{Q}")
            put('ℂ', "\\mathbb{C}")
        }
    }
}
