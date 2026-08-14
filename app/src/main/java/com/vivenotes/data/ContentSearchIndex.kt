package com.vivenotes.data

import com.vivenotes.data.db.ImageTextStatus
import com.vivenotes.model.search.ContentHit
import com.vivenotes.model.search.ContentUnit
import com.vivenotes.model.search.ImagePlacement
import com.vivenotes.model.search.MAX_HITS
import com.vivenotes.model.search.contentUnits
import com.vivenotes.model.search.imagePlacements
import com.vivenotes.model.search.imageUnits
import com.vivenotes.model.search.searchContent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One page's hits, kept together so the panel can head them with the page they came from. */
data class PageResults(
    val pageId: String,
    val sectionId: String,
    val title: String,
    val sectionName: String,
    val hits: List<ContentHit>,
)

/** What one query found. */
data class ContentSearchResults(
    val query: String,
    val pages: List<PageResults> = emptyList(),
    val hitCount: Int = 0,
    /** More matched than were returned, so the list is a best-of rather than the whole answer. */
    val truncated: Boolean = false,
)

/**
 * A query's results, plus the pictures the notebook turned out to contain.
 *
 * The two travel together because the search is the only thing that decodes every page — asking it
 * what pictures it saw costs nothing, and asking anything else would mean decoding them twice
 * (`memory/imageOcrPlan.md` IO6).
 */
data class ContentSearchOutcome(
    val results: ContentSearchResults,
    /** Every attachment id placed in the notebook, for the indexer to read the unread ones. */
    val pictures: Set<String>,
)

/**
 * The Content panel's corpus, held in memory and rebuilt a page at a time — `docs/searchPlan.md` CS7.
 *
 * **No FTS table and no schema change.** `page_fts` is designed (A7, `docs/plan.md` §7) and not built;
 * this is what a notebook-sized search costs without it: one cheap query for the page rows, a decode
 * of only those whose `updatedAt` has moved since they were last seen, and matching in memory. The
 * first query on a notebook pays to decode it once; later ones pay for what changed. When the FTS
 * table does arrive it replaces the two steps below and leaves the matcher untouched.
 *
 * The stamp is the page row's, not the content row's, because reading page rows is what this does
 * anyway — and both halves of what is indexed move it: `saveDoc` through `updatePreview`, and a
 * rename directly.
 *
 * **The open page is never read from here.** Its last keystrokes are up to 400ms from being written,
 * so the caller passes its live units in and this indexes every *other* page (CS8).
 *
 * **Pictures are the one thing not cached with the page.** Their text is produced in the background
 * long after the document was decoded, so what is cached is where each picture *sits*
 * ([Entry.images]) and the words are joined on at query time. That is what lets results grow into an
 * open panel as reading finishes, with no invalidation protocol between the indexer and this cache.
 */
class ContentSearchIndex(private val repository: NotesRepository) {

    private data class Entry(
        val stamp: Long,
        val units: List<ContentUnit>,
        val images: List<ImagePlacement>,
    )

    private val lock = Mutex()
    private var indexed: String? = null
    private val byPage = mutableMapOf<String, Entry>()

    /**
     * Ranks [query] over [notebookId], with [liveUnits] and [liveImages] standing in for the open page.
     *
     * [livePageId] is excluded from the stored index entirely rather than merely overridden, so a
     * page that is open can never be searched twice or searched stale.
     */
    suspend fun search(
        notebookId: String,
        query: String,
        livePageId: String?,
        liveUnits: List<ContentUnit>,
        liveImages: List<ImagePlacement> = emptyList(),
    ): ContentSearchOutcome {
        if (query.isBlank()) return ContentSearchOutcome(ContentSearchResults(query), emptySet())
        val pages = repository.pagesInNotebook(notebookId)
        val sections = repository.sectionsInNotebook(notebookId).associate { it.id to it.name }

        val (units, placements) = lock.withLock {
            if (indexed != notebookId) {
                byPage.clear()
                indexed = notebookId
            }
            // Pages deleted or moved out of the notebook stop being searchable at once, rather than
            // lingering until something else evicts them.
            byPage.keys.retainAll(pages.mapTo(mutableSetOf()) { it.id })

            val stale = pages.filter { it.id != livePageId && byPage[it.id]?.stamp != it.updatedAt }
            if (stale.isNotEmpty()) {
                val docs = repository.docsFor(stale.map { it.id })
                stale.forEach { page ->
                    // A page with no body yet, or one whose body could not be decoded, is cached as
                    // empty *with its stamp* — otherwise every keystroke would try it again.
                    val doc = docs[page.id]
                    byPage[page.id] = Entry(
                        stamp = page.updatedAt,
                        units = doc?.contentUnits(page.id, page.sectionId, page.title).orEmpty(),
                        images = doc?.imagePlacements(page.id, page.sectionId).orEmpty(),
                    )
                }
            }

            val storedUnits = buildList {
                addAll(liveUnits)
                pages.forEach { page ->
                    if (page.id != livePageId) byPage[page.id]?.units?.let(::addAll)
                }
            }
            val storedImages = buildList {
                addAll(liveImages)
                pages.forEach { page ->
                    if (page.id != livePageId) byPage[page.id]?.images?.let(::addAll)
                }
            }
            storedUnits to storedImages
        }

        val pictures = placements.mapTo(mutableSetOf()) { it.attachmentId }
        if (query.isBlank()) {
            return ContentSearchOutcome(ContentSearchResults(query), pictures)
        }

        // Read every query rather than cached beside the documents: this is how text finished a
        // second ago reaches an open panel, and the rows are a handful of short columns.
        val stored = repository.imageTextFor(pictures)
        val withPictures = units + placements.flatMap { placement ->
            val row = stored[placement.attachmentId]
            if (row == null || row.status != ImageTextStatus.Read) {
                emptyList()
            } else {
                imageUnits(placement, row.text.lines())
            }
        }

        val hits = searchContent(withPictures, query)
        val titles = pages.associate { it.id to it.title }
        // Pages in the order their best hit ranked, and hits within a page in the order they ranked:
        // `groupBy` preserves both, since the hits arrive sorted.
        val grouped = hits.groupBy { it.unit.pageId }.map { (pageId, pageHits) ->
            val sectionId = pageHits.first().unit.sectionId
            PageResults(
                pageId = pageId,
                sectionId = sectionId,
                title = titles[pageId].orEmpty(),
                sectionName = sections[sectionId].orEmpty(),
                hits = pageHits,
            )
        }

        return ContentSearchOutcome(
            results = ContentSearchResults(
                query = query,
                pages = grouped,
                hitCount = hits.size,
                truncated = hits.size >= MAX_HITS,
            ),
            pictures = pictures,
        )
    }
}
