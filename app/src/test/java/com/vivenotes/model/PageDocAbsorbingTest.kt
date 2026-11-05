package com.vivenotes.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When a body written without seeing the server's is kept beside it rather than replaced.
 *
 * The null cases matter as much as the merge. Keeping too little loses text somebody typed; keeping
 * too much brings back text somebody deleted, which is the failure that made the server's version win
 * in the first place.
 */
class PageDocAbsorbingTest {

    private fun text(id: String, content: String) =
        Outline.Text(id = id, blocks = listOf(Block.of(content)))

    private val image = Outline.Image(
        id = "picture",
        x = 0f,
        y = 0f,
        width = 10f,
        height = 10f,
        attachmentId = "digest",
    )

    @Test
    fun twoDocumentsWithNoHistoryInCommonAreBothKept() {
        val arrived = PageDoc(outlines = listOf(text("server", "from the server")))
        val written = PageDoc(outlines = listOf(text("here", "typed here")))

        assertEquals(
            listOf("server", "here"),
            arrived.absorbing(written)!!.outlines.map { it.id },
        )
    }

    @Test
    fun whatWasWrittenStaysExactlyWhereItWasWritten() {
        val placed = text("here", "typed here").copy(x = 40f, y = 90f)

        val merged = PageDoc(outlines = listOf(text("server", "s"))).absorbing(PageDoc(outlines = listOf(placed)))

        assertEquals(placed, merged!!.outlines.last())
    }

    @Test
    fun aPageOpenedAndNeverWrittenOnHasNothingToKeep() {
        assertNull(PageDoc(outlines = listOf(text("server", "s"))).absorbing(PageDoc.empty()))
    }

    @Test
    fun documentsSharingAnOutlineShareHistorySoTheServerStillWins() {
        val arrived = PageDoc(outlines = listOf(text("kept", "still here")))
        val oldCopy = PageDoc(outlines = listOf(text("kept", "still here"), text("deleted", "removed elsewhere")))

        assertNull(arrived.absorbing(oldCopy))
    }

    @Test
    fun anOutlineThatIsNotTextIsContentByExisting() {
        val merged = PageDoc(outlines = listOf(text("server", "s"))).absorbing(PageDoc(outlines = listOf(image)))

        assertEquals(listOf("server", "picture"), merged!!.outlines.map { it.id })
    }

    @Test
    fun emptyBoxesWrittenHereAreNotCarriedAcross() {
        val written = PageDoc(outlines = listOf(Outline.Text.empty(), text("here", "typed here")))

        val merged = PageDoc(outlines = listOf(text("server", "s"))).absorbing(written)

        assertEquals(listOf("server", "here"), merged!!.outlines.map { it.id })
    }

    @Test
    fun aPageCreatedElsewhereAndNeverWrittenOnLosesItsEmptyBoxButKeepsItsStyle() {
        val style = PageStyle(hideTitle = true)
        val arrived = PageDoc.empty(style)

        val merged = arrived.absorbing(PageDoc(outlines = listOf(text("here", "typed here"))))

        assertEquals(listOf("here"), merged!!.outlines.map { it.id })
        assertEquals(style, merged.style)
    }
}
