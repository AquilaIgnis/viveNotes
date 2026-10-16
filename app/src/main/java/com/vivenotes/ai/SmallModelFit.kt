package com.vivenotes.ai

/**
 * How well this tablet would run UniMERNet-S, which decides what its card offers.
 *
 * - [Smooth]: a launch on unmetered Wi-Fi fetches it unasked, and it takes over the Math button.
 * - [Good]: an ordinary Download button.
 * - [NotRecommended]: Download in the warning colour, with a line saying it may be slow.
 * - [Unsupported]: Download disabled, with a line saying the tablet is not up to it.
 *
 * Decided from the chip Android names in `Build.SOC_MODEL` — [CHIP_FITS] — and the device's memory.
 */
enum class SmallModelFit { Smooth, Good, NotRecommended, Unsupported }

/**
 * The fit for a chip and the device's memory.
 *
 * A chip [CHIP_FITS] does not list gets [unlistedChip]: [SmallModelFit.Unsupported] in a release, so
 * a new flagship waits for its entry, and null — an ordinary Download — in a debug build, so the
 * emulator, which reports its board `ranchu` as the chip, can still fetch the model to test it.
 *
 * Memory is a floor alone: below it nothing is fast enough, whatever the chip.
 */
internal fun smallModelFit(
    socModel: String,
    totalMemoryBytes: Long,
    lowRamDevice: Boolean,
    unlistedChip: SmallModelFit?,
): SmallModelFit? {
    if (lowRamDevice || totalMemoryBytes < SMALL_MODEL_MIN_MEMORY_BYTES) return SmallModelFit.Unsupported
    return CHIP_FITS[chipCode(socModel)] ?: unlistedChip
}

/**
 * The part code in what Android reports for a chip, upper-cased: `SM8650` from `SM8650-AB`,
 * `MT8781` from `MT8781V/NA`, `S5E9945` from `s5e9945`. A Tensor reports a name instead —
 * `Tensor`, `Tensor G3` — or `GS201`, so anything without a part code is kept whole, which also
 * keeps `TENSOR` from matching `TENSOR G3`.
 */
internal fun chipCode(socModel: String): String {
    val model = socModel.trim().uppercase()
    return PART_CODE.find(model)?.value ?: model
}

private val PART_CODE = Regex("SM\\d{4}|MT\\d{4}|S5E\\d{4}|GS\\d{3}")

/**
 * Every chip whose tier is known, by [chipCode].
 *
 * Tiered by the CPU, because that is where the formula models run: ONNX Runtime on four threads, no
 * GPU or NPU. Four threads land on the four fastest cores, so what matters is how many big cores a
 * chip has and how new they are, not its name or its GPU:
 *
 * - [SmallModelFit.Smooth]: 2023 and newer flagships.
 * - [SmallModelFit.Good]: older flagships, and four big cores of Cortex-A78 class or newer at
 *   2.4 GHz and up.
 * - [SmallModelFit.NotRecommended]: two big cores of Cortex-A76 class or newer, or four slower ones.
 *
 * Anything else is [SmallModelFit.Unsupported] — big cores no newer than Cortex-A75, or none, and
 * every chip not listed here.
 *
 * UniMERNet-S costs about 1.7× UniMERNet-T's time per formula (`simulations/formula-models`). The
 * tiers are by core configuration and have not been timed chip by chip;
 * `OnnxRecognitionSmokeTest.uniMerNetTimingsAreLoggedWithTheChip` logs a device's chip beside its
 * timings, which is how to check one.
 *
 * A chip missing here is refused in a release, so a new flagship needs an entry before it can
 * download the model. The part codes are what devices report in `ro.soc.model`, checked against
 * device trees and Google's LiteRT samples' SoC table.
 */
private val CHIP_FITS: Map<String, SmallModelFit> = buildMap {
    fun tier(fit: SmallModelFit, vararg codes: String) = codes.forEach { put(it, fit) }

    tier(
        SmallModelFit.Smooth,
        "SM8550", // Snapdragon 8 Gen 2
        "SM8635", // Snapdragon 8s Gen 3
        "SM8650", // Snapdragon 8 Gen 3
        "SM8735", // Snapdragon 8s Gen 4
        "SM8750", // Snapdragon 8 Elite
        "SM8850", // Snapdragon 8 Elite Gen 5
        "MT6985", // Dimensity 9200
        "MT6989", // Dimensity 9300
        "MT6991", // Dimensity 9400
        "MT6993", // Dimensity 9500
        "S5E9945", // Exynos 2400
        "S5E9955", // Exynos 2500
        "TENSOR G3",
        "TENSOR G4",
        "TENSOR G5",
    )
    tier(
        SmallModelFit.Good,
        "SM8150", // Snapdragon 855 and 860
        "SM8250", // Snapdragon 865 and 870
        "SM8350", // Snapdragon 888
        "SM8450", // Snapdragon 8 Gen 1
        "SM8475", // Snapdragon 8+ Gen 1
        "SM7325", // Snapdragon 778G and 782G
        "SM7435", // Snapdragon 7s Gen 2
        "SM7450", // Snapdragon 7 Gen 1
        "SM7475", // Snapdragon 7+ Gen 2
        "SM7550", // Snapdragon 7 Gen 3
        "SM7675", // Snapdragon 7+ Gen 3
        "MT6983", // Dimensity 9000
        "MT6891", // Dimensity 1100
        "MT6893", // Dimensity 1200, 1300 and 8050
        "MT6895", // Dimensity 8000 and 8100
        "MT6896", // Dimensity 8200
        "MT6897", // Dimensity 8300
        "MT6899", // Dimensity 8400
        "MT6878", // Dimensity 7300
        "MT8797", // Kompanio 1300T
        "S5E9925", // Exynos 2200
        "S5E8835", // Exynos 1380
        "S5E8845", // Exynos 1480
        "S5E8855", // Exynos 1580
        "TENSOR", // Tensor G1
        "GS101", // Tensor G1, by part
        "TENSOR G2",
        "GS201", // Tensor G2, by part
    )
    tier(
        SmallModelFit.NotRecommended,
        "SM6450", // Snapdragon 6 Gen 1
        "SM7125", // Snapdragon 720G
        "SM7150", // Snapdragon 730 and 730G
        "SM7225", // Snapdragon 750G
        "SM7250", // Snapdragon 765G
        "SM6375", // Snapdragon 695
        "SM4350", // Snapdragon 480
        "SM4375", // Snapdragon 4 Gen 1
        "SM4450", // Snapdragon 4 Gen 2
        "MT6877", // Dimensity 900, 920, 1080 and 7050
        "MT6886", // Dimensity 7200
        "MT6853", // Dimensity 720 and 800U
        "MT6833", // Dimensity 700, 810 and 6020
        "MT6789", // Helio G99
        "MT8781", // Helio G99, tablet part
        "MT6785", // Helio G90 and G95
        "MT8186", // Kompanio 520
        "S5E8825", // Exynos 1280
        "S5E8535", // Exynos 1330
    )
}

/**
 * UniMERNet-S peaks at about 440 MB loaded and running, against UniMERNet-T's 270 MB, measured on a
 * desktop. A tablet sold with 4 GB reports somewhat less than that to `ActivityManager`, so the
 * floor sits under it: 4 GB passes and 3 GB does not.
 */
private const val SMALL_MODEL_MIN_MEMORY_BYTES = 3_584L * 1024 * 1024
