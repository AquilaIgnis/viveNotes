package com.vivenotes.fileimport.odt

import com.vivenotes.fileimport.docx.parseXml
import org.junit.Assert.assertEquals
import org.junit.Test

class MathMlLatexTest {
    private fun latex(body: String): String = mathMlToLatex(parseXml("""<math xmlns="http://www.w3.org/1998/Math/MathML">$body</math>""".toByteArray()))

    @Test
    fun aFraction() {
        assertEquals("""\frac{a}{b}""", latex("<mfrac><mi>a</mi><mi>b</mi></mfrac>"))
    }

    @Test
    fun scripts() {
        assertEquals("""{x}^{2}""", latex("<msup><mi>x</mi><mn>2</mn></msup>"))
        assertEquals("""{x}_{i}""", latex("<msub><mi>x</mi><mi>i</mi></msub>"))
        assertEquals("""{x}_{i}^{2}""", latex("<msubsup><mi>x</mi><mi>i</mi><mn>2</mn></msubsup>"))
    }

    @Test
    fun roots() {
        assertEquals("""\sqrt{x}""", latex("<msqrt><mi>x</mi></msqrt>"))
        assertEquals("""\sqrt[3]{x}""", latex("<mroot><mi>x</mi><mn>3</mn></mroot>"))
    }

    @Test
    fun operatorsAndGreekBecomeCommands() {
        assertEquals("""\alpha \leq \beta""", latex("<mi>α</mi><mo>≤</mo><mi>β</mi>"))
    }

    @Test
    fun aNamedFunctionIsNotALineOfLetters() {
        assertEquals("""\sin x""", latex("<mi>sin</mi><mi>x</mi>"))
    }

    @Test
    fun aMatrixIsRowsAndColumns() {
        assertEquals(
            """\begin{matrix}a & b \\ c & d\end{matrix}""",
            latex("<mtable><mtr><mtd><mi>a</mi></mtd><mtd><mi>b</mi></mtd></mtr><mtr><mtd><mi>c</mi></mtd><mtd><mi>d</mi></mtd></mtr></mtable>"),
        )
    }

    @Test
    fun theSourceTextIsNotPartOfTheFormula() {
        assertEquals("x", latex("<semantics><mi>x</mi><annotation>x source</annotation></semantics>"))
    }

    @Test
    fun theTextOfAFormulaIsItsLeaves() {
        val math = parseXml("""<math xmlns="http://www.w3.org/1998/Math/MathML"><mfrac><mi>a</mi><mn>2</mn></mfrac><annotation>skip</annotation></math>""".toByteArray())

        assertEquals("a2", mathMlPlainText(math))
    }
}
