package com.vivenotes.ui

import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.vivenotes.BuildConfig
import com.vivenotes.data.StylusKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What a pen actually sends, for the button test in Hardware ▸ Stylus.
 *
 * Pens disagree about how a barrel button reaches an app. A Bluetooth pen sends vendor keycodes of its
 * own; a digitizer pen on Android 14 sends `KEYCODE_STYLUS_BUTTON_*`; a digitizer pen whose system does
 * not generate those keys carries the button only as `MotionEvent.buttonState`, and older Samsung
 * firmware switches the whole touch stream to its own action codes while the button is held. Which of
 * these a given tablet does is not written down anywhere — the only way to know is to press the button
 * and look, and before this the only way to look was `adb`.
 *
 * Fed by `MainActivity`, ahead of every view, so the button is seen wherever the pen is and even when a
 * focused view would take the key for itself. It only watches: nothing here claims an event, so a bound
 * button still does its job while the test is open, which is itself part of the answer.
 *
 * Main thread only — every caller is input dispatch or composition.
 */
internal class StylusProbe {

    private val _events = MutableStateFlow<List<ProbeEvent>>(emptyList())

    /** Oldest first, at most [MAX_EVENTS]. */
    val events: StateFlow<List<ProbeEvent>> = _events.asStateFlow()

    /** Set while the button test is on screen; with it off, every event costs one check. */
    var listening: Boolean = false
        private set

    private var nextId = 0L

    /** What each device last reported, so a stream of identical hover events logs once. */
    private val lastPointer = HashMap<Int, PointerState>()

    fun start() {
        listening = true
    }

    fun stop() {
        listening = false
        clear()
    }

    fun clear() {
        _events.value = emptyList()
        lastPointer.clear()
    }

    /** Every key while listening, down and up — a pen can send any keycode at all. Repeats are dropped. */
    fun onKey(keyCode: Int, down: Boolean, repeatCount: Int, scanCode: Int, device: String) {
        if (!listening || repeatCount > 0) return
        record(ProbeEvent.Key(nextId++, keyCode, down, scanCode, device))
    }

    /**
     * A pointer event, logged only when it says something new: its buttons, its tool, or whether it
     * uses a vendor action changed since that device's last event. A button press or release action
     * is always logged, since it is one event rather than a stream.
     *
     * A finger with no buttons is skipped. It is never the pen, and tapping Copy would otherwise
     * put itself in the report.
     */
    fun onPointer(deviceId: Int, action: Int, buttonState: Int, toolType: Int, device: () -> String) {
        if (!listening) return
        val vendor = action >= SAMSUNG_PEN_DOWN
        if (toolType == MotionEvent.TOOL_TYPE_FINGER && buttonState == 0 && !vendor) return
        val state = PointerState(buttonState, toolType, vendor)
        val changed = lastPointer.put(deviceId, state) != state
        val discrete = action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE
        if (changed || discrete) {
            record(ProbeEvent.Pointer(nextId++, action, buttonState, toolType, device()))
        }
    }

    private fun record(event: ProbeEvent) {
        _events.value = (_events.value + event).takeLast(MAX_EVENTS)
    }

    private data class PointerState(val buttonState: Int, val toolType: Int, val vendor: Boolean)

    companion object {
        const val MAX_EVENTS = 8
    }
}

/** One thing the pen did, as the app received it. [id] keeps rows stable as older ones fall off. */
internal sealed interface ProbeEvent {
    val id: Long
    val device: String

    data class Key(
        override val id: Long,
        val keyCode: Int,
        val down: Boolean,
        val scanCode: Int,
        override val device: String,
    ) : ProbeEvent {
        /** Which binding this key presses, by the same rule dispatch uses; null if none. */
        fun press(learned: StylusKeys): StylusPress? = stylusPressFor(keyCode, learned)
    }

    data class Pointer(
        override val id: Long,
        val action: Int,
        val buttonState: Int,
        val toolType: Int,
        override val device: String,
    ) : ProbeEvent {
        /** Whether this carries anything a button could have caused. */
        val pressed: Boolean get() = buttonState != 0 || action >= SAMSUNG_PEN_DOWN
    }
}

/**
 * The event as codes, the way a developer reading the report wants it — not translated, since these
 * are names in the Android API rather than words.
 *
 * [keyName] is `KeyEvent.keyCodeToString` on a device; a parameter so this stays testable on the JVM.
 */
internal fun ProbeEvent.codeLine(keyName: (Int) -> String): String = when (this) {
    is ProbeEvent.Key -> buildString {
        append("KEY ").append(keyCode)
        val name = keyName(keyCode).removePrefix("KEYCODE_")
        if (name != keyCode.toString()) append(' ').append(name)
        append(if (down) " ↓" else " ↑")
        append("  scan ").append(scanCode)
    }
    is ProbeEvent.Pointer ->
        "${actionName(action)} ${toolTypeName(toolType)}  buttons ${buttonNames(buttonState)}"
}

