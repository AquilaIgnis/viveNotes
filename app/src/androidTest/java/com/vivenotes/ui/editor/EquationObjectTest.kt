package com.vivenotes.ui.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.vivenotes.data.DrawTool
import com.vivenotes.data.EraserSettings
import com.vivenotes.data.HighlighterSettings
import com.vivenotes.data.PEN_COLORS
import com.vivenotes.data.PenPreset
import com.vivenotes.data.ShapeSettings
import com.vivenotes.ink.CanvasSelection
import com.vivenotes.ink.InkBounds
import com.vivenotes.model.Outline
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Draw tab's ƒ — an equation as an object on the canvas rather than a mark in a sentence.
 *
 * What needs a device is the seam between the panel and the tool: the formula is *content* held
 * beside the armed tool rather than a persisted setting, so the failure worth catching is a ƒ that
 * arms nothing, or arms without carrying the formula — in either case the next tap on the page would
 * place nothing at all and look exactly like a dead button.
 *
 * The arithmetic behind it — corners, the resize anchor, the round trip — is `EquationOutlineTest`,
 * on the JVM. Its inline twin is `EquationButtonTest`.
 */
class EquationObjectTest {

    @get:Rule
    val compose = createComposeRule()

    private var armed: DrawTool? = null
    private var arming: Pair<String, MeasuredEquation>? = null

    private fun setDrawTab(tool: DrawTool = DrawTool.None, pageOpen: Boolean = true) {
        armed = null
        arming = null
        compose.setContent {
            ViveNotesTheme {
                DrawTab(
                    pens = List(PenPreset.COUNT) { PenPreset.starting(it) },
                    palette = PEN_COLORS,
                    eraser = EraserSettings(),
                    highlighter = HighlighterSettings(),
                    shape = ShapeSettings(),
                    tool = tool,
                    actions = DrawActions(
                        selectTool = { armed = it },
                        updatePen = { _, _ -> },
                        updateEraser = {},
                        armEquation = { latex, measured -> arming = latex to measured },
                        setDrawWithFinger = {},
                    ),
                    pageOpen = pageOpen,
                )
            }
        }
    }

    @Test
    fun theDrawTabsButtonOpensThePanelRatherThanPlacingAnything() {
        setDrawTab()

        compose.onNodeWithTag(EquationTags.OBJECT).performClick()

        compose.onNodeWithText("Insert equation").assertIsDisplayed()
        assertNull("the button placed something before it knew the formula", arming)
    }

    /**
     * **The formula travels with the tool.** It is not a setting on a shelf; if the arm carried only
     * "equation mode" the tap that follows would have nothing to place.
     */
    @Test
    fun submittingArmsTheToolWithTheFormulaAndItsMeasuredBox() {
        setDrawTab()
        compose.onNodeWithTag(EquationTags.OBJECT).performClick()
        compose.onNodeWithTag(EquationTags.SOURCE).performTextReplacement("x^2+y^2=z^2")

        compose.onNodeWithTag(EquationTags.SUBMIT).performClick()

        compose.waitUntil(timeoutMillis = 10_000) { arming != null }
        assertEquals("x^2+y^2=z^2", arming?.first)
        val measured = arming?.second
        assertNotNull("nothing measured it, so it would arrive at the fallback size", measured)
        assertTrue("a rendered formula has width", (measured?.width ?: 0f) > 0f)
        assertTrue("a rendered formula has height", (measured?.height ?: 0f) > 0f)
    }

    /** With no page open there is nowhere to put a formula, so the button is shown and inert. */
    @Test
    fun withNoPageOpenTheButtonIsInert() {
        setDrawTab(pageOpen = false)

        compose.onNodeWithTag(EquationTags.OBJECT).performClick()

        compose.onNodeWithText("Insert equation").assertDoesNotExist()
    }

    // -----------------------------------------------------------------------------------------
    // The object toolkit
    // -----------------------------------------------------------------------------------------

