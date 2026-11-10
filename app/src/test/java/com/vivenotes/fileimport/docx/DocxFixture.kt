package com.vivenotes.fileimport.docx

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds `.docx` files for tests, so the reader is pinned without a document from anywhere else. */
internal object DocxFixture {
    private const val DOCUMENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"

    const val NAMESPACES =
        """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" """ +
            """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
            """xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math" """ +
            """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" """ +
            """xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" """ +
            """xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture" """ +
            """xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape" """ +
            """xmlns:wpg="http://schemas.microsoft.com/office/word/2010/wordprocessingGroup" """ +
            """xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" """ +
            """xmlns:v="urn:schemas-microsoft-com:vml""""

    private const val OFFICE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    /** A relationship from the document part. */
    data class Rel(val id: String, val type: String, val target: String, val external: Boolean = false)

    fun write(
        file: File,
        body: String,
        styles: String? = null,
        numbering: String? = null,
        settings: String? = null,
        relationships: List<Rel> = emptyList(),
        parts: Map<String, ByteArray> = emptyMap(),
    ) {
        val rels = buildList {
            addAll(relationships)
            if (styles != null) add(Rel("rIdStyles", "$OFFICE/styles", "styles.xml"))
            if (numbering != null) add(Rel("rIdNumbering", "$OFFICE/numbering", "numbering.xml"))
            if (settings != null) add(Rel("rIdSettings", "$OFFICE/settings", "settings.xml"))
        }
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            fun put(name: String, text: String) = put(name, text.toByteArray())

            put(
                "[Content_Types].xml",
                """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                    """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
                    """<Default Extension="xml" ContentType="application/xml"/>""" +
                    """<Override PartName="/word/document.xml" ContentType="$DOCUMENT_TYPE"/></Types>""",
            )
            put(
                "_rels/.rels",
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    """<Relationship Id="rId1" Type="$OFFICE/officeDocument" Target="word/document.xml"/></Relationships>""",
            )
            put("word/document.xml", """<w:document $NAMESPACES><w:body>$body</w:body></w:document>""")
            put(
                "word/_rels/document.xml.rels",
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    rels.joinToString("") { rel ->
                        val mode = if (rel.external) """ TargetMode="External"""" else ""
                        """<Relationship Id="${rel.id}" Type="${rel.type}" Target="${rel.target}"$mode/>"""
                    } + "</Relationships>",
            )
            styles?.let { put("word/styles.xml", """<w:styles $NAMESPACES>$it</w:styles>""") }
            numbering?.let { put("word/numbering.xml", """<w:numbering $NAMESPACES>$it</w:numbering>""") }
            settings?.let { put("word/settings.xml", """<w:settings $NAMESPACES>$it</w:settings>""") }
            parts.forEach { (name, bytes) -> put(name, bytes) }
        }
    }

    const val IMAGE_REL = "$OFFICE/image"
    const val HYPERLINK_REL = "$OFFICE/hyperlink"

    fun paragraph(text: String, properties: String = ""): String =
        """<w:p><w:pPr>$properties</w:pPr><w:r><w:t xml:space="preserve">$text</w:t></w:r></w:p>"""

    fun run(text: String, properties: String = ""): String =
        """<w:r><w:rPr>$properties</w:rPr><w:t xml:space="preserve">$text</w:t></w:r>"""
}
