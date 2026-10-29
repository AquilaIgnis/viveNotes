package com.vivenotes.richtext

import org.junit.Assert.assertEquals
import org.junit.Test

/** How typing groups into undo steps: a word at a time, and a run of deletions as one. */
class KeystrokeTest {
    @Test
    fun keystrokesJoinOnlyWithinAWordOrARunOfDeletions() {
        assertEquals(Keystroke(deleting = false, joinable = true), Keystroke.of("ab", "abc", TextSelection(2)))
        assertEquals(Keystroke(deleting = false, joinable = true), Keystroke.of("ab", "ab ", TextSelection(2)))
        assertEquals(Keystroke(deleting = false, joinable = false), Keystroke.of("ab ", "ab c", TextSelection(3)))
        assertEquals(Keystroke(deleting = false, joinable = true), Keystroke.of("", "a", TextSelection(0)))
        assertEquals(Keystroke(deleting = true, joinable = true), Keystroke.of("abc", "ab", TextSelection(3)))
        assertEquals(Keystroke(deleting = true, joinable = true), Keystroke.of("abc", "bc", TextSelection(0)))
        assertEquals(Keystroke(deleting = false, joinable = false), Keystroke.of("abc", "aXc", TextSelection(1, 2)))
        assertEquals(Keystroke(deleting = true, joinable = false), Keystroke.of("abc", "a", TextSelection(1, 3)))
        // The same length, changed: an input method's replacement, never a continuation.
        assertEquals(Keystroke(deleting = false, joinable = false), Keystroke.of("abc", "abd", TextSelection(3)))
    }

    @Test
    fun aNewLineEndsTheWordBeforeIt() {
        assertEquals(Keystroke(deleting = false, joinable = true), Keystroke.of("one", "one\n", TextSelection(3)))
        assertEquals(Keystroke(deleting = false, joinable = false), Keystroke.of("one\n", "one\nt", TextSelection(4)))
    }
}
