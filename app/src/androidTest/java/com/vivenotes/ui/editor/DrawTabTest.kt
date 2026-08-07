package com.vivenotes.ui.editor

import android.graphics.Color as AndroidColor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import com.vivenotes.data.DrawTool
import com.vivenotes.data.EraserMode
import com.vivenotes.data.EraserSettings
import com.vivenotes.data.HIGHLIGHTER_COLORS
import com.vivenotes.data.HighlighterSettings
import com.vivenotes.model.ink.LineType
import com.vivenotes.data.PEN_COLORS
import com.vivenotes.data.PenKind
import com.vivenotes.data.PenPreset
import com.vivenotes.data.RulerKind
import com.vivenotes.data.RulerSettings
import com.vivenotes.data.ShapeSettings
import com.vivenotes.data.forCanvasTheme
import com.vivenotes.data.withColorInFront
import com.vivenotes.ui.panel.PenPanelContent
import com.vivenotes.ui.panel.PenPanelTags
import com.vivenotes.ui.panel.PanelTags
import com.vivenotes.ui.panel.EraserPanelContent
import com.vivenotes.ui.panel.EraserPanelTags
import com.vivenotes.ui.panel.HighlighterPanelContent
import com.vivenotes.ui.panel.HighlighterPanelTags
import com.vivenotes.ui.panel.RulerPanelTags
import com.vivenotes.ui.theme.ViveNotesTheme

/**
 * The Draw tab's three pens are identical by design, so nothing on screen distinguishes them but
 * colour and position — which is exactly the shape of code where pen 2 opens pen 1's settings. These
 * check that a tool reaches the index it belongs to, and that the pane writes back the field it
 * names rather than a neighbouring one.
 */
class DrawTabTest {

    @get:Rule
    val compose = createComposeRule()

    private var selected: DrawTool? = null
    private var changed: PenPreset? = null
    private var changedIndex: Int? = null
    private var changedEraser: EraserSettings? = null
    private var changedHighlighter: HighlighterSettings? = null
    private var palette: MutableState<List<Int>>? = null
    private var fingerDrawing: Boolean? = null
    private var rulerToggles = 0
    private var changedRuler: RulerSettings? = null
    private var undoCount = 0
    private var redoCount = 0

    private fun setTab(
        tool: DrawTool = DrawTool.None,
        pens: List<PenPreset> = List(PenPreset.COUNT) { PenPreset.starting(it) },
        eraser: EraserSettings = EraserSettings(),
        highlighter: HighlighterSettings = HighlighterSettings(),
        allowFinger: Boolean = false,
        canUndo: Boolean = false,
        canRedo: Boolean = false,
        ruler: RulerSettings = RulerSettings(),
        rulerOut: Boolean = false,
    ) {
        compose.setContent {
            ViveNotesTheme {
                DrawTab(
                    pens = pens,
                    palette = PEN_COLORS,
                    eraser = eraser,
                    highlighter = highlighter,
                    shape = ShapeSettings(),
                    ruler = ruler,
                    rulerOut = rulerOut,
                    tool = tool,
                    allowFinger = allowFinger,
                    actions = DrawActions(
                        selectTool = { selected = it },
                        updatePen = { index, pen ->
                            changedIndex = index
                            changed = pen
                        },
                        updateEraser = { changedEraser = it },
                        updateHighlighter = { changedHighlighter = it },
                        updateRuler = { changedRuler = it },
                        toggleRuler = { rulerToggles++ },
                        setDrawWithFinger = { fingerDrawing = it },
                        undo = { undoCount++ },
                        redo = { redoCount++ },
                    ),
                    canUndo = canUndo,
                    canRedo = canRedo,
                )
            }
        }
    }

    /** The pane with a live palette behind it, so a colour added on the wheel lands in the row. */
    private fun setPanel(pen: PenPreset = PenPreset.starting(0)) {
        val current = mutableStateOf(pen)
        val row = mutableStateOf(PEN_COLORS)
        palette = row
        compose.setContent {
            ViveNotesTheme {
                Column {
                    PenPanelContent(
                        pen = current.value,
                        palette = row.value,
                        onChange = {
                            current.value = it
                            changed = it
                        },
                        onAddColor = { row.value = row.value.withColorInFront(it) },
                    )
                }
            }
        }
    }

