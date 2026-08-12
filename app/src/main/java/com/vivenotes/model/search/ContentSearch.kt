package com.vivenotes.model.search

import com.vivenotes.model.Block
import com.vivenotes.model.OBJECT_REPLACEMENT_CHARACTER
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc

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
            FuzzyMatcher.match(query, unit.text)?.let { match ->
                ContentHit(unit, match.score + unit.kind.weight, match.spans)
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

/** A title is a page's subject; a body line only mentions things. */
private val ContentKind.weight: Int
    get() = when (this) {
        ContentKind.Title -> 40
        ContentKind.Text, ContentKind.Cell -> 0
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
