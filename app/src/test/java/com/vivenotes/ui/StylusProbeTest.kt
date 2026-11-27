package com.vivenotes.ui

import android.view.KeyEvent
import android.view.MotionEvent
import com.vivenotes.data.StylusKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pen button test's log: what it keeps, what it drops, and what the report says.
 *
 * The rule that matters is the filter. A hovering pen sends a hundred events a second; logging them
 * all would push the one button press being looked for off the list before anyone could read it.
 */
class StylusProbeTest {

    private val probe = StylusProbe().apply { start() }

    private fun hover(buttonState: Int = 0, toolType: Int = MotionEvent.TOOL_TYPE_STYLUS, deviceId: Int = 7) =
        probe.onPointer(deviceId, MotionEvent.ACTION_HOVER_MOVE, buttonState, toolType) { "sec_e-pen" }

    private fun key(keyCode: Int, down: Boolean = false, repeatCount: Int = 0) =
        probe.onKey(keyCode, down, repeatCount, scanCode = 331, device = "sec_e-pen")

    @Test
    fun nothingIsKeptWhileNotListening() {
        val idle = StylusProbe()

        idle.onKey(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, false, 0, 331, "pen")
        idle.onPointer(7, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.BUTTON_STYLUS_PRIMARY, MotionEvent.TOOL_TYPE_STYLUS) { "pen" }

        assertTrue(idle.events.value.isEmpty())
    }

    @Test
    fun aHoverStreamLogsOnceAndAButtonChangeLogsAgain() {
        repeat(50) { hover() }
        repeat(50) { hover(buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY) }
        repeat(50) { hover() }

        val buttons = probe.events.value.map { (it as ProbeEvent.Pointer).buttonState }
        assertEquals(listOf(0, MotionEvent.BUTTON_STYLUS_PRIMARY, 0), buttons)
    }

    /** The eraser end, or a pen that reports its button by changing tool, is a change too. */
    @Test
    fun aToolChangeLogs() {
        hover()
        hover(toolType = MotionEvent.TOOL_TYPE_ERASER)

        assertEquals(2, probe.events.value.size)
    }

    @Test
    fun eachDeviceIsComparedWithItself() {
        hover(deviceId = 7)
        hover(deviceId = 9)
        hover(deviceId = 7)

        assertEquals(2, probe.events.value.size)
    }

    @Test
    fun aFingerWithNoButtonsIsNotThePen() {
        probe.onPointer(3, MotionEvent.ACTION_DOWN, 0, MotionEvent.TOOL_TYPE_FINGER) { "touchscreen" }

        assertTrue(probe.events.value.isEmpty())
    }

    /** Older Samsung firmware swaps the touch stream to its own actions while the button is held. */
    @Test
    fun samsungsPenActionsLogOnceAndAreFlaggedAsPressed() {
        probe.onPointer(7, MotionEvent.ACTION_DOWN, 0, MotionEvent.TOOL_TYPE_STYLUS) { "sec_e-pen" }
        probe.onPointer(7, 211, 0, MotionEvent.TOOL_TYPE_STYLUS) { "sec_e-pen" }
        repeat(20) { probe.onPointer(7, 213, 0, MotionEvent.TOOL_TYPE_STYLUS) { "sec_e-pen" } }

        val events = probe.events.value.map { it as ProbeEvent.Pointer }
        assertEquals(listOf(MotionEvent.ACTION_DOWN, 211), events.map { it.action })
        assertFalse(events[0].pressed)
        assertTrue(events[1].pressed)
    }

    @Test
    fun buttonPressAndReleaseActionsAlwaysLog() {
        val state = MotionEvent.BUTTON_STYLUS_PRIMARY
        probe.onPointer(7, MotionEvent.ACTION_BUTTON_PRESS, state, MotionEvent.TOOL_TYPE_STYLUS) { "pen" }
        probe.onPointer(7, MotionEvent.ACTION_BUTTON_PRESS, state, MotionEvent.TOOL_TYPE_STYLUS) { "pen" }

        assertEquals(2, probe.events.value.size)
    }

