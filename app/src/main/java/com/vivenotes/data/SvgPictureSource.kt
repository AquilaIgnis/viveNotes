package com.vivenotes.data

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import kotlin.math.roundToInt

/** Bounded, self-contained SVG input. Shared verbatim with the desktop attachment importer. */
internal data class SvgPictureSource(
    val text: String,
    val width: Int,
    val height: Int,
    val viewportWidth: Float,
    val viewportHeight: Float,
) {
    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val MIME_TYPE = "image/svg+xml"
        private const val NAMESPACE = "http://www.w3.org/2000/svg"
        private val length = Regex("([+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)(px|pt|pc|in|cm|mm|q)?")
        private const val QUOTED = "(?:\"[^\"]*\"|'[^']*')"
        private val externalDoctype = Regex("<!DOCTYPE\\s+svg\\s+(?:PUBLIC\\s+$QUOTED\\s+$QUOTED|SYSTEM\\s+$QUOTED)\\s*>")

        fun read(bytes: ByteArray, maxDimension: Int): SvgPictureSource? = runCatching {
            require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES && maxDimension > 0)
            fun at(index: Int) = bytes.getOrElse(index) { 0 }.toInt() and 0xff
            val charset = when {
                at(0) == 0xff && at(1) == 0xfe || at(0) == '<'.code && at(1) == 0 -> Charsets.UTF_16LE
                at(0) == 0xfe && at(1) == 0xff || at(0) == 0 && at(1) == '<'.code -> Charsets.UTF_16BE
                else -> Charsets.UTF_8
            }
            val text = bytes.toString(charset).removePrefix("\uFEFF")
                .replaceFirst(Regex("^\\s*<\\?xml\\s[^?]*\\?>"), "")
                .replace(externalDoctype, "")
            require(text.trimStart().startsWith('<'))
            // Check the same decoded characters given to both parsers. No DTD/entity expansion,
            // including UTF-16 declarations that a byte-level search would miss.
            require('\u0000' !in text && "<!DOCTYPE" !in text && "<!ENTITY" !in text)
            var width: String? = null
            var height: String? = null
            var viewBox: String? = null
            var nodes = 0
            var depth = 0
            val handler = object : DefaultHandler() {
                override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
                    require(++nodes <= 50_000 && ++depth <= 128)
                    if (nodes == 1) {
                        require(localName == "svg" && (uri.isEmpty() || uri == NAMESPACE))
                        width = attributes.getValue("width")
                        height = attributes.getValue("height")
                        viewBox = attributes.getValue("viewBox")
                    }
                }
                override fun endElement(uri: String, localName: String, qName: String) { depth-- }
                override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
                    throw SAXException("External SVG entities are not supported")
                override fun error(e: SAXParseException) { throw e }
                override fun fatalError(e: SAXParseException) { throw e }
            }
            SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser()
                .parse(InputSource(StringReader(text)), handler)
            require(nodes > 0)
            val box = viewBox?.trim()?.split(Regex("[\\s,]+")).orEmpty().map { it.toDouble() }
            require(box.isEmpty() || box.size == 4 && box.all { it.isFinite() } && box[2] > 0 && box[3] > 0)
            val ratio = if (box.isEmpty()) null else box[2] / box[3]
            val w = pixels(width)
            val h = pixels(height)
            val naturalWidth = w ?: if (h != null && ratio != null) h * ratio else box.getOrNull(2) ?: 300.0
            val naturalHeight = h ?: if (w != null && ratio != null) w / ratio else box.getOrNull(3) ?: 150.0
            require(naturalWidth in 0.000001..1_000_000_000.0 && naturalHeight in 0.000001..1_000_000_000.0)
            val scale = minOf(1.0, maxDimension / maxOf(naturalWidth, naturalHeight))
            SvgPictureSource(
                text,
                (naturalWidth * scale).roundToInt().coerceIn(1, maxDimension),
                (naturalHeight * scale).roundToInt().coerceIn(1, maxDimension),
                naturalWidth.toFloat(),
                naturalHeight.toFloat(),
            )
        }.getOrNull()

        private fun pixels(value: String?): Double? {
            if (value.isNullOrBlank() || value.trim().endsWith('%')) return null
            val match = requireNotNull(length.matchEntire(value.trim()))
            val factor = when (match.groupValues[2]) {
                "pt" -> 96.0 / 72
                "pc" -> 16.0
                "in" -> 96.0
                "cm" -> 96.0 / 2.54
                "mm" -> 96.0 / 25.4
                "q" -> 96.0 / 101.6
                else -> 1.0
            }
            return (match.groupValues[1].toDouble() * factor).also { require(it.isFinite() && it > 0) }
        }
    }
}
