package com.vivenotes.ui.panel

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.vivenotes.data.ContentSearchResults
import com.vivenotes.data.PageResults
import com.vivenotes.model.search.ContentHit
import com.vivenotes.model.search.ContentKind
import com.vivenotes.model.search.ContentUnit
import com.vivenotes.model.search.MatchSpan
import com.vivenotes.ui.ContentSearchState
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The Content panel's own behaviour — `docs/searchPlan.md` CS1, CS4.
 *
 * Composed against a made-up result set rather than a database, so what is under test is the panel:
 * that a page's matches are grouped under it, that a title match is the heading rather than a row
 * beneath it, and that opening either hands back the hit the caller has to act on.
 */
class ContentPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private fun unit(
        kind: ContentKind,
        boxId: String,
        text: String,
        blockIndex: Int = 0,
    ) = ContentUnit(
        pageId = "page-1",
        sectionId = "section-1",
        kind = kind,
        boxId = boxId,
        blockIndex = blockIndex,
        text = text,
    )

    private val titleHit = ContentHit(
        unit = unit(ContentKind.Title, "page-1", "Invoices"),
        score = 140,
        spans = listOf(MatchSpan(0, 8)),
    )

    private val bodyHit = ContentHit(
        unit = unit(ContentKind.Text, "box-1", "the invoices are filed", blockIndex = 2),
        score = 100,
        spans = listOf(MatchSpan(4, 12)),
    )

    private fun results(vararg hits: ContentHit) = ContentSearchResults(
        query = "invoices",
        pages = listOf(
            PageResults(
                pageId = "page-1",
                sectionId = "section-1",
                title = "Invoices",
                sectionName = "Finance",
                hits = hits.toList(),
            ),
        ),
        hitCount = hits.size,
    )

    private fun setPanel(
        state: ContentSearchState,
        onQueryChange: (String) -> Unit = {},
        onOpenHit: (ContentHit) -> Unit = {},
    ) {
        compose.setContent {
            ViveNotesTheme {
                Column {
                    ContentPanelHeader(state = state, onQueryChange = onQueryChange)
                    ContentPanelContent(state = state, onOpenHit = onOpenHit)
                }
            }
        }
    }

    @Test
    fun anEmptyQueryExplainsWhatIsSearched() {
        setPanel(ContentSearchState())
        compose.onNodeWithTag(ContentPanelTags.STATUS)
            .assertIsDisplayed()
        compose.onNodeWithText("Search this notebook").assertIsDisplayed()
    }

    @Test
    fun typingReportsEveryKeystrokeStraightAway() {
        val typed = mutableListOf<String>()
        // Hoisted, unlike every other test here, because this is the one that types: the field is
        // value-controlled, so a fixed state would snap it back to empty between keystrokes and the
        // second character would land in an empty field instead of after the first.
        var query by mutableStateOf("")
        compose.setContent {
            ViveNotesTheme {
                Column {
                    ContentPanelHeader(
                        state = ContentSearchState(query = query),
                        onQueryChange = {
                            typed += it
                            query = it
                        },
                    )
                }
            }
        }

        // Three calls, not one `performTextInput("inv")`. That helper is a single IME commit
        // whatever the length of its argument, so it reports once however the field is written —
        // it measures the harness rather than the panel.
        compose.onNodeWithTag(ContentPanelTags.QUERY).performTextInput("i")
        compose.onNodeWithTag(ContentPanelTags.QUERY).performTextInput("n")
        compose.onNodeWithTag(ContentPanelTags.QUERY).performTextInput("v")

        // Three characters, three reports: the field must not wait for the debounced search, or it
        // fights the keyboard.
        assertEquals(listOf("i", "in", "inv"), typed)
    }

    @Test
    fun matchesAreGroupedUnderTheirPageAndCounted() {
        setPanel(
            ContentSearchState(query = "invoices", results = results(titleHit, bodyHit)),
        )

        compose.onNodeWithTag(ContentPanelTags.page("page-1")).assertIsDisplayed()
        compose.onNodeWithText("Finance").assertIsDisplayed()
        compose.onNodeWithText("2 matches on 1 page").assertIsDisplayed()
    }

    @Test
    fun aTitleMatchIsTheHeadingRatherThanARowOfItsOwn() {
        setPanel(ContentSearchState(query = "invoices", results = results(titleHit, bodyHit)))

        // The body match is listed; the title match is the heading above it, not a second row
        // repeating the page's own name.
        compose.onNodeWithTag(ContentPanelTags.hit("box-1", 2)).assertIsDisplayed()
        compose.onNodeWithTag(ContentPanelTags.hit("page-1", 0)).assertDoesNotExist()
    }

    @Test
    fun openingAResultHandsBackTheHitItCameFrom() {
        var opened: ContentHit? = null
        setPanel(
            ContentSearchState(query = "invoices", results = results(titleHit, bodyHit)),
            onOpenHit = { opened = it },
        )

        compose.onNodeWithTag(ContentPanelTags.hit("box-1", 2)).performClick()
        assertEquals(bodyHit, opened)

        compose.onNodeWithTag(ContentPanelTags.page("page-1")).performClick()
        assertEquals(titleHit, opened)
    }

    @Test
    fun aQueryThatFoundNothingSaysSo() {
        setPanel(ContentSearchState(query = "zzz", results = ContentSearchResults("zzz")))
        compose.onNodeWithText("No matches.").assertIsDisplayed()
    }

    @Test
    fun clearingTheQueryEmptiesTheField() {
        var query: String? = null
        setPanel(ContentSearchState(query = "invoices"), onQueryChange = { query = it })

        compose.onNodeWithTag(ContentPanelTags.CLEAR).performClick()
        assertEquals("", query)
    }

    @Test
    fun aSearchInFlightSaysSoRatherThanCountingTheOldAnswer() {
        setPanel(
            ContentSearchState(query = "invoic", running = true, results = results(titleHit)),
        )
        compose.onNodeWithText("Searching…").assertIsDisplayed()
    }
}
