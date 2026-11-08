package com.vivenotes.data

import com.vivenotes.model.ocr.ImageLineLayout
import com.vivenotes.model.ocr.ImageTextLayout
import com.vivenotes.model.ocr.PictureHighlight
import com.vivenotes.model.ocr.PictureHighlights
import com.vivenotes.model.search.MatchSpan
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Turns a query into boxes on the pictures of the open page.
 *
 * Only ever asked about one page's pictures, so what it holds is small and a keystroke touches no
 * database: the layouts are decoded once and re-read only when the indexer writes something new.
 * Search across the rest of the notebook does not come through here, and never reads a layout.
 */
class PictureTextHighlighter(private val repository: NotesRepository) {

    private class Reading(val lines: List<String>, val layouts: List<ImageLineLayout>)

    private val lock = Mutex()
    private val readings = HashMap<String, Reading>()

    /** Pictures known to have nothing to draw, so a keystroke does not ask the database again. */
    private val withoutLayout = HashSet<String>()
    private var version = Long.MIN_VALUE

    /**
     * Where [query] lands on each of [attachmentIds], keyed by attachment and absent where it does
     * not. [indexerVersion] is `ImageTextIndexer.version`, which is what can make a held layout stale.
     */
    suspend fun forQuery(
        attachmentIds: Set<String>,
        query: String,
        indexerVersion: Long,
    ): Map<String, PictureHighlight> = lock.withLock {
        prepare(attachmentIds, indexerVersion)
        readings.keys.retainAll(attachmentIds)
        withoutLayout.retainAll(attachmentIds)
        buildMap {
            attachmentIds.forEach { id ->
                val reading = readings[id] ?: return@forEach
                PictureHighlights.forQuery(query, reading.lines, reading.layouts)?.let { put(id, it) }
            }
        }
    }

    /** Boxes for one matched line, or null if the picture has no layout yet. */
    suspend fun forLine(
        attachmentId: String,
        lineIndex: Int,
        spans: List<MatchSpan>,
        indexerVersion: Long,
    ): PictureHighlight? = lock.withLock {
        prepare(setOf(attachmentId), indexerVersion)
        val reading = readings[attachmentId] ?: return@withLock null
        val line = reading.lines.getOrNull(lineIndex) ?: return@withLock null
        val layout = reading.layouts.getOrNull(lineIndex) ?: return@withLock null
        PictureHighlights.forSpans(line, layout, spans)
    }

    /** Loads whichever of [attachmentIds] is not held yet. Runs under [lock]. */
    private suspend fun prepare(attachmentIds: Set<String>, indexerVersion: Long) {
        if (indexerVersion != version) {
            readings.clear()
            withoutLayout.clear()
            version = indexerVersion
        }
        val missing = attachmentIds.filter { it !in readings && it !in withoutLayout }
        if (missing.isEmpty()) return
        val rows = repository.imageTextLayoutsFor(missing)
        missing.forEach { id ->
            val row = rows[id]
            val layouts = row?.let { ImageTextLayout.decode(it.layout) }
            if (row == null || layouts == null) {
                withoutLayout += id
            } else {
                readings[id] = Reading(row.text.lines(), layouts)
            }
        }
    }
}
