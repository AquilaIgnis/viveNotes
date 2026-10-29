package com.vivenotes.richtext

import com.vivenotes.data.ClipboardContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A paste is scanned into runs before any of them is read, so its tables and text can be read at
 * once and its pictures fetched meanwhile — and the result is the one reading them in turn gives.
 */
class CanvasPastePlanTest {
    /** What an item holds, without the ids a read mints afresh each time. */
    private fun CanvasPasteItem.shape(): String = when (this) {
        is CanvasPasteItem.Text -> "text " + blocks.map { "${it.type}:${it.text}" }
        is CanvasPasteItem.Table -> "table " + outline.rows.map { row -> row.cells.map { it.plainText } } +
            " header=${outline.headerRow}"
        is CanvasPasteItem.Image -> "image ${address ?: bytes?.size}"
    }

    private fun page(tables: Int) = buildString {
        append("<h1>Title</h1>")
        repeat(tables) { n ->
            append("<p>Before table $n</p><table><tr><th>Name</th><th>Value</th></tr>")
            repeat(20) { row -> append("<tr><td><b>Row $row</b></td><td>${n * row}</td></tr>") }
            append("</table><img src='https://example.org/picture$n.png'>")
        }
        append("<p>After</p>")
    }

    private fun readAtOnce(plan: CanvasPastePlan) = runBlocking {
        plan.resolve(plan.runs.map { run -> async(Dispatchers.Default) { run.read() } }.awaitAll())
    }

    @Test
    fun runsReadAtOnceGiveWhatReadingThemInTurnGives() {
        val content = ClipboardContent(html = page(tables = 12))
        val inTurn = canvasPasteItems(content).map { it.shape() }
        val atOnce = readAtOnce(CanvasPastePlan(content, emptySet())).map { it.shape() }
        assertEquals(inTurn, atOnce)
        assertEquals("text, a table and a picture per table, then the text after", 12 * 3 + 1, atOnce.size)
    }

    @Test
    fun theScanFindsEveryTableAndPictureBeforeAnythingIsRead() {
        val plan = CanvasPastePlan(ClipboardContent(html = page(tables = 3)), emptySet())
        assertEquals(3, plan.runs.count { it.isTable })
        assertEquals((0 until 3).map { "https://example.org/picture$it.png" }, plan.pictures.map { it.address })
        val items = readAtOnce(plan)
        plan.pictures.forEach { picture -> assertTrue("the plan's own picture comes back", items.any { it === picture }) }
    }

    @Test
    fun aCopiedBitmapWithoutATableNeedsNothingReadAndNothingDownloaded() {
        val bytes = byteArrayOf(1, 2, 3)
        val plan = CanvasPastePlan(ClipboardContent(html = "<p>Preview</p><img src='https://example.org/p.png'>", image = bytes), emptySet())
        assertTrue(plan.runs.isEmpty())
        assertSame(bytes, plan.pictures.single().bytes)
        assertSame(plan.pictures.single(), plan.resolve(emptyList()).single())
    }

    @Test
    fun aTableTooBigForTheGridLeavesACopiedBitmapToWin() {
        val wide = "<table><tr>" + (1..13).joinToString("") { "<td>v$it</td>" } + "</tr></table>"
        val bytes = byteArrayOf(9)
        val plan = CanvasPastePlan(ClipboardContent(html = wide, image = bytes), emptySet())
        val items = readAtOnce(plan)
        assertSame(bytes, (items.single() as CanvasPasteItem.Image).bytes)
    }

    @Test
    fun morePicturesThanOnePastePlacesMakeOneTextBoxAndNoDownloads() {
        val html = (1..130).joinToString("") { "<p>$it</p><img src='https://example.org/$it.png'>" }
        val plan = CanvasPastePlan(ClipboardContent(html = html), emptySet())
        assertTrue(plan.pictures.isEmpty())
        val item = readAtOnce(plan).single() as CanvasPasteItem.Text
        assertTrue(item.blocks.any { it.text == "130" })
        assertEquals(canvasPasteItems(ClipboardContent(html = html)).map { it.shape() }, listOf(item.shape()))
    }

    @Test
    fun aCopiedAddressIsAPictureFoundWithoutMarkup() {
        val plan = CanvasPastePlan(ClipboardContent(text = "https://example.org/photo.png"), emptySet())
        assertTrue(plan.runs.isEmpty())
        assertSame(plan.pictures.single(), plan.resolve(emptyList()).single())
    }
}
