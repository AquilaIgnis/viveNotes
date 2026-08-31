package com.vivenotes.pdf

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.DisplayMetrics
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import androidx.ink.geometry.outlinesToPath
import com.vivenotes.data.automaticColorOr
import com.vivenotes.ink.InkBounds
import com.vivenotes.model.Outline
import com.vivenotes.model.RuleLines
import com.vivenotes.model.ink.LineType
import com.vivenotes.model.ink.ShapeContour
import com.vivenotes.model.ink.contours
import com.vivenotes.model.ink.fillRegion
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Draws one sheet of an export — `memory/pdfExportPlan.md` PD7.
 *
 * One renderer serves both callers: a `PdfDocument` page and a preview bitmap differ only in the
 * scale they are handed. Everything below works in **page dp**, the unit outlines, shapes, tables,
 * equations and ink all already share, and the paint order is `EditorPane`'s composition order —
 * which is the only thing that makes the output match the screen.
 *
 * Text is the one exception to page-dp, and it has to be: a font size mark measures itself against
 * the device's display metrics, so the layouts [PageMeasurer] built are in device pixels and are
 * drawn through a `1 / density` scale.
 */
class PageRenderer(private val metrics: DisplayMetrics) {

    private val density: Float = metrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val image = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val scratchMatrix = Matrix()

    /**
     * Paints [tile] onto [canvas] as a whole sheet: background, margins, and the rectangle of page
     * that lands inside them.
     *
     * [scale] is what one page dp is worth in the output — `PdfPaper.POINTS_PER_DP` for a PDF page,
     * pixels per dp for a preview. The canvas is left as it was found.
     */
    fun drawSheet(
        canvas: Canvas,
        page: MeasuredPage,
        tile: PdfTile,
        plan: PdfPagePlan,
        paper: PdfPaper,
        ruling: Boolean,
        scale: Float,
    ) {
        val checkpoint = canvas.save()
        canvas.scale(scale, scale)

        fill.color = page.colors.backgroundArgb
        fill.alpha = Color.alpha(page.colors.backgroundArgb)
        canvas.drawRect(0f, 0f, paper.widthDp, paper.heightDp, fill)

        // A bound page is already a sheet — PD3's exception — so its tile lands on the paper's own
        // corner. Everything else is a cut out of the canvas, and it lands inside the margins.
        val insetLeft = if (plan.bound) 0f else paper.marginLeftDp
        val insetTop = if (plan.bound) 0f else paper.marginTopDp

        canvas.translate(insetLeft, insetTop)
        canvas.clipRect(0f, 0f, tile.area.width, tile.area.height)
        canvas.translate(-tile.area.left, -tile.area.top)
        drawContent(canvas, page, tile.area, plan, ruling)

        canvas.restoreToCount(checkpoint)
    }