    @Test
    fun keysAreReadByTheSameRuleDispatchUses() {
        key(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY)
        key(600)
        key(601)
        key(602)
        key(KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY)

        val presses = probe.events.value.map { (it as ProbeEvent.Key).press(StylusKeys()) }
        assertEquals(
            listOf(StylusPress.Single, StylusPress.Single, StylusPress.Double, StylusPress.Triple, null),
            presses,
        )
    }

    /** A key learned on this tablet is read as its row, in the list and in the report. */
    @Test
    fun aLearnedKeyIsReadAsItsRow() {
        key(612)
        val learned = StylusKeys(triple = 612)

        assertEquals(StylusPress.Triple, (probe.events.value.single() as ProbeEvent.Key).press(learned))
        assertTrue(probe.events.value.report("h", learned, ::fakeKeyName).endsWith("read as triple click"))
    }

    @Test
    fun keyRepeatsAreDropped() {
        key(KeyEvent.KEYCODE_PAGE_DOWN, down = true)
        key(KeyEvent.KEYCODE_PAGE_DOWN, down = true, repeatCount = 1)
        key(KeyEvent.KEYCODE_PAGE_DOWN, down = false)

        assertEquals(listOf(true, false), probe.events.value.map { (it as ProbeEvent.Key).down })
    }

    @Test
    fun theLogKeepsTheNewest() {
        repeat(StylusProbe.MAX_EVENTS + 3) { key(600 + it) }

        val codes = probe.events.value.map { (it as ProbeEvent.Key).keyCode }
        assertEquals((603 until 603 + StylusProbe.MAX_EVENTS).toList(), codes)
    }

    @Test
    fun stoppingForgets() {
        hover()
        probe.stop()
        probe.start()
        hover()

        assertEquals(1, probe.events.value.size)
    }

    @Test
    fun aCodeLineNamesTheKeyAndLeavesAVendorCodeAsANumber() {
        key(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, down = true)
        key(600)

        val lines = probe.events.value.map { it.codeLine(::fakeKeyName) }
        assertEquals("KEY 308 STYLUS_BUTTON_PRIMARY ↓  scan 331", lines[0])
        assertEquals("KEY 600 ↑  scan 331", lines[1])
    }

    @Test
    fun aCodeLineDecodesTheButtons() {
        hover(buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY or 0x100)

        assertEquals(
            "HOVER_MOVE stylus  buttons 0x120 STYLUS_PRIMARY",
            probe.events.value.single().codeLine(::fakeKeyName),
        )
    }

    @Test
    fun theReportHasTheHeaderThenEachEventWithItsVerdict() {
        hover()
        hover(buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY)
        key(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY)

        val report = probe.events.value.report(
            "ViveNotes 1.0 · Samsung SM-P613 · Android 14 (API 34)",
            StylusKeys(),
            ::fakeKeyName,
        )

        assertEquals(
            listOf(
                "ViveNotes 1.0 · Samsung SM-P613 · Android 14 (API 34)",
                "HOVER_MOVE stylus  buttons none  [sec_e-pen]  no button",
                "HOVER_MOVE stylus  buttons 0x20 STYLUS_PRIMARY  [sec_e-pen]  not read",
                "KEY 308 STYLUS_BUTTON_PRIMARY ↑  scan 331  [sec_e-pen]  read as single click",
            ),
            report.lines(),
        )
    }

    @Test
    fun anEmptyReportIsJustTheHeader() {
        assertEquals("header", emptyList<ProbeEvent>().report("header", StylusKeys(), ::fakeKeyName))
        assertNull(probe.events.value.firstOrNull())
    }

    /** `KeyEvent.keyCodeToString`'s behaviour: a name it knows, otherwise the number. */
    private fun fakeKeyName(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY -> "KEYCODE_STYLUS_BUTTON_PRIMARY"
        else -> keyCode.toString()
    }
}
