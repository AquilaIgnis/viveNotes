package com.vivenotes.data

import com.vivenotes.data.db.InkEraseWithTargets
import com.vivenotes.data.db.InkMoveWithTargets
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.ink.InkCodec
import com.vivenotes.ink.InkPoint
import com.vivenotes.ink.PageStroke
import com.vivenotes.ink.eraseObjects
import com.vivenotes.ink.replayMove
import com.vivenotes.ink.replayResize
import com.vivenotes.ink.subtract
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private sealed interface StoredInkOperation {
    val createdAt: Long
    val id: String

    data class Erase(val stored: InkEraseWithTargets) : StoredInkOperation {
        override val createdAt: Long get() = stored.erase.createdAt
        override val id: String get() = stored.erase.id
    }

    data class Move(val stored: InkMoveWithTargets) : StoredInkOperation {
        override val createdAt: Long get() = stored.move.createdAt
        override val id: String get() = stored.move.id
    }
}

data class LoadedInkPage(
    val strokes: List<PageStroke>,
    val latestOperationAt: Long,
    /**
     * Rows the eraser has taken the last piece of: they decoded, replay left them with no geometry,
     * and nothing on the page draws them any more.
     *
     * Reported rather than acted on, because a loader is not the place to write. The caller
     * ([com.vivenotes.ui.NotesViewModel.loadInk]) tombstones them so the seven-day purge collects
     * them — see `NotesRepository.collectErasedAwayStrokes` for why that is safe and why it is not
     * told to the server.
     *
     * **Only rows that decoded.** A row this build cannot read produces no projection either, and
     * counting that as erased-away would have the garbage collector delete ink over a codec it does
     * not happen to know.
     */
    val erasedAway: List<String> = emptyList(),
)

/**
 * The single exact decoder/replay path for both the visible canvas and handwriting indexing.
 *
 * Keeping this outside the ViewModel prevents the indexer from growing an approximate version of
 * normal/object erases or lasso transforms. Partial publication is retained for the canvas; a
 * background caller simply omits [onPartial].
 */
class InkPageLoader(
    private val repository: NotesRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun load(
        pageId: String,
        onPartial: (List<PageStroke>) -> Unit = {},
    ): LoadedInkPage = withContext(dispatcher) {
        val rows = repository.inkFor(pageId)
        val operations = buildList {
            repository.partialErasesFor(pageId).forEach { add(StoredInkOperation.Erase(it)) }
            repository.inkMovesFor(pageId).forEach { add(StoredInkOperation.Move(it)) }
        }.sortedWith(compareBy(StoredInkOperation::createdAt, StoredInkOperation::id))
        val streaming = operations.none { it is StoredInkOperation.Move }
        val shown = ArrayList<PageStroke>(rows.size)

        val decoded = decodeConcurrently(rows) { chunk ->
            if (streaming) {
                shown += replay(chunk, operations)
                onPartial(ArrayList(shown))
            }
        }
        val live = if (streaming) shown else replay(decoded, operations)
        val drawn = live.mapTo(HashSet(live.size)) { it.id }
        LoadedInkPage(
            strokes = live,
            // Not `operations.maxOf { it.createdAt }`: the operations here are the *active* ones,
            // and the clock has to clear the tombstoned ones too. See
            // [NotesRepository.latestInkOperationAt].
            latestOperationAt = repository.latestInkOperationAt(pageId),
            // Measured against what decoded rather than against the rows, so an unreadable row is
            // never mistaken for one the eraser finished off — see [LoadedInkPage.erasedAway].
            erasedAway = decoded.mapNotNull { it.id.takeIf { id -> id !in drawn } }.distinct(),
        )
    }

    private fun replay(
        strokes: List<PageStroke>,
        operations: List<StoredInkOperation>,
    ): List<PageStroke> = operations.fold(strokes) { current, operation ->
        when (operation) {
            is StoredInkOperation.Erase -> {
                val stored = operation.stored
                val mask = InkCodec.decodeErase(stored.erase) ?: return@fold current
                val targets = stored.targets.map { it.strokeId }
                when (stored.erase.mode) {
                    EraserMode.Normal -> current.subtract(mask, targets)
                    EraserMode.Object -> current.eraseObjects(mask, targets)
                }
            }
            is StoredInkOperation.Move -> {
                val stored = operation.stored
                val path = InkCodec.decodeMove(stored.move) ?: return@fold current
                val targets = stored.targets.map { it.strokeId }
                current.replayMove(
                    path = path,
                    targetIds = targets,
                    dx = stored.move.dxDp,
                    dy = stored.move.dyDp,
                ).replayResize(
                    path = path,
                    targetIds = targets,
                    anchor = InkPoint(stored.move.anchorX, stored.move.anchorY),
                    scaleX = stored.move.scaleX,
                    scaleY = stored.move.scaleY,
                )
            }
        }
    }

    private suspend fun decodeConcurrently(
        rows: List<InkStrokeEntity>,
        onChunk: (List<PageStroke>) -> Unit,
    ): List<PageStroke> = coroutineScope {
        if (rows.isEmpty()) return@coroutineScope emptyList()
        val pending = rows.chunked(INK_DECODE_CHUNK).map { chunk ->
            async {
                ensureActive()
                chunk.mapNotNull { row ->
                    InkCodec.decode(row)?.let {
                        PageStroke(
                            id = row.id,
                            stroke = it,
                            brushFamily = row.brushFamily,
                            brushVersion = row.brushVersion,
                            stabilization = row.stabilization,
                            colorFollowsTheme = row.colorFollowsTheme,
                            groupId = row.groupId,
                        )
                    }
                }
            }
        }
        val all = ArrayList<PageStroke>(rows.size)
        pending.forEach { chunk ->
            val part = chunk.await()
            all += part
            onChunk(part)
        }
        all
    }

    private companion object {
        const val INK_DECODE_CHUNK = 512
    }
}
