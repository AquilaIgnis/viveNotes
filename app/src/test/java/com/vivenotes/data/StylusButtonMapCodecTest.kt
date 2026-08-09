package com.vivenotes.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a stored binding survives a build that does not have the action it names —
 * `docs/stylusPlan.md` SB4.
 *
 * Not a test of [PenSettingsStore]: that needs a `Context` and is out of reach until Robolectric lands
 * (risk R10). It is a test of [penSettingsJson], the configuration the store actually decodes with, so
 * removing `coerceInputValues` or `ignoreUnknownKeys` from it fails here.
 *
 * What that configuration is worth is easiest to see in the failure it prevents: a pen with three
 * bindings, one of which this build cannot name, must lose *that one* rather than all three.
 */
class StylusButtonMapCodecTest {

    @Test
    fun aRoundTripKeepsEveryBinding() {
        val map = StylusButtonMap(
            single = StylusAction.Highlighter,
            double = StylusAction.Undo,
            triple = StylusAction.Redo,
        )

        assertEquals(map, decode(encode(map)))
    }

    /** An action added by a later build coerces to that field's default and leaves the others alone. */
    @Test
    fun anUnknownActionFallsBackToThatFieldsDefault() {
        val decoded = decode(
            """{"single":"ToggleRuler","double":"Undo","triple":"Pen3"}""",
        )

        assertEquals(StylusButtonMap().single, decoded.single)
        assertEquals(StylusAction.Undo, decoded.double)
        assertEquals(StylusAction.Pen3, decoded.triple)
    }

    /** A field added by a later build is ignored rather than fatal. */
    @Test
    fun anUnknownFieldIsIgnored() {
        val decoded = decode("""{"single":"Pen2","longPress":"Undo"}""")

        assertEquals(StylusAction.Pen2, decoded.single)
        assertEquals(StylusButtonMap().double, decoded.double)
    }

    /**
     * Defaults are written, not omitted. That is what lets a stored map be read back as an explicit
     * choice rather than as "never configured" — the same reason `colorFollowsTheme` must be encoded.
     */
    @Test
    fun everyFieldIsWrittenEvenWhenItIsTheDefault() {
        val encoded = encode(StylusButtonMap())

        assertEquals(true, encoded.contains("single"))
        assertEquals(true, encoded.contains("double"))
        assertEquals(true, encoded.contains("triple"))
    }

    private fun encode(map: StylusButtonMap) =
        penSettingsJson.encodeToString(StylusButtonMap.serializer(), map)

    private fun decode(text: String) =
        penSettingsJson.decodeFromString(StylusButtonMap.serializer(), text)
}
