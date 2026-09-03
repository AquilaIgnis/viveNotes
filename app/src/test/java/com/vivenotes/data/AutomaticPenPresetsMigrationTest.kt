package com.vivenotes.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The saved-tool half of the automatic ink repair; existing-stroke coverage lives beside it. */
class AutomaticPenPresetsMigrationTest {

    private val marker = booleanPreferencesKey("automatic_pen_presets_migrated_v1")

    @Test
    fun explicitWhiteAndBlackPresetsFromTheBrokenPickerBecomeAutomatic() = runBlocking {
        val white = brokenPreset(0, AUTOMATIC_LIGHT)
        val black = brokenPreset(1, AUTOMATIC_DARK)
        val migrated = AutomaticPenPresetsMigration.migrate(
            preferencesOf(penKey(0) to white, penKey(1) to black),
        )

        assertTrue(decode(requireNotNull(migrated[penKey(0)])).colorFollowsTheme)
        assertTrue(decode(requireNotNull(migrated[penKey(1)])).colorFollowsTheme)
        assertTrue(migrated[marker] == true)
        assertFalse(AutomaticPenPresetsMigration.shouldMigrate(migrated))
    }

    @Test
    fun customColorsAndAlreadyAutomaticPresetsAreUnchanged() = runBlocking {
        val red = PenPreset.starting(1)
        val automatic = PenPreset.starting(0).copy(colorArgb = AUTOMATIC_LIGHT)
        val migrated = AutomaticPenPresetsMigration.migrate(
            preferencesOf(
                penKey(0) to penSettingsJson.encodeToString(automatic),
                penKey(1) to penSettingsJson.encodeToString(red),
            ),
        )

        assertEquals(automatic, decode(requireNotNull(migrated[penKey(0)])))
        assertEquals(red, decode(requireNotNull(migrated[penKey(1)])))
    }

    @Test
    fun legacyPresetWithoutTheIntentFlagIsLeftForTheLegacyDecoder() = runBlocking {
        val legacy = buildJsonObject {
            put("colorArgb", AUTOMATIC_DARK)
            put("thickness", 2f)
        }.toString()
        val migrated = AutomaticPenPresetsMigration.migrate(
            preferencesOf(penKey(2) to legacy),
        )

        assertEquals(legacy, migrated[penKey(2)])
        assertTrue(migrated[marker] == true)
    }

    private fun brokenPreset(index: Int, color: Int): String = penSettingsJson.encodeToString(
        PenPreset.starting(index).copy(colorArgb = color, colorFollowsTheme = false),
    )

    private fun decode(stored: String): PenPreset = penSettingsJson.decodeFromString(stored)

    private fun penKey(index: Int) = stringPreferencesKey("pen_$index")
}
