package com.vivenotes.fileimport.docx

import com.vivenotes.fileimport.ImportException
import com.vivenotes.fileimport.ImportFailure
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.Closeable
import java.io.File
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** One line of a part's `.rels`: [target] is a part name, or a URL when [external]. */
internal class DocxRelationship(val id: String, val type: String, val target: String, val external: Boolean)

/**
 * The `.docx` container.
 *
 * It is a zip the user picked, so nothing about it is believed: the entry count and each part's size
 * are capped, the caps hold against a header that lies because reads are counted, and XML carrying a
 * document type declaration is refused outright. OPC forbids them in a package, and refusing them
 * before a parser is involved is what makes external entities and entity-expansion bombs impossible
 * on a platform whose parser offers no switch for it.
 */
internal class DocxPackage private constructor(
    private val zip: ZipFile,
    private val entries: Map<String, ZipEntry>,
    /** The name of the document part — `word/document.xml`, in every file seen so far. */
    val mainPart: String,
) : Closeable {

    fun has(part: String): Boolean = part.lowercase() in entries

    /** The part's bytes, or null when the package has no such part. */
    fun bytes(part: String, limit: Long = MAX_PART_BYTES): ByteArray? {
        val entry = entries[part.lowercase()] ?: return null
        return readZipEntry(zip, entry, limit)
    }

    /** The part parsed as XML, or null when the package has no such part. */
    fun xml(part: String): Element? = bytes(part, MAX_XML_BYTES)?.let(::parseXml)

    /** What [part] points at, by relationship id. */
    fun relationships(part: String): Map<String, DocxRelationship> {
        val rels = xml(relationshipsPartOf(part)) ?: return emptyMap()
        return readRelationships(rels, part)
    }

    override fun close() = zip.close()

    companion object {
        /** The whole file, as staged: a document past this is not lecture notes. */
        const val MAX_FILE_BYTES: Long = 512L * 1024 * 1024

        /** One part. A picture can be big; this is a cap on memory, not a judgement about pictures. */
        const val MAX_PART_BYTES: Long = 96L * 1024 * 1024

        /** One XML part, which becomes a DOM several times its size. */
        const val MAX_XML_BYTES: Long = 48L * 1024 * 1024
        private const val MAX_ENTRIES = 16_384

        /** Whether [file] is an Office package holding a presentation. */
        fun isPresentation(file: File): Boolean = runCatching {
            open(file, PRESENTATION_CONTENT_TYPES).close()
            true
        }.getOrDefault(false)

        /**
         * Opens [file], or throws a [ImportException] saying why it cannot be read. [accepted] is what
         * the package may say it is, and it is a Word document unless asked for otherwise.
         */
        fun open(file: File, accepted: Set<String> = WORD_CONTENT_TYPES): DocxPackage {
            val zip = try {
                ZipFile(file)
            } catch (notZip: ZipException) {
                throw ImportException(ImportFailure.NotWord, notZip)
            }
            try {
                if (zip.size() > MAX_ENTRIES) throw ImportException(ImportFailure.TooLarge)
                val entries = HashMap<String, ZipEntry>()
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    entries[entry.name.trimStart('/').lowercase()] = entry
                }
                return DocxPackage(zip, entries, mainPartOf(zip, entries, accepted))
            } catch (failure: Throwable) {
                zip.close()
                throw failure
            }
        }

        /**
         * The part the package says is its document, confirmed to be a Word one by its content
         * type — so a `.pptx`, also a zip with a main part, is told apart here rather than being
         * read as an empty document.
         */
        private fun mainPartOf(zip: ZipFile, entries: Map<String, ZipEntry>, accepted: Set<String>): String {
            val root = entries["_rels/.rels"]?.let { parseXml(readZipEntry(zip, it, MAX_XML_BYTES)) }
                ?: throw ImportException(ImportFailure.NotWord)
            val main = readRelationships(root, source = "").values
                .firstOrNull { !it.external && it.type.endsWith("/officeDocument") }
                ?: throw ImportException(ImportFailure.NotWord)
            if (main.target.lowercase() !in entries) throw ImportException(ImportFailure.NotWord)
            val types = entries["[content_types].xml"]?.let { parseXml(readZipEntry(zip, it, MAX_XML_BYTES)) }
                ?: throw ImportException(ImportFailure.NotWord)
            val declared = types.elements("Override")
                .firstOrNull { it.getAttribute("PartName").trimStart('/').equals(main.target, ignoreCase = true) }
                ?.getAttribute("ContentType")
                .orEmpty()
            if (declared !in accepted) throw ImportException(ImportFailure.NotWord)
            return main.target
        }

        val PRESENTATION_CONTENT_TYPES = setOf(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml",
            "application/vnd.openxmlformats-officedocument.presentationml.template.main+xml",
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow.main+xml",
            "application/vnd.ms-powerpoint.presentation.macroEnabled.main+xml",
            "application/vnd.ms-powerpoint.template.macroEnabled.main+xml",
            "application/vnd.ms-powerpoint.slideshow.macroEnabled.main+xml",
        )

        /** A document, a template, and the macro-enabled forms, whose macros are never looked at. */
        private val WORD_CONTENT_TYPES = setOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template.main+xml",
            "application/vnd.ms-word.document.macroEnabled.main+xml",
            "application/vnd.ms-word.template.macroEnabledTemplate.main+xml",
        )
    }
}

