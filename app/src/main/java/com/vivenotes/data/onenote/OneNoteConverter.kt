package com.vivenotes.data.onenote

import com.vivenotes.data.DebugLog
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a OneNote file came to: counted from the `.vive` the converter wrote. */
data class OneNoteConversion(
    val sections: Int,
    val pages: Int,
    val strokes: Int,
    val attachments: Int,
    /** Each thing that was left out, as the converter words it: "omitted an embedded file…". */
    val warnings: List<String>,
)

class OneNoteConversionException(val reason: Reason, detail: String) : Exception(detail) {
    enum class Reason {
        /** A `.onetoc2`, which only lists section files kept beside it. */
        TableOfContents,
        /** Not a OneNote package or section, or not one the parser can read. */
        Unreadable,
        /** A OneNote file without a section in it. */
        NoSections,
        /** Anything else: the file was fine, the conversion was not. */
        Failed,
    }
}

/**
 * The OneNote converter: `libvive_converter.so`, built from `vive-converter` by
 * `app/build.gradle.kts`.
 *
 * It reads a `.onepkg` or `.one` and writes the same `.vive` an export writes, so a converted
 * notebook arrives through the one validated import there is, not a second way into the database.
 */
internal object OneNoteConverter {

    /** False when the library is not in this build (`-PnoOneNote`) or does not load. */
    val available: Boolean by lazy {
        try {
            System.loadLibrary(LIBRARY)
            true
        } catch (missing: UnsatisfiedLinkError) {
            DebugLog.w(DebugLog.TRANSFER, missing) { "The OneNote converter is not in this build" }
            false
        }
    }

    /**
     * Converts [input] into a `.vive` at [output].
     *
     * [fileName] is what the user's file is called: the notebook is named after it, and a section
     * that carries no name of its own is too. [input] can be called anything. The notebook's ids
     * derive from [seed], so each seed is a notebook of its own and never an update of another.
     *
     * Blocks for as long as the conversion takes, seconds for a large notebook, and cannot be
     * interrupted.
     */
    fun toBundle(input: File, fileName: String, seed: String, output: File): OneNoteConversion {
        check(available) { "The OneNote converter is not in this build." }
        val report = JSON.decodeFromString<Report>(convert(input.path, fileName, seed, output.path))
        report.error?.let { code ->
            val reason = when (code) {
                "tableOfContents" -> OneNoteConversionException.Reason.TableOfContents
                "unreadable" -> OneNoteConversionException.Reason.Unreadable
                "noSections" -> OneNoteConversionException.Reason.NoSections
                else -> OneNoteConversionException.Reason.Failed
            }
            throw OneNoteConversionException(reason, report.detail ?: code)
        }
        return OneNoteConversion(
            sections = report.sections,
            pages = report.pages,
            strokes = report.strokes,
            attachments = report.attachments,
            warnings = report.warnings,
        )
    }

    /**
     * `Java_com_vivenotes_data_onenote_OneNoteConverter_convert` in the converter's `android.rs`:
     * renaming this package, class or method breaks the call when it is made, not when it builds.
     */
    @JvmStatic
    private external fun convert(input: String, fileName: String, seed: String, output: String): String

    @Serializable
    private data class Report(
        val sections: Int = 0,
        val pages: Int = 0,
        val strokes: Int = 0,
        val attachments: Int = 0,
        val warnings: List<String> = emptyList(),
        val error: String? = null,
        val detail: String? = null,
    )

    private const val LIBRARY = "vive_converter"
    private val JSON = Json { ignoreUnknownKeys = true }
}
