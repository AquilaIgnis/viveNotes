package com.vivenotes.fileimport.doc

import com.vivenotes.fileimport.ImportException
import com.vivenotes.fileimport.ImportFailure
import com.vivenotes.fileimport.docx.WordParagraph
import com.vivenotes.fileimport.docx.WordPicture
import com.vivenotes.fileimport.docx.WordTable
import com.vivenotes.fileimport.docx.WordText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Against a real `.doc`, generated once from the same source as the `.odt` fixture. */
class DocReaderTest {
    private val bytes: ByteArray = requireNotNull(javaClass.getResourceAsStream("/fileimport/sample.doc")).readBytes()

    private fun readBytes(file: ByteArray): DocRead = DocReader(CompoundFile.parse(file)).read()

    private val read = readBytes(bytes)
    private val blocks = read.document.sections.single().blocks
    private val paragraphs = blocks.filterIsInstance<WordParagraph>()

    private fun WordParagraph.plain(): String = inlines.filterIsInstance<WordText>().joinToString("") { it.text }

    private fun paragraph(start: String): WordParagraph = paragraphs.first { it.plain().startsWith(start) }

    @Test
    fun aHeadingKeepsItsLevelAndName() {
        val heading = paragraph("Fixture heading")

        assertEquals(0, heading.style.outlineLevel)
        assertEquals("heading 1", heading.style.name.lowercase())
    }

    @Test
    fun runsKeepTheirLook() {
        val runs = paragraph("Plain").inlines.filterIsInstance<WordText>()

        assertTrue(runs.first { it.text == "bold" }.style.bold)
        val red = runs.first { it.text == "red italic" }
        assertTrue(red.style.italic)
        assertEquals(0xFFFF0000.toInt(), red.style.colorArgb)
        assertFalse(runs.first { it.text.startsWith("Plain") }.style.bold)
    }

    @Test
    fun aHyperlinkFieldBecomesALinkAndItsCodeIsGone() {
        val text = paragraph("Plain")

        assertEquals("https://example.com/page", text.inlines.filterIsInstance<WordText>().first { it.text == "link" }.link)
        assertFalse(text.plain().contains("HYPERLINK"))
    }

    @Test
    fun aFootnoteIsLinkedToItsText() {
        val note = read.document.footnotes.values.single().single() as WordParagraph

        assertEquals("The footnote text.", note.plain())
    }

    @Test
    fun listsCountAndBulletsAreBullets() {
        assertEquals(true, paragraph("First bullet").label?.bullet)
        assertEquals("1.", paragraph("First number").label?.text)
        assertEquals("2.", paragraph("Second number").label?.text)
    }

    @Test
    fun aMergedCellSpansItsColumns() {
        val table = blocks.filterIsInstance<WordTable>().single()

        assertEquals(3, table.gridTwips.size)
        assertEquals(listOf(2, 1), table.rows[0].cells.map { it.gridSpan })
        assertEquals("Across two", (table.rows[0].cells[0].blocks.single() as WordParagraph).plain())
        assertEquals(listOf(1, 1, 1), table.rows[1].cells.map { it.gridSpan })
    }

    @Test
    fun anInlinePictureBringsItsBytes() {
        val picture = paragraphs.flatMap { it.inlines }.filterIsInstance<WordPicture>().single()

        val data = assertNotNull(read.pictures[picture.part]).let { read.pictures.getValue(picture.part!!) }
        assertArrayEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), data.copyOf(4))
    }

    @Test
    fun thePageSizeComesFromTheSection() {
        val page = read.document.sections.single().page

        assertEquals(12240, page.widthTwips)
        assertEquals(15840, page.heightTwips)
    }

    /** Where the WordDocument stream starts, found by its opening bytes. */
    private fun fibAt(file: ByteArray): Int =
        (0 until file.size - 1).first { file[it] == 0xEC.toByte() && file[it + 1] == 0xA5.toByte() }

    @Test
    fun anEncryptedFileSaysSo() {
        val file = bytes.copyOf()
        val flags = fibAt(file) + 11
        file[flags] = (file[flags].toInt() or 0x01).toByte()

        val thrown = assertThrows(ImportException::class.java) { readBytes(file) }

        assertEquals(ImportFailure.PasswordProtected, thrown.failure)
    }

    /** Word 6 and 95 lay the file out differently enough that reading on would only produce junk. */
    @Test
    fun aFileFromBeforeWord97IsRefused() {
        val file = bytes.copyOf()
        val version = fibAt(file) + 2
        file[version] = 0x65
        file[version + 1] = 0x00

        val thrown = assertThrows(ImportException::class.java) { readBytes(file) }

        assertEquals(ImportFailure.Unreadable, thrown.failure)
    }
}

class CompoundFileTest {
    private val bytes: ByteArray = requireNotNull(javaClass.getResourceAsStream("/fileimport/sample.doc")).readBytes()

    @Test
    fun itsStreamsAreFoundByName() {
        val compound = CompoundFile.parse(bytes)

        assertTrue(compound.has("WordDocument"))
        assertFalse(compound.has("EncryptedPackage"))
        assertEquals(0xA5EC, requireNotNull(compound.stream("WordDocument")).u16(0))
    }

    @Test
    fun aNameIsMatchedWhateverItsCase() {
        assertTrue(CompoundFile.parse(bytes).has("worddocument"))
    }

    @Test
    fun anAbsentStreamIsNull() {
        assertEquals(null, CompoundFile.parse(bytes).stream("Nothing"))
    }

    @Test
    fun tooShortToBeOneIsUnreadable() {
        val thrown = assertThrows(ImportException::class.java) { CompoundFile.parse(ByteArray(16)) }

        assertEquals(ImportFailure.Unreadable, thrown.failure)
    }

    /** A sector table that points back at itself would otherwise read forever. */
    @Test
    fun aSectorChainThatLoopsStops() {
        val file = bytes.copyOf()
        val fatSector = file.i32(76)
        val shift = file.u16(30)
        val table = (fatSector + 1) shl shift
        for (index in 0 until (1 shl shift) step 4) {
            file[table + index] = (index / 4).toByte()
            file[table + index + 1] = ((index / 4) ushr 8).toByte()
            file[table + index + 2] = 0
            file[table + index + 3] = 0
        }

        // Any failure will do so long as it comes back: the file is now nonsense.
        runCatching { CompoundFile.parse(file).stream("WordDocument") }
    }
}