    private fun setHighlighterPanel(settings: HighlighterSettings = HighlighterSettings()) {
        val current = mutableStateOf(settings)
        compose.setContent {
            ViveNotesTheme {
                Column {
                    HighlighterPanelContent(settings = current.value) {
                        current.value = it
                        changedHighlighter = it
                    }
                }
            }
        }
    }

    private fun setEraserPanel(eraser: EraserSettings = EraserSettings()) {
        val current = mutableStateOf(eraser)
        compose.setContent {
            ViveNotesTheme {
                Column {
                    EraserPanelContent(settings = current.value) {
                        current.value = it
                        changedEraser = it
                    }
                }
            }
        }
    }

    // --- the tray ------------------------------------------------------------------------------

    @Test
    fun tappingAPenSelectsThatPen() {
        setTab()
        compose.onNodeWithTag(DrawTags.pen(2)).performClick()
        assertEquals(DrawTool.Pen(2), selected)
        compose.onNodeWithTag(PenPanelTags.PREVIEW).assertDoesNotExist()
    }

    @Test
    fun holdingAPenOpensItsSettings() {
        setTab()
        compose.onNodeWithTag(DrawTags.pen(1)).performTouchInput { longClick() }
        compose.onNodeWithText("Pen 2").assertIsDisplayed()
        compose.onNodeWithTag(PenPanelTags.PREVIEW).assertIsDisplayed()
        // Configuring a pen picks it up: the settings about to change are the ones you would draw with.
        assertEquals(DrawTool.Pen(1), selected)
    }

    @Test
    fun tappingTheSelectedPenOpensItsSettings() {
        setTab(tool = DrawTool.Pen(0))
        compose.onNodeWithTag(DrawTags.pen(0)).performClick()
        compose.onNodeWithText("Pen 1").assertIsDisplayed()
        assertEquals("an already-selected pen did not reassert Draw mode", DrawTool.Pen(0), selected)
    }

    @Test
    fun tappingOutsideThePenSettingsDismissesThem() {
        setTab(tool = DrawTool.Pen(0))
        compose.onNodeWithTag(DrawTags.pen(0)).performClick()
        compose.onNodeWithTag(PenPanelTags.PREVIEW).assertIsDisplayed()

        tapOutsidePopup()

        compose.onNodeWithTag(PenPanelTags.PREVIEW).assertDoesNotExist()
    }

    @Test
    fun floatingSettingsWriteBackToTheirOwnPen() {
        setTab(tool = DrawTool.Pen(2))
        compose.onNodeWithTag(DrawTags.pen(2)).performClick()
        val green = 0xFF00C853.toInt()

        compose.onNodeWithTag(PenPanelTags.color(green)).performClick()

        assertEquals(2, changedIndex)
        assertEquals(green, changed?.colorArgb)
    }

    /**
     * Every other button in the tray arms something, so this one is the only way to put a tool down
     * without picking up another. It heads the row, left of the first pen.
     */
    @Test
    fun theEmptyHandPutsTheArmedToolDown() {
        setTab(tool = DrawTool.Pen(1))

        compose.onNodeWithTag(DrawTags.NONE).performClick()

        assertEquals(DrawTool.None, selected)
    }

    /** "Left of the first pen" is the whole placement, and a row is easy to reorder by accident. */
    @Test
    fun theEmptyHandSitsLeftOfTheFirstPen() {
        setTab()
        val hand = compose.onNodeWithTag(DrawTags.NONE).fetchSemanticsNode().boundsInRoot
        val pen = compose.onNodeWithTag(DrawTags.pen(0)).fetchSemanticsNode().boundsInRoot

        assertTrue("$hand is not left of $pen", hand.right <= pen.left)
    }

    /** Putting the tool down is not picking one up: it opens no settings of its own. */
    @Test
    fun theEmptyHandOpensNoSettings() {
        setTab(tool = DrawTool.None)

        compose.onNodeWithTag(DrawTags.NONE).performClick()

        assertEquals(DrawTool.None, selected)
        compose.onNodeWithTag(PenPanelTags.PREVIEW).assertDoesNotExist()
        compose.onNodeWithTag(HighlighterPanelTags.PREVIEW).assertDoesNotExist()
    }

    @Test
    fun theEraserIsPickedUp() {
        setTab()
        compose.onNodeWithTag(DrawTags.ERASER).performClick()
        assertEquals(DrawTool.Eraser, selected)
    }

