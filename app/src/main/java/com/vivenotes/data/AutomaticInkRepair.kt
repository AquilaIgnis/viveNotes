package com.vivenotes.data

import androidx.room.withTransaction
import com.vivenotes.data.db.LocalMetadataEntity
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.model.DocumentCodecs
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.TextDocumentCodec

/**
 * A mark stored as a *deliberate* pure white or pure black.
 *
 * The state this file exists to undo, and the one combination [automaticColorOr] can do nothing
 * with: `false` means the colour was chosen, so it is kept, and white kept on the white sheet a PDF
 * export always is, is a mark that does not appear at all. Nobody picks ink they cannot see; what
 * they picked was the colour the automatic pen was already showing them on a dark canvas, which
 * `PenPanel` now records as automatic. This is the same conclusion applied to what was written
 * before it did.
 *
 * Alpha is part of the match, so a translucent highlight is never a candidate — deliberately, per
 * the highlighter's note in `InkCodec`.
 */
fun isChosenAutomaticInk(argb: Int, followsTheme: Boolean?): Boolean =
    followsTheme == false && (argb == AUTOMATIC_LIGHT || argb == AUTOMATIC_DARK)

/**
 * The same rule over one page body, or null when the page has nothing to repair.
 *
 * Null rather than the unchanged document so a caller cannot rewrite a row it did not need to
 * touch: every rewrite here queues a sync push, and pages without a white line on them must not.
 *
 * Shapes and tables both, because [automaticColorOr] treats the three kinds of mark identically and
 * a straight line held out of a stroke inherits its pen's flag — repairing the ink and leaving the
 * lines drawn beside it would fix half of a page.
 */
fun PageDoc.withRepairedAutomaticInk(): PageDoc? {
    var repaired = 0
    val outlines = outlines.map { outline ->
        when {
            outline is Outline.Shape &&
                isChosenAutomaticInk(outline.borderArgb, outline.borderFollowsTheme) -> {
                repaired++
                outline.copy(borderFollowsTheme = null)
            }

            outline is Outline.Table &&
                isChosenAutomaticInk(outline.borderArgb, outline.borderFollowsTheme) -> {
                repaired++
                outline.copy(borderFollowsTheme = null)
            }

            else -> outline
        }
    }
    return if (repaired == 0) null else copy(outlines = outlines)
}

/** What one repair pass changed. */
data class InkRepairResult(val strokes: Int, val pages: Int) {
    val isEmpty: Boolean get() = strokes == 0 && pages == 0
}

/**
 * Gives back the marks that were recorded as deliberately white or black — once per installation.
 *
 * **Why the flag has to be cleared rather than read around.** `false` is a statement about intent,
 * and the renderers are right to obey it; the bug was upstream, in a picker that could not tell
 * "white" from "the ink this canvas is showing me". Teaching the renderers to distrust `false`
 * would take the standing rule — a colour you picked survives the theme changing under it — away
 * from everyone, forever, to fix marks written in one window of time. Clearing the flag on those
 * marks says the honest thing instead: their intent is no longer known, which is exactly what
 * `null` means to [automaticColorOr] and how every mark drawn before the flag existed is already
 * read.
 *
 * One-shot, recorded in `local_metadata`. Not a Room migration: the schema is unchanged, and a
 * repair that has to run inside the transaction Room opens for a version bump cannot be retried
 * when a page body fails to decode. A failed pass writes no marker and is simply tried again on the
 * next launch.
 *
 * The rewrites queue sync pushes like any other local edit, so a notebook repaired here reaches the
 * other devices on the account rather than being fixed one install at a time. That is the reason
 * this touches stored rows at all instead of resolving at paint time.
 */
class AutomaticInkRepair(private val database: NotesDatabase) {

    suspend fun runIfNeeded(): InkRepairResult {
        val metadata = database.localMetadataDao()
        if (metadata.value(KEY) != null) return InkRepairResult(strokes = 0, pages = 0)

        val strokeDao = database.inkStrokeDao()
        val contentDao = database.pageContentDao()
        return database.withTransaction {
            val strokes = strokeDao.clearChosenAutomaticInk(AUTOMATIC_LIGHT, AUTOMATIC_DARK)
            var pages = 0
            contentDao.borderCarryingBodies().forEach { row ->
                // The row's own codec, not the current default: a repair must not also be a format
                // migration. A body written by a codec that cannot round-trip through the TEXT
                // column it is stored in is not one this pass wrote, so it is left alone.
                val codec = DocumentCodecs.byId(row.format) as? TextDocumentCodec ?: return@forEach
                val doc = runCatching { codec.decodeFromString(row.docJson) }.getOrNull()
                    ?: return@forEach
                val repaired = doc.withRepairedAutomaticInk() ?: return@forEach
                // `updatedAt` is left as it was. This corrects what a page already said rather than
                // editing it, and the outbox timestamps the push itself.
                contentDao.upsert(row.copy(docJson = codec.encodeToString(repaired)))
                pages++
            }
            metadata.put(LocalMetadataEntity(KEY, MARKER))
            InkRepairResult(strokes = strokes, pages = pages)
        }
    }

    private companion object {
        /** Versioned, so a later repair of a different kind is a new key rather than a re-run. */
        const val KEY = "repair.chosenAutomaticInk.1"
        const val MARKER = "done"
    }
}
