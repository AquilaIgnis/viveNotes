package com.vivenotes.data

import com.vivenotes.data.ReviewPrompt.Companion.SESSION_GAP_MS
import com.vivenotes.data.ReviewPrompt.Companion.sessionsReached
import com.vivenotes.data.ReviewPrompt.Companion.startsSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPromptTest {

    @Test
    fun `the first start ever is a session`() {
        assertTrue(startsSession(lastStopped = null, now = 0L))
    }

    @Test
    fun `coming back within the gap is the same session`() {
        assertFalse(startsSession(lastStopped = 1_000L, now = 1_000L + SESSION_GAP_MS - 1))
    }

    @Test
    fun `coming back after the gap is a new session`() {
        assertTrue(startsSession(lastStopped = 1_000L, now = 1_000L + SESSION_GAP_MS))
    }

    @Test
    fun `the session ask is due from the twentieth session until it is made`() {
        assertFalse(sessionsReached(sessions = 19, asked = false))
        assertTrue(sessionsReached(sessions = 20, asked = false))
        assertTrue(sessionsReached(sessions = 45, asked = false))
        assertFalse(sessionsReached(sessions = 45, asked = true))
    }
}
