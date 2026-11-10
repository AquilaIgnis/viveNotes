package com.vivenotes.fileimport.docx

import org.junit.Assert.assertEquals
import org.junit.Test

class OmmlLatexTest {
    private fun latex(content: String): String =
        ommlToLatex(parseXml("""<m:oMath xmlns:m="$NS_M">$content</m:oMath>""".toByteArray()))

    private fun r(text: String): String = "<m:r><m:t>$text</m:t></m:r>"

    private fun e(content: String): String = "<m:e>$content</m:e>"

    @Test
    fun aFraction() {
        assertEquals("\\frac{a}{b}", latex("<m:f><m:num>${r("a")}</m:num><m:den>${r("b")}</m:den></m:f>"))
    }

    @Test
    fun aLinearFractionKeepsItsSlash() {
        assertEquals(
            "{a}/{b}",
            latex("""<m:f><m:fPr><m:type m:val="lin"/></m:fPr><m:num>${r("a")}</m:num><m:den>${r("b")}</m:den></m:f>"""),
        )
    }

    @Test
    fun scripts() {
        assertEquals("x^{2}", latex("<m:sSup>${e(r("x"))}<m:sup>${r("2")}</m:sup></m:sSup>"))
        assertEquals("x_{i}", latex("<m:sSub>${e(r("x"))}<m:sub>${r("i")}</m:sub></m:sSub>"))
        assertEquals(
            "x_{i}^{2}",
            latex("<m:sSubSup>${e(r("x"))}<m:sub>${r("i")}</m:sub><m:sup>${r("2")}</m:sup></m:sSubSup>"),
        )
    }

    /** Anything longer than one character is braced, or `ab^2` would square only the `b`. */
    @Test
    fun aLongBaseIsBraced() {
        assertEquals("{ab}^{2}", latex("<m:sSup>${e(r("ab"))}<m:sup>${r("2")}</m:sup></m:sSup>"))
    }

    @Test
    fun radicals() {
        val hidden = """<m:radPr><m:degHide m:val="1"/></m:radPr>"""
        assertEquals("\\sqrt{x}", latex("<m:rad>$hidden<m:deg/>${e(r("x"))}</m:rad>"))
        assertEquals("\\sqrt[3]{x}", latex("<m:rad><m:deg>${r("3")}</m:deg>${e(r("x"))}</m:rad>"))
    }

    @Test
    fun aSumWithItsLimits() {
        val properties = """<m:naryPr><m:chr m:val="∑"/><m:limLoc m:val="undOvr"/></m:naryPr>"""
        assertEquals(
            "\\sum\\limits_{i=0}^{n} i",
            latex("<m:nary>$properties<m:sub>${r("i=0")}</m:sub><m:sup>${r("n")}</m:sup>${e(r("i"))}</m:nary>"),
        )
    }

    @Test
    fun anIntegralIsTheDefaultOperatorAndHiddenLimitsGo() {
        val properties = """<m:naryPr><m:subHide m:val="1"/><m:supHide m:val="1"/></m:naryPr>"""
        assertEquals("\\int x", latex("<m:nary>$properties<m:sub/><m:sup/>${e(r("x"))}</m:nary>"))
    }

    @Test
    fun delimiters() {
        assertEquals("\\left( x \\right)", latex("<m:d>${e(r("x"))}</m:d>"))
        val square = """<m:dPr><m:begChr m:val="["/><m:endChr m:val="]"/></m:dPr>"""
        assertEquals("\\left[ a|b \\right]", latex("<m:d>$square${e(r("a"))}${e(r("b"))}</m:d>"))
        val braces = """<m:dPr><m:begChr m:val="{"/><m:endChr m:val="}"/></m:dPr>"""
        assertEquals("\\left\\{ x \\right\\}", latex("<m:d>$braces${e(r("x"))}</m:d>"))
    }

    @Test
    fun aBraceOverStackedEquationsIsCases() {
        val open = """<m:dPr><m:begChr m:val="{"/><m:endChr m:val=""/></m:dPr>"""
        assertEquals(
            "\\begin{cases}a \\\\ b\\end{cases}",
            latex("<m:d>$open${e("<m:eqArr>${e(r("a"))}${e(r("b"))}</m:eqArr>")}</m:d>"),
        )
    }

    @Test
    fun aFunctionWordIsACommandAndAnUnknownOneAnOperator() {
        assertEquals("\\sin x", latex("<m:func><m:fName>${r("sin")}</m:fName>${e(r("x"))}</m:func>"))
        assertEquals("\\operatorname{foo} x", latex("<m:func><m:fName>${r("foo")}</m:fName>${e(r("x"))}</m:func>"))
    }

    @Test
    fun aLimitUnderAKnownFunctionIsASubscript() {
        assertEquals(
            "\\lim_{n} x",
            latex("<m:func><m:fName><m:limLow>${e(r("lim"))}<m:lim>${r("n")}</m:lim></m:limLow></m:fName>${e(r("x"))}</m:func>"),
        )
    }

    @Test
    fun aMatrix() {
        assertEquals(
            "\\begin{matrix}a & b \\\\ c & d\\end{matrix}",
            latex("<m:m><m:mr>${e(r("a"))}${e(r("b"))}</m:mr><m:mr>${e(r("c"))}${e(r("d"))}</m:mr></m:m>"),
        )
    }

    @Test
    fun accentsAndBars() {
        assertEquals("\\vec{v}", latex("""<m:acc><m:accPr><m:chr m:val="⃗"/></m:accPr>${e(r("v"))}</m:acc>"""))
        assertEquals("\\overline{x}", latex("""<m:bar><m:barPr><m:pos m:val="top"/></m:barPr>${e(r("x"))}</m:bar>"""))
    }

    @Test
    fun unicodeBecomesCommands() {
        assertEquals("\\alpha +\\beta \\le \\infty", latex(r("α+β≤∞").replace(" ", "")).trim())
    }

    @Test
    fun specialCharactersAreEscaped() {
        assertEquals("\\%\\#\\{a\\}", latex(r("%#{a}")))
    }

    @Test
    fun literalTextIsTextAndEscaped() {
        assertEquals(
            "\\text{50\\% of}",
            latex("""<m:r><m:rPr><m:nor/></m:rPr><m:t>50% of</m:t></m:r>"""),
        )
    }

    @Test
    fun boldAndScriptFaces() {
        assertEquals("\\mathbf{x}", latex("""<m:r><m:rPr><m:sty m:val="b"/></m:rPr><m:t>x</m:t></m:r>"""))
        assertEquals("\\mathbb{R}", latex("""<m:r><m:rPr><m:scr m:val="double-struck"/></m:rPr><m:t>R</m:t></m:r>"""))
    }

    /** Styled letters pasted from elsewhere are the letter, not a character RaTeX has no glyph for. */
    @Test
    fun mathematicalAlphanumericsBecomePlainLetters() {
        assertEquals("x", latex(r("𝑥")))
    }

    @Test
    fun anUnknownWrapperKeepsItsContents() {
        assertEquals("y", latex("<m:somethingNew>${r("y")}</m:somethingNew>"))
    }

    @Test
    fun plainTextIsTheCharactersInOrder() {
        val element = parseXml(
            """<m:oMath xmlns:m="$NS_M"><m:f><m:num>${r("1")}</m:num><m:den>${r("2")}</m:den></m:f></m:oMath>""".toByteArray(),
        )
        assertEquals("12", ommlPlainText(element))
    }
}
