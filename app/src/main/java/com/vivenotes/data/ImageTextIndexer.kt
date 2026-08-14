package com.vivenotes.data

import com.vivenotes.ai.ImageTextResult
import com.vivenotes.ai.InkRecognitionEngine
import com.vivenotes.data.db.AttachmentTextEntity
import com.vivenotes.data.db.ImageTextStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** What the panel says about reading pictures. */
data class ImageTextProgress(
    val enabled: Boolean = true,
    val running: Boolean = false,
    /** Pictures still to read in the notebook that was last searched. */
    val pending: Int = 0,
    /** Pictures read so far in the pass that is running, or the one that just finished. */
    val done: Int = 0,
    val failed: Int = 0,
)

/**
 * Reads the pictures of a notebook with PP-OCRv5, in the background — `memory/imageOcrPlan.md`
 * IO6, IO7, IO9.
 *
 * **Nothing here is on the keystroke path.** A search runs against whatever has already been read
 * and returns at once; this is handed the notebook's picture ids afterwards, reads the ones with no
 * current row, and bumps [version] as it writes. The search flow re-runs on that, so results grow
 * into an open panel instead of needing a retype.
 *
 * **The concurrency is deliberately lopsided.** Inference is serialized inside
 * `OnnxInkRecognitionEngine` — one mutex, one tensor at a time, because ONNX Runtime is already
 * given four intra-op threads and a second concurrent session would only multiply resident model
 * memory to compete for the same cores. What runs in parallel is everything around it: decoding a
 * picture off disk, normalizing it, and labelling the probability map's components, which on the
 * desktop study cost as much as inference did. So [IMAGE_WORKERS] pictures are in flight and one is
 * ever in the graph, and picture N+1 is being decoded while picture N is being read.
 */
