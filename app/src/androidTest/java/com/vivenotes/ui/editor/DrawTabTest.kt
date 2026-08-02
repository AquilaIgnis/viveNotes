package com.vivenotes.ui.editor

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
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
import com.vivenotes.data.DrawTool
import com.vivenotes.data.EraserMode
import com.vivenotes.data.EraserSettings
import com.vivenotes.data.PenKind
import com.vivenotes.data.PenPreset
import com.vivenotes.ui.panel.PenPanelContent
import com.vivenotes.ui.panel.PenPanelTags
import com.vivenotes.ui.panel.PanelTags
import com.vivenotes.ui.panel.EraserPanelContent
import com.vivenotes.ui.panel.EraserPanelTags
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
    private var fingerDrawing: Boolean? = null
    private var undoCount = 0
    private var redoCount = 0

    private fun setTab(
        tool: DrawTool = DrawTool.None,
        pens: List<PenPreset> = List(PenPreset.COUNT) { PenPreset.starting(it) },
        eraser: EraserSettings = EraserSettings(),
        allowFinger: Boolean = false,
        canUndo: Boolean = false,
        canRedo: Boolean = false,
    ) {
        compose.setContent {
            ViveNotesTheme {
                DrawTab(
                    pens = pens,
                    eraser = eraser,
                    tool = tool,
                    allowFinger = allowFinger,
                    actions = DrawActions(
                        selectTool = { selected = it },
                        updatePen = { index, pen ->
                            changedIndex = index
                            changed = pen
                        },
                        updateEraser = { changedEraser = it },
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

    private fun setPanel(pen: PenPreset = PenPreset.starting(0)) {
        val current = mutableStateOf(pen)
        compose.setContent {
            ViveNotesTheme {
                Column {
                    PenPanelContent(pen = current.value) {
                        current.value = it
                        changed = it
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

    // --- the pen pane --------------------------------------------------------------------------

    @Test
    fun eachToggleWritesItsOwnField() {
        setPanel()
        compose.onNodeWithTag(PanelTags.field("Circle to lasso")).performClick()
        assertEquals(true, changed?.circleToLasso)
        // Nothing else moved: the copy() went to the field the row is named after.
        assertEquals(PenPreset.starting(0).holdToDrawShape, changed?.holdToDrawShape)
        assertEquals(PenPreset.starting(0).scribbleToErase, changed?.scribbleToErase)
    }

    @Test
    fun pickingAColorChangesOnlyTheColor() {
        val pen = PenPreset.starting(0)
        setPanel(pen)
        val green = 0xFF00C853.toInt()
        compose.onNodeWithTag(PenPanelTags.color(green)).performClick()
        assertEquals(pen.copy(colorArgb = green), changed)
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
