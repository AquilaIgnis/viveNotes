package com.vivenotes.fileimport

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes small `.docx` files, so these tests depend on no document from outside the repository. */
internal object DocxTestFile {
    private const val OFFICE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val NAMESPACES =
        """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" """ +
            """xmlns:r="$OFFICE" """ +
            """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" """ +
            """xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" """ +
            """xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape""""

    /** Letter, one-inch margins, and a footer if there is one. */
    private const val SECTION =
        """<w:pgSz w:w="12240" w:h="15840"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720" w:gutter="0"/>"""

    fun write(file: File, body: String, footer: String? = null) {
        val section = buildString {
            append("<w:sectPr>")
            if (footer != null) append("""<w:footerReference w:type="default" r:id="rIdFooter"/>""")
            append(SECTION)
            append("</w:sectPr>")
        }
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put(
                "[Content_Types].xml",
                """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                    """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
            )
            put(
                "_rels/.rels",
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    """<Relationship Id="rId1" Type="$OFFICE/officeDocument" Target="word/document.xml"/></Relationships>""",
            )
            put("word/document.xml", """<w:document $NAMESPACES><w:body>$body$section</w:body></w:document>""")
            put(
                "word/_rels/document.xml.rels",
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    (if (footer != null) """<Relationship Id="rIdFooter" Type="$OFFICE/footer" Target="footer1.xml"/>""" else "") +
                    "</Relationships>",
            )
            footer?.let { put("word/footer1.xml", """<w:ftr $NAMESPACES>$it</w:ftr>""") }
        }
    }

    fun paragraph(text: String, properties: String = ""): String =
        """<w:p><w:pPr>$properties</w:pPr><w:r><w:t xml:space="preserve">$text</w:t></w:r></w:p>"""

    fun cell(content: String): String = "<w:tc>$content</w:tc>"

    fun row(vararg cells: String, properties: String = ""): String =
        "<w:tr><w:trPr>$properties</w:trPr>${cells.joinToString("")}</w:tr>"

    fun table(columns: Int, vararg rows: String): String {
        val grid = List(columns) { """<w:gridCol w:w="${9360 / columns}"/>""" }.joinToString("")
        return """<w:tbl><w:tblPr><w:tblBorders><w:insideH w:val="single" w:sz="4"/></w:tblBorders></w:tblPr><w:tblGrid>$grid</w:tblGrid>${rows.joinToString("")}</w:tbl>"""
    }

    /** A text box [heightPoints] tall holding [paragraphs], the way Word writes one inline. */
    fun textBox(heightPoints: Int, paragraphs: String): String =
        """<w:p><w:r><w:drawing><wp:inline><wp:extent cx="4572000" cy="${heightPoints * 12700}"/>""" +
            """<a:graphic><a:graphicData uri="http://schemas.microsoft.com/office/word/2010/wordprocessingShape">""" +
            """<wps:wsp><wps:spPr><a:solidFill><a:srgbClr val="DDDDDD"/></a:solidFill></wps:spPr>""" +
            """<wps:txbx><w:txbxContent>$paragraphs</w:txbxContent></wps:txbx><wps:bodyPr/></wps:wsp>""" +
            """</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"""
}
