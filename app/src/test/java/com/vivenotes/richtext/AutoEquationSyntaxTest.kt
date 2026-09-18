package com.vivenotes.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEquationSyntaxTest {

    @Test
    fun `finds inline dollar and parenthesis equations`() {
        val found = findAutoEquationCandidates("Area is \$r^2\$ and \\(x+y\\).")

        assertEquals(listOf("r^2", "x+y"), found.map { it.latex })
        assertEquals("\$r^2\$", "Area is \$r^2\$ and \\(x+y\\).".substring(found[0].start, found[0].end))
    }

    @Test
    fun `display delimiters request display style`() {
        val found = findAutoEquationCandidates("\$\$\\int_a^b f(x)dx\$\$\n\\[x^2\\]")

        assertEquals(
            listOf("{\\displaystyle \\int_a^b f(x)dx}", "{\\displaystyle x^2}"),
            found.map { it.latex },
        )
    }

    @Test
    fun `finds multiline display blocks and anchors each preview to one source line`() {
        val dollars = "before\n\$\$\n\\frac{a}{b} = c\n\$\$\nafter"
        val brackets = "\\[\n  x^2 + y^2\n= z^2\n\\]"

        val dollarBlock = findAutoEquationCandidates(dollars).single()
        val bracketBlock = findAutoEquationCandidates(brackets).single()

        assertEquals("{\\displaystyle \\frac{a}{b} = c}", dollarBlock.latex)
        assertEquals("\$\$\n\\frac{a}{b} = c\n\$\$", dollars.substring(dollarBlock.start, dollarBlock.end))
        assertEquals("\\frac{a}{b} = c", dollars.substring(dollarBlock.renderStart, dollarBlock.renderEnd))
        assertEquals("{\\displaystyle x^2 + y^2\n= z^2}", bracketBlock.latex)
        assertEquals("x^2 + y^2", brackets.substring(bracketBlock.renderStart, bracketBlock.renderEnd))
    }

    @Test
    fun `inline delimiters do not cross a line break`() {
        assertTrue(findAutoEquationCandidates("\$x + y\nstill prose\$").isEmpty())
        assertTrue(findAutoEquationCandidates("\\(x + y\nstill prose\\)").isEmpty())
    }

    @Test
    fun `recognises a balanced wikipedia displaystyle paragraph`() {
        val source = "{\\displaystyle \\int _{a}^{b}f'(t)\\,dt=f(b)-f(a)}"

        assertEquals(listOf(source), findAutoEquationCandidates(source).map { it.latex })
    }

    @Test
    fun `leaves unfinished and escaped syntax as text`() {
        assertTrue(findAutoEquationCandidates("unfinished \$x^2").isEmpty())
        val escaped = "price \\${'$'}5 and an escaped \\\\( token"
        assertTrue(findAutoEquationCandidates(escaped).isEmpty())
        assertTrue(findAutoEquationCandidates("{\\displaystyle \\frac{1}{2}").isEmpty())
    }

    @Test
    fun `does not mistake ordinary prose for math`() {
        assertTrue(findAutoEquationCandidates("hello x plus 123").isEmpty())
    }

    @Test
    fun `source is revealed only while a focused selection touches it`() {
        val equation = AutoEquationCandidate(start = 10, end = 15, latex = "x^2")

        assertTrue(equation.isBeingEdited(true, 10, 10))
        assertTrue(equation.isBeingEdited(true, 12, 12))
        assertTrue(equation.isBeingEdited(true, 15, 15))
        assertTrue(equation.isBeingEdited(true, 9, 11))
        assertTrue(!equation.isBeingEdited(true, 3, 3))
        assertTrue(!equation.isBeingEdited(false, 12, 12))
    }
}