    /**
     * The one action Prime Object does not already cover: change what the formula says.
     *
     * Note what is *not* asserted here, because it is the point — no move, resize, delete, copy or
     * colour action is tested for the equation, since none of them is the equation's code. They are
     * `CanvasSelection`'s and the tooltip's, and they work on this kind for the same reason they work
     * on a shape.
     */
    @Test
    fun theToolkitEditsTheFormulaOfWhatIsSelected() {
        var edited: String? = null
        compose.setContent {
            ViveNotesTheme {
                ObjectTooltip(
                    swatch = null,
                    selectionBoundsInView = { android.graphics.RectF(0f, 0f, 100f, 60f) },
                    viewportSize = androidx.compose.ui.unit.IntSize(1000, 1000),
                    onDelete = {},
                    onCopy = {},
                    onRecolor = {},
                    // The bar without its lock: these cases are about the kind's own half of it.
                    locked = null,
                    onToggleLock = {},
                    extras = {
                        EquationEditAction(latex = "x^2", onEdit = { edited = it })
                    },
                )
            }
        }

        compose.onNodeWithTag(OBJECT_EQUATION_EDIT_TAG).performClick()
        compose.onNodeWithTag(EquationTags.SOURCE).performTextReplacement("y^3")
        compose.onNodeWithTag(EquationTags.SUBMIT).performClick()

        compose.waitUntil(timeoutMillis = 10_000) { edited != null }
        assertEquals("y^3", edited)
    }

    /** Two different formulas have no one source to open, so the action is absent rather than dead. */
    @Test
    fun theEditActionIsAbsentOverAMixedSelection() {
        compose.setContent {
            ViveNotesTheme {
                ObjectTooltip(
                    swatch = null,
                    selectionBoundsInView = { android.graphics.RectF(0f, 0f, 100f, 60f) },
                    viewportSize = androidx.compose.ui.unit.IntSize(1000, 1000),
                    onDelete = {},
                    onCopy = {},
                    onRecolor = {},
                    // The bar without its lock: these cases are about the kind's own half of it.
                    locked = null,
                    onToggleLock = {},
                    extras = { EquationEditAction(latex = null, onEdit = {}) },
                )
            }
        }

        compose.onNodeWithTag(OBJECT_EQUATION_EDIT_TAG).assertDoesNotExist()
    }

    // -----------------------------------------------------------------------------------------
    // Selection
    // -----------------------------------------------------------------------------------------

    /**
     * An equation is held by the same selection every other kind is — AD7's first row.
     *
     * Pinned because `isEquationOnly` is what decides whether the toolkit shows the equation's half,
     * and the four `is…Only` flags are the kind of code where adding a kind quietly makes an existing
     * flag lie.
     */
    @Test
    fun aSelectionHoldingOneEquationIsEquationOnly() {
        val held = CanvasSelection.ofEquation(
            Outline.Equation(id = "eq", x = 10f, y = 20f, width = 40f, height = 30f, latex = "x"),
        )

        assertTrue(held.isEquationOnly)
        assertTrue(held.holdsEquation("eq"))
        assertEquals(InkBounds(10f, 20f, 50f, 50f), held.bounds)
        assertTrue("an equation is not a shape", !held.isShapeOnly)
        assertTrue("an equation is not ink", !held.isInkOnly)
        assertTrue("an equation is not a table", !held.isTableOnly)
    }

    /** A selection that lost its equation loses its handles rather than floating over nothing. */
    @Test
    fun aSelectionReconcilesAwayWhenItsEquationIsGone() {
        val equation =
            Outline.Equation(id = "eq", x = 10f, y = 20f, width = 40f, height = 30f, latex = "x")
        val held = CanvasSelection.ofEquation(equation)

        assertNotNull(held.reconcile(emptyList(), emptyList(), emptyList(), listOf(equation)))
        assertNull(held.reconcile(emptyList(), emptyList(), emptyList(), emptyList()))
    }
}
