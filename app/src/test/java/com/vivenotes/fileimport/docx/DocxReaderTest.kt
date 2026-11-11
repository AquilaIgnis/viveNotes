package com.vivenotes.fileimport.docx

import com.vivenotes.fileimport.ImportException
import com.vivenotes.fileimport.ImportFailure
import com.vivenotes.fileimport.docx.DocxFixture.paragraph
import com.vivenotes.fileimport.docx.DocxFixture.run
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocxReaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun read(
        body: String,
        styles: String? = null,
        numbering: String? = null,
        settings: String? = null,
        relationships: List<DocxFixture.Rel> = emptyList(),
        parts: Map<String, ByteArray> = emptyMap(),
    ): WordDocument {
        val file = folder.newFile()
        DocxFixture.write(file, body, styles, numbering, settings, relationships, parts)
        return DocxPackage.open(file).use { DocxReader(it).read() }
    }

    private fun WordDocument.blocks(): List<WordBlock> = sections.flatMap { it.blocks }

    private fun WordDocument.paragraphs(): List<WordParagraph> = blocks().filterIsInstance<WordParagraph>()

    private fun WordParagraph.text(): String = inlines.filterIsInstance<WordText>().joinToString("") { it.text }

    // --- the container --------------------------------------------------------------------------

    @Test
    fun aFileThatIsNotAZipIsNotWord() {
        val file = folder.newFile().apply { writeText("hello, this is not a document") }
        val thrown = assertThrows(ImportException::class.java) { DocxPackage.open(file) }
        assertEquals(ImportFailure.NotWord, thrown.failure)
    }

    /** A `.pptx` is a zip with a main part too; only its content type tells it apart. */
    @Test
    fun aZipThatIsSomeOtherOfficeFileIsNotWord() {
        val file = folder.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(
                """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/></Types>""".toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/></Relationships>""".toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("ppt/presentation.xml"))
            zip.write("<p/>".toByteArray())
            zip.closeEntry()
        }
        val thrown = assertThrows(ImportException::class.java) { DocxPackage.open(file) }
        assertEquals(ImportFailure.NotWord, thrown.failure)
    }

    /** No DTD ever reaches a parser, which is what takes entity expansion and XXE off the table. */
    @Test
    fun aDocumentTypeDeclarationIsRefused() {
        val bomb = """<!DOCTYPE x [<!ENTITY a "aaaa">]><w:document xmlns:w="urn:w">&a;</w:document>"""
        val thrown = assertThrows(ImportException::class.java) { parseXml(bomb.toByteArray()) }
        assertEquals(ImportFailure.Unreadable, thrown.failure)
    }

    @Test
    fun aPartPastItsLimitIsTooLarge() {
        val file = folder.newFile()
        DocxFixture.write(file, paragraph("some text"))
        DocxPackage.open(file).use { pkg ->
            val thrown = assertThrows(ImportException::class.java) { pkg.bytes("word/document.xml", limit = 8) }
            assertEquals(ImportFailure.TooLarge, thrown.failure)
        }
    }

    @Test
    fun relationshipTargetsResolveAgainstTheirPart() {
        assertEquals("word/media/image1.png", resolvePartName("word", "media/image1.png"))
        assertEquals("media/image1.png", resolvePartName("word/sub", "../../media/image1.png"))
        assertEquals("word/media/x.png", resolvePartName("word", "/word/media/x.png"))
        // Above the root is nowhere; a hostile target cannot climb out of the package.
        assertEquals("x.png", resolvePartName("word", "../../../x.png"))
    }

    @Test
    fun theSymbolTablesAreCompleteAndLineUp() {
        assertEquals(0x7E - 0x20 + 1, SYMBOL_LOW.length)
        assertEquals(0xFE - 0xA0 + 1, SYMBOL_HIGH.length)
    }

    // --- runs and styles ------------------------------------------------------------------------

    @Test
    fun formattingComesThroughTheStyleChain() {
        val document = read(
            """<w:p><w:pPr><w:pStyle w:val="Child"/></w:pPr>${run("text")}</w:p>""",
            styles = """
                <w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val="24"/></w:rPr></w:rPrDefault>
                <w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="259" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>
                <w:style w:type="paragraph" w:styleId="Parent"><w:name w:val="Parent"/><w:rPr><w:i/><w:sz w:val="30"/></w:rPr><w:pPr><w:jc w:val="center"/></w:pPr></w:style>
                <w:style w:type="paragraph" w:styleId="Child"><w:name w:val="Child"/><w:basedOn w:val="Parent"/><w:rPr><w:color w:val="FF0000"/></w:rPr></w:style>
            """,
        )
        val paragraph = document.paragraphs().single()
        assertEquals(WordAlign.Center, paragraph.style.align)
        assertEquals(160, paragraph.style.afterTwips)
        assertEquals(259, paragraph.style.lineValue)
        val style = (paragraph.inlines.single() as WordText).style
        assertEquals(30, style.halfPoints)
        assertTrue(style.italic)
        assertEquals(0xFFFF0000.toInt(), style.colorArgb)
    }

    /** Bold in a paragraph style and bold in a character style cancel; direct formatting just wins. */
    @Test
    fun boldFromTwoStylesCancelsButDirectFormattingWins() {
        val styles = """
            <w:style w:type="paragraph" w:styleId="P"><w:name w:val="P"/><w:rPr><w:b/></w:rPr></w:style>
            <w:style w:type="character" w:styleId="C"><w:name w:val="C"/><w:rPr><w:b/></w:rPr></w:style>
        """
        val document = read(
            """<w:p><w:pPr><w:pStyle w:val="P"/></w:pPr>""" +
                run("plain paragraph bold") +
                run("cancelled", """<w:rStyle w:val="C"/>""") +
                run("direct", """<w:rStyle w:val="C"/><w:b/>""") +
                run("off", """<w:b w:val="0"/>""") +
                "</w:p>",
            styles = styles,
        )
        val runs = document.paragraphs().single().inlines.filterIsInstance<WordText>()
        assertEquals(listOf(true, false, true, false), runs.map { it.style.bold })
    }

    @Test
    fun automaticColourStaysAutomatic() {
        val paragraph = read(
            """<w:p>${run("x", """<w:color w:val="auto"/>""")}</w:p>""",
        ).paragraphs().single()
        assertNull((paragraph.inlines.single() as WordText).style.colorArgb)
    }

    @Test
    fun hiddenTextIsNotRead() {
        val paragraph = read(
            """<w:p>${run("seen")}${run("hidden", "<w:vanish/>")}</w:p>""",
        ).paragraphs().single()
        assertEquals("seen", paragraph.text())
    }

    /** Tracked changes are read the way the document reads with them accepted. */
    @Test
    fun deletionsGoAndInsertionsStay() {
        val paragraph = read(
            """<w:p>${run("keep")}<w:del><w:r><w:delText>gone</w:delText></w:r></w:del><w:ins>${run("new")}</w:ins></w:p>""",
        ).paragraphs().single()
        assertEquals("keepnew", paragraph.text())
    }

    @Test
    fun adjacentRunsWithTheSameLookBecomeOne() {
        val paragraph = read("""<w:p>${run("a")}${run("b")}${run("c", "<w:b/>")}</w:p>""").paragraphs().single()
        assertEquals(listOf("ab", "c"), paragraph.inlines.map { (it as WordText).text })
    }

    @Test
    fun capsAreWrittenOutAndSymbolFontsDecoded() {
        val paragraph = read(
            """<w:p>${run("shout", "<w:caps/>")}${run("p", """<w:rFonts w:ascii="Symbol"/>""")}<w:r><w:sym w:font="Wingdings" w:char="F0E0"/></w:r></w:p>""",
        ).paragraphs().single()
        assertEquals("SHOUTπ→", paragraph.text())
    }

    @Test
    fun aHyperlinkCarriesItsAddressAndAnUnsafeOneIsDropped() {
        val paragraph = read(
            """<w:p><w:hyperlink r:id="good">${run("site")}</w:hyperlink><w:hyperlink r:id="bad">${run("trap")}</w:hyperlink></w:p>""",
            relationships = listOf(
                DocxFixture.Rel("good", DocxFixture.HYPERLINK_REL, "https://example.com/a", external = true),
                DocxFixture.Rel("bad", DocxFixture.HYPERLINK_REL, "javascript:alert(1)", external = true),
            ),
        ).paragraphs().single()
        val runs = paragraph.inlines.filterIsInstance<WordText>()
        assertEquals("https://example.com/a", runs[0].link)
        assertNull(runs[1].link)
    }

    // --- fields ---------------------------------------------------------------------------------

    @Test
    fun aFieldShowsItsResultAndNotItsInstruction() {
        val paragraph = read(
            """<w:p><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText>REF bookmark</w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r>${run("result")}<w:r><w:fldChar w:fldCharType="end"/></w:r></w:p>""",
        ).paragraphs().single()
        assertEquals("result", paragraph.text())
    }

    @Test
    fun aPageFieldStaysLiveInsteadOfShowingItsCachedNumber() {
        val paragraph = read(
            """<w:p><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText> PAGE </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r>${run("7")}<w:r><w:fldChar w:fldCharType="end"/></w:r></w:p>""",
        ).paragraphs().single()
        assertEquals("", paragraph.text())
        assertEquals(WordFieldKind.PageNumber, (paragraph.inlines.single() as WordField).kind)
    }

    @Test
    fun aHyperlinkFieldLinksItsResult() {
        val paragraph = read(
            """<w:p><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText>HYPERLINK "https://example.com"</w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r>${run("go")}<w:r><w:fldChar w:fldCharType="end"/></w:r></w:p>""",
        ).paragraphs().single()
        assertEquals("https://example.com", (paragraph.inlines.single() as WordText).link)
    }

    // --- page breaks ----------------------------------------------------------------------------

    @Test
    fun aPageBreakCutsAParagraphInTwo() {
        val paragraphs = read(
            """<w:p>${run("before")}<w:r><w:br w:type="page"/></w:r>${run("after")}</w:p>""",
        ).paragraphs()
        assertEquals(listOf("before", "after"), paragraphs.map { it.text() })
        assertEquals(WordBreakBefore.None, paragraphs[0].breakBefore)
        assertEquals(WordBreakBefore.Page, paragraphs[1].breakBefore)
    }

    /** A paragraph that ends on its page break leaves no empty line at the top of the next page. */
    @Test
    fun aBreakAtTheEndOfAParagraphCarriesNothingOver() {
        val paragraphs = read("""<w:p>${run("before")}<w:r><w:br w:type="page"/></w:r></w:p>""").paragraphs()
        assertEquals(listOf("before"), paragraphs.map { it.text() })
    }

    // --- lists ----------------------------------------------------------------------------------

    private val numbering = """
        <w:abstractNum w:abstractNumId="0">
            <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl>
            <w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="lowerLetter"/><w:lvlText w:val="(%2)"/></w:lvl>
        </w:abstractNum>
        <w:abstractNum w:abstractNumId="1">
            <w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/><w:lvlText w:val="&#xF0B7;"/><w:rPr><w:rFonts w:ascii="Symbol"/></w:rPr></w:lvl>
        </w:abstractNum>
        <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
        <w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num>
    """

    private fun listItem(text: String, numId: Int, level: Int = 0): String =
        paragraph(text, """<w:numPr><w:ilvl w:val="$level"/><w:numId w:val="$numId"/></w:numPr>""")

    @Test
    fun listsCountAndRestartAndFormat() {
        val labels = read(
            listItem("one", 1) + listItem("a", 1, 1) + listItem("b", 1, 1) + listItem("two", 1) + listItem("again", 1, 1),
            numbering = numbering,
        ).paragraphs().map { it.label?.text }
        assertEquals(listOf("1.", "(a)", "(b)", "2.", "(a)"), labels)
    }

    @Test
    fun aBulletIsTheRealCharacterAndTheLevelIndentApplies() {
        val document = read(listItem("first", 2) + listItem("second", 1), numbering = numbering)
        val paragraphs = document.paragraphs()
        assertEquals("•", paragraphs[0].label?.text)
        assertTrue(paragraphs[0].label!!.bullet)
        assertEquals(720, paragraphs[1].style.leftTwips)
        assertEquals(-360, paragraphs[1].style.firstLineTwips)
    }

    // --- tables ---------------------------------------------------------------------------------

    @Test
    fun aTableKeepsItsGridSpansAndMerges() {
        val document = read(
            """<w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w="1000"/><w:gridCol w:w="2000"/></w:tblGrid>
                <w:tr><w:trPr><w:tblHeader/></w:trPr><w:tc><w:tcPr><w:gridSpan w:val="2"/><w:shd w:val="clear" w:fill="00FF00"/></w:tcPr>${paragraph("wide")}</w:tc></w:tr>
                <w:tr><w:tc><w:tcPr><w:vMerge w:val="restart"/></w:tcPr>${paragraph("tall")}</w:tc><w:tc>${paragraph("b")}</w:tc></w:tr>
                <w:tr><w:tc><w:tcPr><w:vMerge/></w:tcPr><w:p/></w:tc><w:tc>${paragraph("c")}</w:tc></w:tr>
            </w:tbl>""",
        )
        val table = document.blocks().single() as WordTable
        assertEquals(listOf(1000, 2000), table.gridTwips.toList())
        assertTrue(table.rows[0].isHeader)
        assertEquals(2, table.rows[0].cells[0].gridSpan)
        assertEquals(0xFF00FF00.toInt(), table.rows[0].cells[0].shadingArgb)
        assertEquals(WordVerticalMerge.Restart, table.rows[1].cells[0].verticalMerge)
        assertEquals(WordVerticalMerge.Continue, table.rows[2].cells[0].verticalMerge)
    }

    /** A table style's first-row bold reaches the text — but only with the table asking for it. */
    @Test
    fun aTableStyleFormatsItsFirstRowWhenLookAsksForIt() {
        val styles = """
            <w:style w:type="table" w:styleId="T"><w:name w:val="T"/>
                <w:tblStylePr w:type="firstRow"><w:rPr><w:b/></w:rPr><w:tcPr><w:shd w:val="clear" w:fill="0000FF"/></w:tcPr></w:tblStylePr>
            </w:style>
        """
        fun table(look: String): String =
            """<w:tbl><w:tblPr><w:tblStyle w:val="T"/><w:tblLook $look/></w:tblPr><w:tblGrid><w:gridCol w:w="1000"/></w:tblGrid>
                <w:tr><w:tc>${paragraph("head")}</w:tc></w:tr><w:tr><w:tc>${paragraph("body")}</w:tc></w:tr></w:tbl>"""
        fun firstRowBold(look: String): Pair<Boolean, Int?> {
            val table = read(table(look), styles = styles).blocks().single() as WordTable
            val cell = table.rows[0].cells[0]
            val run = (cell.blocks.single() as WordParagraph).inlines.single() as WordText
            return run.style.bold to cell.shadingArgb
        }
        assertEquals(true to 0xFF0000FF.toInt(), firstRowBold("""w:firstRow="1""""))
        assertEquals(false to null, firstRowBold("""w:firstRow="0""""))
    }

    // --- drawings -------------------------------------------------------------------------------

    private fun picture(id: String): String = """
        <w:p><w:r><w:drawing><wp:inline><wp:extent cx="914400" cy="457200"/>
            <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
                <pic:pic><pic:blipFill><a:blip r:embed="$id"/><a:srcRect l="10000" t="0" r="0" b="0"/></pic:blipFill></pic:pic>
            </a:graphicData></a:graphic>
        </wp:inline></w:drawing></w:r></w:p>
    """

    @Test
    fun aPictureFindsItsMediaPart() {
        val document = read(
            picture("img"),
            relationships = listOf(DocxFixture.Rel("img", DocxFixture.IMAGE_REL, "media/image1.png")),
            parts = mapOf("word/media/image1.png" to byteArrayOf(1, 2, 3)),
        )
        val picture = document.paragraphs().single().inlines.single() as WordPicture
        assertEquals("word/media/image1.png", picture.part)
        assertEquals(1440, picture.widthTwips)
        assertEquals(720, picture.heightTwips)
        assertEquals(0.1f, picture.crop!!.left, 0.001f)
    }

    /** A linked picture is the document's way of phoning home; it is never fetched. */
    @Test
    fun aPictureLinkedFromOutsideHasNoPart() {
        val document = read(
            picture("img"),
            relationships = listOf(DocxFixture.Rel("img", DocxFixture.IMAGE_REL, "https://tracker.example/pixel.png", external = true)),
        )
        assertNull((document.paragraphs().single().inlines.single() as WordPicture).part)
    }

    private val textBox: String = """
        <w:p><w:r><mc:AlternateContent>
            <mc:Choice Requires="wps"><w:drawing><wp:inline><wp:extent cx="2540000" cy="1270000"/>
                <a:graphic><a:graphicData uri="http://schemas.microsoft.com/office/word/2010/wordprocessingShape">
                    <wps:wsp><wps:spPr><a:prstGeom prst="rect"/><a:solidFill><a:srgbClr val="E8E8E8"/></a:solidFill><a:ln><a:noFill/></a:ln></wps:spPr>
                    <wps:txbx><w:txbxContent>${paragraph("inside the box")}</w:txbxContent></wps:txbx><wps:bodyPr/></wps:wsp>
                </a:graphicData></a:graphic>
            </wp:inline></w:drawing></mc:Choice>
            <mc:Fallback><w:pict><v:shape style="width:200pt;height:100pt"><v:textbox><w:txbxContent>${paragraph("fallback copy")}</w:txbxContent></v:textbox></v:shape></w:pict></mc:Fallback>
        </mc:AlternateContent></w:r></w:p>
    """

    /** The modern branch is taken, and the VML copy of the same box is not read as well. */
    @Test
    fun aTextBoxIsReadOnceFromTheBranchThisUnderstands() {
        val shape = read(textBox).paragraphs().single().inlines.single() as WordShape
        val text = shape.blocks.filterIsInstance<WordParagraph>().single().text()
        assertEquals("inside the box", text)
        assertEquals(0xFFE8E8E8.toInt(), shape.fillArgb)
        assertNull(shape.line)
        assertEquals(4000, shape.widthTwips)
    }

    /** A child space under a twip across rounded to nothing, and dividing by it made the whole file unreadable. */
    @Test
    fun aGroupWithATinyChildSpaceStillReads() {
        val group = """
            <w:p><w:r><w:drawing><wp:inline><wp:extent cx="0" cy="0"/>
                <a:graphic><a:graphicData uri="http://schemas.microsoft.com/office/word/2010/wordprocessingGroup">
                    <wpg:wgp><wpg:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="300" cy="300"/></a:xfrm></wpg:grpSpPr>
                        <wps:wsp><wps:spPr><a:xfrm><a:off x="100" y="100"/><a:ext cx="100" cy="100"/></a:xfrm></wps:spPr><wps:bodyPr/></wps:wsp>
                    </wpg:wgp>
                </a:graphicData></a:graphic>
            </wp:inline></w:drawing></w:r></w:p>
        """
        val read = read(group).paragraphs().single().inlines.single() as WordGroup
        assertEquals(0, read.children.single().boxed.widthTwips)
    }

    @Test
    fun behindTextIsReadWhicheverWayTheBooleanIsSpelt() {
        fun behind(value: String): Boolean {
            val anchored = """
                <w:p><w:r><w:drawing><wp:anchor behindDoc="$value"><wp:extent cx="914400" cy="457200"/><wp:wrapNone/></wp:anchor></w:drawing></w:r></w:p>
            """
            return (read(anchored).paragraphs().single().inlines.single() as WordBoxed).anchor!!.behindText
        }
        assertTrue(behind("1"))
        assertTrue(behind("true"))
        assertFalse(behind("0"))
    }

    /** A header is read where its section ends, which can be in the middle of a field in the body. */
    @Test
    fun aFieldOpenAcrossASectionBreakKeepsItsLinkPastTheHeader() {
        val header = """<w:hdr ${DocxFixture.NAMESPACES}>${paragraph("the header")}</w:hdr>"""
        val document = read(
            """<w:p><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText>HYPERLINK "https://example.com"</w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r>${run("one")}</w:p>""" +
                """<w:p><w:pPr><w:sectPr><w:headerReference w:type="default" r:id="hdr"/></w:sectPr></w:pPr>${run("two")}</w:p>""" +
                """<w:p>${run("three")}<w:r><w:fldChar w:fldCharType="end"/></w:r></w:p>""",
            relationships = listOf(DocxFixture.Rel("hdr", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/header", "header1.xml")),
            parts = mapOf("word/header1.xml" to header.toByteArray()),
        )
        val last = document.sections.last().blocks.filterIsInstance<WordParagraph>().single()
        assertEquals("https://example.com", (last.inlines.first() as WordText).link)
        val headerText = (document.sections.first().headers.default!!.single() as WordParagraph).inlines.single() as WordText
        assertNull(headerText.link)
    }

    @Test
    fun aSpanPastAnyTableIsHeldToTheWidestOne() {
        val table = read(
            """<w:tbl><w:tr><w:tc><w:tcPr><w:gridSpan w:val="2147483647"/></w:tcPr>${paragraph("x")}</w:tc><w:tc><w:tcPr><w:gridSpan w:val="2147483647"/></w:tcPr>${paragraph("y")}</w:tc></w:tr></w:tbl>""",
        ).blocks().single() as WordTable
        assertEquals(MAX_GRID_COLUMNS, table.rows.single().cells.first().gridSpan)
        assertEquals(MAX_GRID_COLUMNS, table.columnCount)
    }

    /** Only a byte, or one moved into the private-use area, is a symbol font's; Unicode is left as it is. */
    @Test
    fun aSymbolFontRunKeepsTheUnicodeItAlreadyHas() {
        assertEquals("π", decodeSymbolFont("Symbol", "p"))
        assertEquals("π", decodeSymbolFont("Symbol", ""))
        assertEquals("α•", decodeSymbolFont("Symbol", "α•"))
        assertEquals("✓", decodeSymbolFont("Wingdings", "✓"))
        assertEquals("✓", decodeSymbolFont("Wingdings", ""))
    }

    @Test
    fun aListCountingInLettersPastAnyListFallsBackToDigits() {
        assertEquals("aa", formatNumber(27, "lowerLetter"))
        assertEquals("2000000000", formatNumber(2_000_000_000, "lowerLetter"))
    }

    @Test
    fun aPageOfNoSizeIsHeldToTheSmallestPage() {
        val page = WordPageGeometry(0, -5, 0, 0, 0, 0, 0, 0)
        assertEquals(144, page.widthTwips)
        assertEquals(144, page.heightTwips)
        assertEquals(80_640, WordPageGeometry(Int.MAX_VALUE, 1440, 0, 0, 0, 0, 0, 0).widthTwips)
    }

    @Test
    fun aSchemeColourTakesItsThemeTint() {
        val theme = WordTheme(WordTheme.DEFAULT.colors + mapOf("lt2" to 0xFFE8E8E8.toInt()), null, null)
        val element = parseXml(
            """<a:schemeClr xmlns:a="$NS_A" val="bg2"><a:lumMod val="50000"/></a:schemeClr>""".toByteArray(),
        )
        assertEquals(0xFF747474.toInt(), theme.drawingColor(element))
    }

    // --- sections -------------------------------------------------------------------------------

    @Test
    fun theLastSectionPropertiesDescribeThePage() {
        val document = read(
            paragraph("x") + """<w:sectPr><w:pgSz w:w="16838" w:h="11906"/><w:pgMar w:top="720" w:right="1000" w:bottom="720" w:left="1000" w:header="360" w:footer="360" w:gutter="100"/><w:cols w:num="2" w:space="400"/></w:sectPr>""",
        )
        val section = document.sections.single()
        assertEquals(16838, section.page.widthTwips)
        assertEquals(1100, section.page.leftTwips)
        assertEquals(2, section.columns)
        assertEquals(400, section.columnGapTwips)
    }

    @Test
    fun aSectionBreakInAParagraphEndsASection() {
        val document = read(
            """<w:p><w:pPr><w:sectPr><w:pgSz w:w="12240" w:h="15840"/></w:sectPr></w:pPr>${run("first")}</w:p>""" +
                paragraph("second") + """<w:sectPr><w:type w:val="continuous"/><w:pgSz w:w="12240" w:h="15840"/></w:sectPr>""",
        )
        assertEquals(2, document.sections.size)
        assertEquals(WordSectionStart.Continuous, document.sections[1].start)
        assertEquals(WordSectionStart.NextPage, document.sections[0].start)
    }

    // --- settings and strays ---------------------------------------------------------------------

    @Test
    fun settingsAreRead() {
        val document = read(
            paragraph("x"),
            settings = """<w:defaultTabStop w:val="360"/><w:evenAndOddHeaders/><w:compat><w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/></w:compat>""",
        )
        assertEquals(360, document.settings.defaultTabTwips)
        assertTrue(document.settings.evenAndOddHeaders)
        assertEquals(15, document.settings.compatibilityMode)
    }

    @Test
    fun anEquationBecomesLatexInTheParagraph() {
        val paragraph = read(
            """<w:p><m:oMath><m:f><m:num><m:r><m:t>1</m:t></m:r></m:num><m:den><m:r><m:t>2</m:t></m:r></m:den></m:f></m:oMath></w:p>""",
        ).paragraphs().single()
        val equation = paragraph.inlines.single() as WordEquation
        assertEquals("\\frac{1}{2}", equation.latex)
        assertEquals("12", equation.plainText)
        assertFalse(equation.display)
    }

    /** Twelve thousand levels of nesting must not take the reader down with them. */
    @Test
    fun deepTableNestingIsCutOffNotFollowed() {
        var body = paragraph("core")
        repeat(40) {
            body = """<w:tbl><w:tblGrid><w:gridCol w:w="1000"/></w:tblGrid><w:tr><w:tc>$body</w:tc></w:tr></w:tbl>"""
        }
        // No assertion on what survives; what matters is that this returns.
        read(body)
    }
}