class ImageTextIndexer(
    private val repository: NotesRepository,
    private val attachments: AttachmentStore,
    private val engine: InkRecognitionEngine,
    private val clock: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val gate = Semaphore(IMAGE_WORKERS)

    private val _version = MutableStateFlow(0L)

    /** Bumped whenever a picture's text is written. A search collector re-runs on it. */
    val version: StateFlow<Long> = _version.asStateFlow()

    private val _progress = MutableStateFlow(ImageTextProgress())
    val progress: StateFlow<ImageTextProgress> = _progress.asStateFlow()

    private var pass: Job? = null

    /**
     * Reads whichever of [attachmentIds] has no current row.
     *
     * Cheap to call on every query, which is how it is called: the common case is that everything
     * has been read and this does one indexed lookup and stops. A pass already running for the same
     * work is left alone rather than restarted, so holding a key down does not thrash the models.
     *
     * Synchronized on the guard rather than trusting the caller's thread: the search flow is the only
     * caller today and is single-threaded, and "a picture read twice because two callers appeared" is
     * not a failure worth leaving to that staying true.
     */
    @Synchronized
    fun request(attachmentIds: Set<String>) {
        if (attachmentIds.isEmpty() || pass?.isActive == true) return
        pass = scope.launch {
            val enabled = readEnabled()
            if (!enabled) {
                _progress.value = ImageTextProgress(enabled = false)
                return@launch
            }
            // Must find nothing. See `ImageTextDao.deleteOrphans` for why it runs anyway.
            repository.deleteOrphanImageText()

            val known = repository.imageTextFor(attachmentIds)
            val unread = attachmentIds.filter { known[it]?.engine != ENGINE }
            if (unread.isEmpty()) {
                _progress.value = ImageTextProgress(enabled = true, pending = 0)
                return@launch
            }

            _progress.value = ImageTextProgress(enabled = true, running = true, pending = unread.size)
            val done = AtomicInteger()
            val failed = AtomicInteger()
            coroutineScope {
                unread.map { id ->
                    async {
                        gate.withPermit {
                            ensureActive()
                            val outcome = read(id)
                            // Written one at a time as they finish rather than in one batch at the
                            // end: a long pass over a picture-heavy notebook should make the panel
                            // better as it goes, and a pass that is cancelled half way should keep
                            // the half it did.
                            repository.saveImageText(outcome)
                            val finished = done.incrementAndGet()
                            val broken =
                                if (outcome.status == ImageTextStatus.Failed) failed.incrementAndGet()
                                else failed.get()
                            _progress.value = ImageTextProgress(
                                enabled = true,
                                running = true,
                                pending = unread.size - finished,
                                done = finished,
                                failed = broken,
                            )
                            _version.update { it + 1 }
                        }
                    }
                }.awaitAll()
            }
            _progress.value = ImageTextProgress(
                enabled = true,
                running = false,
                pending = 0,
                done = done.get(),
                failed = failed.get(),
            )
        }
    }

    /**
     * Waits for the pass [request] started, if there is one.
     *
     * Nothing in the app calls this — the whole design is that no one waits for indexing. It exists
     * because a test that asserts what a pass *wrote* has to know when it stopped writing, and
     * polling [progress] for that is a race with the launch itself.
     */
    suspend fun awaitPass() {
        pass?.join()
    }

    /** Turns reading pictures on or off for this installation. Off cancels any pass in flight. */
    suspend fun setEnabled(enabled: Boolean) {
        repository.putLocalValue(ENABLED_KEY, enabled.toString())
        cachedEnabled = enabled
        if (!enabled) {
            pass?.cancel()
            _progress.value = ImageTextProgress(enabled = false)
        } else {
            _progress.value = _progress.value.copy(enabled = true)
        }
    }

    /**
     * Throws away every reading, so the next search reads them all again.
     *
     * The pictures are untouched — this table is derived, and clearing it can lose nothing except
     * the time it takes to rebuild.
     */
    suspend fun clear() {
        pass?.cancel()
        repository.clearImageText()
        _progress.value = _progress.value.copy(running = false, pending = 0, done = 0, failed = 0)
        _version.value = _version.value + 1
    }

    /** How many pictures currently hold a reading from the engine this build ships. */
    suspend fun readCount(): Int = repository.imageTextCount(ENGINE)

    private suspend fun read(attachmentId: String): AttachmentTextEntity {
        val began = clock()
        val bitmap = withContext(Dispatchers.IO) {
            attachments.loadBitmap(attachmentId, OCR_MAX_DIMENSION)
        }
        val result = if (bitmap == null) {
            null
        } else {
            try {
                runCatching { engine.recognizeImageText(bitmap) }.getOrNull()
            } finally {
                // A native allocation the size of the picture, and nothing downstream wants it.
                bitmap.recycle()
            }
        }
        val elapsed = clock() - began
        return when {
            // A picture that cannot be read is recorded as Failed rather than left absent, or every
            // query for the life of the notebook would queue it again.
            result == null -> row(attachmentId, ImageTextResult.Empty, ImageTextStatus.Failed, elapsed)
            result.lines.isEmpty() -> row(attachmentId, result, ImageTextStatus.Empty, elapsed)
            else -> row(attachmentId, result, ImageTextStatus.Read, elapsed)
        }
    }

    private fun row(
        attachmentId: String,
        result: ImageTextResult,
        status: ImageTextStatus,
        elapsed: Long,
    ) = AttachmentTextEntity(
        attachmentId = attachmentId,
        text = result.text,
        lineCount = result.lines.size,
        confidence = result.meanConfidence,
        engine = ENGINE,
        status = status,
        durationMs = elapsed,
        updatedAt = clock(),
    )

    private suspend fun readEnabled(): Boolean = cachedEnabled ?: run {
        val stored = repository.localValue(ENABLED_KEY)?.toBooleanStrictOrNull() ?: true
        cachedEnabled = stored
        stored
    }

    @Volatile
    private var cachedEnabled: Boolean? = null

    companion object {
        /**
         * Model plus preprocessing version, stamped on every row.
         *
         * Bumping it makes every stored reading stale and re-read, which is what turns changing the
         * detector, the thresholds or the crop into a rolling change rather than a migration.
         */
        const val ENGINE = "ppocrv5-en/1"

        /** Installation-local, and therefore in `local_metadata` rather than in a notebook. */
        const val ENABLED_KEY = "imageTextEnabled"

        /**
         * How many pictures are prepared at once.
         *
         * Two, not the core count. Only the preparation overlaps — inference is one lane — so the
         * third worker would have nothing to overlap with and would hold a third decoded bitmap
         * (up to about 10 MB at [OCR_MAX_DIMENSION]) waiting for the lock.
         */
        const val IMAGE_WORKERS = 2

        /**
         * The longest side a picture is decoded at for reading.
         *
         * Detection resizes to 960 regardless, but the *crops* are taken from this bitmap, and that
         * is where the recognizer's resolution comes from — cropping from a 960-pixel copy throws
         * away the detail that makes small type readable. Below `AttachmentStore.MAX_DIMENSION`
         * (2048) because two of these are resident at once by [IMAGE_WORKERS].
         */
        const val OCR_MAX_DIMENSION = 1600
    }
}
