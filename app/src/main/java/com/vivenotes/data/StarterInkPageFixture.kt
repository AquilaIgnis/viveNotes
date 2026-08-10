package com.vivenotes.data

import android.content.Context
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.data.db.InkEraseTargetEntity
import com.vivenotes.data.db.InkMoveEntity
import com.vivenotes.data.db.InkMoveTargetEntity
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.model.newId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Ink captured from a real device and bundled as the second page of a fresh install.
 *
 * The fixture keeps the original geometry and replay operations, but [materialize] assigns fresh
 * row and group ids on every install. Starter content must not reuse globally visible ids once
 * notebook sync exists.
 *
 * **[moves] are as load-bearing as the strokes.** A stroke's stored points are the coordinates the
 * pen produced and are never rewritten, so where ink sits on the page is the stroke *plus* every
 * lasso drag replayed over it. Schema 1 carried only strokes and erases, which meant a fixture
 * exported from a page whose ink had been dragged into place seeded that ink back at its original
 * scatter. Anything captured from a real page after arranging it needs this list or the arrangement
 * is silently lost.
 */
@Serializable
data class StarterInkPageFixture(
    val schemaVersion: Int,
    val sourcePageId: String,
    val sourcePageTitle: String,
    val title: String,
    val strokes: List<StarterStroke>,
    val erases: List<StarterErase>,
    val eraseTargets: List<StarterEraseTarget>,
    val moves: List<StarterMove> = emptyList(),
    val moveTargets: List<StarterMoveTarget> = emptyList(),
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported starter-ink schema $schemaVersion" }
        require(title.isNotBlank()) { "Starter-ink page title is blank" }
        require(strokes.isNotEmpty()) { "Starter-ink page has no strokes" }
        require(strokes.map(StarterStroke::id).distinct().size == strokes.size) {
            "Starter-ink stroke ids are not unique"
        }
        require(erases.map(StarterErase::id).distinct().size == erases.size) {
            "Starter-ink erase ids are not unique"
        }
        require(moves.map(StarterMove::id).distinct().size == moves.size) {
            "Starter-ink move ids are not unique"
        }
        val strokeIds = strokes.mapTo(mutableSetOf(), StarterStroke::id)
        val eraseIds = erases.mapTo(mutableSetOf(), StarterErase::id)
        val moveIds = moves.mapTo(mutableSetOf(), StarterMove::id)
        require(eraseTargets.all { it.strokeId in strokeIds && it.eraseId in eraseIds }) {
            "Starter-ink erase target references a missing row"
        }
        require(moveTargets.all { it.strokeId in strokeIds && it.moveId in moveIds }) {
            "Starter-ink move target references a missing row"
        }
        // A transform with nothing left to transform is not replayed by the canvas, and keeping it
        // would only invite a later reader to think some stroke was meant to be in its target set.
        require(moves.all { move -> moveTargets.any { it.moveId == move.id } }) {
            "Starter-ink move has no targets"
        }
    }

    internal fun materialize(pageId: String): MaterializedStarterInk {
        val strokeIds = strokes.associate { it.id to newId() }
        val groupIds = strokes.mapNotNull(StarterStroke::groupId).distinct().associateWith { newId() }
        val eraseIds = erases.associate { it.id to newId() }
        val moveIds = moves.associate { it.id to newId() }
        return MaterializedStarterInk(
            strokes = strokes.map { source ->
                InkStrokeEntity(
                    id = strokeIds.getValue(source.id),
                    pageId = pageId,
                    seq = source.seq,
                    brushFamily = source.brushFamily,
                    brushVersion = source.brushVersion,
                    sizeDp = source.sizeDp,
                    colorArgb = source.colorArgb,
                    epsilon = source.epsilon,
                    stabilization = source.stabilization,
                    minX = source.minX,
                    minY = source.minY,
                    maxX = source.maxX,
                    maxY = source.maxY,
                    points = source.pointsHex.hexBytes(),
                    enc = source.enc,
                    createdAt = source.createdAt,
                    groupId = source.groupId?.let(groupIds::getValue),
                )
            },
            erases = erases.map { source ->
                InkEraseEntity(
                    id = eraseIds.getValue(source.id),
                    pageId = pageId,
                    mode = source.mode,
                    sizeDp = source.sizeDp,
                    points = source.pointsHex.hexBytes(),
                    enc = source.enc,
                    createdAt = source.createdAt,
                )
            },
            eraseTargets = eraseTargets.map { source ->
                InkEraseTargetEntity(
                    eraseId = eraseIds.getValue(source.eraseId),
                    strokeId = strokeIds.getValue(source.strokeId),
                )
            },
            moves = moves.map { source ->
                InkMoveEntity(
                    id = moveIds.getValue(source.id),
                    pageId = pageId,
                    dxDp = source.dxDp,
                    dyDp = source.dyDp,
                    scaleX = source.scaleX,
                    scaleY = source.scaleY,
                    anchorX = source.anchorX,
                    anchorY = source.anchorY,
                    points = source.pointsHex.hexBytes(),
                    enc = source.enc,
                    createdAt = source.createdAt,
                )
            },
            moveTargets = moveTargets.map { source ->
                InkMoveTargetEntity(
                    moveId = moveIds.getValue(source.moveId),
                    strokeId = strokeIds.getValue(source.strokeId),
                )
            },
        )
    }

    companion object {
        const val ASSET_PATH = "default_notebook/recognition_page_2.json"

        /** 2 — [moves]; a schema-1 fixture seeded arranged ink back at its unarranged coordinates. */
        private const val SCHEMA_VERSION = 2
        private val json = Json { ignoreUnknownKeys = true }

        fun load(context: Context): StarterInkPageFixture =
            context.assets.open(ASSET_PATH).bufferedReader().use { reader ->
                json.decodeFromString(serializer(), reader.readText())
            }
    }
}

