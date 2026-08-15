package com.vivenotes.ui.panel

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.vivenotes.model.search.ContentHit
import com.vivenotes.model.search.ContentKind
import com.vivenotes.model.search.MatchSpan
import com.vivenotes.model.search.snippetOf
import com.vivenotes.data.ImageTextProgress
import com.vivenotes.data.PageResults
import com.vivenotes.ui.ContentSearchState
import com.vivenotes.ui.icons.MaterialSymbols

internal object ContentPanelTags {
    const val QUERY = "content-query"
    const val CLEAR = "content-clear"
    const val STATUS = "content-status"

    /** The "reading N pictures" line, shown only while an indexing pass is in flight. */
    const val PICTURES = "content-pictures"

    /** A page heading in the result list, which is also the way a title match is opened. */
    fun page(pageId: String) = "content-page-$pageId"

    /** One matched block. Addressed by where it is, since two blocks can read the same. */
    fun hit(boxId: String, blockIndex: Int) = "content-hit-$boxId-$blockIndex"
}

/**
 * The query field — `docs/searchPlan.md` CS1, pinned above the results by [ToolPanel]'s header slot.
 *
 * Its own composable rather than the first row of the list, because a field that scrolls away is a
 * field you cannot correct: the whole interaction here is type, look, retype.
 */
@Composable
internal fun ColumnScope.ContentPanelHeader(
    state: ContentSearchState,
    onQueryChange: (String) -> Unit,
    imageProgress: ImageTextProgress = ImageTextProgress(enabled = false),
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(QUERY_FIELD_HEIGHT)
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MaterialSymbols.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (state.query.isEmpty()) {
                Text(
                    text = "Search this notebook",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = state.query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                // Nothing to submit: results follow the typing, so the key that would submit them
                // closes the keyboard instead of promising a second kind of search.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ContentPanelTags.QUERY),
            )
        }
        if (state.query.isNotEmpty()) {
            Icon(
                imageVector = MaterialSymbols.Close,
                contentDescription = "Clear search",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable { onQueryChange("") }
                    .padding(4.dp)
                    .testTag(ContentPanelTags.CLEAR),
            )
        }
    }

    Text(
        text = state.statusLine(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(top = 6.dp, bottom = 2.dp)
            .testTag(ContentPanelTags.STATUS),
    )

    // Said only while it is happening, and only when there is a query to be incomplete about: a
    // result list that is still growing should say so, and one that is finished should not carry a
    // line about machinery.
    if (imageProgress.running && state.query.isNotBlank()) {
        Text(
            text = imageProgress.readingLine(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(bottom = 2.dp)
                .testTag(ContentPanelTags.PICTURES),
        )
    }
}

private fun ImageTextProgress.readingLine(): String {
    val remaining = pending.coerceAtLeast(0)
    val pictures = if (remaining == 1) "1 picture" else "$remaining pictures"
    return "Reading $pictures…"
}

/**
 * The results, grouped by the page they were found on — CS2, CS4.
 *
 * **A title match is the page heading itself, not a row under it.** Listing it separately would print
 * the page's name directly beneath the page's name, so the heading takes the highlight and opening it
 * goes to that page.
 *
 * A plain [Column] rather than a `LazyColumn`: this sits inside [ToolPanel]'s scrolling column, which
 * a lazy list cannot be nested in, and the result count is capped precisely so it does not need to be.
 */
@Composable
internal fun ColumnScope.ContentPanelContent(
    state: ContentSearchState,
    onOpenHit: (ContentHit) -> Unit,
) {
    val results = state.results ?: return
    results.pages.forEach { page ->
        PageHeading(page, onOpenHit)
        val blocks = page.hits.filter { it.unit.kind != ContentKind.Title }
        blocks.take(HITS_PER_PAGE).forEach { hit ->
            HitRow(hit, onOpenHit)
        }
        val hidden = blocks.size - HITS_PER_PAGE
        if (hidden > 0) {
            Text(
                text = "+$hidden more on this page",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PageHeading(page: PageResults, onOpenHit: (ContentHit) -> Unit) {
    val titleHit = page.hits.firstOrNull { it.unit.kind == ContentKind.Title }
    val title = page.title.ifBlank { "Untitled page" }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable { onOpenHit(titleHit ?: page.hits.first()) }
            .padding(vertical = 4.dp, horizontal = 4.dp)
            .testTag(ContentPanelTags.page(page.pageId)),
    ) {
        Text(
            text = highlighted(title, titleHit?.spans.orEmpty()),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (page.sectionName.isNotBlank()) {
            Text(
                text = page.sectionName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HitRow(hit: ContentHit, onOpenHit: (ContentHit) -> Unit) {
    val snippet = snippetOf(hit.unit.text, hit.spans)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable { onOpenHit(hit) }
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .testTag(ContentPanelTags.hit(hit.unit.boxId, hit.unit.blockIndex)),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Only the kinds that are not plain text say what they are. A text box is what a note is
        // made of, so labelling every ordinary line "text" would be noise down the whole list — but
        // a line nobody typed had better say so before someone goes looking for it in the editor.
        when (hit.unit.kind) {
            ContentKind.Cell -> Icon(
                imageVector = MaterialSymbols.Table,
                contentDescription = "In a table",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(14.dp),
            )
            ContentKind.Image -> Icon(
                imageVector = MaterialSymbols.Image,
                contentDescription = "Read from a picture",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(14.dp),
            )
            ContentKind.Ink -> Icon(
                imageVector = MaterialSymbols.Stylus,
                contentDescription = "Read from handwriting",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(14.dp),
            )
            ContentKind.Title, ContentKind.Text -> Unit
        }
        Text(
            text = highlighted(snippet.text, snippet.spans),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The matched characters, marked the way a search marks them: emphasised, not recoloured. */
@Composable
private fun highlighted(text: String, spans: List<MatchSpan>): AnnotatedString {
    if (spans.isEmpty()) return AnnotatedString(text)
    val emphasis = SpanStyle(
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        background = MaterialTheme.colorScheme.primaryContainer,
        fontWeight = FontWeight.SemiBold,
    )
    return buildAnnotatedString {
        var at = 0
        spans.forEach { span ->
            // Spans are computed against the text they came with; a caller that shortened it since
            // is a bug, but not one worth crashing a search panel over.
            val start = span.start.coerceIn(0, text.length)
            val end = span.end.coerceIn(start, text.length)
            if (start > at) append(text.substring(at, start))
            withStyle(emphasis) { append(text.substring(start, end)) }
            at = end
        }
        if (at < text.length) append(text.substring(at))
    }
}

private fun ContentSearchState.statusLine(): String = when {
    query.isBlank() -> "Text, handwriting, pictures and page titles across this notebook."
    // While a query is in flight the list below is still the previous one's, so the line says what
    // is happening rather than counting an answer to a question that has changed.
    running -> "Searching…"
    results == null -> ""
    results.hitCount == 0 -> "No matches."
    else -> {
        val matches = if (results.hitCount == 1) "1 match" else "${results.hitCount} matches"
        val pages = if (results.pages.size == 1) "1 page" else "${results.pages.size} pages"
        val counted = if (results.truncated) "First $matches" else matches
        "$counted on $pages"
    }
}

/**
 * How many of one page's blocks are listed before the rest are counted.
 *
 * A page where every line matches would otherwise push every *other* page off the bottom of the pane,
 * which turns a notebook search back into a page search.
 */
private const val HITS_PER_PAGE = 8

private val QUERY_FIELD_HEIGHT = 36.dp