/** The entry's bytes, read no further than [limit] whatever its header claims. */
internal fun readZipEntry(zip: ZipFile, entry: ZipEntry, limit: Long): ByteArray {
    if (entry.size > limit) throw ImportException(ImportFailure.TooLarge)
    val out = java.io.ByteArrayOutputStream(entry.size.coerceIn(32, limit).toInt())
    zip.getInputStream(entry).use { input ->
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            // Counted as it comes, because the size in the header is the file's word for it.
            if (total > limit) throw ImportException(ImportFailure.TooLarge)
            out.write(buffer, 0, read)
        }
    }
    return out.toByteArray()
}

/** `word/document.xml` → `word/_rels/document.xml.rels`. */
internal fun relationshipsPartOf(part: String): String {
    val slash = part.lastIndexOf('/')
    val directory = if (slash < 0) "" else part.substring(0, slash + 1)
    return "${directory}_rels/${part.substring(slash + 1)}.rels"
}

private fun readRelationships(rels: Element, source: String): Map<String, DocxRelationship> {
    val base = source.substringBeforeLast('/', "")
    return rels.elements("Relationship").associate { element ->
        val id = element.getAttribute("Id")
        val external = element.getAttribute("TargetMode").equals("External", ignoreCase = true)
        val rawTarget = element.getAttribute("Target")
        id to DocxRelationship(
            id = id,
            type = element.getAttribute("Type"),
            target = if (external) rawTarget else resolvePartName(base, rawTarget),
            external = external,
        )
    }
}

/** A relationship's target as a part name: absolute from the root, or relative to [base]. */
internal fun resolvePartName(base: String, target: String): String {
    val segments = ArrayList<String>()
    val path = if (target.startsWith("/")) target else "$base/$target"
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> segments.removeLastOrNull()
            else -> segments += segment
        }
    }
    return segments.joinToString("/")
}

/**
 * Parses [bytes] as namespace-aware DOM.
 *
 * Decoded here rather than by the parser so the refusal below reads exactly what the parser would.
 */
internal fun parseXml(bytes: ByteArray): Element {
    var text = decodeXml(bytes)
    if ("<!DOCTYPE" in text || "<!ENTITY" in text) throw ImportException(ImportFailure.Unreadable)
    if (STRICT_NAMESPACE_MARK in text) {
        STRICT_NAMESPACES.forEach { (strict, transitional) -> text = text.replace(strict, transitional) }
    }
    try {
        val builder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        builder.setErrorHandler(null)
        return builder.parse(InputSource(StringReader(text))).documentElement
    } catch (malformed: org.xml.sax.SAXException) {
        throw ImportException(ImportFailure.Unreadable, malformed)
    } catch (malformed: java.io.IOException) {
        throw ImportException(ImportFailure.Unreadable, malformed)
    }
}

/** XML's own rule for finding its encoding: a byte-order mark, then the declaration, then UTF-8. */
private fun decodeXml(bytes: ByteArray): String {
    fun at(index: Int): Int = bytes.getOrElse(index) { 0 }.toInt() and 0xFF
    return when {
        at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        at(0) == 0xFF && at(1) == 0xFE -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        at(0) == 0xFE && at(1) == 0xFF -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> {
            val head = String(bytes, 0, minOf(bytes.size, 128), Charsets.ISO_8859_1)
            val named = Regex("""encoding=["']([\w-]+)["']""").find(head)?.groupValues?.get(1)
            val charset = named?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
            String(bytes, charset)
        }
    }
}

private const val STRICT_NAMESPACE_MARK = "purl.oclc.org/ooxml"