    @Test
    fun theLassoIsPickedUp() {
        setTab()
        compose.onNodeWithTag(DrawTags.LASSO).performClick()
        assertEquals(DrawTool.Lasso, selected)
    }

    @Test
    fun tappingTheArmedEraserOpensItsSettings() {
        setTab(tool = DrawTool.Eraser)
        compose.onNodeWithTag(DrawTags.ERASER).performClick()

        assertEquals("an already-selected eraser did not reassert Draw mode", DrawTool.Eraser, selected)
        compose.onNodeWithText("Eraser").assertIsDisplayed()
        compose.onNodeWithText("Normal").assertIsDisplayed()
        compose.onNodeWithText("Object").assertIsDisplayed()
        compose.onNodeWithTag(EraserPanelTags.mode(EraserMode.Normal)).assertIsDisplayed()
        compose.onNodeWithTag(EraserPanelTags.mode(EraserMode.Object)).assertIsDisplayed()
        compose.onNodeWithTag(PanelTags.field("Erase mode")).assertDoesNotExist()
        compose.onNodeWithTag(PanelTags.field("Eraser size")).assertIsDisplayed()
    }

    @Test
    fun holdingTheEraserSelectsItAndOpensItsSettings() {
        setTab()
        compose.onNodeWithTag(DrawTags.ERASER).performTouchInput { longClick() }

        assertEquals(DrawTool.Eraser, selected)
        compose.onNodeWithText("Eraser").assertIsDisplayed()
    }

    @Test
    fun eraserModeCanBeChangedToWholeObject() {
        setTab(tool = DrawTool.Eraser)
        compose.onNodeWithTag(DrawTags.ERASER).performClick()
        compose.onNodeWithTag(EraserPanelTags.mode(EraserMode.Object)).performClick()

        assertEquals(EraserMode.Object, changedEraser?.mode)
    }

    @Test
    fun eraserSizeCanBeChanged() {
        setTab(tool = DrawTool.Eraser)
        compose.onNodeWithTag(DrawTags.ERASER).performClick()
        compose.onNodeWithContentDescription("Increase Size").performClick()

        assertEquals(EraserSettings.DEFAULT_SIZE + 1, changedEraser?.size)
    }

    @Test
    fun holdingTheEraserSizeSliderShowsItsSizeUntilRelease() {
        setEraserPanel()
        val slider = compose.onNodeWithTag(PanelTags.field("Eraser size"))

        slider.performTouchInput { down(center) }
        compose.onNodeWithTag(PanelTags.sizePreview("Eraser size")).assertIsDisplayed()

        slider.performTouchInput { up() }
        compose.onNodeWithTag(PanelTags.sizePreview("Eraser size")).assertDoesNotExist()
    }

    @Test
    fun theEraserSizeSliderHasNoDiscreteTicks() {
        setEraserPanel()

        val rangeInfo = compose.onNodeWithTag(PanelTags.field("Eraser size"))
            .fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo]

