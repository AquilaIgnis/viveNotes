package com.vivenotes.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Which pages the server has written ink into, so an open canvas can absorb it — `memory/inkSyncPlan.md` IS5.
 *
 * **Why this is not a Room `Flow`.** The obvious shape is `observeInk(pageId)` beside the
 * `observeDoc(pageId)` that page bodies already use. It does not work here: Room invalidates by
 * *table*, so every stroke the user draws would re-emit and rebuild the page — decoding seconds of
 * ink on the one path that has a 16 ms budget — and the emission carries no way to tell a write this
 * device just made from one that arrived from another. A body is one row replaced wholesale, which
 * is why the same shape fits there and not here.
 *
 * [HierarchySync.applyRemoteRow] is the single place a remote row is written, covering the pull and
 * the server-wins half of a push conflict alike, so recording there is exact: this fires when there
 * is something to absorb and never otherwise.
 *
 * **The value is a per-page count, and cumulative on purpose.** A `SharedFlow` of "the pages that
 * changed" loses rows the moment a collector is slower than a sync run — `DROP_OLDEST` drops a page
 * id, and `SUSPEND` would let a busy canvas hold up synchronisation. A `StateFlow` conflates by
 * `equals` and always delivers the *latest* value, so a collector that misses intermediate maps
 * still sees every page that has ever been touched, with a generation it can compare against what
 * its canvas already reflects.
 *
 * In memory rather than persisted: the sole consumer is a canvas that is open right now, and a
 * process that died reloads its page from Room from scratch. WorkManager's catch-up runs in this
 * same process against the same [SyncAccounts], which is what makes one instance enough.
 */
class RemoteInkSignal {

    private val _pages = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Page id to the number of times the server has written ink into it in this process. */
    val pages: StateFlow<Map<String, Long>> = _pages.asStateFlow()

    /**
     * Publishes one transaction's worth of remotely applied ink.
     *
     * Called *after* the transaction commits, never inside it: a collector that reacted to this by
     * reading the page back has to find the rows it is being told about.
     */
    fun record(pageIds: Collection<String>) {
        if (pageIds.isEmpty()) return
        _pages.update { current ->
            HashMap(current).apply { pageIds.forEach { put(it, (get(it) ?: 0L) + 1L) } }
        }
    }
}
