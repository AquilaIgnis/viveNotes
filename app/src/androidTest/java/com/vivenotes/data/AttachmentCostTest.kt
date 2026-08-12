package com.vivenotes.data

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Debug
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.data.db.NotesDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * What the stored image format costs, measured rather than assumed.
 *
 * Exists to settle the JPEG → WebP change with numbers on the two axes that could have made it a bad
 * trade: the time an import spends encoding, and the memory a draw spends decoding.
 *
 * The second is the one that mattered. [AttachmentStore.loadBitmap] leans entirely on
 * `setTargetSize` picking a sample size from the file's header, so that a picture shown small is
 * never fully decoded — JPEG gets that from DCT scaling, and whether WebP has an equivalent is a
 * property of Skia, not something the calling code can assert. So it is measured here, against the
 * full-size cost it is supposed to avoid.
 *
 * **Sources come from `/data/local/tmp`,** pushed before the run, because `connectedAndroidTest`
 * uninstalls the app and would take anything in its own storage with it. Skipped rather than failed
 * when they are absent, so the suite still passes on a machine that has not staged them.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentCostTest {

    private lateinit var db: NotesDatabase
    private lateinit var store: AttachmentStore
    private lateinit var file: File

    private val staging = File("/data/local/tmp")

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "attachment-cost.db")
        listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
        db = Room.databaseBuilder(context, NotesDatabase::class.java, file.absolutePath)
            .allowMainThreadQueries()
            .build()
        store = AttachmentStore(context, db)
    }

    @After
    fun tearDown() {
        db.close()
        listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    private fun source(name: String): File? = File(staging, name).takeIf { it.exists() }

    private fun decode(file: File): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }

    private fun Bitmap.encode(format: Bitmap.CompressFormat, quality: Int): ByteArray =
        ByteArrayOutputStream().use { out ->
            compress(format, quality, out)
            out.toByteArray()
        }

    private inline fun median(runs: Int, body: () -> Unit): Double {
        repeat(3) { body() }
        val samples = (0 until runs).map {
            val start = System.nanoTime()
            body()
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        return samples[samples.size / 2]
    }

    /**
     * Peak native heap reached while [body] runs, in bytes above where it started.
     *
     * Bitmaps live in the native heap, and the allocation this is looking for is transient — a full
     * decode that is immediately scaled down and freed would leave no trace in a before/after
     * reading. Hence a sampler thread rather than two measurements.
     */
    private fun peakNativeBytes(body: () -> Unit): Long {
        System.gc()
        Thread.sleep(50)
        val baseline = Debug.getNativeHeapAllocatedSize()
        val running = AtomicBoolean(true)
        val peak = AtomicLong(baseline)
        val sampler = thread(start = true, isDaemon = true) {
            while (running.get()) {
                val now = Debug.getNativeHeapAllocatedSize()
                peak.updateAndGet { previous -> maxOf(previous, now) }
                // Yields rather than sleeps: the allocation being looked for lives for a few
                // milliseconds at most, and a sleep long enough to be polite would step over it.
                Thread.yield()
            }
        }
        try {
            body()
        } finally {
            running.set(false)
            sampler.join(1_000)
        }
        return peak.get() - baseline
    }

    @Test
    fun measureEncodeCost() {
        val photos = listOfNotNull(
            source("bench-photo-a.jpg")?.let { "photo-a" to it },
            source("bench-photo-b.jpg")?.let { "photo-b" to it },
            source("bench-photo-c.jpg")?.let { "photo-c" to it },
        )
        assumeTrue("no benchmark sources in $staging", photos.isNotEmpty())

        photos.forEach { (label, file) ->
            val bitmap = decode(file)
            val jpeg = bitmap.encode(Bitmap.CompressFormat.JPEG, 88)
            val webp = bitmap.encode(Bitmap.CompressFormat.WEBP_LOSSY, 88)
            val jpegMs = median(10) { bitmap.encode(Bitmap.CompressFormat.JPEG, 88) }
            val webpMs = median(10) { bitmap.encode(Bitmap.CompressFormat.WEBP_LOSSY, 88) }

            Log.i(
                TAG,
                "encode $label ${bitmap.width}x${bitmap.height} | " +
                    "jpeg=${jpeg.size}B ${"%.1f".format(jpegMs)}ms | " +
                    "webp=${webp.size}B ${"%.1f".format(webpMs)}ms | " +
                    "size=${"%.0f".format(webp.size * 100.0 / jpeg.size)}% of jpeg, " +
                    "time=${"%.1f".format(webpMs / jpegMs)}x",
            )
            bitmap.recycle()
        }
    }

    /** The whole import path — read, downscale, re-encode, hash, write — as the user pays for it. */
    @Test
    fun measureImportPath() = runBlocking {
        val camera = source("bench-camera-4000x3000.jpg")
        assumeTrue("no camera-sized source in $staging", camera != null)

        val uri = Uri.fromFile(camera)
        // Warmed, then measured cold-ish: the file cache is hot either way, which is the honest
        // case for a picture the picker has just handed over.
        store.import(uri)
        val samples = (0 until 5).map {
            val start = System.nanoTime()
            runBlocking { store.import(uri) }
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        val imported = store.import(uri)!!
        val stored = store.fileFor(imported.id).length()

        Log.i(
            TAG,
            "import 4000x3000 -> ${imported.pixelWidth}x${imported.pixelHeight} " +
                "stored=${stored}B median=${"%.0f".format(samples[samples.size / 2])}ms " +
                "min=${"%.0f".format(samples.first())}ms max=${"%.0f".format(samples.last())}ms",
        )

        assertEquals("long side should be capped", AttachmentStore.MAX_DIMENSION, imported.pixelWidth)
    }

    /**
     * The measurement the change actually turned on: whether a WebP shown small is sampled at decode
     * or fully decoded and then scaled.
     */
    @Test
    fun measureDecodePeakMemory() {
        val photo = source("bench-photo-a.jpg")
        assumeTrue("no benchmark source in $staging", photo != null)

        val full = decode(photo!!)
        val fullBytes = full.width.toLong() * full.height * 4
        // The same pixels in both containers, staged under names loadBitmap will find. It reads
        // fileFor(id) and never the database, so the id here need not be a real hash.
        store.fileFor("bench-jpeg").writeBytes(full.encode(Bitmap.CompressFormat.JPEG, 88))
        store.fileFor("bench-webp").writeBytes(full.encode(Bitmap.CompressFormat.WEBP_LOSSY, 88))
        full.recycle()

        val target = 512
        listOf("bench-jpeg", "bench-webp").forEach { id ->
            var decoded: Bitmap? = null
            // Median of several: the native heap is shared with everything else in the process, so
            // a single reading can catch an unrelated allocation.
            val peaks = (0 until 5).map {
                // Freed *before* the baseline is taken, not inside the window: releasing the
                // previous bitmap and allocating the next one inside the same measurement cancel
                // out, and the peak comes back smaller than the bitmap it is supposed to contain.
                decoded?.recycle()
                decoded = null
                peakNativeBytes {
                    decoded = runBlocking { store.loadBitmap(id, target) }
                }
            }.sorted()
            val peak = peaks[peaks.size / 2]
            val bitmap = decoded!!
            val width = bitmap.width

            Log.i(
                TAG,
                "decode $id -> ${width}x${bitmap.height} " +
                    "peakNative=${peak / 1024}KB " +
                    "(full decode would be ${fullBytes / 1024}KB, " +
                    "target ${bitmap.allocationByteCount / 1024}KB) " +
                    "ratio=${"%.2f".format(peak.toDouble() / fullBytes)}",
            )
            bitmap.recycle()

            assertTrue("$id decoded to ${width}px, expected no more than $target", width <= target)
        }

        store.fileFor("bench-jpeg").delete()
        store.fileFor("bench-webp").delete()
    }

    /**
     * The bug the format change exists to fix: alpha has to survive the round trip.
     *
     * Builds its own source rather than reading one from [staging], because unlike the measurements
     * above this is a property worth holding on any machine — it is the whole reason the stored
     * format is WebP, and JPEG would fail it.
     */
    @Test
    fun transparencySurvivesImport() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val png = File(context.cacheDir, "alpha-source.png")
        val source = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        // Opaque in the middle, fully clear at the edges — so the assertion below is about a pixel
        // the source really did leave transparent.
        android.graphics.Canvas(source).drawCircle(
            128f,
            128f,
            80f,
            android.graphics.Paint().apply { color = android.graphics.Color.RED },
        )
        png.writeBytes(source.encode(Bitmap.CompressFormat.PNG, 100))
        source.recycle()

        val imported = store.import(Uri.fromFile(png))!!
        png.delete()
        val loaded = store.loadBitmap(imported.id, AttachmentStore.MAX_DIMENSION)!!

        assertTrue("stored bitmap reports no alpha channel", loaded.hasAlpha())
        // A corner the source left fully clear. JPEG returned it opaque black, which is the whole
        // reason this test exists.
        val corner = loaded.getPixel(2, 2)
        val cornerAlpha = corner ushr 24
        // And the centre, to prove the picture itself is still there and the file is not simply
        // transparent everywhere.
        val centre = loaded.getPixel(loaded.width / 2, loaded.height / 2)
        Log.i(
            TAG,
            "alpha round trip: corner=0x${"%08X".format(corner)} alpha=$cornerAlpha " +
                "centre=0x${"%08X".format(centre)}",
        )
        assertTrue("clear corner came back opaque (alpha=$cornerAlpha)", cornerAlpha < 16)
        assertTrue("centre should be opaque", (centre ushr 24) > 240)
        loaded.recycle()
    }

    private companion object {
        const val TAG = "AttachmentCost"
    }
}