        assertEquals(0, rangeInfo.steps)
    }

    @Test
    fun undoAndRedoStayDisabledWhenTheirHistoryIsEmpty() {
        setTab()
        compose.onNodeWithContentDescription("Undo").assertIsDisplayed()
        compose.onNodeWithContentDescription("Redo").assertIsDisplayed()
        compose.onNodeWithContentDescription("Undo").assertHasNoClickAction()
        compose.onNodeWithContentDescription("Redo").assertHasNoClickAction()
        compose.onNodeWithText("Undo").assertDoesNotExist()
    }

    @Test
    fun enabledUndoAndRedoInvokeTheirHistoryActions() {
        setTab(canUndo = true, canRedo = true)

        compose.onNodeWithContentDescription("Undo").performClick()
        compose.onNodeWithContentDescription("Redo").performClick()

        assertEquals(1, undoCount)
        assertEquals(1, redoCount)
    }

    @Test
    fun theFingerButtonTogglesWhoMayDraw() {
        setTab(allowFinger = false)
        compose.onNodeWithTag(DrawTags.FINGER).performClick()
        assertEquals(true, fingerDrawing)
    }

    @Test
    fun theFingerButtonTurnsBackOff() {
        setTab(allowFinger = true)
        compose.onNodeWithTag(DrawTags.FINGER).performClick()
        assertEquals(false, fingerDrawing)
    }


    // --- the ruler -------------------------------------------------------------------------------

    /**
     * A ruler is not a tool — `docs/rulerPlan.md` RD1. It is something you draw *against*, so it
     * must not disarm the pen you were going to draw along it with.
     */
    @Test
    fun theRulerTogglesWithoutTakingTheToolOutOfYourHand() {
        setTab(tool = DrawTool.Pen(1))

        compose.onNodeWithTag(DrawTags.RULER).performClick()

        assertEquals(1, rulerToggles)
        assertTrue("laying down a ruler changed the armed tool", selected == null)
    }

    /** Its tap has to be able to mean *away*, or the ruler could never be put down — RD7. */
    @Test
    fun tappingTheRulerWhileItIsOutPutsItAwayRatherThanOpeningSettings() {
        setTab(rulerOut = true)

        compose.onNodeWithTag(DrawTags.RULER).performClick()

        assertEquals(1, rulerToggles)
        compose.onNodeWithTag(RulerPanelTags.kind(RulerKind.Straight)).assertDoesNotExist()
    }

    /** Which is why the settings are on the hold — the one gesture left that can carry them. */
    @Test
    fun holdingTheRulerOpensItsSettingsAndBringsItOut() {
        setTab(rulerOut = false)

        compose.onNodeWithTag(DrawTags.RULER).performTouchInput { longClick() }

        assertEquals("holding it should also lay it down", 1, rulerToggles)
        compose.onNodeWithText("Ruler").assertIsDisplayed()
        compose.onNodeWithTag(RulerPanelTags.kind(RulerKind.Straight)).assertIsDisplayed()
        compose.onNodeWithTag(RulerPanelTags.kind(RulerKind.Protractor)).assertIsDisplayed()
    }

    @Test
    fun holdingTheRulerWhileItIsOutOpensSettingsWithoutPuttingItAway() {
        setTab(rulerOut = true)

        compose.onNodeWithTag(DrawTags.RULER).performTouchInput { longClick() }

        assertEquals("the hold put an already-laid ruler away", 0, rulerToggles)
        compose.onNodeWithTag(RulerPanelTags.kind(RulerKind.Straight)).assertIsDisplayed()
    }

    @Test
    fun theSemicircleIsChosenFromThePane() {
        setTab(rulerOut = true)
        compose.onNodeWithTag(DrawTags.RULER).performTouchInput { longClick() }

        compose.onNodeWithTag(RulerPanelTags.kind(RulerKind.Protractor)).performClick()

        assertEquals(RulerKind.Protractor, changedRuler?.kind)
        assertEquals("choosing a kind must not resize it", RulerSettings().diameterDp, changedRuler?.diameterDp)
    }

    /**
     * The straightedge has no size to set — it spans the viewport — so its pane has no slider at
     * all, and the semicircle's is a diameter.
     */
    @Test
    fun onlyTheSemicircleHasASizeToSet() {
        setTab(rulerOut = true, ruler = RulerSettings(kind = RulerKind.Straight))
        compose.onNodeWithTag(DrawTags.RULER).performTouchInput { longClick() }

        compose.onNodeWithTag(PanelTags.field("Ruler size")).assertDoesNotExist()
    }

    @Test
    fun theSemicircleDiameterCanBeChangedAndStepsByMoreThanADp() {
        setTab(rulerOut = true, ruler = RulerSettings(kind = RulerKind.Protractor))
        compose.onNodeWithTag(DrawTags.RULER).performTouchInput { longClick() }

        compose.onNodeWithText("Diameter").assertIsDisplayed()
        compose.onNodeWithContentDescription("Increase Diameter").performClick()

        val size = changedRuler?.diameterDp ?: error("the pane wrote nothing back")
        assertTrue(
            "a ruler stepping by 1dp would make the button ornamental: $size",
            size >= RulerSettings.DEFAULT_DIAMETER + 10,
        )
        assertTrue(size <= RulerSettings.MAX_DIAMETER)
    }

    // --- the pen pane --------------------------------------------------------------------------

    @Test
    fun eachRemainingToggleWritesItsOwnField() {
        setPanel()
        compose.onNodeWithTag(PanelTags.field("Hold to draw shape")).performClick()
        assertEquals(false, changed?.holdToDrawShape)

        compose.onNodeWithTag(PanelTags.field("Scribble to erase")).performClick()
        assertEquals(false, changed?.scribbleToErase)
        assertEquals(false, changed?.holdToDrawShape)
    }

    @Test
    fun circleToLassoIsRemoved() {
        setPanel()
        compose.onNodeWithText("Circle to lasso").assertDoesNotExist()
        compose.onNodeWithTag(PanelTags.field("Circle to lasso")).assertDoesNotExist()
    }

    @Test
    fun lineTypesAreThreeCenteredIconsWithoutARowLabel() {
        setPanel()

        compose.onNodeWithText("Line type").assertDoesNotExist()
        LineType.entries.forEach {
            compose.onNodeWithTag(PenPanelTags.lineType(it)).assertIsDisplayed()
        }

        compose.onNodeWithTag(PenPanelTags.lineType(LineType.Dashed)).performClick()
        assertEquals(LineType.Dashed, changed?.lineType)
    }

    @Test
    fun pickingAColorChangesOnlyTheColor() {
        val pen = PenPreset.starting(0)
        setPanel(pen)
        val green = 0xFF00C853.toInt()
        compose.onNodeWithTag(PenPanelTags.color(green)).performClick()
        assertEquals(pen.copy(colorArgb = green, colorFollowsTheme = false), changed)
    }

    @Test
    fun untouchedDefaultInkContrastsWithTheCanvasTheme() {
        val automatic = PenPreset.starting(0)
        assertEquals(0xFFFFFFFF.toInt(), automatic.forCanvasTheme(isDark = true).colorArgb)
        assertEquals(0xFF000000.toInt(), automatic.forCanvasTheme(isDark = false).colorArgb)

        val explicitBlack = automatic.copy(colorFollowsTheme = false)
        assertEquals(0xFF000000.toInt(), explicitBlack.forCanvasTheme(isDark = true).colorArgb)
    }

    @Test
    fun theCalligraphyPenIsSelectable() {
        setPanel()
        compose.onNodeWithTag(PenPanelTags.kind(PenKind.Calligraphy)).performClick()
        assertEquals(PenKind.Calligraphy, changed?.kind)
    }

    /**
     * The middle pen type in `docs/references/pen-tooltip.jpeg` is crossed out, which this project
     * reads as out of scope — so it is absent rather than disabled, the way the View tab handles
     * Dock to Desktop. Only two cards exist.
     */
    @Test
    fun theCrossedOutPenTypeIsNotPresent() {
        setPanel()
        assertEquals(2, PenKind.entries.size)
        compose.onNodeWithTag(PenPanelTags.kind(PenKind.Fountain)).assertIsDisplayed()
        compose.onNodeWithTag(PenPanelTags.kind(PenKind.Calligraphy)).assertIsDisplayed()
    }

    /**
     * The fountain pen is this app's plain pen — one width, whatever the pressure — so the control
     * is absent rather than disabled. The calligraphy pen is the one that responds.
     */
    @Test
    fun theFountainPenHasNoPressureSetting() {
        setPanel(PenPreset.starting(0).copy(kind = PenKind.Fountain))
        compose.onNodeWithTag(PanelTags.field("Pressure sensitivity")).assertDoesNotExist()
    }

    @Test
    fun theCalligraphyPenHasAPressureSetting() {
        setPanel(PenPreset.starting(0).copy(kind = PenKind.Calligraphy))
        compose.onNodeWithTag(PanelTags.field("Pressure sensitivity")).assertIsDisplayed()
    }

    @Test
    fun theStrokePreviewIsShown() {
        setPanel()
        compose.onNodeWithTag(PenPanelTags.PREVIEW).assertIsDisplayed()
    }

    @Test
    fun holdingThePenThicknessSliderShowsItsSizeUntilRelease() {
        setPanel()
        val slider = compose.onNodeWithTag(PanelTags.field("Thickness"))

        slider.performTouchInput { down(center) }
        compose.onNodeWithTag(PanelTags.sizePreview("Thickness")).assertIsDisplayed()

        slider.performTouchInput { up() }
        compose.onNodeWithTag(PanelTags.sizePreview("Thickness")).assertDoesNotExist()
    }

    @Test
    fun addColorIsPlacedButNotWired() {
        setPanel()
        compose.onNodeWithContentDescription("Add color").assertIsDisplayed()
        assertTrue("nothing should have been written by merely showing the pane", changed == null)
    }

    // --- the highlighter -------------------------------------------------------------------------

    @Test
    fun theHighlighterIsATrayToolOfItsOwn() {
        setTab()
        compose.onNodeWithTag(DrawTags.HIGHLIGHTER).assertIsDisplayed()

        compose.onNodeWithTag(DrawTags.HIGHLIGHTER).performClick()

        assertEquals(DrawTool.Highlighter, selected)
        assertTrue("picking it up is not a settings change", changedHighlighter == null)
    }

    /** Same two-meanings-one-target gesture as a pen and the eraser: tap to pick up, hold to set up. */
    @Test
    fun holdingTheHighlighterOpensItsSettings() {
        setTab()
        compose.onNodeWithTag(HighlighterPanelTags.PREVIEW).assertDoesNotExist()

        compose.onNodeWithTag(DrawTags.HIGHLIGHTER).performTouchInput { longClick() }

        assertEquals(DrawTool.Highlighter, selected)
        compose.onNodeWithTag(HighlighterPanelTags.PREVIEW).assertIsDisplayed()
    }

    @Test
    fun tappingTheHighlighterAgainOpensItsSettings() {
        setTab(tool = DrawTool.Highlighter)

        compose.onNodeWithTag(DrawTags.HIGHLIGHTER).performClick()

        compose.onNodeWithTag(HighlighterPanelTags.PREVIEW).assertIsDisplayed()
    }

    @Test
    fun theHighlighterPaneChangesOnlyTheFieldItNames() {
        val settings = HighlighterSettings()
        setHighlighterPanel(settings)
        val green = HIGHLIGHTER_COLORS[1]

        compose.onNodeWithTag(HighlighterPanelTags.color(green)).performClick()

        assertEquals(settings.copy(colorArgb = green), changedHighlighter)
    }

    /**
     * A highlighter ink is translucent by definition — that is what lets the text show through it —
     * so every colour it offers has to carry alpha. An opaque one would be a marker.
     */
    @Test
    fun everyHighlighterInkIsTranslucent() {
        HIGHLIGHTER_COLORS.forEach { argb ->
            val alpha = AndroidColor.alpha(argb)
            assertTrue("$argb is opaque", alpha in 1..254)
        }
        assertTrue(
            "the default ink is not one of the offered ones",
            HighlighterSettings.DEFAULT_COLOR in HIGHLIGHTER_COLORS,
        )
    }

    /**
     * The marker wears the ink it will lay down, the way each pen wears the colour it writes in —
     * and wears it opaque, because at 40% alpha over ribbon chrome every ink in the palette is the
     * same grey. Read off the rendered button with a colour that is not the default, so a static
     * glyph cannot pass.
     */
    @Test
    fun theHighlighterWearsTheInkItWillLayDown() {
        val pink = HIGHLIGHTER_COLORS[3]
        setTab(highlighter = HighlighterSettings(colorArgb = pink))
        val want = Color(pink).copy(alpha = 1f)

        val glyph = compose.onNodeWithTag(DrawTags.HIGHLIGHTER).captureToImage().toPixelMap()
        var found = 0
        for (x in 0 until glyph.width) {
            for (y in 0 until glyph.height) {
                val pixel = glyph[x, y]
                val hit = abs(pixel.red - want.red) < 0.04f &&
                    abs(pixel.green - want.green) < 0.04f &&
                    abs(pixel.blue - want.blue) < 0.04f
                if (hit && pixel.alpha > 0.99f) found++
            }
        }

        assertTrue("the highlighter is not wearing $want", found > 20)
    }

    /** Wide by default: a band narrower than a line of text is a pen wearing the wrong icon. */
    @Test
    fun theHighlighterIsWiderThanAPen() {
        assertTrue(
            "highlighter ${HighlighterSettings.DEFAULT_THICKNESS} vs pen ${PenPreset().thickness}",
            HighlighterSettings.DEFAULT_THICKNESS > PenPreset().thickness,
        )
        assertTrue(HighlighterSettings.DEFAULT_THICKNESS in
            HighlighterSettings.MIN_THICKNESS..HighlighterSettings.MAX_THICKNESS)
    }

    // --- the colour wheel ------------------------------------------------------------------------

    /** The row starts as the shipped palette with the wheel after it — nothing displaced yet. */
    @Test
    fun theWheelEndsTheRowWithoutTakingASwatchWithIt() {
        setPanel()
        PEN_COLORS.forEach {
            compose.onNodeWithTag(PenPanelTags.color(it)).assertIsDisplayed()
        }
        compose.onNodeWithTag(PenPanelTags.CUSTOM_COLOR).assertIsDisplayed()
        assertTrue("showing the palette must not write a pen", changed == null)
    }

    /**
     * The row rolls: what you mixed becomes the first swatch, and the row stays the length that
     * fits the panel, so the oldest colour on the end falls off to make room.
     */
    @Test
    fun aMixedColorTakesTheFrontOfTheRowAndPushesTheLastOneOff() {
        setPanel(PenPreset.starting(0).copy(colorArgb = 0xFFFFFFFF.toInt()))
        compose.onNodeWithTag(PenPanelTags.CUSTOM_COLOR).performClick()
        compose.onNodeWithTag(PenPanelTags.WHEEL).performTouchInput {
            down(Offset(width - 1f, height / 2f))
            up()
        }
        compose.onNodeWithText("Done").performClick()

        val picked = requireNonNull(changed).colorArgb
        val row = requireNotNull(palette).value
        assertEquals("the mixed colour leads the row", picked, row.first())
        assertEquals("the row is the width it fits in", PEN_COLORS.size, row.size)
        assertTrue("the tail makes room", PEN_COLORS.last() !in row)

        compose.onNodeWithTag(PenPanelTags.color(picked)).assertIsDisplayed()
        compose.onNodeWithTag(PenPanelTags.color(PEN_COLORS.last())).assertDoesNotExist()
    }

    /**
     * Hunting for a colour means trying several. Charging the row a swatch per touch would spend
     * the whole palette on the near-misses, so a visit to the wheel costs exactly one — the colour
     * it was left on.
     */
    @Test
    fun clickingAroundTheWheelCostsTheRowOneSpotAndNoMore() {
        setPanel(PenPreset.starting(0).copy(colorArgb = 0xFFFFFFFF.toInt()))
        compose.onNodeWithTag(PenPanelTags.CUSTOM_COLOR).performClick()
        val wheel = compose.onNodeWithTag(PenPanelTags.WHEEL)

        listOf(0.24f to 0.30f, 0.78f to 0.36f, 0.50f to 0.84f).forEach { (x, y) ->
            wheel.performTouchInput {
                down(Offset(width * x, height * y))
                up()
            }
            assertEquals(
                "the row must not move while the wheel is still open",
                PEN_COLORS,
                requireNotNull(palette).value,
            )
        }

        compose.onNodeWithText("Done").performClick()

        val row = requireNotNull(palette).value
        assertEquals("the colour it was left on", requireNonNull(changed).colorArgb, row.first())
        assertEquals("one spot taken, not three", PEN_COLORS.dropLast(1), row.drop(1))
    }

    /** Opening the picker and thinking better of it is not a choice, so it costs the row nothing. */
    @Test
    fun leavingTheWheelUntouchedLeavesTheRowAlone() {
        setPanel()
        compose.onNodeWithTag(PenPanelTags.CUSTOM_COLOR).performClick()
        compose.onNodeWithText("Done").performClick()

        assertEquals(PEN_COLORS, requireNotNull(palette).value)
        assertTrue("and writes no pen", changed == null)
    }

    /**
     * The mixed colour belongs to its own swatch, not to the wheel. Read off the rendered button:
     * its middle is the pale centre of the disc, and must not have become the ink just chosen.
     */
    @Test
    fun theWheelDoesNotWearTheColorItMixed() {
        setPanel(PenPreset.starting(0).copy(colorArgb = 0xFFFFFFFF.toInt()))
        compose.onNodeWithTag(PenPanelTags.CUSTOM_COLOR).performClick()
        compose.onNodeWithTag(PenPanelTags.WHEEL).performTouchInput {
            down(Offset(width - 1f, height / 2f))
            up()
        }
        compose.onNodeWithText("Done").performClick()

        val button = compose.onNodeWithTag(PenPanelTags.CUSTOM_COLOR).captureToImage().toPixelMap()
        val middle = button[button.width / 2, button.height / 2]
        assertTrue(
            "the wheel is wearing the picked red: $middle",
            middle.green > 0.75f && middle.blue > 0.75f,
        )
    }

    @Test
    fun theWheelSwatchOpensThePicker() {
        setPanel()
        compose.onNodeWithTag(PenPanelTags.WHEEL).assertDoesNotExist()

        compose.onNodeWithTag(PenPanelTags.CUSTOM_COLOR).performClick()

        compose.onNodeWithTag(PenPanelTags.WHEEL).assertIsDisplayed()
        compose.onNodeWithTag(PenPanelTags.BRIGHTNESS).assertIsDisplayed()
        assertTrue("opening the picker is not yet a choice", changed == null)
    }

    /**
     * The whole point of the wheel: a colour the fixed row cannot reach. Sampling the right-hand edge
     * is hue 0 at full saturation, so the ink comes back red — and a red no swatch offers.
     */
    @Test
    fun theWheelPicksAColorThePaletteDoesNotCarry() {
        setPanel(PenPreset.starting(0).copy(colorArgb = 0xFFFFFFFF.toInt()))
        compose.onNodeWithTag(PenPanelTags.CUSTOM_COLOR).performClick()

        compose.onNodeWithTag(PenPanelTags.WHEEL).performTouchInput {
            down(Offset(width - 1f, height / 2f))
            up()
        }

        val picked = requireNonNull(changed).colorArgb
        assertEquals(255, AndroidColor.red(picked))
        assertTrue("hue 0 is red, not $picked", AndroidColor.green(picked) < 20)
        assertTrue("hue 0 is red, not $picked", AndroidColor.blue(picked) < 20)
        assertTrue("a wheel colour is an explicit one", !requireNonNull(changed).colorFollowsTheme)
        assertTrue("$picked is a palette colour, so the wheel proved nothing", picked !in PEN_COLORS)
    }

    /**
     * A drag across the wheel is hundreds of points and every commit is a DataStore write, so the
     * colour is written when the finger lifts rather than at every point it passes over.
     */
    @Test
    fun theWheelWritesThePenOnceTheGestureEnds() {
        setPanel(PenPreset.starting(0).copy(colorArgb = 0xFFFFFFFF.toInt()))
        compose.onNodeWithTag(PenPanelTags.CUSTOM_COLOR).performClick()
        val wheel = compose.onNodeWithTag(PenPanelTags.WHEEL)

        wheel.performTouchInput { down(Offset(width - 1f, height / 2f)) }
        assertTrue("mid-gesture is not a decision", changed == null)

        wheel.performTouchInput { up() }
        assertTrue("lifting off is", changed != null)
    }

    /** Without the bar the wheel could only reach fully lit colours — the dark half is most ink. */
    @Test
    fun theBrightnessBarReachesTheDarkEndOfTheWheel() {
        setPanel(PenPreset.starting(0).copy(colorArgb = 0xFFE53935.toInt()))
        compose.onNodeWithTag(PenPanelTags.CUSTOM_COLOR).performClick()

        compose.onNodeWithTag(PenPanelTags.BRIGHTNESS).performTouchInput {
            down(Offset(0f, height / 2f))
            up()
        }

        assertEquals(0xFF000000.toInt(), requireNonNull(changed).colorArgb)
    }

    @Test
    fun doneClosesThePickerAndLeavesThePenPaneOpen() {
        setPanel()
        compose.onNodeWithTag(PenPanelTags.CUSTOM_COLOR).performClick()
        compose.onNodeWithText("Done").performClick()

        compose.onNodeWithTag(PenPanelTags.WHEEL).assertDoesNotExist()
        compose.onNodeWithTag(PenPanelTags.PREVIEW).assertIsDisplayed()
    }

    private fun requireNonNull(pen: PenPreset?): PenPreset =
        requireNotNull(pen) { "the pane wrote nothing back" }

    /** Popup dismissal is window-level, so inject through Android rather than one Compose owner. */
    private fun tapOutsidePopup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val display = instrumentation.targetContext.resources.displayMetrics
        val downTime = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
            val event = MotionEvent.obtain(
                downTime,
                downTime + index,
                action,
                display.widthPixels - 2f,
                display.heightPixels / 2f,
                0,
            ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            instrumentation.uiAutomation.injectInputEvent(event, true)
            event.recycle()
        }
        compose.waitForIdle()
    }
}
