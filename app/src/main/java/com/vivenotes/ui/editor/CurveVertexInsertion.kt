package com.vivenotes.ui.editor

import android.graphics.Matrix
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vivenotes.R
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.curveInsertionPoints
import com.vivenotes.model.ink.curveRemovalPoints
import com.vivenotes.ui.icons.MaterialSymbols
import kotlin.math.roundToInt

internal const val CURVE_INSERTION_OVERLAY_TAG = "curve-insertion-overlay"
internal const val CURVE_REMOVAL_OVERLAY_TAG = "curve-removal-overlay"
internal fun curveInsertionTag(index: Int) = "curve-insertion-$index"
internal fun curveRemovalTag(index: Int) = "curve-removal-$index"

internal enum class CurveVertexMode { Add, Remove }
internal data class CurveVertexEdit(val shapeId: String, val mode: CurveVertexMode)

private data class VertexTarget(val x: Float, val y: Float, val tag: String, val description: String, val onClick: () -> Unit)

/** Viewport chrome shared by normal and lasso selection; ghost targets keep a 48dp touch area. */
@Composable
internal fun CurveVertexInsertionOverlay(
    curve: Outline.Shape,
    visible: Boolean,
    pageToView: () -> Matrix,
    onInsert: (String) -> Unit,
    onDismiss: () -> Unit,
    mode: CurveVertexMode = CurveVertexMode.Add,
    onRemove: (String, Boolean) -> Unit = { _, _ -> },
) {
    val dismiss = rememberUpdatedState(onDismiss)
    var lastVisibleCurve by remember(curve.id) { mutableStateOf(curve) }
    var lastVisibleMode by remember(curve.id) { mutableStateOf(mode) }
    if (visible) SideEffect {
        lastVisibleCurve = curve
        lastVisibleMode = mode
    }
    // The document already contains the new vertex when exit starts. Fade the old targets in
    // place rather than briefly showing the next insertion choices around the new vertex.
    val ghostCurve = if (visible) curve else lastVisibleCurve
    val ghostMode = if (visible) mode else lastVisibleMode
    val removing = ghostMode == CurveVertexMode.Remove
    val targets = when (ghostMode) {
        CurveVertexMode.Add -> ghostCurve.curveInsertionPoints().mapIndexed { index, point ->
            VertexTarget(point.x, point.y, curveInsertionTag(index),
                stringResource(R.string.shape_insert_vertex_between, index + 1, index + 2),
                { onInsert(point.segmentId) })
        }
        CurveVertexMode.Remove -> ghostCurve.curveRemovalPoints().map { point ->
            VertexTarget(point.x, point.y, curveRemovalTag(point.index),
                stringResource(R.string.shape_remove_vertex_point, point.index + 1),
                { onRemove(point.segmentId, point.atEnd) })
        }
    }
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val accent = (if (removing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary).copy(alpha = 0.75f)
    val fill = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(effects),
        exit = fadeOut(effects),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier.fillMaxSize().testTag(if (removing) CURVE_REMOVAL_OVERLAY_TAG else CURVE_INSERTION_OVERLAY_TAG)
                .then(if (visible) Modifier.pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { if (!tryAwaitRelease()) dismiss.value() },
                        onTap = { dismiss.value() },
                    )
                } else Modifier),
        ) {
            targets.forEach { point ->
                IconButton(
                    enabled = visible,
                    onClick = point.onClick,
                    modifier = Modifier
                        .offset {
                            val at = floatArrayOf(point.x, point.y)
                            pageToView().mapPoints(at)
                            IntOffset((at[0] - 24.dp.toPx()).roundToInt(), (at[1] - 24.dp.toPx()).roundToInt())
                        }
                        .size(48.dp)
                        .testTag(point.tag),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(22.dp)) {
                            drawCircle(fill)
                            drawCircle(
                                accent,
                                style = Stroke(
                                    width = 1.5.dp.toPx(),
                                    pathEffect = if (removing) null else PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                                ),
                            )
                        }
                        Icon(
                            if (removing) MaterialSymbols.Remove else MaterialSymbols.Add,
                            contentDescription = point.description,
                            tint = accent,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}
