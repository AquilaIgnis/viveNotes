package com.vivenotes.data

import android.content.Context
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.data.db.InkEraseTargetEntity
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
        val strokeIds = strokes.mapTo(mutableSetOf(), StarterStroke::id)
        val eraseIds = erases.mapTo(mutableSetOf(), StarterErase::id)
        require(eraseTargets.all { it.strokeId in strokeIds && it.eraseId in eraseIds }) {
            "Starter-ink erase target references a missing row"
        }
    }

    internal fun materialize(pageId: String): MaterializedStarterInk {
        val strokeIds = strokes.associate { it.id to newId() }
        val groupIds = strokes.mapNotNull(StarterStroke::groupId).distinct().associateWith { newId() }
        val eraseIds = erases.associate { it.id to newId() }
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
        )
    }

    companion object {
        const val ASSET_PATH = "default_notebook/recognition_page_2.json"
        private const val SCHEMA_VERSION = 1
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

internal data class MaterializedStarterInk(
    val strokes: List<InkStrokeEntity>,
    val erases: List<InkEraseEntity>,
    val eraseTargets: List<InkEraseTargetEntity>,
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
