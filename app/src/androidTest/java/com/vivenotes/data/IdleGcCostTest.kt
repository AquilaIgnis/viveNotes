package com.vivenotes.data

import android.app.ActivityManager
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vivenotes.MainActivity
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.ai.AiModelInstallState
import com.vivenotes.ai.AiModelStore
import com.vivenotes.ai.HandwritingRecognizer
import com.vivenotes.ai.OnnxInkRecognitionEngine
import com.vivenotes.ai.inkPageLayout
import com.vivenotes.data.db.NotesDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Whether a garbage collection forced while the app sits idle makes the next heavy job cheaper.
 *
 * The idea: collect while the owner is on a menu, so the page load or recognition pass that
 * follows starts on an empty heap instead of paying for a collection part-way through. Each round
 * runs a job twice — once after an idle second, once after an idle second that began with
 * `Runtime.gc()` — in alternating order, and records what the job cost each time.
 *
 * **Before both arms the heap is filled with garbage to just under ART's next collection point.**
 * That is the worst case for the uncollected arm, so whatever the collected arm wins here is the
 * most the idea can win.
 *
 * **`-e idleGcForeground true` opens [MainActivity] for the length of the run**, and is the setting
 * that answers the question. On Android 16 an instrumentation process alone is not jank-perceptible,
 * so ART sizes its heap as for a background app, without the foreground growth multiplier: on the
 * emulator a page load ran 18 collections that way against 2.5 with the editor on screen. On screen
 * the process is the top app, and the heap carries the app's own live set.
 *
 * Opt-in, because a run takes minutes: `-e idleGc true`, plus `-e idleGcRounds N` to change the
 * number of rounds. Each sample is logged under [TAG] and written as CSV to the app's external
 * cache directory.
 */
@RunWith(AndroidJUnit4::class)
class IdleGcCostTest {

    private val arguments = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: NotesDatabase
    private lateinit var file: File
    private var engine: OnnxInkRecognitionEngine? = null

    /** Keeps the litter from being optimised away; never read. */
    @Volatile
    private var sink: Any? = null

    @Before
    fun setUp() {
        assumeTrue("opt-in: pass -e idleGc true", arguments.getString("idleGc") == "true")
        // On disk, as the app's is: the page load being measured includes SQLite's reads.
        file = File(context.cacheDir, "idle-gc-cost.db")
        listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
        db = Room.databaseBuilder(context, NotesDatabase::class.java, file.absolutePath).build()
    }

    @After
    fun tearDown() {
        if (!::db.isInitialized) return
        engine?.close()
        db.close()
        listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    @Test
    fun measureWhatAnIdleCollectionBuysTheNextJob() {
        val screen = if (arguments.getString("idleGcForeground") == "true") {
            ActivityScenario.launch(MainActivity::class.java)
        } else {
            null
        }
        try {
            runBlocking { measure(foreground = screen != null) }
        } finally {
            screen?.close()
        }
    }

    private suspend fun measure(foreground: Boolean) {
        val repository = NotesRepository(db, starterInkPage = StarterInkPageFixture.load(context))
        repository.seedIfEmpty()
        val pageId = db.query(
            "SELECT id FROM pages WHERE title = ? AND deletedAt IS NULL",
            arrayOf("Recognition Test"),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "seeded Recognition Test page is missing" }
            cursor.getString(0)
        }

        val models = AiModelStore(context, autoDownload = false)
        withTimeout(60_000) {
            models.state.first { it.handwritingText !is AiModelInstallState.Verifying }
        }
        assertEquals(AiModelInstallState.Installed, models.state.value.handwritingText)
        val engine = OnnxInkRecognitionEngine(models).also { engine = it }

        val loader = InkPageLoader(repository)
        val recognizer = HandwritingRecognizer(engine)
        val layout = inkPageLayout(emptyList())
        val strokes = loader.load(pageId).strokes
        val jobs = listOf(
            // The editor opening the page: 433 strokes decoded, 87 erases and 19 moves replayed.
            Job("open") { loader.load(pageId).strokes.size },
            // The search indexer reading the same page.
            Job("recognize") { recognizer.recognize(strokes, layout).size },
        )
        // Sessions open, code compiled, the page's rows in SQLite's cache.
        jobs.forEach { job -> repeat(WARMUP) { assertTrue(job.run() > 0) } }

        logEnvironment(foreground)
        val rounds = arguments.getString("idleGcRounds")?.toInt() ?: ROUNDS
        val samples = mutableListOf<Sample>()
        repeat(rounds) { round ->
            jobs.forEach { job ->
                val arms = if (round % 2 == 0) Arm.entries else Arm.entries.reversed()
                arms.forEach { arm -> samples += cycle(round, job, arm).also { Log.i(TAG, it.csv()) } }
            }
        }
        summarize(samples)
        val mode = if (foreground) "foreground" else "background"
        File(context.externalCacheDir, "idle-gc-cost-$mode.csv").writeText(
            (listOf(Sample.HEADER) + samples.map(Sample::csv)).joinToString("\n", postfix = "\n"),
        )
    }