    private fun drawContent(
        canvas: Canvas,
        page: MeasuredPage,
        visible: InkBounds,
        plan: PdfPagePlan,
        ruling: Boolean,
    ) {
        // An outline's own rectangle decides whether it lands on this sheet; the *group's* shift
        // decides where. Asking the plan for an outline's own shift would find none — the plan moves
        // whole entities — and the one part of a diagram whose id it did not know would stay behind.
        fun shiftOf(id: String): PdfShift = page.shiftOf(id, plan)
        fun lands(id: String): Boolean {
            val bounds = page.boundsOf(id) ?: return true
            val shift = shiftOf(id)
            return bounds.translated(shift.dx, shift.dy).overlaps(visible)
        }

        if (ruling && page.style.ruleLines != RuleLines.None) {
            // A page bound to its sheet rules the sheet and stops. One whose content has outgrown
            // the sheet is writable everywhere, so the ruling goes wherever the cut does — which is
            // exactly what `EditorPane.PageSurface` does with the same two cases.
            val limit = page.style.pageSizeDp
                ?.takeIf { plan.bound }
                ?.let { (width, height) -> InkBounds(0f, 0f, width, height) }
            drawRuling(canvas, page.style.ruleLines, page.colors.ruleLineArgb, visible, limit)
        }

        page.doc.outlines.filterIsInstance<Outline.Text>().forEach { outline ->
            val block = page.texts[outline.id] ?: return@forEach
            if (!lands(outline.id)) return@forEach
            drawLayout(canvas, block, shiftOf(outline.id))
        }

        page.doc.outlines.filterIsInstance<Outline.Shape>().forEach { shape ->
            val shift = shiftOf(shape.id)
            if (!shape.pageRect().translated(shift.dx, shift.dy).overlaps(visible)) return@forEach
            drawShape(canvas, shape, page.colors.textArgb, shift)
        }

        page.doc.outlines.filterIsInstance<Outline.Equation>().forEach { equation ->
            val shift = shiftOf(equation.id)
            if (!equation.pageRect().translated(shift.dx, shift.dy).overlaps(visible)) return@forEach
            drawEquation(canvas, equation, page, shift)
        }

        page.doc.outlines.filterIsInstance<Outline.Image>().forEach { picture ->
            val shift = shiftOf(picture.id)
            if (!picture.pageRect().translated(shift.dx, shift.dy).overlaps(visible)) return@forEach
            val bitmap = page.images[picture.attachmentId]
            val target = RectF(
                picture.x + shift.dx,
                picture.y + shift.dy,
                picture.x + picture.width + shift.dx,
                picture.y + picture.height + shift.dy,
            )
            if (bitmap == null) {
                // A picture whose bytes cannot be reached is not a picture; its frame is still where
                // the writer put it, so the space it holds is kept rather than silently closed up.
                line.color = page.colors.ruleLineArgb
                line.strokeWidth = 1f
                line.pathEffect = null
                canvas.drawRect(target, line)
            } else {
                canvas.drawBitmap(bitmap, null, target, image)
            }
        }

        page.header?.takeIf { it.bounds.overlaps(visible) }?.let { drawHeader(canvas, it, page) }

        page.doc.outlines.filterIsInstance<Outline.Table>().forEach { table ->
            val grid = page.tables[table.id] ?: return@forEach
            if (!lands(table.id)) return@forEach
            drawTable(canvas, table, grid, page, shiftOf(table.id))
        }

        // Ink last, as it is on screen: a stroke is drawn over whatever it was written across.
        page.groups.forEach { group ->
            val shift = plan.shiftFor(group.id)
            if (!group.bounds.translated(shift.dx, shift.dy).overlaps(visible)) return@forEach
            group.strokes.forEach { stroke -> drawStroke(canvas, stroke, page.colors.textArgb, shift) }
        }
    }

    // --- ink ---------------------------------------------------------------------------------

    /**
     * A stroke as a filled outline, not as a mesh.
     *
     * **This is not a preference.** `PdfDocument` records into a picture that is replayed onto a PDF
     * device: `Canvas.drawMesh` needs hardware acceleration, which a recording canvas has not got,
     * and Skia's PDF backend does not implement `drawVertices` — so `CanvasStrokeRenderer`, which
     * picks the mesh path on any modern device, silently draws nothing here.
     * [androidx.ink.geometry.outlinesToPath] gives the stroke's own outline as public API, and
     * filling it puts vector ink in the PDF at any zoom.
     *
     * Overlapping outlines inside one stroke are filled once by the winding rule, which is also what
     * stops a translucent highlighter doubling its alpha where it crosses itself.
     */
    private fun drawStroke(
        canvas: Canvas,
        stroke: com.vivenotes.ink.PageStroke,
        canvasInkArgb: Int,
        shift: PdfShift,
    ) {
        val brush = stroke.stroke.brush
        fill.color = automaticColorOr(
            stored = brush.colorIntArgb,
            followsTheme = stroke.colorFollowsTheme,
            canvasInk = canvasInkArgb,
        )
        scratchMatrix.setScale(stroke.scaleX, stroke.scaleY)
        scratchMatrix.postTranslate(stroke.offsetX + shift.dx, stroke.offsetY + shift.dy)
        val shape = stroke.stroke.shape
        for (group in 0 until shape.getRenderGroupCount()) {
            val path = shape.outlinesToPath(group)
            if (path.isEmpty) continue
            path.transform(scratchMatrix)
            canvas.drawPath(path, fill)
        }
    }

    // --- text --------------------------------------------------------------------------------

