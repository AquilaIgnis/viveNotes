package com.vivenotes.model.search

import com.vivenotes.model.Block
import com.vivenotes.model.OBJECT_REPLACEMENT_CHARACTER
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.ai.InkTextRegion
import com.vivenotes.ink.InkBounds

/**
 * What a hit was found in — `docs/searchPlan.md` CS3.
 *
 * Ink, equations, shapes and pictures are absent on purpose rather than merely unimplemented: the
 * plan's table says why for each, and adding one means deciding what its snippet reads like and what
 * opening it should reveal, not just extending this enum.
 */
enum class ContentKind {
    /** The page's own title field. */
    Title,

    /** A block of a text container — a text box. */
    Text,

    /** A block of one table cell — the "grid of text fields" of `docs/tablePlan.md` TA15. */
    Cell,

    /**
     * One line PP-OCRv5 read inside a picture — `memory/imageOcrPlan.md` IO5.
     *
     * The only kind whose text nobody typed. That is why it is weighted below the others and why
     * opening one selects the picture rather than pretending to put a caret in it.
     */
    Image,

    /** One phrase read from replayed handwriting; opening it selects the source strokes. */
    Ink,
}

/**
 * One searchable block, and everything needed to go back to it — CS4.
 *
 * A block rather than a page, so a result is a real line of the note and the caret can land on the
 * word that matched.
 *
 * [blockStart] is this block's offset inside its box's *editor* text, which is what makes
 * `blockStart + span.start` an argument `setSelection` understands (CS5). Zero for a title, which
 * is a field of its own with nothing before it.
 */
data class ContentUnit(
    val pageId: String,
    val sectionId: String,
    val kind: ContentKind,
    /** The container or cell holding the block; the page id for a title, which has no box. */
    val boxId: String,
    /** The table a cell belongs to, so the canvas can scroll to something it can lay out. */
    val tableId: String? = null,
    val blockIndex: Int = 0,
    val blockStart: Int = 0,
    val text: String,
    /** Other readings of the same source region; fuzzy matching chooses the strongest candidate. */
    val alternatives: List<String> = emptyList(),
    /** Set only for [ContentKind.Image]: which picture's recognized text this line came from. */
    val attachmentId: String? = null,
    /** Set only for [ContentKind.Ink], so reveal can select the canonical strokes. */
    val inkStrokeIds: Set<String> = emptySet(),
    val inkBounds: InkBounds? = null,
)

/** A [ContentUnit] the query matched, with where in it the match landed. */
data class ContentHit(
    val unit: ContentUnit,
    val score: Int,
    val spans: List<MatchSpan>,
) {
    /** The match in editor coordinates — the pair the reveal selects (CS5, CS9). */
    val editorStart: Int get() = unit.blockStart + spans.first().start
    val editorEnd: Int get() = unit.blockStart + spans.last().end
}

/** A shortened line for the panel, with its emphasis moved to match. */
data class ContentSnippet(val text: String, val spans: List<MatchSpan>)

/**
 * Every searchable block of a stored page.
 *
 * The open page does not come through here — its units are built from live editor state instead, so
 * that search can find what was typed a moment ago rather than what was last written 400ms behind it
 * (CS8). Both routes share [blockUnits], which is where the offsets are computed, so the two can not
 * drift apart.
 */
fun PageDoc.contentUnits(pageId: String, sectionId: String, title: String): List<ContentUnit> {
    val units = mutableListOf<ContentUnit>()
    titleUnit(pageId, sectionId, title)?.let(units::add)
    outlines.forEach { outline ->
        when (outline) {
            is Outline.Text -> units += blockUnits(
                pageId = pageId,
                sectionId = sectionId,
                kind = ContentKind.Text,
                boxId = outline.id,
                blocks = outline.blocks,
            )
            is Outline.Table -> outline.rows.forEach { row ->
                row.cells.forEach { cell ->
                    // An ink table's cells hold no blocks at all (TA15), so this contributes nothing
                    // for one without needing to ask whether it is one.
                    units += blockUnits(
                        pageId = pageId,
                        sectionId = sectionId,
                        kind = ContentKind.Cell,
                        boxId = cell.id,
                        tableId = outline.id,
                        blocks = cell.blocks,
                    )
                }
            }
            is Outline.Equation, is Outline.Image, is Outline.Ink, is Outline.Shape -> Unit
        }
    }
    return units
}

