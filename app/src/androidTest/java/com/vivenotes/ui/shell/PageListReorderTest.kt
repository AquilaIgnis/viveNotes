package com.vivenotes.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vivenotes.data.db.PageEntity
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Dragging in the page list, its Move up and Move down, and the sorts where neither is on offer. */
class PageListReorderTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var density: Density
    private var reordered: List<String>? = null

    private fun page(id: String, title: String, index: Int, updatedAt: Long = 0) = PageEntity(
        id = id,
        sectionId = "sec",
        title = title,
        sortIndex = index,
        preview = "",
        createdAt = 0,
        updatedAt = updatedAt,
    )

    private val pages = listOf(
        page("a", "Alpha", 0, updatedAt = 300),
        page("b", "Bravo", 1, updatedAt = 200),
        page("c", "Charlie", 2, updatedAt = 100),
    )

    private fun setList() {
        compose.setContent {
            density = LocalDensity.current
            ViveNotesTheme {
                Box(Modifier.width(260.dp).height(600.dp)) {
                    PageListPane(
                        pages = pages,
                        selectedPageId = "a",
                        onSelectPage = {},
                        onAddPage = {},
                        onDeletePage = {},
                        onReorderPages = { reordered = it },
                    )
                }
            }
        }
    }

    @Test
    fun draggingAPageDownMovesItPastTheOneBelow() {
        setList()

        dragPage("a", rows = 1.4f)

        assertEquals(listOf("b", "a", "c"), reordered)
    }

    @Test
    fun draggingAPageUpMovesItPastEverythingAbove() {
        setList()

        dragPage("c", rows = -2.4f)

        assertEquals(listOf("c", "a", "b"), reordered)
    }

    /**
     * The other two sorts are derived from the pages themselves, so a dropped row would be sorted
     * straight back out of where it was put. The handle is absent rather than inert.
     */
    @Test
    fun theHandleIsOnlyOfferedUnderSectionOrder() {
        setList()
        compose.onNodeWithTag(PageListTags.dragHandle("a"), useUnmergedTree = true).assertIsDisplayed()

        compose.onNodeWithContentDescription("Sort pages").performClick()
        compose.onNodeWithText("By title").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(PageListTags.dragHandle("a"), useUnmergedTree = true).assertDoesNotExist()
        assertNull("switching sorts is not itself a reorder", reordered)
    }

    /** What TalkBack and Switch Access do in place of the drag, which neither can make. */
    @Test
    fun moveDownTradesAPageWithTheOneBelow() {
        setList()

        performMove(PageListTags.row("a"), "Move down")

        assertEquals(listOf("b", "a", "c"), reordered)
    }

    @Test
    fun moveUpTradesAPageWithTheOneAbove() {
        setList()

        performMove(PageListTags.row("c"), "Move up")

        assertEquals(listOf("a", "c", "b"), reordered)
    }

    @Test
    fun theEndPagesOfferOnlyTheWayTheyCanGo() {
        setList()

        assertEquals(listOf("Move down"), moveLabels(PageListTags.row("a")))
        assertEquals(listOf("Move up", "Move down"), moveLabels(PageListTags.row("b")))
        assertEquals(listOf("Move up"), moveLabels(PageListTags.row("c")))
    }

    /** Offered where the handle is and nowhere else, for the handle's reason. */
    @Test
    fun theMovesAreOnlyOfferedUnderSectionOrder() {
        setList()

        compose.onNodeWithContentDescription("Sort pages").performClick()
        compose.onNodeWithText("By title").performClick()
        compose.waitForIdle()

        assertEquals(emptyList<String>(), moveLabels(PageListTags.row("b")))
    }

    /** The row's own actions: TalkBack never offers ones merged up into it from the handle. */
    private fun moveLabels(tag: String): List<String> =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    private fun performMove(tag: String, label: String) {
        val action = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == label }
        compose.runOnIdle { action.action() }
        compose.waitForIdle()
    }

    private fun dragPage(pageId: String, rows: Float) {
        val pitch = with(density) { (rowTop("b") - rowTop("a")).toPx() }
        compose.onNodeWithTag(PageListTags.dragHandle(pageId), useUnmergedTree = true).performTouchInput {
            val distance = pitch * rows + viewConfiguration.touchSlop * (if (rows < 0) -1f else 1f)
            down(center)
            // Stepwise: a swap is decided from where the row's centre sits each time it moves.
            repeat(STEPS) { moveBy(Offset(0f, distance / STEPS)) }
            up()
        }
        compose.waitForIdle()
    }

    private fun rowTop(pageId: String) =
        compose.onNodeWithTag(PageListTags.row(pageId)).getUnclippedBoundsInRoot().top

    private companion object {
        const val STEPS = 12
    }
}
