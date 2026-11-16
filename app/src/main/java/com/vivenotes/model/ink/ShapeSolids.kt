package com.vivenotes.model.ink

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil

const val DEFAULT_SOLID_WALL_THICKNESS = 25
const val MIN_SOLID_WALL_THICKNESS = 5
const val MAX_SOLID_WALL_THICKNESS = 80

/**
 * Curved educational solids sharing the same arcs between preview and storage. Hollow solids also
 * carry explicit fill boundaries to preserve their openings. Thickness is a percentage of the
 * outer radius; height and width come from the ordinary drag and resize handles.
 */
internal fun seedSolidSegments(
    kind: ShapeKind,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    wallThickness: Int,
    nextId: () -> String,
): List<ShapeSegment> = SolidSeed(nextId).apply {
    val radiusX = (right - left) / 2f
    val centreX = (left + right) / 2f
    val height = bottom - top
    val cap = (radiusX * EQUATOR_FLATTEN).coerceAtMost(height * MAX_CAP_FRACTION)
    val inner = 1f - wallThickness.coerceIn(MIN_SOLID_WALL_THICKNESS, MAX_SOLID_WALL_THICKNESS) / 100f

    when (kind) {
        ShapeKind.HollowCylinder -> {
            val topY = top + cap
            val bottomY = bottom - cap
            // Exterior silhouette: far top rim, right side, near base, left side.
            arc(centreX, topY, radiusX, cap, PI_F, TWO_PI, boundary = 0)
            line(right, topY, right, bottomY, boundary = 0)
            arc(centreX, bottomY, radiusX, cap, 0f, PI_F, boundary = 0)
            line(left, bottomY, left, topY, boundary = 0)
            // The open bore stays transparent even with a fill colour.
            arc(centreX, topY, radiusX * inner, cap * inner, 0f, TWO_PI, boundary = 1)
            arc(centreX, topY, radiusX, cap, 0f, PI_F)
            arc(centreX, bottomY, radiusX, cap, PI_F, TWO_PI, hidden = true)
            arc(centreX, bottomY, radiusX * inner, cap * inner, 0f, TWO_PI, hidden = true)
            line(centreX - radiusX * inner, topY, centreX - radiusX * inner, bottomY, hidden = true)
            line(centreX + radiusX * inner, topY, centreX + radiusX * inner, bottomY, hidden = true)
        }

        ShapeKind.Hemisphere -> {
            val baseY = bottom - cap
            arc(centreX, baseY, radiusX, height - cap, PI_F, TWO_PI)
            arc(centreX, baseY, radiusX, cap, 0f, PI_F)
            arc(centreX, baseY, radiusX, cap, PI_F, TWO_PI, hidden = true)
        }

        ShapeKind.Frustum -> {
            val topRadius = radiusX * FRUSTUM_TOP_FRACTION
            val topCap = cap * FRUSTUM_TOP_FRACTION
            val topY = top + topCap
            val bottomY = bottom - cap
            arc(centreX, topY, topRadius, topCap, PI_F, TWO_PI)
            line(centreX + topRadius, topY, right, bottomY)
            arc(centreX, bottomY, radiusX, cap, 0f, PI_F)
            line(left, bottomY, centreX - topRadius, topY)
            arc(centreX, topY, topRadius, topCap, 0f, PI_F)
            arc(centreX, bottomY, radiusX, cap, PI_F, TWO_PI, hidden = true)
        }

        else -> error("No explicit solid geometry for $kind")
    }
}.segments

private class SolidSeed(private val nextId: () -> String) {
    val segments = mutableListOf<ShapeSegment>()

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, hidden: Boolean = false, boundary: Int? = null) {
        segments += ShapeSegment.straight(nextId(), x1, y1, x2, y2, hidden).copy(fillContour = boundary)
    }

    fun arc(
        x: Float,
        y: Float,
        radiusX: Float,
        radiusY: Float,
        from: Float,
        to: Float,
        hidden: Boolean = false,
        boundary: Int? = null,
    ) {
        val count = ceil(abs(to - from) / (PI_F / 8f)).toInt().coerceAtLeast(1)
        segments += ellipticalArc(x, y, radiusX, radiusY, from, to, count, nextId, hidden)
            .map { it.copy(fillContour = boundary) }
    }
}

private val PI_F = PI.toFloat()
private val TWO_PI = 2f * PI_F
private const val FRUSTUM_TOP_FRACTION = 0.55f
