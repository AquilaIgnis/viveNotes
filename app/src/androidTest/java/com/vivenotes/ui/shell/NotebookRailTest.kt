package com.vivenotes.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vivenotes.data.db.NotebookEntity
import com.vivenotes.data.db.NotebookWithSections
import com.vivenotes.data.db.SectionEntity
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The rail's per-row commands, its section drag, and the Move up and Move down that stand in for it.
 *
 * The drag tests are the ones that matter. Reordering is written against the live layout — row
 * heights and offsets read back from the lazy list as it reflows — so none of it can be checked by
 * reading the code, only by moving a finger across a composed list and seeing where the row lands.
 */
class NotebookRailTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var density: Density

    private val notebook = NotebookEntity(
        id = "nb",
        name = "Field notes",
        colorArgb = 0xFF4CAF50.toInt(),
        sortIndex = 0,
        expanded = true,
        createdAt = 0,
        updatedAt = 0,
    )

    private var renamedNotebook: NotebookEntity? = null
    private var renamedSection: SectionEntity? = null
    private var deletedSection: SectionEntity? = null
    private var reordered: Pair<String, List<String>>? = null
    private var toggled: Pair<String, Boolean>? = null
    private var selected: String? = null

    private fun section(id: String, name: String, index: Int) = SectionEntity(
        id = id,
        notebookId = notebook.id,
        name = name,
        colorArgb = 0xFF2196F3.toInt(),
        sortIndex = index,
        createdAt = 0,
        updatedAt = 0,
    )

    private fun setRail(
        sections: List<SectionEntity> = listOf(
            section("a", "Alpha", 0),
            section("b", "Bravo", 1),
            section("c", "Charlie", 2),
        ),
        others: List<NotebookWithSections> = emptyList(),
        /** The system font size, as a multiple; the device's own is left alone. */
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ViveNotesTheme {
                    Box(Modifier.width(232.dp).height(500.dp)) {
                        NotebookRail(
                            tree = listOf(NotebookWithSections(notebook, sections)) + others,
                            selectedSectionId = sections.firstOrNull()?.id,
                            onSelectSection = { selected = it },
                            onToggleNotebook = { id, expanded -> toggled = id to expanded },
                            onAddSection = {},
                            onAddNotebook = {},
                            onRenameNotebook = { renamedNotebook = it },
                            onRenameSection = { renamedSection = it },
                            onDeleteSection = { deletedSection = it },
                            onReorderSections = { id, order -> reordered = id to order },
                        )
                    }
                }
            }
        }
    }

    /**
     * At twice the system font size "New Notebook" no longer fits one line. The row has to grow to
     * show the second, where a fixed height showed only "New".
     */
    @Test
    fun theAddRowsShowTheirWholeLabelAtTwiceTheFontSize() {
        setRail(fontScale = 2f)

        listOf("New Notebook", "New Section").forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            // Height only. The layout semantics hands over is redone at the row's full width, so a
            // label narrower than that reads as overflowing it; the bug was a hidden second line.
            assertFalse("\"$label\" is cut off", layouts.single().didOverflowHeight)
        }
    }

    /** The commands are held for, not docked beside the name — nothing shows until a long press. */
    @Test
    fun sectionCommandsStayHiddenUntilTheRowIsHeld() {
        setRail()

        compose.onNodeWithTag(RailTags.sectionRename("b")).assertDoesNotExist()
        compose.onNodeWithTag(RailTags.sectionDelete("b")).assertDoesNotExist()
    }

    @Test
    fun holdingASectionOffersRenameForThatSection() {
        setRail()

        compose.onNodeWithTag(RailTags.section("b")).performTouchInput { longClick() }
        compose.onNodeWithTag(RailTags.sectionRename("b")).performClick()

        assertEquals("Bravo", renamedSection?.name)
    }

    @Test
    fun holdingASectionOffersDeleteForThatSection() {
        setRail()

        compose.onNodeWithTag(RailTags.section("c")).performTouchInput { longClick() }
        compose.onNodeWithTag(RailTags.sectionDelete("c")).performClick()

        assertEquals("Charlie", deletedSection?.name)
    }

    /** Selecting a section is a tap, so holding one must not also open it. */
    @Test
    fun holdingASectionDoesNotSelectIt() {
        setRail()

        compose.onNodeWithTag(RailTags.section("b")).performTouchInput { longClick() }

        assertNull(selected)
    }

    /**
     * The header's own tap collapses the notebook. Holding it has to reach the menu without the
     * tap firing underneath, or renaming would always fold the notebook shut on the way.
     */
    @Test
    fun holdingANotebookOffersRenameWithoutCollapsingIt() {
        setRail()

        compose.onNodeWithTag(RailTags.notebook("nb")).performTouchInput { longClick() }
        compose.onNodeWithTag(RailTags.notebookRename("nb")).performClick()

        assertEquals("Field notes", renamedNotebook?.name)
        assertNull("the header's own tap fired underneath the long press", toggled)
    }

    @Test
    fun draggingASectionDownMovesItPastTheOneBelow() {
        setRail()

        dragSection("a", rows = 1.4f)

        assertEquals("nb" to listOf("b", "a", "c"), reordered)
    }

    @Test
    fun draggingASectionUpMovesItPastEverythingAbove() {
        setRail()

        dragSection("c", rows = -2.4f)

        assertEquals("nb" to listOf("c", "a", "b"), reordered)
    }

    /** A press that never travels is not a reorder, and must not write one. */
    @Test
    fun tappingTheHandleReordersNothing() {
        setRail()

        compose.onNodeWithTag(RailTags.sectionDrag("a"), useUnmergedTree = true).performTouchInput {
            down(center)
            up()
        }
        compose.waitForIdle()

        assertNull(reordered)
    }

    /** What TalkBack and Switch Access do in place of the drag, which neither can make. */
    @Test
    fun moveUpTradesASectionWithTheOneAbove() {
        setRail()

        performMove(RailTags.section("c"), "Move up")

        assertEquals("nb" to listOf("a", "c", "b"), reordered)
    }

    @Test
    fun theEndSectionsOfferOnlyTheWayTheyCanGo() {
        setRail()

        assertEquals(listOf("Move down"), moveLabels(RailTags.section("a")))
        assertEquals(listOf("Move up", "Move down"), moveLabels(RailTags.section("b")))
        assertEquals(listOf("Move up"), moveLabels(RailTags.section("c")))
    }

    /**
     * Sections move within their own notebook, as a drag keeps them: the first section of the second
     * notebook is first in its list, whatever the rail shows above it.
     */
    @Test
    fun theMovesStayInsideTheSectionsNotebook() {
        val second = notebook.copy(id = "nb2", name = "Recipes", sortIndex = 1)
        setRail(
            others = listOf(
                NotebookWithSections(
                    second,
                    listOf(section("x", "X-ray", 0), section("y", "Yankee", 1))
                        .map { it.copy(notebookId = second.id) },
                ),
            ),
        )

        assertEquals(listOf("Move down"), moveLabels(RailTags.section("x")))
        performMove(RailTags.section("y"), "Move up")

        assertEquals("nb2" to listOf("y", "x"), reordered)
    }

    /** The same moves in the long-press menu, for a hand that can open it but cannot hold a drag. */
    @Test
    fun theLongPressMenuMovesASection() {
        setRail()

        compose.onNodeWithTag(RailTags.section("a")).performTouchInput { longClick() }
        compose.onNodeWithText("Move down").performClick()

        assertEquals("nb" to listOf("b", "a", "c"), reordered)
    }

    @Test
    fun theLastSectionsMenuOffersNoMoveDown() {
        setRail()

        compose.onNodeWithTag(RailTags.section("c")).performTouchInput { longClick() }

        compose.onNodeWithText("Move up").assertIsDisplayed()
        compose.onNodeWithText("Move down").assertDoesNotExist()
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

    /**
     * Drags [sectionId] by [rows] row pitches, positive downwards.
     *
     * The pitch is measured off two composed rows rather than assumed, and the gesture is delivered
     * in steps: a swap is decided from where the dragged row's centre sits each time it moves, so
     * one jump would leap straight over the row it is meant to trade places with. Touch slop is
     * added back on because `detectDragGestures` swallows it before reporting the first delta.
     */
    private fun dragSection(sectionId: String, rows: Float) {
        val pitch = with(density) {
            (rowTop("b") - rowTop("a")).toPx()
        }
        compose.onNodeWithTag(RailTags.sectionDrag(sectionId), useUnmergedTree = true).performTouchInput {
            val travel = pitch * rows
            val distance = travel + viewConfiguration.touchSlop * (if (rows < 0) -1f else 1f)
            down(center)
            repeat(STEPS) { moveBy(Offset(0f, distance / STEPS)) }
            up()
        }
        compose.waitForIdle()
    }

    private fun rowTop(sectionId: String) =
        compose.onNodeWithTag(RailTags.section(sectionId)).getUnclippedBoundsInRoot().top

    private companion object {
        const val STEPS = 12
    }
}