/** The page title as a unit, or null when the page has not been named. */
fun titleUnit(pageId: String, sectionId: String, title: String): ContentUnit? =
    title.takeIf { it.isNotBlank() }?.let {
        ContentUnit(
            pageId = pageId,
            sectionId = sectionId,
            kind = ContentKind.Title,
            boxId = pageId,
            text = it,
        )
    }

/**
 * One unit per non-blank block, each stamped with where it begins in the box's editor text.
 *
 * The offsets follow `SpannableCodec.render` exactly: blocks are concatenated in order with a single
 * newline between them, and an equation run contributes one character (CS5). Blank blocks are skipped
 * as results but still counted in the running offset — they are the empty lines of the note, and
 * dropping their length would push every later match off by one per blank line.
 */
fun blockUnits(
    pageId: String,
    sectionId: String,
    kind: ContentKind,
    boxId: String,
    tableId: String? = null,
    blocks: List<Block>,
): List<ContentUnit> {
    val units = mutableListOf<ContentUnit>()
    var offset = 0
    blocks.forEachIndexed { index, block ->
        val text = block.editorText
        if (text.isNotBlank()) {
            units += ContentUnit(
                pageId = pageId,
                sectionId = sectionId,
                kind = kind,
                boxId = boxId,
                tableId = tableId,
                blockIndex = index,
                blockStart = offset,
                text = text,
            )
        }
        offset += text.length + 1
    }
    return units
}

/**
 * Where one picture sits on one page — `memory/imageOcrPlan.md` IO5.
 *
 * Held instead of units because the *text* of a picture arrives later than the page does: indexing
 * runs in the background, so the placements are cached with the decoded document and the words are
 * joined on at query time, when whatever has been read by then is available.
 */
data class ImagePlacement(
    val pageId: String,
    val sectionId: String,
    /** The `Outline.Image` to select when a hit is opened. */
    val outlineId: String,
    val attachmentId: String,
)

/**
 * Every distinct picture on a page, at its first placement in document order.
 *
 * **Distinct by attachment, which is the whole of "do not duplicate the text".** A page that shows
 * the same screenshot twice offers one answer to "where is this written", with two frames around it;
 * listing it twice would be two identical rows in the panel leading to the same page. Two *pages*
 * showing it do each contribute, because those are genuinely different places to go.
 */
fun PageDoc.imagePlacements(pageId: String, sectionId: String): List<ImagePlacement> =
    outlines.filterIsInstance<Outline.Image>()
        .distinctBy { it.attachmentId }
        .map { ImagePlacement(pageId, sectionId, it.id, it.attachmentId) }

/**
 * One unit per recognized line of the picture at [placement].
 *
 * [lines] is what `attachment_text` stored, already in reading order. A picture that has not been
 * read yet, or that holds no text, contributes nothing — which is what makes the panel fill in as
 * indexing proceeds rather than showing empty rows for pictures nobody has looked at.
 */
fun imageUnits(placement: ImagePlacement, lines: List<String>): List<ContentUnit> {
    var offset = 0
    return lines.mapIndexedNotNull { index, line ->
        val start = offset
        offset += line.length + 1
        line.takeIf { it.isNotBlank() }?.let {
            ContentUnit(
                pageId = placement.pageId,
                sectionId = placement.sectionId,
                kind = ContentKind.Image,
                boxId = placement.outlineId,
                blockIndex = index,
                blockStart = start,
                text = it,
                attachmentId = placement.attachmentId,
            )
        }
    }
}

