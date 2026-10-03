package com.vivenotes.data

import com.vivenotes.ai.HandwritingRecognizer
import com.vivenotes.ai.InkPageLayout
import com.vivenotes.ai.InkRecognitionEngine
import com.vivenotes.ai.InkTextRegionsCodec
import com.vivenotes.data.db.InkTextEntity
import com.vivenotes.data.db.InkTextStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class InkTextPageRequest(
    val pageId: String,
    val layout: InkPageLayout,
)

data class InkTextProgress(
    val enabled: Boolean = true,
    val running: Boolean = false,
    val pending: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
)

/**
 * Lazily reads replayed page ink for fuzzy search.
 *
 * [PAGE_WORKERS] pages are in flight at once, and each page's regions are read side by side by
 * [HandwritingRecognizer], so one page's ink is being replayed from the database while another's
 * regions are in the graph. How many model calls actually run at once is the engine's to bound.
 *
 * Requests merge while a pass is running, which avoids the last-page race a simple "already
 * active" guard creates. [NotesRepository.saveInkText] checks the page's monotonic ink generation
 * in the save transaction, so an edit made during OCR cannot resurrect the stale row it deleted.
 */
class InkTextIndexer(
    private val repository: NotesRepository,
    engine: InkRecognitionEngine,
    private val clock: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val loader = InkPageLoader(repository, dispatcher)
    private val recognizer = HandwritingRecognizer(engine)
    private val pageGate = Semaphore(PAGE_WORKERS)
    private val guard = Any()
    private val pending = linkedMapOf<String, InkTextPageRequest>()
    private var pass: Job? = null

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private val _progress = MutableStateFlow(InkTextProgress())
    val progress: StateFlow<InkTextProgress> = _progress.asStateFlow()

    fun request(requests: Collection<InkTextPageRequest>) {
        if (requests.isEmpty()) return
        synchronized(guard) {
            requests.forEach { pending[it.pageId] = it }
            if (pass?.isActive != true) startPassLocked()
        }
    }

    private fun startPassLocked() {
        pass = scope.launch {
            try {
                drain()
            } finally {
                synchronized(guard) {
                    pass = null
                    if (pending.isNotEmpty()) startPassLocked()
                }
            }
        }
    }

    private suspend fun drain() {
        if (!readEnabled()) {
            synchronized(guard) { pending.clear() }
            _progress.value = InkTextProgress(enabled = false)
            return
        }

        var done = 0
        var failed = 0
        while (true) {
            val batch = synchronized(guard) {
                pending.values.toList().also { pending.clear() }
            }
            if (batch.isEmpty()) break
            val known = repository.inkTextFor(batch.map(InkTextPageRequest::pageId))
            val unread = batch.filter { request ->
                val row = known[request.pageId]
                row == null || row.engine != ENGINE || row.layoutHash != request.layout.hash
            }
            if (unread.isEmpty()) continue

            var remaining = unread.size
            _progress.value = InkTextProgress(
                enabled = true,
                running = true,
                pending = remaining,
                done = done,
                failed = failed,
            )
            coroutineScope {
                unread.forEach { request ->
                    launch {
                        pageGate.withPermit {
                            ensureActive()
                            val outcome = read(request)
                            val saved = repository.saveInkText(outcome.row, outcome.generation)
                            // Counted and published under the guard: pages finish in any order, and
                            // a count read outside it could be overtaken by an older one.
                            synchronized(guard) {
                                if (saved) {
                                    done++
                                    if (outcome.row.status == InkTextStatus.Failed) failed++
                                }
                                remaining--
                                _progress.value = InkTextProgress(
                                    enabled = true,
                                    running = true,
                                    pending = remaining + pending.size,
                                    done = done,
                                    failed = failed,
                                )
                            }
                            // A discarded stale pass also bumps: when a page had no previous row,
                            // its edit had nothing for Room to invalidate, and this is what asks
                            // search to queue it again.
                            _version.update { it + 1 }
                        }
                    }
                }
            }
        }
        _progress.value = InkTextProgress(
            enabled = true,
            running = false,
            pending = 0,
            done = done,
            failed = failed,
        )
    }

    suspend fun awaitPass() {
        while (true) {
            val active = synchronized(guard) { pass } ?: return
            active.join()
            if (synchronized(guard) { pass == null }) return
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        repository.putLocalValue(ENABLED_KEY, enabled.toString())
        cachedEnabled = enabled
        if (!enabled) {
            synchronized(guard) {
                pending.clear()
                pass?.cancel()
            }
            _progress.value = InkTextProgress(enabled = false)
        } else {
            _progress.value = _progress.value.copy(enabled = true)
            _version.update { it + 1 }
        }
    }

    suspend fun clear() {
        synchronized(guard) {
            pending.clear()
            pass?.cancel()
        }
        repository.clearInkText()
        _progress.value = _progress.value.copy(running = false, pending = 0, done = 0, failed = 0)
        _version.update { it + 1 }
    }

    suspend fun readCount(): Int = repository.inkTextCount(ENGINE)

    private suspend fun read(request: InkTextPageRequest): Outcome {
        val generation = repository.inkTextGeneration(request.pageId)
        val began = clock()
        return try {
            val strokes = loader.load(request.pageId).strokes
            val regions = if (strokes.isEmpty()) {
                emptyList()
            } else {
                recognizer.recognize(strokes, request.layout)
            }
            val status = if (regions.isEmpty()) InkTextStatus.Empty else InkTextStatus.Read
            Outcome(
                row = InkTextEntity(
                    pageId = request.pageId,
                    regionsJson = InkTextRegionsCodec.encode(regions),
                    regionCount = regions.size,
                    confidence = regions.map { it.confidence }.average().takeUnless(Double::isNaN)
                        ?.toFloat() ?: 0f,
                    layoutHash = request.layout.hash,
                    engine = ENGINE,
                    status = status,
                    durationMs = clock() - began,
                    updatedAt = clock(),
                ),
                generation = generation,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Outcome(
                row = InkTextEntity(
                    pageId = request.pageId,
                    regionsJson = "[]",
                    regionCount = 0,
                    confidence = 0f,
                    layoutHash = request.layout.hash,
                    engine = ENGINE,
                    status = InkTextStatus.Failed,
                    durationMs = clock() - began,
                    updatedAt = clock(),
                ),
                generation = generation,
            )
        }
    }

    private suspend fun readEnabled(): Boolean = cachedEnabled ?: run {
        val stored = repository.localValue(ENABLED_KEY)?.toBooleanStrictOrNull() ?: true
        cachedEnabled = stored
        stored
    }

    @Volatile
    private var cachedEnabled: Boolean? = null

    private data class Outcome(val row: InkTextEntity, val generation: Long)

    companion object {
        const val ENGINE = "ppocrv5-en-ink/1"
        const val ENABLED_KEY = "inkTextEnabled"

        /**
         * Pages read at once. Two, so one page's replay overlaps the other's inference; a page's
         * own regions already fill the engine, and a third page would only hold its strokes waiting.
         */
        const val PAGE_WORKERS = 2
    }
}
