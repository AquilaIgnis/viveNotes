package com.vivenotes.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphOutlineTest {

    private val graph = Outline.Graph(id = "g", x = 10f, y = 20f, expression = "((x^2)+(-4))", latex = "x^{2} - 4")

    @Test
    fun roundTripsAndStaysSmallOnDisk() {
        val doc = PageDoc(outlines = listOf(graph))
        val json = doc.encode()
        assertEquals(doc, decodePageDoc(json))
        // Defaults are not written, so a graph nobody has touched is its function and its place.
        assertFalse(json, json.contains("showLabels"))
        assertFalse(json, json.contains("background"))
        assertTrue(json, json.contains("\"t\":\"graph\""))
    }

    @Test
    fun theFirstFunctionIsKeptAcrossRewritesAndReset() {
        val once = graph.rewritten("((x^2)+(-1))", "x^{2} - 1")
        val twice = once.rewritten("(x^2)", "x^{2}")
        assertEquals("((x^2)+(-4))", twice.originalExpression)
        assertEquals("x^{2} - 4", twice.originalLatex)
        assertTrue(twice.isMutated)

        val reset = twice.reset()
        assertEquals(graph.expression, reset.expression)
        assertEquals(graph.latex, reset.latex)
        assertNull(reset.originalExpression)
        assertFalse(reset.isMutated)
    }

    @Test
    fun aGraphIsContentAndItsFunctionIsSearchable() {
        val doc = PageDoc(outlines = listOf(graph))
        assertFalse(doc.isBlank())
        assertEquals("x^{2} - 4", doc.plainText())
    }

    @Test
    fun resizingNeverCollapsesTheBox() {
        val squashed = graph.scaledAbout(0f, 0f, 0.01f, 0.01f)
        assertEquals(Outline.Graph.MIN_SIZE, squashed.width, 0f)
        assertEquals(Outline.Graph.MIN_SIZE, squashed.height, 0f)
    }
}
