package com.vivenotes.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Which pages the server has written ink into, so an open canvas can absorb it.
 *
 * Not a Room `Flow`. The obvious shape is `observeInk(pageId)` beside the `observeDoc(pageId)` page
 * bodies use, but Room invalidates by table: every stroke the user draws would re-emit and rebuild
 * the page — decoding seconds of ink on the one path with a 16 ms budget — and the emission carries
 * no way to tell a local write from a remote one. A body is one row replaced wholesale, which is
 * why the same shape fits there and not here.
 *
 * [HierarchySync.applyRemoteRow] is the single place a remote row is written, covering the pull and
 * the server-wins half of a push conflict alike, so recording there is exact.
 *
 * The value is a per-page count, cumulative on purpose. A `SharedFlow` of "the pages that changed"
 * loses rows the moment a collector is slower than a sync run. A `StateFlow` conflates by `equals`
 * and always delivers the latest value, so a collector that misses intermediate maps still sees
 * every page that has been touched, with a generation it can compare against what it already shows.
 *
 * In memory rather than persisted: the sole consumer is a canvas that is open right now, and a
 * process that died reloads its page from Room anyway.
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