    private fun drawLayout(canvas: Canvas, block: PdfTextBlock, shift: PdfShift) {
        val checkpoint = canvas.save()
        canvas.translate(block.leftDp + shift.dx, block.topDp + shift.dy)
        canvas.scale(1f / density, 1f / density)
        block.layout.draw(canvas)
        canvas.restoreToCount(checkpoint)
    }

    private fun drawHeader(canvas: Canvas, header: PdfPageHeader, page: MeasuredPage) {
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SANS_SERIF
            textSize = spToPx(PageMeasurer.TITLE_SP)
            color = if (page.title.isEmpty()) page.colors.secondaryTextArgb else page.colors.textArgb
        }
        val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SANS_SERIF
            textSize = spToPx(PageMeasurer.SUBTITLE_SP)
            color = page.colors.secondaryTextArgb
        }
        val checkpoint = canvas.save()
        canvas.scale(1f / density, 1f / density)
        val title = TextUtils.ellipsize(
            header.title,
            titlePaint,
            header.widthDp * density,
            TextUtils.TruncateAt.END,
        )
        canvas.drawText(
            title,
            0,
            title.length,
            header.leftDp * density,
            header.titleBaselineDp * density,
            titlePaint,
        )
        if (header.subtitle.isNotEmpty()) {
            canvas.drawText(
                header.subtitle,
                header.leftDp * density,
                header.subtitleBaselineDp * density,
                subtitlePaint,
            )
        }
        canvas.restoreToCount(checkpoint)

        fill.color = page.colors.ruleLineArgb
        canvas.drawRect(
            header.leftDp,
            header.ruleTopDp,
            header.leftDp + header.ruleWidthDp,
            header.ruleTopDp + PageMeasurer.HEADER_RULE_HEIGHT_DP,
            fill,
        )
    }

    // --- shapes ------------------------------------------------------------------------------

    /** `ShapeLayer.drawShape`, on an `android.graphics.Canvas`. */
    private fun drawShape(canvas: Canvas, shape: Outline.Shape, canvasInkArgb: Int, shift: PdfShift) {
        val checkpoint = canvas.save()
        canvas.translate(shift.dx, shift.dy)

        shape.fillArgb?.let { argb ->
            fill.color = argb
            shape.fillRegion().forEach { region ->
                canvas.drawPath(region.asClosedPath(), fill)
            }
        }

        line.color = automaticColorOr(shape.borderArgb, shape.borderFollowsTheme, canvasInkArgb)
        line.strokeWidth = shape.borderWidth
        line.strokeCap = Paint.Cap.ROUND
        line.strokeJoin = Paint.Join.ROUND
        // By contour rather than by segment: a dash pattern restarts on every path it is given, so
        // stroking a rim's arcs one at a time doubles the dots at each joint.
        shape.segments.contours().forEach { contour ->
            val type = if (contour.hidden) LineType.Dotted else shape.lineType
            line.pathEffect = type.dashEffect(shape.borderWidth)
            canvas.drawPath(contour.asPath(), line)
        }
        line.pathEffect = null
        canvas.restoreToCount(checkpoint)
    }

    // --- equations ---------------------------------------------------------------------------

    /** `EquationLayer.drawEquation`: the formula stretched from its own metrics into a stored box. */
    private fun drawEquation(
        canvas: Canvas,
        equation: Outline.Equation,
        page: MeasuredPage,
        shift: PdfShift,
    ) {
        val left = equation.x + shift.dx
        val top = equation.y + shift.dy
        val renderer = page.equations[equation.id]
        if (renderer == null) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = equation.colorArgb ?: page.colors.textArgb
                textSize = PageMeasurer.EQUATION_BASE_DP
            }
            canvas.drawText(equation.latex, left, top + equation.height, paint)
            return
        }
        val naturalWidth = renderer.widthPx
        val naturalHeight = renderer.heightPx + renderer.depthPx
        if (naturalWidth <= 0f || naturalHeight <= 0f) return
        val checkpoint = canvas.save()
        canvas.translate(left, top)
        canvas.scale(equation.width / naturalWidth, equation.height / naturalHeight)
        renderer.draw(canvas)
        canvas.restoreToCount(checkpoint)
    }

    // --- tables ------------------------------------------------------------------------------

    /** `TableContainer`'s `drawBehind`, plus the cells its editors would have drawn. */
    private fun drawTable(
        canvas: Canvas,
        table: Outline.Table,
        grid: PdfTableGrid,
        page: MeasuredPage,
        shift: PdfShift,
    ) {
        val checkpoint = canvas.save()
        canvas.translate(grid.leftDp + shift.dx, grid.topDp + shift.dy)
        val width = table.width
        val height = grid.heightDp

        table.fillArgb?.let {
            fill.color = it
            canvas.drawRect(0f, 0f, width, height, fill)
        }

        val rowTops = FloatArray(table.rowCount + 1)
        var y = 0f
        grid.rowHeightsDp.forEachIndexed { index, rowHeight ->
            rowTops[index] = y
            y += rowHeight
        }
        rowTops[table.rowCount] = height

        val headerTint = page.colors.textArgb.withAlpha(HEADER_TINT_ALPHA)
        if (table.headerRow && table.rowCount > 0) {
            fill.color = headerTint
            canvas.drawRect(0f, 0f, width, rowTops[1], fill)
        }
        if (table.headerColumn && table.columnCount > 0) {
            fill.color = headerTint
            canvas.drawRect(0f, 0f, table.columns[0], height, fill)
        }

        val rule = table.borderWidth.coerceAtLeast(MIN_RULE_DP)
        line.color = automaticColorOr(table.borderArgb, table.borderFollowsTheme, page.colors.textArgb)
        line.strokeWidth = rule
        line.pathEffect = null
        line.strokeCap = Paint.Cap.BUTT
        var x = 0f
        for (index in 0..table.columnCount) {
            // Half a rule in at each end, so the outer rules are not clipped in half by the edge of
            // the box they bound — `TableContainer` draws them the same way.
            val at = x.coerceIn(rule / 2f, (width - rule / 2f).coerceAtLeast(rule / 2f))
            canvas.drawLine(at, 0f, at, height, line)
            if (index < table.columnCount) x += table.columns[index]
        }
        for (index in 0..table.rowCount) {
            val at = rowTops[index].coerceIn(rule / 2f, (height - rule / 2f).coerceAtLeast(rule / 2f))
            canvas.drawLine(0f, at, width, at, line)
        }

        table.rows.forEachIndexed { rowIndex, row ->
            var cellLeft = 0f
            row.cells.forEachIndexed { columnIndex, cell ->
                val layout = grid.cells[cell.id]
                if (layout != null) {
                    val inner = canvas.save()
                    canvas.translate(
                        cellLeft + PageMeasurer.CELL_PADDING_DP,
                        rowTops[rowIndex] + PageMeasurer.CELL_PADDING_DP,
                    )
                    canvas.scale(1f / density, 1f / density)
                    layout.draw(canvas)
                    canvas.restoreToCount(inner)
                }
                cellLeft += table.columns.getOrElse(columnIndex) { 0f }
            }
        }
        canvas.restoreToCount(checkpoint)
    }

    // --- ruling ------------------------------------------------------------------------------

    /** `EditorPane.PageRuling`, in page dp and bounded by the tile rather than by a window. */
    private fun drawRuling(
        canvas: Canvas,
        rules: RuleLines,
        colorArgb: Int,
        visible: InkBounds,
        limit: InkBounds?,
    ) {
        val step = rules.spacingDp
        if (step <= 0f) return
        val area = limit?.let { visible.intersect(it) ?: return } ?: visible

        line.color = colorArgb
        line.strokeWidth = 1f
        line.pathEffect = null
        line.strokeCap = Paint.Cap.BUTT

        if (rules.hexagonal) {
            val side = step
            val hexWidth = side * HEXAGON_WIDTH_RATIO
            val rowStep = side * 1.5f
            val firstCenterY = side
            val firstRow = floor((area.top - firstCenterY - side) / rowStep).toInt().coerceAtLeast(0)
            val lastRow = ceil((area.bottom - firstCenterY + side) / rowStep).toInt()
            val hexagons = Path()
            for (row in firstRow..lastRow) {
                val centerY = firstCenterY + row * rowStep
                val rowOffset = if (row % 2 == 0) 0f else -hexWidth / 2f
                val firstCenterX = hexWidth / 2f + rowOffset
                val firstColumn = floor((area.left - firstCenterX - hexWidth / 2f) / hexWidth)
                    .toInt().coerceAtLeast(0)
                val lastColumn = ceil((area.right - firstCenterX + hexWidth / 2f) / hexWidth).toInt()
                for (column in firstColumn..lastColumn) {
                    val centerX = firstCenterX + column * hexWidth
                    hexagons.moveTo(centerX, centerY - side)
                    hexagons.lineTo(centerX + hexWidth / 2f, centerY - side / 2f)
                    hexagons.lineTo(centerX + hexWidth / 2f, centerY + side / 2f)
                    hexagons.lineTo(centerX, centerY + side)
                    hexagons.lineTo(centerX - hexWidth / 2f, centerY + side / 2f)
                    hexagons.lineTo(centerX - hexWidth / 2f, centerY - side / 2f)
                    hexagons.close()
                }
            }
            line.color = colorArgb.withAlpha(Color.alpha(colorArgb) / 255f * HEXAGON_RULE_ALPHA)
            canvas.drawPath(hexagons, line)
            return
        }

        if (rules.dotted) {
            fill.color = colorArgb
            var dotY = maxOf(step, ceil(area.top / step) * step)
            while (dotY < area.bottom) {
                var dotX = maxOf(step, ceil(area.left / step) * step)
                while (dotX < area.right) {
                    canvas.drawCircle(dotX, dotY, DOTTED_RULE_RADIUS_DP, fill)
                    dotX += step
                }
                dotY += step
            }
            return
        }

        var y = maxOf(step, ceil(area.top / step) * step)
        while (y < area.bottom) {
            canvas.drawLine(area.left, y, area.right, y, line)
            y += step
        }
        if (!rules.squared) return
        var x = maxOf(step, ceil(area.left / step) * step)
        while (x < area.right) {
            canvas.drawLine(x, area.top, x, area.bottom, line)
            x += step
        }
    }

    private fun spToPx(sp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)

    private companion object {
        const val DOTTED_RULE_RADIUS_DP = 0.8f
        const val HEXAGON_WIDTH_RATIO = 1.7320508f
        const val HEXAGON_RULE_ALPHA = 0.5f

        /** `TableContainer.HEADER_TINT_ALPHA`. */
        const val HEADER_TINT_ALPHA = 0.10f

        /** A rule thinner than this vanishes on paper the way a hairline does on screen. */
        const val MIN_RULE_DP = 0.4f
    }
}