@Serializable
data class StarterStroke(
    val id: String,
    val seq: Int,
    val brushFamily: String,
    val brushVersion: Int,
    val sizeDp: Float,
    val colorArgb: Int,
    val epsilon: Float,
    val stabilization: Int,
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
    val pointsHex: String,
    val enc: String,
    val createdAt: Long,
    val groupId: String? = null,
) {
    init {
        require(seq >= 0) { "Starter stroke $id has a negative sequence" }
        require(sizeDp > 0f && sizeDp.isFinite()) { "Starter stroke $id has an invalid size" }
        require(minX.isFinite() && minY.isFinite() && maxX.isFinite() && maxY.isFinite()) {
            "Starter stroke $id has non-finite bounds"
        }
        require(minX <= maxX && minY <= maxY) { "Starter stroke $id has inverted bounds" }
        pointsHex.requireHex("Starter stroke $id")
    }
}

@Serializable
data class StarterErase(
    val id: String,
    val mode: EraserMode,
    val sizeDp: Float,
    val pointsHex: String,
    val enc: String,
    val createdAt: Long,
) {
    init {
        require(sizeDp > 0f && sizeDp.isFinite()) { "Starter erase $id has an invalid size" }
        pointsHex.requireHex("Starter erase $id")
    }
}

@Serializable
data class StarterEraseTarget(
    val eraseId: String,
    val strokeId: String,
)

/**
 * One replayed lasso transform: a drag, a corner resize, or both at once.
 *
 * [pointsHex] is the lasso path, not the ink. Replay applies the transform only to a target stroke
 * whose outline still falls inside that path, so the path has to survive the export as exactly as
 * the stroke payloads do.
 */
@Serializable
data class StarterMove(
    val id: String,
    val dxDp: Float,
    val dyDp: Float,
    val scaleX: Float,
    val scaleY: Float,
    val anchorX: Float,
    val anchorY: Float,
    val pointsHex: String,
    val enc: String,
    val createdAt: Long,
) {
    init {
        require(dxDp.isFinite() && dyDp.isFinite()) { "Starter move $id has a non-finite offset" }
        require(scaleX.isFinite() && scaleY.isFinite() && scaleX > 0f && scaleY > 0f) {
            "Starter move $id has an invalid scale"
        }
        require(anchorX.isFinite() && anchorY.isFinite()) { "Starter move $id has a non-finite anchor" }
        pointsHex.requireHex("Starter move $id")
    }
}

@Serializable
data class StarterMoveTarget(
    val moveId: String,
    val strokeId: String,
)

internal data class MaterializedStarterInk(
    val strokes: List<InkStrokeEntity>,
    val erases: List<InkEraseEntity>,
    val eraseTargets: List<InkEraseTargetEntity>,
    val moves: List<InkMoveEntity> = emptyList(),
    val moveTargets: List<InkMoveTargetEntity> = emptyList(),
)

private fun String.requireHex(label: String) {
    require(isNotEmpty() && length % 2 == 0 && all(Char::isHexDigit)) {
        "$label has invalid point bytes"
    }
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun String.hexBytes(): ByteArray = ByteArray(length / 2) { index ->
    val offset = index * 2
    ((this[offset].digitToInt(16) shl 4) or this[offset + 1].digitToInt(16)).toByte()
}