/**
 * The plain-text report Copy puts on the clipboard: [header], then each event oldest first with what
 * the app makes of it. English throughout, because it is read by whoever fixes the pen, not by the
 * person pressing it.
 */
internal fun List<ProbeEvent>.report(
    header: String,
    learned: StylusKeys,
    keyName: (Int) -> String,
): String = buildString {
    appendLine(header)
    for (event in this@report) {
        append(event.codeLine(keyName)).append("  [").append(event.device).append("]  ")
        appendLine(
            when (event) {
                is ProbeEvent.Key -> event.press(learned)?.let { "read as ${it.name.lowercase()} click" } ?: "not read"
                is ProbeEvent.Pointer -> if (event.pressed) "not read" else "no button"
            },
        )
    }
}.trimEnd()

/** App version, device and Android version — all a report needs to say about where it came from. */
internal fun probeReportHeader(): String =
    "ViveNotes ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · " +
        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

/** From `MainActivity.dispatchKeyEvent`. Costs one check while the test is closed. */
internal fun StylusProbe.record(event: KeyEvent) {
    if (!listening) return
    onKey(
        keyCode = event.keyCode,
        down = event.action == KeyEvent.ACTION_DOWN,
        repeatCount = event.repeatCount,
        scanCode = event.scanCode,
        device = event.device?.name ?: "device ${event.deviceId}",
    )
}

/** From `MainActivity`'s touch and generic-motion dispatch — touches, hovers and button actions. */
internal fun StylusProbe.record(event: MotionEvent) {
    if (!listening || !event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return
    onPointer(
        deviceId = event.deviceId,
        action = event.actionMasked,
        buttonState = event.buttonState,
        toolType = event.getToolType(event.actionIndex),
    ) { event.device?.name ?: "device ${event.deviceId}" }
}

/**
 * Samsung's own actions for a touch made with the S Pen's button held — down, up and move — which
 * its older firmware sends in place of `ACTION_DOWN`/`UP`/`MOVE`. Not in the SDK.
 */
private const val SAMSUNG_PEN_DOWN = 211
private const val SAMSUNG_PEN_UP = 212
private const val SAMSUNG_PEN_MOVE = 213

private fun actionName(action: Int): String = when (action) {
    MotionEvent.ACTION_DOWN -> "DOWN"
    MotionEvent.ACTION_UP -> "UP"
    MotionEvent.ACTION_MOVE -> "MOVE"
    MotionEvent.ACTION_CANCEL -> "CANCEL"
    MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
    MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
    MotionEvent.ACTION_HOVER_ENTER -> "HOVER_ENTER"
    MotionEvent.ACTION_HOVER_MOVE -> "HOVER_MOVE"
    MotionEvent.ACTION_HOVER_EXIT -> "HOVER_EXIT"
    MotionEvent.ACTION_SCROLL -> "SCROLL"
    MotionEvent.ACTION_BUTTON_PRESS -> "BUTTON_PRESS"
    MotionEvent.ACTION_BUTTON_RELEASE -> "BUTTON_RELEASE"
    SAMSUNG_PEN_DOWN -> "SPEN_DOWN($action)"
    SAMSUNG_PEN_UP -> "SPEN_UP($action)"
    SAMSUNG_PEN_MOVE -> "SPEN_MOVE($action)"
    else -> "ACTION($action)"
}

private fun toolTypeName(toolType: Int): String = when (toolType) {
    MotionEvent.TOOL_TYPE_FINGER -> "finger"
    MotionEvent.TOOL_TYPE_STYLUS -> "stylus"
    MotionEvent.TOOL_TYPE_ERASER -> "eraser"
    MotionEvent.TOOL_TYPE_MOUSE -> "mouse"
    else -> "tool($toolType)"
}

private val BUTTON_NAMES = listOf(
    MotionEvent.BUTTON_PRIMARY to "PRIMARY",
    MotionEvent.BUTTON_SECONDARY to "SECONDARY",
    MotionEvent.BUTTON_TERTIARY to "TERTIARY",
    MotionEvent.BUTTON_BACK to "BACK",
    MotionEvent.BUTTON_FORWARD to "FORWARD",
    MotionEvent.BUTTON_STYLUS_PRIMARY to "STYLUS_PRIMARY",
    MotionEvent.BUTTON_STYLUS_SECONDARY to "STYLUS_SECONDARY",
)

/** `0x20 STYLUS_PRIMARY`, with any bit Android has no name for left in the hex. */
private fun buttonNames(buttonState: Int): String {
    if (buttonState == 0) return "none"
    val names = BUTTON_NAMES.filter { (bit, _) -> buttonState and bit != 0 }.map { it.second }
    return "0x%x".format(buttonState) + names.joinToString(separator = "") { " $it" }
}