    /** Litter, an idle second — collected or not — and then the job, measured. */
    private suspend fun cycle(round: Int, job: Job, arm: Arm): Sample {
        litter()
        val idleStart = SystemClock.elapsedRealtime()
        val cpuStart = Process.getElapsedCpuTime()
        var idleGcWallMs = 0.0
        var idleGcCpuMs = 0.0
        var idleGcFreed = 0L
        if (arm == Arm.Collected) {
            val usedBefore = used()
            val threadCpu = Debug.threadCpuTimeNanos()
            val start = System.nanoTime()
            Runtime.getRuntime().gc()
            idleGcWallMs = (System.nanoTime() - start) / 1e6
            idleGcCpuMs = (Debug.threadCpuTimeNanos() - threadCpu) / 1e6
            idleGcFreed = usedBefore - used()
        }
        // Both arms start the job the same time after the litter, so the collection is the only
        // difference between them.
        delay((IDLE_MS - (SystemClock.elapsedRealtime() - idleStart)).coerceAtLeast(0))

        val freeBefore = Runtime.getRuntime().freeMemory()
        val usedBefore = used()
        val before = GcStats.now()
        val start = System.nanoTime()
        check(job.run() > 0) { "${job.name} produced nothing" }
        val wallMs = (System.nanoTime() - start) / 1e6
        val during = GcStats.now() - before
        return Sample(
            round = round,
            job = job.name,
            arm = arm,
            wallMs = wallMs,
            gcs = during.count,
            gcMs = during.timeMs,
            blockingGcs = during.blockingCount,
            blockingGcMs = during.blockingTimeMs,
            waitedForGcMs = during.waitedNs / 1e6,
            allocatedMb = during.allocatedBytes / MB,
            freeBeforeMb = freeBefore / MB,
            usedBeforeMb = usedBefore / MB,
            idleGcWallMs = idleGcWallMs,
            idleGcCpuMs = idleGcCpuMs,
            idleGcFreedMb = idleGcFreed / MB,
            cycleCpuMs = Process.getElapsedCpuTime() - cpuStart,
        )
    }

    /**
     * Fills the heap with garbage up to just under ART's next collection point.
     *
     * `freeMemory()` is ART's distance to its target footprint, which is where it collects next.
     * Stopping [LITTER_MARGIN] short of it leaves the job's first allocations to cross it. Small
     * arrays of mixed sizes, like what a recomposition leaves, so none of it lands in the
     * large-object space.
     */
    private fun litter() {
        val runtime = Runtime.getRuntime()
        var allocated = 0L
        var index = 0
        while (runtime.freeMemory() > LITTER_MARGIN && allocated < LITTER_CAP) {
            val size = LITTER_SIZES[index++ % LITTER_SIZES.size]
            sink = ByteArray(size)
            allocated += size
        }
    }

    private fun used(): Long = Runtime.getRuntime().run { totalMemory() - freeMemory() }

    private fun logEnvironment(foreground: Boolean) {
        val activities = context.getSystemService(ActivityManager::class.java)
        val state = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
        // A bare System.gc() did nothing before API 35 unless runFinalization followed; whether it
        // collects on this runtime is worth one line.
        val countBefore = GcStats.now().count
        System.gc()
        val systemGcCollects = GcStats.now().count > countBefore
        Log.i(
            TAG,
            "env model=${Build.MODEL} soc=${Build.SOC_MODEL} sdk=${Build.VERSION.SDK_INT} " +
                "cores=${Runtime.getRuntime().availableProcessors()} " +
                "growthLimitMb=${Runtime.getRuntime().maxMemory() / MB} " +
                "memoryClass=${activities.memoryClass} foreground=$foreground " +
                "importance=${state.importance} " +
                "vm=${System.getProperty("java.vm.version")} systemGcCollects=$systemGcCollects",
        )
    }

