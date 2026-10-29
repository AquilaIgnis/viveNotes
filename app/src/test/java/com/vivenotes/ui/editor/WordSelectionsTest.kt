package com.vivenotes.ui.editor

import com.vivenotes.richtext.TextSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which words Ctrl+double-click leaves selected, and what typing at one of them does at the rest. */
class WordSelectionsTest {
    private fun range(id: String, start: Int, end: Int) = SelectedTextRange(id, TextSelection(start, end))

    @Test
    fun ctrlDoubleClickAddsToANormalSelectionAndRepeatingAWordRemovesIt() {
        val selected = addWordSelection(null, "box", "box", TextSelection(11, 16), focused = range("box", 0, 3))
        assertEquals(listOf(range("box", 0, 3), range("box", 11, 16)), selected!!.ranges)
        val one = addWordSelection(selected, "box", "box", TextSelection(0, 3), focused = null)
        assertEquals(listOf(range("box", 11, 16)), one!!.ranges)
        assertNull(addWordSelection(one, "box", "box", TextSelection(11, 16), focused = null))
    }

    @Test
    fun aCaretIsNoSelectionToStartFrom() {
        val selected = addWordSelection(null, "box", "box", TextSelection(4, 10), focused = range("box", 2, 2))
        assertEquals(listOf(range("box", 4, 10)), selected!!.ranges)
    }

    @Test
    fun anOverlappingWordMergesWithTheOneItTouches() {
        val group = WordSelections("box", listOf(range("box", 0, 3)))
        assertEquals(listOf(range("box", 0, 5)), addWordSelection(group, "box", "box", TextSelection(2, 5), null)!!.ranges)
    }

    @Test
    fun wordsInDifferentCellsOfOneTableStayTogether() {
        val right = addWordSelection(null, "table", "right", TextSelection(6, 11), focused = null)
        val both = addWordSelection(right, "table", "left", TextSelection(0, 5), focused = null)!!
        assertEquals(listOf(range("right", 6, 11), range("left", 0, 5)), both.ranges)
        assertEquals("the reading order, not the order they were picked in",
            listOf(range("left", 0, 5), range("right", 6, 11)), orderedTextRanges(both, listOf("left", "right")))
    }

    @Test
    fun aWordInAnotherBoxStartsAGroupOfItsOwn() {
        val box = addWordSelection(null, "one", "one", TextSelection(0, 3), focused = null)
        // The caller passes the focused selection only when it is in the same owner, as here it is not.
        val other = addWordSelection(box, "two", "two", TextSelection(4, 8), focused = null)
        assertEquals(WordSelections("two", listOf(range("two", 4, 8))), other)
    }

    @Test
    fun typingOverTheActiveWordReplacesIt() {
        assertEquals(RangeEdit.Replace("x"), rangeEdit("one middle three", "one middle x", TextSelection(11, 16), TextSelection(12)))
        assertEquals(RangeEdit.Replace(""), rangeEdit("one middle three", "one middle ", TextSelection(11, 16), TextSelection(11)))
    }

    @Test
    fun typingOnAtTheCaretInsertsAndBackspaceOrDeleteRemoves() {
        assertEquals(RangeEdit.Replace("y"), rangeEdit("x middle x", "x middle xy", TextSelection(10), TextSelection(11)))
        assertEquals(RangeEdit.Delete(1, backwards = true), rangeEdit("xy middle xy", "xy middle x", TextSelection(12), TextSelection(11)))
        assertEquals(RangeEdit.Delete(1, backwards = false), rangeEdit("ab cd", "ab d", TextSelection(3), TextSelection(3)))
    }

    @Test
    fun anEditAwayFromTheActiveWordIsNoneOfTheirs() {
        assertNull("an input method rewriting the word behind the caret",
            rangeEdit("teh x", "the x", TextSelection(3), TextSelection(3)))
        assertNull(rangeEdit("same", "same", TextSelection(1), TextSelection(1)))
    }
}