/** One searchable unit per vector-derived handwriting region. */
fun inkUnits(pageId: String, sectionId: String, regions: List<InkTextRegion>): List<ContentUnit> =
    regions.mapIndexedNotNull { index, region ->
        val candidates = listOfNotNull(region.text, region.alternateText)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        val primary = candidates.firstOrNull() ?: return@mapIndexedNotNull null
        ContentUnit(
            pageId = pageId,
            sectionId = sectionId,
            kind = ContentKind.Ink,
            boxId = region.id,
            blockIndex = index,
            text = primary,
            alternatives = candidates.drop(1),
            inkStrokeIds = region.strokeIds.toSet(),
            inkBounds = InkBounds(region.left, region.top, region.right, region.bottom),
        )
    }

/**
 * Ranks [units] against [query] — CS6.
 *
 * The matcher decides whether a block matches and how well; this adds the one piece of context it
 * cannot see, which is what kind of field the block was in. A title carries a page's subject, so a
 * page called "Invoices" outranks a line that merely mentions the word.
 *
 * Ties break on document order rather than on nothing, so the same query twice gives the same list.
 */
fun searchContent(
    units: List<ContentUnit>,
    query: String,
    limit: Int = MAX_HITS,
): List<ContentHit> {
    if (query.isBlank()) return emptyList()
    return units
        .mapNotNull { unit ->
            (listOf(unit.text) + unit.alternatives)
                .mapNotNull { candidate ->
                    FuzzyMatcher.match(query, candidate)?.let { match -> candidate to match }
                }
                .maxByOrNull { (_, match) -> match.score }
                ?.let { (candidate, match) ->
                    ContentHit(
                        unit = if (candidate == unit.text) unit else unit.copy(text = candidate),
                        score = match.score + unit.kind.weight,
                        spans = match.spans,
                    )
                }
        }
        .sortedWith(
            compareByDescending<ContentHit> { it.score }
                .thenBy { it.unit.pageId }
                .thenBy { it.unit.blockIndex },
        )
        .take(limit)
}

/**
 * A window of [text] around its first match, short enough for a 320dp pane.
 *
 * The spans come back moved to match, so the panel emphasises the same characters it would have
 * before the trim. Anything that fell outside the window is dropped rather than clamped to its edge,
 * which would draw emphasis on a word that is no longer there.
 */
fun snippetOf(text: String, spans: List<MatchSpan>, maxChars: Int = SNIPPET_CHARS): ContentSnippet {
    // An inline equation is one character in the editor and a tofu box on screen; a snippet shows a
    // space instead. One character for one, so every offset below still lines up.
    val clean = text.replace(OBJECT_REPLACEMENT_CHARACTER, ' ')
    if (clean.length <= maxChars) return ContentSnippet(clean, spans)

    val first = spans.firstOrNull()?.start ?: 0
    // Keep a little of what came before the match, so it reads as part of a sentence rather than
    // starting mid-word at the hit.
    val from = (first - SNIPPET_LEAD).coerceIn(0, (clean.length - maxChars).coerceAtLeast(0))
    val to = (from + maxChars).coerceAtMost(clean.length)
    val head = if (from > 0) ELLIPSIS else ""
    val tail = if (to < clean.length) ELLIPSIS else ""
    val shift = head.length - from
    return ContentSnippet(
        text = head + clean.substring(from, to) + tail,
        spans = spans
            .filter { it.start >= from && it.end <= to }
            .map { MatchSpan(it.start + shift, it.end + shift) },
    )
}

/**
 * A title is a page's subject; a body line only mentions things.
 *
 * A picture's line is docked instead of promoted, and not because it matters less: it is the one
 * kind nobody typed. Typed text is what someone wrote and a reading is what a model thinks it can
 * see, so on an equally good match the certain one goes first. Docked rather than excluded, because
 * a picture that *does* contain the word is still the answer when nothing else does.
 */
private val ContentKind.weight: Int
    get() = when (this) {
        ContentKind.Title -> 40
        ContentKind.Text, ContentKind.Cell -> 0
        ContentKind.Image, ContentKind.Ink -> -8
    }

/**
 * How many hits a query may return.
 *
 * A one-letter query matches most of a notebook, and a list nobody can reach the bottom of is not a
 * more useful answer than a list they can.
 */
const val MAX_HITS = 120

private const val SNIPPET_CHARS = 120
private const val SNIPPET_LEAD = 24
private const val ELLIPSIS = "…"