    private fun summarize(samples: List<Sample>) {
        samples.groupBy { it.job }.forEach { (job, ofJob) ->
            Arm.entries.forEach { arm ->
                val runs = ofJob.filter { it.arm == arm }
                Log.i(
                    TAG,
                    "summary job=$job arm=$arm n=${runs.size} " +
                        "wallMs p50=${runs.median(Sample::wallMs).fmt()} " +
                        "p25=${runs.quantile(0.25, Sample::wallMs).fmt()} " +
                        "p75=${runs.quantile(0.75, Sample::wallMs).fmt()} " +
                        "gcs/job=${runs.map(Sample::gcs).average().fmt()} " +
                        "blockingGcs=${runs.sumOf(Sample::blockingGcs)} " +
                        "waitedForGcMs=${runs.sumOf(Sample::waitedForGcMs).fmt()} " +
                        "freeBeforeMb p50=${runs.median(Sample::freeBeforeMb).fmt()} " +
                        "idleGcWallMs p50=${runs.median(Sample::idleGcWallMs).fmt()} " +
                        "cycleCpuMs p50=${runs.median { it.cycleCpuMs.toDouble() }.fmt()}",
                )
            }
            // Paired by round, so drift in the emulator or the device's thermals cancels out.
            val differences = ofJob.groupBy { it.round }.values.mapNotNull { pair ->
                val idle = pair.firstOrNull { it.arm == Arm.Idle } ?: return@mapNotNull null
                val collected = pair.firstOrNull { it.arm == Arm.Collected } ?: return@mapNotNull null
                idle.wallMs - collected.wallMs
            }.sorted()
            Log.i(
                TAG,
                "summary job=$job idle-minus-collected wallMs p50=${differences.quantile(0.5).fmt()} " +
                    "p25=${differences.quantile(0.25).fmt()} p75=${differences.quantile(0.75).fmt()}",
            )
        }
    }

    private class Job(val name: String, val run: suspend () -> Int)

    private enum class Arm { Idle, Collected }

    private data class GcStats(
        val count: Long,
        val timeMs: Long,
        val blockingCount: Long,
        val blockingTimeMs: Long,
        val waitedNs: Long,
        val allocatedBytes: Long,
    ) {
        operator fun minus(other: GcStats) = GcStats(
            count = count - other.count,
            timeMs = timeMs - other.timeMs,
            blockingCount = blockingCount - other.blockingCount,
            blockingTimeMs = blockingTimeMs - other.blockingTimeMs,
            waitedNs = waitedNs - other.waitedNs,
            allocatedBytes = allocatedBytes - other.allocatedBytes,
        )

        companion object {
            /**
             * ART's process-wide counters. "Blocking" is any collection a thread other than the
             * heap task daemon ran or waited for — an allocation that could not proceed.
             */
            fun now() = GcStats(
                count = stat("art.gc.gc-count"),
                timeMs = stat("art.gc.gc-time"),
                blockingCount = stat("art.gc.blocking-gc-count"),
                blockingTimeMs = stat("art.gc.blocking-gc-time"),
                waitedNs = stat("art.gc.total-time-waiting-for-gc"),
                allocatedBytes = stat("art.gc.bytes-allocated"),
            )

            /** Zero where this runtime does not report [name]. */
            private fun stat(name: String): Long = Debug.getRuntimeStat(name)?.toLongOrNull() ?: 0
        }
    }

    private data class Sample(
        val round: Int,
        val job: String,
        val arm: Arm,
        val wallMs: Double,
        val gcs: Long,
        val gcMs: Long,
        val blockingGcs: Long,
        val blockingGcMs: Long,
        val waitedForGcMs: Double,
        val allocatedMb: Double,
        /** How far the heap was from ART's next collection when the job started. */
        val freeBeforeMb: Double,
        val usedBeforeMb: Double,
        val idleGcWallMs: Double,
        val idleGcCpuMs: Double,
        val idleGcFreedMb: Double,
        /** Every thread in the process, from the end of the litter to the end of the job. */
        val cycleCpuMs: Long,
    ) {
        fun csv(): String = listOf(
            round, job, arm, wallMs.fmt(), gcs, gcMs, blockingGcs, blockingGcMs,
            waitedForGcMs.fmt(), allocatedMb.fmt(), freeBeforeMb.fmt(), usedBeforeMb.fmt(),
            idleGcWallMs.fmt(), idleGcCpuMs.fmt(), idleGcFreedMb.fmt(), cycleCpuMs,
        ).joinToString(",")

        companion object {
            const val HEADER = "round,job,arm,wallMs,gcs,gcMs,blockingGcs,blockingGcMs," +
                "waitedForGcMs,allocatedMb,freeBeforeMb,usedBeforeMb,idleGcWallMs,idleGcCpuMs," +
                "idleGcFreedMb,cycleCpuMs"
        }
    }

    private companion object {
        const val TAG = "IdleGcCost"
        const val ROUNDS = 12
        const val WARMUP = 3
        const val IDLE_MS = 1_000L
        const val LITTER_MARGIN = 1L shl 20
        const val LITTER_CAP = 128L shl 20
        const val MB = 1024.0 * 1024.0
        val LITTER_SIZES = intArrayOf(48, 96, 256, 1_024, 4_096)

        fun Double.fmt(): String = "%.2f".format(this)

        fun List<Double>.quantile(q: Double): Double {
            if (isEmpty()) return Double.NaN
            val position = q * (size - 1)
            val low = position.toInt()
            val high = minOf(low + 1, size - 1)
            return this[low] + (this[high] - this[low]) * (position - low)
        }

        fun List<Sample>.quantile(q: Double, value: (Sample) -> Double): Double =
            map(value).sorted().quantile(q)

        fun List<Sample>.median(value: (Sample) -> Double): Double = quantile(0.5, value)
    }
}
