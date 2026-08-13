package com.vivenotes.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vivenotes.data.db.NotebookEntity
import com.vivenotes.data.db.NotebookWithSections
import com.vivenotes.data.db.SectionEntity
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The rail's per-row commands and its section drag.
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
    ) {
        compose.setContent {
            density = LocalDensity.current
            ViveNotesTheme {
                Box(Modifier.width(232.dp).height(500.dp)) {
                    NotebookRail(
                        tree = listOf(NotebookWithSections(notebook, sections)),
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
