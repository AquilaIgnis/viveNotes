package com.vivenotes.ink

import com.vivenotes.model.Outline
import com.vivenotes.model.newTable
import org.junit.Assert.assertEquals
import org.junit.Test

class CanvasPasteLayoutTest {
    @Test
    fun stackedObjectsLeaveAMarginAfterTheirMeasuredHeights() {
        val text = Outline.Text.empty()
        val table = newTable(2, 2)
        val image = Outline.Image("picture", attachmentId = "attachment", height = 160f)
        val pasted = stackPastedOutlines(listOf(text, table, image),
            mapOf(text.id to 80f, table.id to 480f, image.id to 160f))
        assertEquals(listOf(0f, 104f, 608f), pasted.map { it.y })
        assertEquals(listOf(text.id, table.id, image.id), pasted.map { it.id })
        assertEquals(text, pasted.first())
        assertEquals(table.copy(y = 104f), pasted[1])
        assertEquals(image.copy(y = 608f), pasted[2])
    }

    /** A frame drawn below its outline's y — a box's grip strip, a table's gutter — keeps the same margin. */
    @Test
    fun insetFramesLeaveTheMarginBetweenWhatIsDrawn() {
        val text = Outline.Text.empty()
        val table = newTable(2, 2)
        val second = Outline.Text.empty()
        val pasted = stackPastedOutlines(listOf(text, table, second),
            mapOf(text.id to 80f, table.id to 100f, second.id to 40f),
            mapOf(text.id to 18f, table.id to 16f, second.id to 18f))
        // The first frame ends at 18 + 80; the table's starts 24 below, at 16 under its y.
        assertEquals(listOf(0f, 106f, 228f), pasted.map { it.y })
    }
}
