package com.vivenotes.fileimport.pptx

import com.vivenotes.fileimport.ImportException
import com.vivenotes.fileimport.ImportFailure
import com.vivenotes.fileimport.docx.DocxPackage
import com.vivenotes.fileimport.docx.WordAlign
import com.vivenotes.fileimport.docx.WordParagraph
import com.vivenotes.fileimport.docx.WordText
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

class PptxReaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val ns =
        """xmlns:p="$NS_P" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships""""
    private val relationshipBase = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun rels(vararg entries: Triple<String, String, String>): String =
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            entries.joinToString("") { (id, type, target) -> """<Relationship Id="$id" Type="$relationshipBase/$type" Target="$target"/>""" } +
            "</Relationships>"

    private fun tree(content: String) =
        """<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>$content</p:spTree></p:cSld>"""

    private fun placeholder(type: String, x: Int, y: Int, cx: Int, cy: Int, extra: String = "") =
        """<p:sp><p:nvSpPr><p:cNvPr id="2" name="$type"/><p:cNvSpPr/><p:nvPr><p:ph type="$type"/></p:nvPr></p:nvSpPr>
           <p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm></p:spPr><p:txBody><a:bodyPr/><a:lstStyle>$extra</a:lstStyle><a:p/></p:txBody></p:sp>"""

    private val clrMap = """bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink""""

    private fun presentation(slide: String, master: String = masterXml(), layoutShapes: String = defaultLayout, extra: Map<String, String> = emptyMap(), slideRels: String = ""): File {
        val entries = linkedMapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/></Types>""",
            "_rels/.rels" to rels(Triple("rId1", "officeDocument", "ppt/presentation.xml")),
            "ppt/presentation.xml" to """<p:presentation $ns><p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst><p:sldSz cx="9144000" cy="6858000"/></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to rels(Triple("rId1", "slideMaster", "slideMasters/slideMaster1.xml"), Triple("rId2", "slide", "slides/slide1.xml")),
            "ppt/slideMasters/slideMaster1.xml" to master,
            "ppt/slideMasters/_rels/slideMaster1.xml.rels" to rels(Triple("rId2", "theme", "../theme/theme1.xml")),
            "ppt/slideLayouts/slideLayout1.xml" to """<p:sldLayout $ns>${tree(layoutShapes)}</p:sldLayout>""",
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to rels(Triple("rId1", "slideMaster", "../slideMasters/slideMaster1.xml")),
            "ppt/theme/theme1.xml" to """<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:themeElements><a:clrScheme name="c"><a:dk1><a:srgbClr val="111111"/></a:dk1><a:lt1><a:srgbClr val="EEEEEE"/></a:lt1><a:accent1><a:srgbClr val="4472C4"/></a:accent1></a:clrScheme><a:fontScheme name="f"><a:majorFont><a:latin typeface="Major"/></a:majorFont><a:minorFont><a:latin typeface="Minor"/></a:minorFont></a:fontScheme></a:themeElements></a:theme>""",
            "ppt/slides/slide1.xml" to """<p:sld $ns>${tree(slide)}</p:sld>""",
            "ppt/slides/_rels/slide1.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$relationshipBase/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>$slideRels</Relationships>""",
        )
        entries += extra
        val file = folder.newFile("deck-${entries.hashCode()}-${System.nanoTime()}.pptx")
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    private fun masterXml(map: String = clrMap, styles: String = bodyStyles) =
        """<p:sldMaster $ns>${tree(placeholder("title", 100, 200, 8000, 900) + placeholder("body", 300, 400, 8000, 5000))}<p:clrMap $map/><p:txStyles>$styles</p:txStyles></p:sldMaster>"""

    private val bodyStyles = """<p:titleStyle><a:lvl1pPr algn="ctr"><a:defRPr sz="4400"/></a:lvl1pPr></p:titleStyle>
        <p:bodyStyle><a:lvl1pPr marL="342900" indent="-342900"><a:buChar char="•"/><a:defRPr sz="2800"/></a:lvl1pPr><a:lvl2pPr marL="742950" indent="-285750"><a:buChar char="–"/><a:defRPr sz="2400"/></a:lvl2pPr></p:bodyStyle><p:otherStyle/>"""

    private val defaultLayout = placeholder("title", 1270, 2540, 8000000, 900000) + placeholder("body", 1270, 2540000, 8000000, 3000000)

    private fun read(file: File): PptxRead = DocxPackage.open(file, DocxPackage.PRESENTATION_CONTENT_TYPES).use { PptxReader(it).read() }

    private fun titleSp(text: String) =
        """<p:sp><p:nvSpPr><p:cNvPr id="2" name="t"/><p:cNvSpPr/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>$text</a:t></a:r></a:p></p:txBody></p:sp>"""

    private fun bodySp(paragraphs: String, bodyPr: String = "<a:bodyPr/>") =
        """<p:sp><p:nvSpPr><p:cNvPr id="3" name="b"/><p:cNvSpPr/><p:nvPr><p:ph type="body" idx="1"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody>$bodyPr<a:lstStyle/>$paragraphs</p:txBody></p:sp>"""

    private fun para(text: String, properties: String = "", run: String = "") = """<a:p>$properties<a:r>$run<a:t>$text</a:t></a:r></a:p>"""

    private fun objects(read: PptxRead): List<PptxObject> = read.slides.single()

    private fun textOf(read: PptxRead): List<WordParagraph> = objects(read).filterIsInstance<PptxObject.Text>().flatMap { it.paragraphs }

    private fun WordParagraph.plain(): String = inlines.filterIsInstance<WordText>().joinToString("") { it.text }

    private fun WordParagraph.firstRun(): WordText = inlines.first() as WordText

    @Test
    fun theSlideSizeSetsThePage() {
        val page = read(presentation(titleSp("T"))).document.sections.single().page

        assertEquals(14400, page.widthTwips)
        assertEquals(10800, page.heightTwips)
    }

    @Test
    fun aTitleIsAHeadingOfItsOwn() {
        val paragraph = textOf(read(presentation(titleSp("Hello")))).single()

        assertEquals(0, paragraph.style.outlineLevel)
        assertEquals("Hello", paragraph.plain())
    }

    @Test
    fun aTitleTakesItsLookFromTheMaster() {
        val paragraph = textOf(read(presentation(titleSp("Hello")))).single()

        assertEquals(WordAlign.Center, paragraph.style.align)
        assertEquals(88, paragraph.firstRun().style.halfPoints)
    }

    @Test
    fun theThemeNamesFontsAndColours() {
        val run = textOf(read(presentation(titleSp("Hello")))).single().firstRun().style

        assertEquals("Minor", run.font)
        assertEquals(0xFF111111.toInt(), run.colorArgb)
    }

    @Test
    fun aDarkTemplateSwapsItsTextAndBackground() {
        val master = masterXml(map = clrMap.replace("""bg1="lt1" tx1="dk1"""", """bg1="dk1" tx1="lt1""""))

        val run = textOf(read(presentation(titleSp("Hello"), master = master))).single().firstRun().style

        assertEquals(0xFFEEEEEE.toInt(), run.colorArgb)
    }

    @Test
    fun bulletsAndTheirIndentComeFromTheMaster() {
        val paragraph = textOf(read(presentation(bodySp(para("One"))))).single()

        assertEquals(true, paragraph.label?.bullet)
        assertEquals("•", paragraph.label?.text)
        assertEquals(540, paragraph.style.leftTwips)
        assertEquals(-540, paragraph.style.firstLineTwips)
    }

    @Test
    fun aParagraphsLevelPicksItsListLevel() {
        val paragraphs = textOf(read(presentation(bodySp(para("One") + para("Two", """<a:pPr lvl="1"/>""")))))

        assertEquals(1, paragraphs[1].label?.level)
        assertEquals("–", paragraphs[1].label?.text)
        assertEquals(48, paragraphs[1].firstRun().style.halfPoints)
    }

    @Test
    fun noBulletMeansNoLabel() {
        assertNull(textOf(read(presentation(bodySp(para("One", "<a:pPr><a:buNone/></a:pPr>"))))).single().label)
    }

    @Test
    fun automaticNumbersCountAndRestartWhenInterrupted() {
        val numbered = """<a:pPr><a:buAutoNum type="arabicPeriod"/></a:pPr>"""
        val paragraphs = textOf(read(presentation(bodySp(para("A", numbered) + para("B", numbered) + para("C", "<a:pPr><a:buNone/></a:pPr>") + para("D", numbered)))))

        assertEquals(listOf("1.", "2.", null, "1."), paragraphs.map { it.label?.text })
    }

    @Test
    fun aRunCanSayItsOwnLook() {
        val run = textOf(read(presentation(bodySp(para("One", run = """<a:rPr b="1" i="1" sz="1200" u="sng"><a:solidFill><a:srgbClr val="FF0000"/></a:solidFill></a:rPr>"""))))).single().firstRun().style

        assertTrue(run.bold && run.italic && run.underline)
        assertEquals(24, run.halfPoints)
        assertEquals(0xFFFF0000.toInt(), run.colorArgb)
    }

    @Test
    fun shrunkenTextAppliesToSizesTheRunStates() {
        val body = bodySp(para("One", run = """<a:rPr sz="2000"/>"""), bodyPr = """<a:bodyPr><a:normAutofit fontScale="50000"/></a:bodyPr>""")

        assertEquals(20, textOf(read(presentation(body))).single().firstRun().style.halfPoints)
    }

    @Test
    fun aHyperlinkGoesThroughTheSlidesRelationships() {
        val body = bodySp(para("Link", run = """<a:rPr><a:hlinkClick r:id="rId9"/></a:rPr>"""))
        val link = """<Relationship Id="rId9" Type="$relationshipBase/hyperlink" Target="https://example.com/a" TargetMode="External"/>"""

        assertEquals("https://example.com/a", textOf(read(presentation(body, slideRels = link))).single().firstRun().link)
    }

    private fun autoshape(id: Int, x: Int, y: Int, cx: Int, cy: Int, prst: String = "rect", text: String = "", style: String = """<a:solidFill><a:srgbClr val="4472C4"/></a:solidFill>""", shapeStyle: String = "") =
        """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="s"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="$prst"><a:avLst/></a:prstGeom>$style</p:spPr>$shapeStyle""" +
            (if (text.isEmpty()) "" else """<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>$text</a:t></a:r></a:p></p:txBody>""") + "</p:sp>"

    private fun textBox(x: Int, y: Int, text: String) =
        """<p:sp><p:nvSpPr><p:cNvPr id="9" name="x"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="2000000" cy="400000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>$text</a:t></a:r></a:p></p:txBody></p:sp>"""

    private fun connector(x: Int, y: Int, cx: Int, cy: Int) =
        """<p:cxnSp><p:nvCxnSpPr><p:cNvPr id="7" name="c"/><p:cNvCxnSpPr/><p:nvPr/></p:nvCxnSpPr><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="straightConnector1"><a:avLst/></a:prstGeom><a:ln w="12700"><a:solidFill><a:srgbClr val="000000"/></a:solidFill></a:ln></p:spPr></p:cxnSp>"""

    @Test
    fun aTextBoxIsText() {
        assertEquals("Note", textOf(read(presentation(textBox(100000, 100000, "Note")))).single().plain())
    }

    @Test
    fun aFilledShapeOnItsOwnIsTextToo() {
        assertEquals("Callout", textOf(read(presentation(autoshape(5, 100000, 100000, 2000000, 1000000, text = "Callout")))).single().plain())
    }

    @Test
    fun shapesJoinedByALineAreOneFigure() {
        val slide = autoshape(5, 100000, 100000, 1500000, 800000, text = "Drivers") + connector(1600000, 500000, 1000000, 0) + autoshape(6, 2600000, 100000, 1500000, 800000, text = "Cars")

        val result = objects(read(presentation(slide)))

        assertEquals(3, (result.single() as PptxObject.Figure).members.size)
    }

    @Test
    fun aLabelOverADrawingIsPartOfIt() {
        val slide = autoshape(5, 100000, 100000, 1500000, 800000) + connector(1600000, 500000, 1000000, 0) + autoshape(6, 2600000, 100000, 1500000, 800000) + textBox(1400000, 300000, "N")

        assertTrue(objects(read(presentation(slide))).single() is PptxObject.Figure)
    }

    @Test
    fun aGroupIsOneFigureAndItsChildrenLandWhereTheGroupPutsThem() {
        val group = """<p:grpSp><p:nvGrpSpPr><p:cNvPr id="4" name="g"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="635000" y="635000"/><a:ext cx="1270000" cy="1270000"/><a:chOff x="0" y="0"/><a:chExt cx="2540000" cy="2540000"/></a:xfrm></p:grpSpPr>""" +
            autoshape(5, 0, 0, 1000000, 1000000) + autoshape(6, 1540000, 1540000, 1000000, 1000000) + "</p:grpSp>"

        val figure = objects(read(presentation(group))).single() as PptxObject.Figure

        assertEquals(1000, figure.left)
        assertEquals(1000, figure.top)
        assertEquals(2000f, figure.width.toFloat(), 2f)
    }

    @Test
    fun aThinBarIsDecoration() {
        assertTrue(objects(read(presentation(autoshape(5, 0, 0, 9000000, 50000)))).isEmpty())
    }

    @Test
    fun aPanelTheSizeOfTheSlideIsDecoration() {
        assertTrue(objects(read(presentation(autoshape(5, 0, 0, 9144000, 6000000)))).isEmpty())
    }

    @Test
    fun thingsAreReadTopToBottomAndLeftToRightWithTheTitleFirst() {
        val slide = textBox(4000000, 3000000, "Right") + textBox(100000, 3000000, "Left") + textBox(100000, 5000000, "Below") + titleSp("Title")

        assertEquals(listOf("Title", "Left", "Right", "Below"), textOf(read(presentation(slide))).map { it.plain() })
    }

    @Test
    fun aDateAndSlideNumberAreLeftOutOfTheObjects() {
        val number = """<p:sp><p:nvSpPr><p:cNvPr id="8" name="n"/><p:cNvSpPr/><p:nvPr><p:ph type="sldNum"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="500000" cy="300000"/></a:xfrm></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:fld id="{1}" type="slidenum"><a:t>9</a:t></a:fld></a:p></p:txBody></p:sp>"""

        assertEquals(listOf("T"), textOf(read(presentation(titleSp("T") + number))).map { it.plain() })
    }

    @Test
    fun whatALayoutDrawsIsPaintedButIsNotTheSlides() {
        val layout = defaultLayout + autoshape(20, 100000, 6000000, 3000000, 500000)
        val read = read(presentation(titleSp("T"), layoutShapes = layout))

        assertEquals(1, objects(read).size)
        val floats = (read.document.sections.single().blocks.single() as WordParagraph).inlines
        assertTrue(floats.size >= 3)
    }

    private fun tableFrame(tblPr: String, extra: String = "") =
        """<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="4" name="t"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off x="100000" y="100000"/><a:ext cx="4000000" cy="800000"/></p:xfrm><a:graphic><a:graphicData uri="x"><a:tbl>$tblPr<a:tblGrid><a:gridCol w="2000000"/><a:gridCol w="2000000"/></a:tblGrid>
           <a:tr h="400000"><a:tc gridSpan="2"><a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>Across</a:t></a:r></a:p></a:txBody><a:tcPr/></a:tc><a:tc hMerge="1"><a:txBody><a:bodyPr/><a:lstStyle/><a:p/></a:txBody><a:tcPr/></a:tc></a:tr>
           <a:tr h="400000"><a:tc><a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>A</a:t></a:r></a:p></a:txBody><a:tcPr$extra</a:tc><a:tc><a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>B</a:t></a:r></a:p></a:txBody><a:tcPr/></a:tc></a:tr></a:tbl></a:graphicData></a:graphic></p:graphicFrame>"""

    private fun tableOf(file: File) = (objects(read(file)).single() as PptxObject.Table).table

    @Test
    fun aTableKeepsItsGridAndSpans() {
        val table = tableOf(presentation(tableFrame("<a:tblPr/>", "/>")))

        assertEquals(2, table.gridTwips.size)
        assertEquals(listOf(2), table.rows[0].cells.map { it.gridSpan })
        assertEquals(listOf(1, 1), table.rows[1].cells.map { it.gridSpan })
    }

    @Test
    fun aFirstRowFlagMakesAHeader() {
        val table = tableOf(presentation(tableFrame("""<a:tblPr firstRow="1"/>""", "/>")))

        assertTrue(table.rows[0].isHeader)
        assertFalse(table.rows[1].isHeader)
    }

    @Test
    fun aCellInAColourKeepsItAsAHighlight() {
        val fill = """><a:solidFill><a:srgbClr val="FFC000"/></a:solidFill></a:tcPr>"""
        val cell = tableOf(presentation(tableFrame("<a:tblPr/>", fill))).rows[1].cells[0]

        assertEquals(0xFFFFC000.toInt(), ((cell.blocks.single() as WordParagraph).inlines.first() as WordText).style.highlightArgb)
    }

    @Test
    fun aPictureKeepsItsPartAndCrop() {
        val pic = """<p:pic><p:nvPicPr><p:cNvPr id="5" name="p"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rId5"/><a:srcRect l="10000" r="20000"/></p:blipFill><p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="914400" cy="914400"/></a:xfrm></p:spPr></p:pic>"""
        val link = """<Relationship Id="rId5" Type="$relationshipBase/image" Target="../media/a.png"/>"""

        val picture = (objects(read(presentation(pic, slideRels = link))).single() as PptxObject.Picture).picture

        assertEquals("ppt/media/a.png", picture.part)
        assertEquals(0.1f, picture.crop!!.left, 0.001f)
        assertEquals(0.2f, picture.crop!!.right, 0.001f)
    }

    @Test
    fun anEmfPictureIsPaintedAsAMissingBoxAndIsNoObject() {
        val pic = """<p:pic><p:nvPicPr><p:cNvPr id="5" name="p"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rId5"/></p:blipFill><p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="914400" cy="914400"/></a:xfrm></p:spPr></p:pic>"""
        val link = """<Relationship Id="rId5" Type="$relationshipBase/image" Target="../media/a.emf"/>"""

        assertTrue(objects(read(presentation(pic, slideRels = link))).isEmpty())
    }

    /** A chart in the file is its data, with no picture of it to bring along. */
    @Test
    fun aChartIsPaintedAsAMissingBoxAndIsNoObject() {
        val chart = """<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="4" name="c"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off x="0" y="0"/><a:ext cx="4000000" cy="3000000"/></p:xfrm><a:graphic><a:graphicData uri="chart"><c:chart xmlns:c="$NS_CHART" r:id="rId7"/></a:graphicData></a:graphic></p:graphicFrame>"""
        val read = read(presentation(titleSp("T") + chart))

        assertEquals(1, objects(read).size)
        val floats = (read.document.sections.single().blocks.single() as WordParagraph).inlines
        assertTrue(floats.any { it is com.vivenotes.fileimport.docx.WordPlaceholder })
    }

    @Test
    fun anAlternativeBranchIsChosenByWhatItRequires() {
        val alternative = """<mc:AlternateContent xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" xmlns:cx1="u"><mc:Choice Requires="cx1">${textBox(0, 0, "Unknown")}</mc:Choice><mc:Fallback>${textBox(0, 0, "Fallback")}</mc:Fallback></mc:AlternateContent>"""

        assertEquals(listOf("Fallback"), textOf(read(presentation(alternative))).map { it.plain() })
    }

    @Test
    fun aMissingLayoutIsUnreadable() {
        val file = folder.newFile("broken.pptx")
        ZipOutputStream(file.outputStream()).use { out ->
            mapOf(
                "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/></Types>""",
                "_rels/.rels" to rels(Triple("rId1", "officeDocument", "ppt/presentation.xml")),
                "ppt/presentation.xml" to """<p:presentation $ns><p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst></p:presentation>""",
                "ppt/_rels/presentation.xml.rels" to rels(Triple("rId2", "slide", "slides/slide1.xml")),
                "ppt/slides/slide1.xml" to """<p:sld $ns>${tree("")}</p:sld>""",
            ).forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }

        val thrown = assertThrows(ImportException::class.java) { read(file) }

        assertEquals(ImportFailure.Unreadable, thrown.failure)
    }

    @Test
    fun aPresentationIsToldFromAWordDocument() {
        val file = presentation(titleSp("T"))

        assertTrue(DocxPackage.isPresentation(file))
        assertNotNull(assertThrows(ImportException::class.java) { DocxPackage.open(file) })
    }
}