private fun Outline.Shape.pageRect(): InkBounds {
    val pen = borderWidth / 2f
    return InkBounds(x - pen, y - pen, x + width + pen, y + height + pen)
}

private fun Outline.Equation.pageRect(): InkBounds = InkBounds(x, y, x + width, y + height)

private fun Outline.Image.pageRect(): InkBounds = InkBounds(x, y, x + width, y + height)

private fun InkBounds.intersect(other: InkBounds): InkBounds? {
    val left = maxOf(left, other.left)
    val top = maxOf(top, other.top)
    val right = minOf(right, other.right)
    val bottom = minOf(bottom, other.bottom)
    return if (left < right && top < bottom) InkBounds(left, top, right, bottom) else null
}

private fun Int.withAlpha(alpha: Float): Int =
    Color.argb((alpha * 255f).toInt().coerceIn(0, 255), Color.red(this), Color.green(this), Color.blue(this))

/** `PenPanel.pathEffect`, as the platform effect rather than the Compose one. */
private fun LineType.dashEffect(width: Float): DashPathEffect? = when (this) {
    LineType.Solid -> null
    LineType.Dashed -> DashPathEffect(floatArrayOf(width * 2.6f, width * 1.8f), 0f)
    LineType.Dotted -> DashPathEffect(floatArrayOf(0.01f, width * 2f), 0f)
}

private fun FloatArray.asClosedPath(): Path = Path().apply {
    if (size < 4) return@apply
    moveTo(this@asClosedPath[0], this@asClosedPath[1])
    for (index in 2 until size step 2) lineTo(this@asClosedPath[index], this@asClosedPath[index + 1])
    close()
}

private fun ShapeContour.asPath(): Path = Path().apply {
    val points = polyline()
    if (points.size < 4) return@apply
    moveTo(points[0], points[1])
    for (index in 2 until points.size step 2) lineTo(points[index], points[index + 1])
    if (isClosed) close()
}
