package com.vivenotes.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** How a tablet's chip and memory become UniMERNet-S's Download button. */
class SmallModelFitTest {
    private val eightGigabytes = 8L * 1024 * 1024 * 1024

    /** As a release build asks. */
    private fun fit(socModel: String, memory: Long = eightGigabytes, lowRam: Boolean = false): SmallModelFit? =
        smallModelFit(socModel, memory, lowRam, unlistedChip = SmallModelFit.Unsupported)

    @Test
    fun eachTierHasItsChips() {
        assertEquals(SmallModelFit.Smooth, fit("SM8650"))
        assertEquals(SmallModelFit.Good, fit("SM8250"))
        assertEquals(SmallModelFit.NotRecommended, fit("SM6375"))
        // Snapdragon 680: on no list, so refused like any other.
        assertEquals(SmallModelFit.Unsupported, fit("SM6225"))
    }

    /** The strings are as devices report them, suffixes and case included. */
    @Test
    fun suffixesAndCaseAreIgnored() {
        assertEquals(SmallModelFit.Smooth, fit("SM8650-AB"))
        assertEquals(SmallModelFit.Smooth, fit("s5e9945"))
        assertEquals(SmallModelFit.NotRecommended, fit("MT8781V/NA"))
        assertEquals(SmallModelFit.Unsupported, fit("MT8768WT"))
        assertEquals(SmallModelFit.NotRecommended, fit("MT6877"))
    }

    /** A Tensor reports a name, and the first one's name is a prefix of every later one's. */
    @Test
    fun tensorsAreMatchedWhole() {
        assertEquals(SmallModelFit.Good, fit("Tensor"))
        assertEquals(SmallModelFit.Good, fit("GS201"))
        assertEquals(SmallModelFit.Smooth, fit("Tensor G3"))
        assertEquals(SmallModelFit.Smooth, fit("Tensor G5"))
    }

    /** A release refuses every chip it does not list, a future flagship included. */
    @Test
    fun aReleaseRefusesAnUnlistedChip() {
        assertEquals(SmallModelFit.Unsupported, fit("unknown"))
        assertEquals(SmallModelFit.Unsupported, fit("SM9999"))
    }

    /** A debug build gives an unlisted chip an ordinary Download, so the emulator can test it. */
    @Test
    fun aDebugBuildOffersAnUnlistedChipTheDownload() {
        // What the emulator reports.
        assertNull(smallModelFit("ranchu", eightGigabytes, lowRamDevice = false, unlistedChip = null))
        // The memory floor still holds there.
        assertEquals(
            SmallModelFit.Unsupported,
            smallModelFit("ranchu", 3L * 1024 * 1024 * 1024, lowRamDevice = false, unlistedChip = null),
        )
    }

    /** Memory is a floor alone: below it the chip does not matter. */
    @Test
    fun tooLittleMemoryIsUnsupportedWhateverTheChip() {
        val threeGigabytes = 3L * 1024 * 1024 * 1024
        assertEquals(SmallModelFit.Unsupported, fit("SM8650", memory = threeGigabytes))
        assertEquals(SmallModelFit.Unsupported, fit("unknown", memory = threeGigabytes))
        assertEquals(SmallModelFit.Unsupported, fit("SM8650", lowRam = true))
    }

    /** A tablet sold with 4 GB reports a little under 4 GiB, and still passes. */
    @Test
    fun aFourGigabyteTabletPassesTheFloor() {
        val reportedByFourGigabytes = 3_700L * 1024 * 1024
        assertEquals(SmallModelFit.Smooth, fit("SM8650", memory = reportedByFourGigabytes))
    }
}
