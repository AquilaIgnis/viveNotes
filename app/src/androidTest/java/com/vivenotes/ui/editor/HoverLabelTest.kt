package com.vivenotes.ui.editor

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.vivenotes.data.DrawTool
import com.vivenotes.data.EraserSettings
import com.vivenotes.data.HighlighterSettings
import com.vivenotes.data.PEN_COLORS
import com.vivenotes.data.PenPreset
import com.vivenotes.data.ShapeSettings
import com.vivenotes.ui.theme.ViveNotesTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Ribbon buttons name themselves while a pen hovers over them.
 *
 * The events are real stylus hovers rather than `performMouseInput`: Material's own tooltip already
 * answers a mouse, and a stylus is the case it ignores.
 */
class HoverLabelTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setTab() {
        compose.setContent {
            ViveNotesTheme {
                DrawTab(
                    pens = List(PenPreset.COUNT) { PenPreset.starting(it) },
                    palette = PEN_COLORS,
                    eraser = EraserSettings(),
                    highlighter = HighlighterSettings(),
                    shape = ShapeSettings(),
                    tool = DrawTool.None,
                    actions = DrawActions(
                        selectTool = {},
                        updatePen = { _, _ -> },
                        updateEraser = {},
                        setDrawWithFinger = {},
                    ),
                )
            }
        }
        compose.mainClock.autoAdvance = false
    }

    @Test
    fun aHoveringPenNamesTheButtonUnderIt() {
        hover(MotionEvent.ACTION_HOVER_ENTER, DrawTags.LASSO)
        hover(MotionEvent.ACTION_HOVER_MOVE, DrawTags.LASSO)
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText("Lasso").assertIsDisplayed()
    }

    @Test
    fun theToolButtonsWithTheirOwnChromeAreNamedToo() {
        hover(MotionEvent.ACTION_HOVER_ENTER, DrawTags.pen(1))
        hover(MotionEvent.ACTION_HOVER_MOVE, DrawTags.pen(1))
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText("Pen 2").assertIsDisplayed()
    }

    @Test
    fun aPenCrossingTheRibbonShowsNothing() {
        hover(MotionEvent.ACTION_HOVER_ENTER, DrawTags.LASSO)
        hover(MotionEvent.ACTION_HOVER_MOVE, DrawTags.LASSO)
        compose.mainClock.advanceTimeBy(200)

        compose.onNodeWithText("Lasso").assertDoesNotExist()
    }

    @Test
    fun theLabelGoesWhenThePenLeaves() {
        hover(MotionEvent.ACTION_HOVER_ENTER, DrawTags.LASSO)
        hover(MotionEvent.ACTION_HOVER_MOVE, DrawTags.LASSO)
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Lasso").assertIsDisplayed()

        hover(MotionEvent.ACTION_HOVER_EXIT, DrawTags.LASSO)
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText("Lasso").assertDoesNotExist()
    }

    /** Sends one stylus hover event to the centre of the node tagged [tag]. */
    private fun hover(action: Int, tag: String) {
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        val at: Offset = bounds.center
        compose.runOnUiThread {
            val root: View = resumedActivityRoot()
            val properties = MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_STYLUS
            }
            val coords = MotionEvent.PointerCoords().apply {
                x = at.x
                y = at.y
            }
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(
                now, now, action, 1, arrayOf(properties), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0,
            )
            root.dispatchGenericMotionEvent(event)
            event.recycle()
        }
        // Compose defers a hover exit to a looper post, to see whether a press follows it. Let that
        // run before the clock moves, or the exit lands after the time meant to follow it.
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
    }

    private fun resumedActivityRoot(): View =
        ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .single()
            .window
            .decorView
}
