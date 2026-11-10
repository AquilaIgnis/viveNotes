package com.vivenotes.fileimport.odt

import com.vivenotes.fileimport.ImportException
import com.vivenotes.fileimport.ImportFailure
import com.vivenotes.fileimport.docx.DocxPackage
import com.vivenotes.fileimport.docx.parseXml
import com.vivenotes.fileimport.docx.readZipEntry
import org.w3c.dom.Element
import java.io.Closeable
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * The `.odt` container: a zip of XML, picked by the user and so believed no more than a `.docx` is.
 *
 * Entries are looked up as written, because unlike OOXML's part names ODF's are case-sensitive.
 */
internal class OdtPackage private constructor(
    private val zip: ZipFile,
    private val entries: Map<String, ZipEntry>,
) : Closeable {

    fun bytes(part: String, limit: Long = DocxPackage.MAX_PART_BYTES): ByteArray? =
        entries[normalized(part)]?.let { readZipEntry(zip, it, limit) }

    fun xml(part: String): Element? = bytes(part, DocxPackage.MAX_XML_BYTES)?.let(::parseXml)

    override fun close() = zip.close()

    private fun normalized(part: String): String = part.removePrefix("./").trimStart('/')

    companion object {
        private const val MAX_ENTRIES = 16_384
        private const val TEXT_MEDIA_TYPE = "application/vnd.oasis.opendocument.text"

        /** Whether [file] is an OpenDocument text, going by the media type every ODF package opens with. */
        fun isOdt(file: File): Boolean = runCatching {
            ZipFile(file).use { zip ->
                zip.getEntry("mimetype")?.let { String(readZipEntry(zip, it, 256), Charsets.US_ASCII).trim() }
                    ?.startsWith(TEXT_MEDIA_TYPE) == true
            }
        }.getOrDefault(false)

        /** Opens [file], or throws a [ImportException] saying why it cannot be read. */
        fun open(file: File): OdtPackage {
            val zip = ZipFile(file)
            try {
                if (zip.size() > MAX_ENTRIES) throw ImportException(ImportFailure.TooLarge)
                val entries = HashMap<String, ZipEntry>()
                for (entry in zip.entries()) if (!entry.isDirectory) entries[entry.name.trimStart('/')] = entry
                refuseEncrypted(zip, entries)
                return OdtPackage(zip, entries)
            } catch (failure: Throwable) {
                zip.close()
                throw failure
            }
        }

        /** Searched as text: older manifests carry a DOCTYPE, which the XML reader rightly refuses. */
        private fun refuseEncrypted(zip: ZipFile, entries: Map<String, ZipEntry>) {
            val manifest = entries["META-INF/manifest.xml"] ?: return
            val text = String(readZipEntry(zip, manifest, DocxPackage.MAX_XML_BYTES), Charsets.UTF_8)
            if ("encryption-data" in text) throw ImportException(ImportFailure.PasswordProtected)
        }
    }
}
