package com.vivenotes.fileimport.odt

import com.vivenotes.fileimport.ImportException
import com.vivenotes.fileimport.ImportFailure
import com.vivenotes.fileimport.docx.WordEquation
import com.vivenotes.fileimport.docx.WordParagraph
import com.vivenotes.fileimport.docx.WordPicture
import com.vivenotes.fileimport.docx.WordRenderedBreak
import com.vivenotes.fileimport.docx.WordSection
import com.vivenotes.fileimport.docx.WordTable
import com.vivenotes.fileimport.docx.WordText
import com.vivenotes.fileimport.docx.WordVerticalMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OdtReaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val namespaces = listOf(
        """xmlns:office="$NS_OFFICE"""",
        """xmlns:style="$NS_STYLE"""",
        """xmlns:text="$NS_TEXT"""",
        """xmlns:table="$NS_TABLE"""",
        """xmlns:draw="$NS_DRAW"""",
        """xmlns:fo="$NS_FO"""",
        """xmlns:svg="$NS_SVG"""",
        """xmlns:xlink="$NS_XLINK"""",
        """xmlns:loext="$NS_LOEXT"""",
    ).joinToString(" ")

    private fun zip(entries: Map<String, String>): File {
        val file = folder.newFile("made-${entries.hashCode()}.odt")
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    private fun documentWith(body: String, automatic: String = "", extra: Map<String, String> = emptyMap()): File = zip(
        mapOf(
            "mimetype" to "application/vnd.oasis.opendocument.text",
            "content.xml" to
                """<office:document-content $namespaces><office:automatic-styles>$automatic</office:automatic-styles><office:body><office:text>$body</office:text></office:body></office:document-content>""",
        ) + extra,
    )

    private fun read(file: File): OdtRead = OdtPackage.open(file).use { OdtReader(it).read() }

    private fun section(body: String, automatic: String = ""): WordSection = read(documentWith(body, automatic)).document.sections.single()

    private fun firstParagraph(body: String, automatic: String = ""): WordParagraph = section(body, automatic).blocks.first() as WordParagraph

    private fun WordParagraph.plain(): String = inlines.filterIsInstance<WordText>().joinToString("") { it.text }

    private val sample: OdtRead by lazy {
        val file = folder.newFile("sample.odt")
        file.writeBytes(requireNotNull(javaClass.getResourceAsStream("/fileimport/sample.odt")).readBytes())
        read(file)
    }

    private val sampleParagraphs get() = sample.document.sections.single().blocks.filterIsInstance<WordParagraph>()

    private fun sampleParagraph(start: String): WordParagraph = sampleParagraphs.first { it.plain().startsWith(start) }

    @Test
    fun aRealFileKeepsHeadingsAndTheirNames() {
        val heading = sampleParagraph("Fixture heading")

        assertEquals(0, heading.style.outlineLevel)
        assertEquals("Heading 1", heading.style.name)
    }

    @Test
    fun aRealFileKeepsRunsAndLinks() {
        val runs = sampleParagraph("Plain").inlines.filterIsInstance<WordText>()

        assertTrue(runs.first { it.text == "bold" }.style.bold)
        assertEquals(0xFFFF0000.toInt(), runs.first { it.text == "red italic" }.style.colorArgb)
        assertEquals("https://example.com/page", runs.first { it.text == "link" }.link)
    }

    @Test
    fun aRealFileKeepsItsFootnotes() {
        assertEquals("The footnote text.", (sample.document.footnotes.values.single().single() as WordParagraph).plain())
    }

    @Test
    fun aRealFilesListsCount() {
        assertEquals(true, sampleParagraph("First bullet").label?.bullet)
        assertEquals("1.", sampleParagraph("First number").label?.text)
        assertEquals("2.", sampleParagraph("Second number").label?.text)
    }

    @Test
    fun aRealFilesTableMergesCells() {
        val table = sample.document.sections.single().blocks.filterIsInstance<WordTable>().single()

        assertEquals(listOf(2, 1), table.rows[0].cells.map { it.gridSpan })
        assertEquals(listOf(1, 1, 1), table.rows[1].cells.map { it.gridSpan })
    }

    @Test
    fun aRealFilesPictureNamesAPart() {
        val picture = sampleParagraphs.flatMap { it.inlines }.filterIsInstance<WordPicture>().single()

        assertTrue(picture.part.orEmpty().startsWith("Pictures/"))
        assertEquals(1440, picture.widthTwips)
    }

    @Test
    fun spacesCollapseAcrossSpansAndOpeningOnesGo() {
        val paragraph = firstParagraph("""<text:p>  one   two <text:span> three</text:span></text:p>""")

        assertEquals("one two three", paragraph.plain())
    }

    @Test
    fun anExplicitSpaceElementIsKept() {
        assertEquals("a   b", firstParagraph("""<text:p>a<text:s text:c="3"/>b</text:p>""").plain())
    }

    @Test
    fun aSoftPageBreakIsAHintOnTheNextParagraph() {
        val blocks = section("""<text:p>one</text:p><text:soft-page-break/><text:p>two</text:p>""").blocks

        assertTrue((blocks[1] as WordParagraph).inlines.first() === WordRenderedBreak)
    }

    private val numbers =
        """<text:list-style style:name="L"><text:list-level-style-number text:level="1" style:num-format="1" style:num-suffix="."/><text:list-level-style-number text:level="2" style:num-format="a" text:display-levels="2" style:num-suffix=")"/></text:list-style>"""

    private fun labels(body: String): List<String?> = section(body, numbers).blocks.map { (it as WordParagraph).label?.text }

    private fun item(text: String) = """<text:list-item><text:p>$text</text:p></text:list-item>"""

    @Test
    fun aNestedListCountsPerLevelAndShowsTheLevelsAskedFor() {
        val nested = """<text:list-item><text:p>b</text:p><text:list>${item("c")}${item("d")}</text:list></text:list-item>"""

        assertEquals(listOf("1.", "2.", "2.a)", "2.b)", "3."), labels("""<text:list text:style-name="L">${item("a")}$nested${item("e")}</text:list>"""))
    }

    @Test
    fun aNewListStartsAgain() {
        val list = """<text:list text:style-name="L">${item("a")}</text:list>"""

        assertEquals(listOf("1.", "1."), labels(list + list))
    }

    @Test
    fun aListThatContinuesCarriesOn() {
        val first = """<text:list text:style-name="L">${item("a")}</text:list>"""
        val second = """<text:list text:style-name="L" text:continue-numbering="true">${item("b")}</text:list>"""

        assertEquals(listOf("1.", "2."), labels(first + second))
    }

    @Test
    fun aSecondParagraphInAnItemHasNoLabel() {
        val item = """<text:list-item><text:p>a</text:p><text:p>b</text:p></text:list-item>"""

        assertEquals(listOf("1.", null), labels("""<text:list text:style-name="L">$item</text:list>"""))
    }

    @Test
    fun aCellSpanningRowsContinuesInTheRowBelow() {
        val table = section(
            """<table:table><table:table-column table:number-columns-repeated="2"/>
               <table:table-row><table:table-cell table:number-rows-spanned="2"><text:p>a</text:p></table:table-cell><table:table-cell><text:p>b</text:p></table:table-cell></table:table-row>
               <table:table-row><table:covered-table-cell/><table:table-cell><text:p>c</text:p></table:table-cell></table:table-row></table:table>""",
        ).blocks.single() as WordTable

        assertEquals(WordVerticalMerge.Restart, table.rows[0].cells[0].verticalMerge)
        assertEquals(WordVerticalMerge.Continue, table.rows[1].cells[0].verticalMerge)
        assertEquals(2, table.rows[1].cells.size)
    }

    /** LibreOffice writes a table inside a text box under its own namespace. */
    @Test
    fun aTableInLibreOfficesOwnNamespaceIsStillATable() {
        val box = """<draw:frame text:anchor-type="as-char" svg:width="2in" svg:height="1in"><draw:text-box>
            <loext:table><loext:table-column/><loext:table-row><loext:table-cell><text:p>inside</text:p></loext:table-cell></loext:table-row></loext:table>
            </draw:text-box></draw:frame>"""
        val shape = firstParagraph("""<text:p>$box</text:p>""").inlines.filterIsInstance<com.vivenotes.fileimport.docx.WordShape>().single()

        assertTrue(shape.blocks.single() is WordTable)
    }

    @Test
    fun aFormulaBecomesLatex() {
        val math = """<math xmlns="http://www.w3.org/1998/Math/MathML"><semantics><mfrac><mi>a</mi><mn>2</mn></mfrac><annotation>a over 2</annotation></semantics></math>"""
        val file = documentWith(
            """<text:p><draw:frame text:anchor-type="as-char"><draw:object xlink:href="./Object 1"/></draw:frame></text:p>""",
            extra = mapOf("Object 1/content.xml" to math),
        )

        val equation = (read(file).document.sections.single().blocks.first() as WordParagraph).inlines.single() as WordEquation

        assertEquals("""\frac{a}{2}""", equation.latex)
    }

    @Test
    fun anotherOpenDocumentKindIsNotAText() {
        val file = zip(mapOf("mimetype" to "application/vnd.oasis.opendocument.spreadsheet", "content.xml" to "<a/>"))

        assertFalse(OdtPackage.isOdt(file))
    }

    @Test
    fun aZipWithNoMediaTypeIsNotAnOdt() {
        assertFalse(OdtPackage.isOdt(zip(mapOf("word/document.xml" to "<a/>"))))
    }

    @Test
    fun aPasswordProtectedFileSaysSo() {
        val file = documentWith("", extra = mapOf("META-INF/manifest.xml" to """<manifest><file-entry><encryption-data/></file-entry></manifest>"""))

        val thrown = assertThrows(ImportException::class.java) { OdtPackage.open(file) }

        assertEquals(ImportFailure.PasswordProtected, thrown.failure)
    }

    @Test
    fun aFileWithNoBodyIsNotADocument() {
        val file = zip(mapOf("mimetype" to "application/vnd.oasis.opendocument.text", "content.xml" to """<office:document-content $namespaces/>"""))

        val thrown = assertThrows(ImportException::class.java) { read(file) }

        assertEquals(ImportFailure.NotWord, thrown.failure)
    }

    @Test
    fun aPackageFindsPartsAsWritten() {
        val file = documentWith("", extra = mapOf("Pictures/a.png" to "x"))

        OdtPackage.open(file).use { pkg ->
            assertNotNull(pkg.bytes("./Pictures/a.png"))
            assertNull(pkg.bytes("pictures/A.PNG"))
        }
    }
}
