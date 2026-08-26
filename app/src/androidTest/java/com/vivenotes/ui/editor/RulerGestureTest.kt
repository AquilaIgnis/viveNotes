package com.vivenotes.ui.editor

import android.graphics.Matrix
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.vivenotes.data.RulerKind
import com.vivenotes.ink.Ruler
import com.vivenotes.ui.theme.ViveNotesTheme

/**
 * The ruler's own gesture, composed alone — `memory/rulerPlan.md` RD4.
 *
 * Written because driving this through the running app proved worthless: the dial travels with the
 * ruler, so every check needed a fresh screenshot to aim at, and a tap that misses lands on the page
 * instead. A tap here is a tap, and the count is a number rather than a photograph.
 *
 * The transform is identity, so view pixels and page units are the same thing.
 */
class RulerGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private var taps = 0
    private var moved = Offset.Zero
    private var turned = 0f

    private val tag = "ruler-gesture"

    private fun setGesture(ruler: Ruler) {
        taps = 0
        moved = Offset.Zero
        turned = 0f
        compose.setContent {
            ViveNotesTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag(tag)
                        .pointerInput(Unit) {
                            detectRulerDrag(
                                rulerAt = { ruler },
                                toPage = { Matrix() },
                                onMove = { dx, dy -> moved += Offset(dx, dy) },
                                onTurn = { turned += it },
                                onTapDial = { taps++ },
                            )
                        },
                )
            }
        }
    }

    /** A ruler lying across the middle of a 1000×1000 test surface. */
    private fun ruler(angle: Float = 0f) =
        Ruler(500f, 500f, angle, RulerKind.Straight, 900f)

    /**
     * **One tap is one step.** The reason this test exists: on device a single tap on the dial
     * appeared to advance the ruler three eighths of a turn, and the arithmetic in `RulerTest` says
     * one. Only a counted tap can tell which of the two was lying.
     */
    @Test
    fun oneTapOnTheDialIsOneStep() {
        setGesture(ruler())

        compose.onNodeWithTag(tag).performTouchInput { click(Offset(500f, 500f)) }
        compose.waitForIdle()

        assertEquals("a tap on the dial should step the ruler exactly once", 1, taps)
    }

    @Test
    fun tappingTheBodyAwayFromTheDialDoesNotStepIt() {
        setGesture(ruler())

        // On the ruler, well along it from the middle: that is a place to take hold, not a control.
        compose.onNodeWithTag(tag).performTouchInput { click(Offset(800f, 500f)) }
        compose.waitForIdle()

        assertEquals(0, taps)
    }

    @Test
    fun tappingOffTheRulerDoesNothingAtAll() {
        setGesture(ruler())

        compose.onNodeWithTag(tag).performTouchInput { click(Offset(500f, 900f)) }
        compose.waitForIdle()

        assertEquals(0, taps)
        assertEquals(Offset.Zero, moved)
    }

    /** A drag that starts on the dial slides the ruler; it must not also count as a tap. */
    @Test
    fun draggingFromTheDialMovesTheRulerRatherThanSteppingIt() {
        setGesture(ruler())

        compose.onNodeWithTag(tag).performTouchInput {
            swipe(Offset(500f, 500f), Offset(700f, 520f))
        }
        compose.waitForIdle()

        assertEquals("a drag is not a tap", 0, taps)
        assertEquals("and it should have carried the ruler", 200f, moved.x, 8f)
    }

    /**
     * **Two fingers carry it as far as the hand went, and no further.**
     *
     * The reported bug, counted rather than photographed (RR6). The old detector followed one
     * nominated finger — `pressed.first()` — and that finger changes identity the moment the hand
     * adds or lifts one, so lifting the *first* of a pair handed the ruler a delta the width of the
     * hand: here, a 400px sideways jump on top of the 100px the fingers actually travelled.
     */
    @Test
    fun twoFingersCarryTheRulerByWhatTheHandMovedNotByHowFarApartItIs() {
        setGesture(ruler())

        compose.onNodeWithTag(tag).performTouchInput {
            down(0, Offset(300f, 500f))
            down(1, Offset(700f, 500f))
            updatePointerTo(0, Offset(300f, 600f))
            updatePointerTo(1, Offset(700f, 600f))
            move()
            // Lifted one at a time, which is what a hand does and what used to produce the jump.
            up(0)
            up(1)
        }
        compose.waitForIdle()

        assertEquals("the hand went nowhere sideways", 0f, moved.x, 8f)
        assertEquals("and 100 down", 100f, moved.y, 8f)
        assertEquals("two fingers are never a tap on the dial", 0, taps)
    }

    /** And the twist between them turns it, without the pair also dragging it about. */
    @Test
    fun twoFingersTwistingTurnTheRulerInPlace() {
        setGesture(ruler())

        compose.onNodeWithTag(tag).performTouchInput {
            down(0, Offset(300f, 500f))
            down(1, Offset(700f, 500f))
            // A quarter turn about the midpoint they started on, which does not move.
            updatePointerTo(0, Offset(500f, 300f))
            updatePointerTo(1, Offset(500f, 700f))
            move()
            up(0)
            up(1)
        }
        compose.waitForIdle()

        assertEquals("a quarter turn", (PI / 2).toFloat(), turned, 0.05f)
        assertTrue("and the centre stayed put", abs(moved.x) < 8f && abs(moved.y) < 8f)
    }

    @Test
    fun aFingerOnTheBodySlidesTheRuler() {
        setGesture(ruler())

        compose.onNodeWithTag(tag).performTouchInput {
            swipe(Offset(700f, 500f), Offset(700f, 620f))
        }
        compose.waitForIdle()

        assertEquals(120f, moved.y, 8f)
        assertEquals(0, taps)
    }
}
