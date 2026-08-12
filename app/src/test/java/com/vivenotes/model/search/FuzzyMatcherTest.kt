package com.vivenotes.model.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matcher's three tiers and their ranking — `docs/searchPlan.md` CS6.
 *
 * This is where the feature is actually verified: the panel, the index and the reveal all need a
 * device, and this does not (R10). What is pinned here is the behaviour a user would notice — that a
 * typo still finds the word, that a second term narrows rather than widens, and that the obvious
 * match outranks the clever one.
 */
class FuzzyMatcherTest {

    private fun score(query: String, text: String): Int? = FuzzyMatcher.match(query, text)?.score

    private fun matched(query: String, text: String): String? =
        FuzzyMatcher.match(query, text)?.let { match ->
            match.spans.joinToString("|") { text.substring(it.start, it.end) }
        }

    @Test
    fun `finds a substring regardless of case`() {
        assertEquals("Container", matched("container", "The Container holds text"))
        assertEquals("CONTAINER", matched("container", "A CONTAINER"))
    }

    @Test
    fun `reports the match position, so the editor can select it`() {
        val match = FuzzyMatcher.match("holds", "The container holds text")
        assertNotNull(match)
        assertEquals(14, match!!.start)
        assertEquals(19, match.end)
    }

    @Test
    fun `every term must match`() {
        assertNotNull(FuzzyMatcher.match("container text", "The container holds text"))
        assertNull(FuzzyMatcher.match("container missing", "The container holds text"))
    }

    @Test
    fun `terms may match in any order, and each is marked`() {
        assertEquals("container|text", matched("text container", "The container holds text"))
    }

    @Test
    fun `a single typo still finds the word`() {
        assertNotNull(FuzzyMatcher.match("contaner", "The container holds text"))
        assertNotNull(FuzzyMatcher.match("holsd", "The container holsd text"))
    }

    @Test
    fun `a typo'd prefix finds the word it started`() {
        // Five edits from "container" as whole words, one from its first five characters — which is
        // why the budget is spent against a prefix rather than the whole word.
        assertNotNull(FuzzyMatcher.match("contn", "The container holds text"))
    }

    @Test
    fun `short terms get no typo budget`() {
        assertEquals(0, FuzzyMatcher.typoBudget(3))
        assertEquals(1, FuzzyMatcher.typoBudget(4))
        assertEquals(1, FuzzyMatcher.typoBudget(6))
        assertEquals(2, FuzzyMatcher.typoBudget(7))
        // "cat" must not reach "car" — one edit on a three-letter word is most of the language.
        assertNull(FuzzyMatcher.match("cat", "the car is red"))
    }

    @Test
    fun `letters in order inside one word match, spread across the line do not`() {
        assertNotNull(FuzzyMatcher.match("cntnr", "The container holds text"))
        // a … b … c across the whole line is the classic fuzzy rule, and the one deliberately absent.
        assertNull(FuzzyMatcher.match("thx", "The container holds text"))
    }

    @Test
    fun `an exact hit outranks a typo, which outranks a subsequence`() {
        val exact = score("container", "container")!!
        val typo = score("containr", "container")!!
        val subsequence = score("cntnr", "container")!!
        assertTrue("$exact should beat $typo", exact > typo)
        assertTrue("$typo should beat $subsequence", typo > subsequence)
    }

    @Test
    fun `a match at the start of a word beats one buried inside`() {
        val atStart = score("cat", "cat sat")!!
        val buried = score("cat", "concatenate")!!
        assertTrue("$atStart should beat $buried", atStart > buried)
    }

    @Test
    fun `a word-start occurrence is preferred over an earlier buried one`() {
        assertEquals(12, FuzzyMatcher.match("cat", "concatenate cat")!!.start)
    }

    @Test
    fun `two terms score higher than one, so more of the query means a better answer`() {
        val one = score("container", "The container holds text")!!
        val two = score("container text", "The container holds text")!!
        assertTrue("$two should beat $one", two > one)
    }

    @Test
    fun `an empty or blank query matches nothing`() {
        assertNull(FuzzyMatcher.match("", "The container holds text"))
        assertNull(FuzzyMatcher.match("   ", "The container holds text"))
        assertNull(FuzzyMatcher.match("container", ""))
    }

    @Test
    fun `overlapping term matches are reported as one span`() {
        // "con" and "conta" both land on the same word; a highlight with a seam in it is a bug.
        val match = FuzzyMatcher.match("con conta", "container")
        assertNotNull(match)
        assertEquals(1, match!!.spans.size)
        assertEquals(0, match.spans.first().start)
        assertEquals(5, match.spans.first().end)
    }
}