/** Strict OOXML is Transitional under other names; rewriting them leaves one dialect to read. */
private val STRICT_NAMESPACES = listOf(
    "http://purl.oclc.org/ooxml/wordprocessingml/main" to "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
    "http://purl.oclc.org/ooxml/officeDocument/relationships" to "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
    "http://purl.oclc.org/ooxml/officeDocument/math" to "http://schemas.openxmlformats.org/officeDocument/2006/math",
    "http://purl.oclc.org/ooxml/drawingml/main" to "http://schemas.openxmlformats.org/drawingml/2006/main",
    "http://purl.oclc.org/ooxml/drawingml/wordprocessingDrawing" to "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing",
    "http://purl.oclc.org/ooxml/drawingml/picture" to "http://schemas.openxmlformats.org/drawingml/2006/picture",
)

// --- DOM conveniences -------------------------------------------------------------------------

internal const val NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
internal const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
internal const val NS_M = "http://schemas.openxmlformats.org/officeDocument/2006/math"
internal const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
internal const val NS_WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
internal const val NS_PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture"
internal const val NS_WPS = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
internal const val NS_WPG = "http://schemas.microsoft.com/office/word/2010/wordprocessingGroup"
internal const val NS_WPC = "http://schemas.microsoft.com/office/word/2010/wordprocessingCanvas"
internal const val NS_MC = "http://schemas.openxmlformats.org/markup-compatibility/2006"
internal const val NS_V = "urn:schemas-microsoft-com:vml"
internal const val NS_XML = "http://www.w3.org/XML/1998/namespace"

/** Element children, in order. Listed rather than a live `NodeList`, which is linear per index on some DOMs. */
internal val Element.childElements: List<Element>
    get() {
        val out = ArrayList<Element>()
        var node = firstChild
        while (node != null) {
            if (node is Element) out += node
            node = node.nextSibling
        }
        return out
    }

/** Direct children with [localName], in any namespace — relationship and content-type parts. */
internal fun Element.elements(localName: String): List<Element> =
    childElements.filter { it.localName == localName }

/** The first direct child in [namespace] called [localName]. */
internal fun Element.child(namespace: String, localName: String): Element? {
    var node = firstChild
    while (node != null) {
        if (node is Element && node.localName == localName && node.namespaceURI == namespace) return node
        node = node.nextSibling
    }
    return null
}

internal fun Element.children(namespace: String, localName: String): List<Element> =
    childElements.filter { it.localName == localName && it.namespaceURI == namespace }

internal fun Element.isIn(namespace: String, localName: String): Boolean =
    this.localName == localName && namespaceURI == namespace

/** A namespaced attribute, or null. `getAttributeNS` answers "" for a missing one on some DOMs. */
internal fun Element.attr(namespace: String, localName: String): String? =
    getAttributeNodeNS(namespace, localName)?.value

/** A plain attribute, or null. */
internal fun Element.plain(name: String): String? = getAttributeNode(name)?.value

internal fun Element.w(name: String): String? = attr(NS_W, name)

internal fun Element.wChild(name: String): Element? = child(NS_W, name)

/** The `w:val` of a child element, or null when the child is absent. */
internal fun Element.wVal(name: String): String? = wChild(name)?.w("val")

/** Every descendant in [namespace] called [localName], depth first. */
internal fun Element.descendants(namespace: String, localName: String): List<Element> {
    val out = ArrayList<Element>()
    fun walk(element: Element) {
        element.childElements.forEach { child ->
            if (child.isIn(namespace, localName)) out += child
            walk(child)
        }
    }
    walk(this)
    return out
}

/** All the [localName] descendants' text, joined. */
internal fun Element.allText(namespace: String, localName: String): String =
    descendants(namespace, localName).joinToString("") { it.textContent.orEmpty() }

internal val Element.hasPreservedSpace: Boolean
    get() = attr(NS_XML, "space") == "preserve"

/** OOXML's on/off values. An element with no `w:val` is on. */
internal fun Element.isOn(): Boolean = when (w("val")?.lowercase()) {
    "0", "false", "off", "no" -> false
    else -> true
}

/** A number of twips, from a plain integer or Strict's `12pt`, `0.5in` and so on. */
internal fun parseTwips(value: String?): Int? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    text.toIntOrNull()?.let { return it }
    val number = text.takeWhile { it.isDigit() || it == '.' || it == '-' || it == '+' }
    val amount = number.toDoubleOrNull() ?: return null
    val factor = when (text.substring(number.length).trim().lowercase()) {
        "", "tw" -> 1.0
        "pt" -> 20.0
        "in" -> 1440.0
        "mm" -> 1440.0 / 25.4
        "cm" -> 1440.0 / 2.54
        "pc", "pi" -> 240.0
        else -> return null
    }
    return Math.round(amount * factor).toInt()
}

/** EMU — DrawingML's unit, 12700 to the point and 635 to the twip — to twips. */
internal fun emuToTwips(emu: Long): Int = (emu / 635).toInt()

internal fun Element.emu(name: String): Long? = plain(name)?.toLongOrNull()
