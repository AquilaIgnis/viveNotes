package com.vivenotes.richtext

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import com.vivenotes.model.Block
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The pasted-link preview, end to end inside a real editor.
 *
 * Instrumented rather than JVM because every claim here is about `Spannable`, `Layout` and touch
 * dispatch — the URL parsing itself is covered without a device by `VideoLinkTest`.
 */
class VideoEmbedTest {

    @get:Rule
    val compose = createComposeRule()

    private val url = "https://youtu.be/dQw4w9WgXcQ?t=42"

    @Test
    fun drawsTheThumbnailOverTheLinkWithoutTouchingTheDocument() {
        val source = "watch $url later"
        val thumbnails = FakeThumbnails()
        val editor = editorShowing(source, thumbnails)

        compose.waitUntil(timeoutMillis = 5_000) { editor.cards().isNotEmpty() }

        compose.runOnIdle {
            val card = editor.cards().single()
            assertEquals("dQw4w9WgXcQ", card.videoId)
            // The card covers the URL and nothing either side of it.
            assertEquals(source.indexOf(url), editor.text.getSpanStart(card))
            assertEquals(source.indexOf(url) + url.length, editor.text.getSpanEnd(card))

            // The whole point of drawing over the source rather than replacing it: the block is
            // byte for byte what was typed, and carries no mark the preview invented.
            val block = editor.blocks().single()
            assertEquals(source, block.text)
            assertTrue(block.runs.all { it.marks.isEmpty() })
        }
    }

    @Test
    fun stepsAsideWhenTheCaretReachesTheUrl() {
        val source = "watch $url later"
        val editor = editorShowing(source, FakeThumbnails())
        compose.waitUntil(timeoutMillis = 5_000) { editor.cards().isNotEmpty() }

        compose.runOnIdle {
            assertTrue(editor.requestFocus())
            editor.setSelection(source.indexOf(url) + 4)
        }
        compose.waitUntil(timeoutMillis = 2_000) { editor.cards().isEmpty() }
        compose.runOnIdle { assertEquals(source, editor.text.toString()) }

        // And comes back once the caret leaves, from the cache rather than a second fetch.
        compose.runOnIdle { editor.setSelection(0) }
        compose.waitUntil(timeoutMillis = 2_000) { editor.cards().isNotEmpty() }
    }

    @Test
    fun tappingThePlayBadgeOpensThePastedUrl() {
        val editor = editorShowing(url, FakeThumbnails())
        compose.waitUntil(timeoutMillis = 5_000) { editor.cards().isNotEmpty() }

        var opened: String? = null
        compose.runOnIdle { editor.onOpenVideo = { opened = it } }

        compose.runOnIdle {
            val card = editor.cards().single()
            val badge = editor.badgeCenterOf(card)
            editor.tap(badge.first, badge.second)
        }

        compose.runOnIdle {
            // The pasted URL, timestamp included — not a canonical form rebuilt from the id.
            assertEquals(url, opened)
            // The tap never reached the editor, so the card is still up rather than replaced by
            // the raw link the caret would have uncovered.
            assertEquals(1, editor.cards().size)
        }
    }

    @Test
    fun tappingTheCardAwayFromTheBadgeStillPlacesTheCaret() {
        val editor = editorShowing(url, FakeThumbnails())
        compose.waitUntil(timeoutMillis = 5_000) { editor.cards().isNotEmpty() }

        var opened: String? = null
        compose.runOnIdle { editor.onOpenVideo = { opened = it } }

        compose.runOnIdle {
            val card = editor.cards().single()
            val badge = editor.badgeCenterOf(card)
            // Just inside the card's left edge, well clear of the badge in the middle.
            editor.tap(badge.first - card.cardWidthPx / 2f + 6f, badge.second)
        }

        compose.runOnIdle { assertNull("a tap off the badge must not leave the app", opened) }
        // The caret landed in the URL, which is what uncovers it for editing.
        compose.waitUntil(timeoutMillis = 2_000) { editor.cards().isEmpty() }
    }

    /**
     * Switching previews off takes the cards down and stops the fetching, on the page already open.
     *
     * Withholding the source is the entire off switch — `NotesApp` provides null for it — so this is
     * the test of the Settings toggle's actual effect, with the ribbon left to `SettingsTabTest`.
     */
    @Test
    fun withdrawingTheSourceTakesTheCardsDownAndStopsAsking() {
        val thumbnails = FakeThumbnails()
        val editor = editorShowing("watch $url later", thumbnails)
        compose.waitUntil(timeoutMillis = 5_000) { editor.cards().isNotEmpty() }

        val asked = thumbnails.requested.size
        compose.runOnIdle { editor.videoThumbnails = null }

        compose.waitUntil(timeoutMillis = 2_000) { editor.cards().isEmpty() }
        compose.runOnIdle {
            assertEquals("nothing may be requested once previews are off", asked, thumbnails.requested.size)
            assertEquals("watch $url later", editor.blocks().single().text)
        }
    }

    /** Composes one editor showing [text], with [source] as its thumbnails, and returns it laid out. */
    private fun editorShowing(text: String, source: VideoThumbnails?): OutlineEditText {
        var editor: OutlineEditText? = null
        compose.setContent {
            ViveNotesTheme {
                AndroidView(
                    factory = { context ->
                        OutlineEditText(context).apply {
                            videoThumbnails = source
                            setBlocks(listOf(Block.of(text)))
                            editor = this
                        }
                    },
                )
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) { (editor?.width ?: 0) > 0 }
        return checkNotNull(editor)
    }

    private fun OutlineEditText.cards(): List<VideoEmbedSpan> =
        text.getSpans(0, text.length, VideoEmbedSpan::class.java).toList()

    /**
     * Where the play badge sits in the view's own coordinates.
     *
     * Derived from the layout the same way `badgeAt` derives it, because there is no other honest
     * source for it — a hard-coded point would pass while the card moved.
     */
    private fun OutlineEditText.badgeCenterOf(card: VideoEmbedSpan): Pair<Float, Float> {
        val start = text.getSpanStart(card)
        val line = layout.getLineForOffset(start)
        return Pair(
            totalPaddingLeft + layout.getPrimaryHorizontal(start) + card.cardWidthPx / 2f - scrollX,
            totalPaddingTop + layout.getLineBaseline(line) - card.cardHeightPx / 2f - scrollY,
        )
    }

    private fun OutlineEditText.tap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        dispatchTouchEvent(MotionEvent.obtain(down, down + 20, MotionEvent.ACTION_UP, x, y, 0))
    }

    /**
     * A thumbnail source that never leaves the process.
     *
     * Answers the first [cached] with null so the editor takes its asynchronous path — the one a
     * real fetch takes — and only then hands the picture over.
     */
    private class FakeThumbnails : VideoThumbnails {
        val requested = mutableListOf<String>()
        private val ready = mutableSetOf<String>()
        private val bitmap: Bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)

        override fun cached(videoId: String): Bitmap? = if (videoId in ready) bitmap else null

        override fun request(videoId: String, onReady: () -> Unit) {
            requested += videoId
            ready += videoId
            onReady()
        }
    }
}
