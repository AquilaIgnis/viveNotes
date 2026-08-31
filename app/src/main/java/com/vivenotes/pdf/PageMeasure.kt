package com.vivenotes.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.Layout
import android.text.Spannable
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.util.TypedValue
import androidx.compose.ui.graphics.toArgb
import com.vivenotes.data.AttachmentStore
import com.vivenotes.data.EditorDefaults
import com.vivenotes.ink.InkBounds
import com.vivenotes.ink.PageStroke
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.PageStyle
import com.vivenotes.richtext.EditorStyle
import com.vivenotes.richtext.EquationSpan
import com.vivenotes.richtext.SpannableCodec
import com.vivenotes.richtext.createEquationRenderer
import com.vivenotes.ui.theme.canvasColorsFor
import com.vivenotes.ui.theme.paintedWith
import io.ratex.RaTeXRenderer
import kotlin.math.roundToInt

/**
 * The colours an exported page is painted with — `memory/pdfExportPlan.md` PD7, item 1.
 *
 * **Always the light canvas**, whatever the app's Switch Background is set to. That setting is a
 * preference about the screen somebody is writing on, not a property of the document, and nobody
 * wants a black PDF because they happened to be working at night. A page carrying a background
 * colour of its own keeps it — that *is* in the document — and
 * [com.vivenotes.ui.theme.paintedWith] re-derives legible ink for it, exactly as the canvas does.
 *
 * The pleasant consequence: ink, shape borders, table rules and equations that were drawn with the
 * *automatic* colour all re-resolve against [textArgb] through the `automaticColorOr` path they
 * already use, so a page written in white on a dark canvas exports as black on white paper.
 */
data class PdfCanvasColors(
    val backgroundArgb: Int,
    val ruleLineArgb: Int,
    val textArgb: Int,
    val secondaryTextArgb: Int,
    val accentArgb: Int,
    val codeBackgroundArgb: Int,
) {
    companion object {
        /** The app's light primary — `ui/theme/Theme.kt`. Bullets, numbering and quote rules. */
        private const val ACCENT_ARGB = 0xFF0063C6.toInt()

        fun forPage(style: PageStyle): PdfCanvasColors {
            val canvas = canvasColorsFor(dark = false).paintedWith(style.backgroundArgb)
            return PdfCanvasColors(
                backgroundArgb = canvas.background.toArgb(),
                ruleLineArgb = canvas.ruleLine.toArgb(),
                textArgb = canvas.text.toArgb(),
                secondaryTextArgb = canvas.secondaryText.toArgb(),
                accentArgb = ACCENT_ARGB,
                // On screen this is white at 7%, which is invisible on paper. Inverted with the
                // canvas, so a code block reads as a block on either.
                codeBackgroundArgb = if (canvas.isDark) 0x12FFFFFF else 0x14000000,
            )
        }
    }
}

/** One text box, laid out once and then both measured and drawn from. */
class PdfTextBlock(val layout: StaticLayout, val leftDp: Float, val topDp: Float)

/** A table's measured geometry: where its grid starts, how tall each row came out, and its cells. */
class PdfTableGrid(
    val leftDp: Float,
    val topDp: Float,
    val rowHeightsDp: FloatArray,
    val cells: Map<String, StaticLayout>,
) {
    val heightDp: Float get() = rowHeightsDp.sum()
}

/**
 * Everything one page needs to be drawn, measured once.
 *
 * The document does not know how tall a text container or a table is — only a canvas does, which is
 * `PageSpace`'s "a far edge is a number the model does not have". The exporter is a second canvas,
 * so it measures the same text with the same engine at the same width, and hands the result to both
 * the tiling (which needs rectangles) and the renderer (which needs the layouts).
 */
class MeasuredPage(
    val pageId: String,
    val title: String,
    val createdAt: Long,
    val doc: PageDoc,
    val colors: PdfCanvasColors,
    val items: List<PdfItem>,
    val texts: Map<String, PdfTextBlock>,
    val tables: Map<String, PdfTableGrid>,
    /** The entities the fit moves — `ContentGroups.kt`. Objects and ink together, by design. */
    val groups: List<PdfGroup>,
    /** The sheet this page is already laid out on, or null when it has to be tiled — PD3. */
    val boundSheet: InkBounds?,
    val equations: Map<String, RaTeXRenderer>,
    val images: Map<String, Bitmap>,
    val header: PdfPageHeader?,
) {
    val style: PageStyle get() = doc.style

    /** Which group each outline ended up in, so a shift planned for the group reaches its parts. */
    private val groupOf: Map<String, String> = buildMap {
        groups.forEach { group ->
            group.atoms.forEach { atom -> atom.outlineId?.let { put(it, group.id) } }
        }
    }

    /** Every outline's own rectangle, for culling a sheet down to what lands on it. */
    private val atomBounds: Map<String, InkBounds> = buildMap {
        groups.forEach { group ->
            group.atoms.forEach { atom -> atom.outlineId?.let { put(it, atom.bounds) } }
        }
    }

    /**
     * What [plan] moved this outline by.
     *
     * Through the group, never the outline: the plan shifts whole entities, and an outline that
     * asked for its own answer would be the one part of a diagram that stayed behind.
     */
    fun shiftOf(outlineId: String, plan: PdfPagePlan): PdfShift =
        plan.shiftFor(groupOf[outlineId] ?: outlineId)

    fun boundsOf(outlineId: String): InkBounds? = atomBounds[outlineId]
}

/**
 * The title band's measured geometry, or null on a page that hides it.
 *
 * Laid out at the origin and then [offsetBy] wherever the sheet wants it, because where it wants it
 * is not a fact about the header — see [PageMeasurer.measure], which anchors it to the content
 * block rather than to the page's own corner.
 */
class PdfPageHeader(
    val title: String,
    val subtitle: String,
    val leftDp: Float,
    val topDp: Float,
    val widthDp: Float,
    val heightDp: Float,
    val ruleWidthDp: Float,
    val ruleTopDp: Float,
    val subtitleBaselineDp: Float,
    val titleBaselineDp: Float,
) {
    val bounds: InkBounds
        get() = InkBounds(leftDp, topDp, leftDp + widthDp, topDp + heightDp)

    fun offsetBy(dx: Float, dy: Float): PdfPageHeader = PdfPageHeader(
        title = title,
        subtitle = subtitle,
        leftDp = leftDp + dx,
        topDp = topDp + dy,
        widthDp = widthDp,
        heightDp = heightDp,
        ruleWidthDp = ruleWidthDp,
        ruleTopDp = ruleTopDp + dy,
        subtitleBaselineDp = subtitleBaselineDp + dy,
        titleBaselineDp = titleBaselineDp + dy,
    )
}

/**
 * Lays out a page for export.
 *
 * **Everything is measured in device pixels and drawn in page dp.** A font size mark is stored as an
 * `AbsoluteSizeSpan(sp, dip = true)`, which converts itself using the device's own display metrics
 * whenever it is measured — so a layout built at any other density would disagree with the marks
 * inside it. Measuring at the device's density and scaling the *drawing* by `1/density` is what
 * makes the exported line breaks the same line breaks the writer saw.
 */
class PageMeasurer(
    private val context: Context,
    private val attachments: AttachmentStore? = null,
) {
    private val metrics = context.resources.displayMetrics
    private val density: Float = metrics.density

    fun editorStyleFor(colors: PdfCanvasColors) = EditorStyle(
        // `EditorPane`'s own numbers, in the same units it computes them in.
        indentStepPx = (INDENT_STEP_DP * density).roundToInt(),
        listGapPx = (LIST_GAP_DP * density).roundToInt(),
        bulletRadiusPx = (BULLET_RADIUS_DP * density).roundToInt(),
        accentColor = colors.accentArgb,
        codeBackgroundColor = colors.codeBackgroundArgb,
        quoteColor = colors.accentArgb,
    )

    suspend fun measure(
        pageId: String,
        title: String,
        createdAt: Long,
        doc: PageDoc,
        strokes: List<PageStroke>,
        paper: PdfPaper,
        /**
         * Whether to decode the page's pictures.
         *
         * False while a plan is being built, and that costs the plan nothing: a picture's rectangle
         * is in the document, so the tiling and the fit reach the same answer either way. It is what
         * lets a fifty-page section be planned without fifty pages of print-resolution bitmaps.
         */
        loadPictures: Boolean = true,
    ): MeasuredPage {
        val colors = PdfCanvasColors.forPage(doc.style)
        val editorStyle = editorStyleFor(colors)
        val items = mutableListOf<PdfItem>()
        val atoms = mutableListOf<PdfAtom>()

        val texts = mutableMapOf<String, PdfTextBlock>()
        doc.outlines.filterIsInstance<Outline.Text>().forEach { outline ->
            val layout = layoutFor(outline.blocks, outline.width - TEXT_PADDING_DP * 2, editorStyle, colors)
            val left = outline.x + TEXT_PADDING_DP
            val top = outline.y + GRIP_HEIGHT_DP + TEXT_PADDING_DP
            texts[outline.id] = PdfTextBlock(layout, left, top)
            val height = maxOf(layout.height / density, outline.minHeight)
            atoms += PdfAtom(
                id = outline.id,
                outlineId = outline.id,
                bounds = InkBounds(
                    left = outline.x,
                    top = outline.y + GRIP_HEIGHT_DP,
                    right = outline.x + outline.width,
                    bottom = top + height + TEXT_PADDING_DP,
                ),
            )
        }

        val tables = mutableMapOf<String, PdfTableGrid>()
        doc.outlines.filterIsInstance<Outline.Table>().forEach { table ->
            val grid = measureTable(table, editorStyle, colors)
            tables[table.id] = grid
            atoms += PdfAtom(
                id = table.id,
                outlineId = table.id,
                bounds = InkBounds(
                    left = grid.leftDp,
                    top = grid.topDp,
                    right = grid.leftDp + table.width,
                    bottom = grid.topDp + grid.heightDp,
                ),
            )
        }

        doc.outlines.filterIsInstance<Outline.Shape>().forEach { shape ->
            // Grown by half the pen, because a border is drawn centred on the geometry and the
            // stored box is the geometry — a two-dp rule on the edge of a tile is otherwise halved.
            val pen = shape.borderWidth / 2f
            atoms += PdfAtom(
                id = shape.id,
                outlineId = shape.id,
                bounds = InkBounds(
                    left = shape.x - pen,
                    top = shape.y - pen,
                    right = shape.x + shape.width + pen,
                    bottom = shape.y + shape.height + pen,
                ),
            )
        }

        val equations = mutableMapOf<String, RaTeXRenderer>()
        doc.outlines.filterIsInstance<Outline.Equation>().forEach { equation ->
            runCatching {
                createEquationRenderer(
                    context = context,
                    latex = equation.latex,
                    fontSizePx = EQUATION_BASE_DP * density,
                    color = equation.colorArgb ?: colors.textArgb,
                )
            }.getOrNull()?.let { equations[equation.id] = it }
            atoms += PdfAtom(
                id = equation.id,
                outlineId = equation.id,
                bounds = InkBounds(
                    left = equation.x,
                    top = equation.y,
                    right = equation.x + equation.width,
                    bottom = equation.y + equation.height,
                ),
            )
        }

        val images = mutableMapOf<String, Bitmap>()
        doc.outlines.filterIsInstance<Outline.Image>().forEach { image ->
            val store = attachments
            if (loadPictures && store != null && image.attachmentId !in images) {
                // Asked for at print resolution rather than at screen resolution: a picture that
                // looked fine at 320 dp on a tablet is a 300-dpi block of paper here.
                val target = (maxOf(image.width, image.height) * PRINT_PIXELS_PER_DP)
                    .roundToInt()
                    .coerceIn(1, MAX_IMAGE_EDGE)
                store.loadBitmap(image.attachmentId, target)?.let { images[image.attachmentId] = it }
            }
            atoms += PdfAtom(
                id = image.id,
                outlineId = image.id,
                bounds = InkBounds(
                    left = image.x,
                    top = image.y,
                    right = image.x + image.width,
                    bottom = image.y + image.height,
                ),
            )
        }

        atoms += inkAtoms(strokes)

        // The whole correction: objects and ink are gathered into entities *together*, by the empty
        // page between them, rather than each outline standing alone with ink clustered separately.
        // A sum written beside a paragraph is part of it, and now travels with it.
        val groups = groupContent(atoms, paper.tileWidthDp, paper.tileHeightDp)
        groups.forEach { group -> items += PdfItem(group.id, PdfItemKind.Content, group.bounds) }

        val content = groups.map(PdfGroup::bounds).unionOrNull()
        val boundSheet = boundSheetFor(doc.style, paper, content)
        val header = if (doc.style.hideTitle) {
            null
        } else {
            placeHeader(
                measureHeader(title, createdAt, paper.tileWidthDp),
                content = content,
                bound = boundSheet != null,
            )
        }
        header?.let { items += PdfItem(HEADER_ITEM_ID, PdfItemKind.Title, it.bounds) }

        return MeasuredPage(
            pageId = pageId,
            title = title,
            createdAt = createdAt,
            doc = doc,
            colors = colors,
            items = items,
            texts = texts,
            tables = tables,
            groups = groups,
            boundSheet = boundSheet,
            equations = equations,
            images = images,
            header = header,
        )
    }

    /** The sheet a bound page fits inside, or null when it does not — PD3's one exception. */
    private fun boundSheetFor(style: PageStyle, paper: PdfPaper, content: InkBounds?): InkBounds? {
        val (sheetWidth, sheetHeight) = style.pageSizeDp ?: return null
        // Only when the export is going onto the sheet the page was already laid out for. A page
        // bound to A5 printed onto A4 is a re-layout, and re-layout is what the tiling is.
        if (sheetWidth != paper.widthDp || sheetHeight != paper.heightDp) return null
        if (content == null) return InkBounds(0f, 0f, sheetWidth, sheetHeight)
        if (content.right > sheetWidth || content.bottom > sheetHeight) return null
        return InkBounds(0f, 0f, sheetWidth, sheetHeight)
    }

    /**
     * Where the title band goes.
     *
     * **On a tiled export it is anchored to the content, not to the page's own corner**, and that is
     * the whole of PD3's left-hand normalisation. The grid starts at the leftmost thing on the page,
     * so a page whose writing begins two inches in has those two inches collapsed and gets its one
     * inch of margin from the sheet instead — but only if the *title* stops holding the left edge
     * open at x = 40. It used to, and the result was every page exported with its own dead margin
     * baked in, and content pushed off the right edge that would otherwise have fitted.
     *
     * So the header sits directly above the content block and shares its left edge, which is what a
     * letterhead does. Never above the page's own top, so a page whose writing starts at the very
     * top does not push the band off the sheet.
     *
     * **A bound page keeps its own placement**, because that page already *is* a sheet: the user put
     * the title where it is and the export is not entitled to move it.
     */
    private fun placeHeader(header: PdfPageHeader, content: InkBounds?, bound: Boolean): PdfPageHeader {
        if (bound || content == null) return header.offsetBy(HEADER_INSET_DP, HEADER_TOP_DP)
        return header.offsetBy(
            dx = content.left,
            dy = maxOf(0f, content.top - header.heightDp - HEADER_CONTENT_GAP_DP),
        )
    }

    fun basePaint(colors: PdfCanvasColors): TextPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        // `NoteEditor`'s fixed base, which is what every character with no span of its own renders
        // as. Read from the same constants, so the two cannot drift.
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            EditorDefaults.FALLBACK_FONT_SIZE.toFloat(),
            metrics,
        )
        typeface = Typeface.SANS_SERIF
        color = colors.textArgb
    }

    private suspend fun layoutFor(
        blocks: List<Block>,
        widthDp: Float,
        editorStyle: EditorStyle,
        colors: PdfCanvasColors,
    ): StaticLayout {
        val text = SpannableCodec.render(blocks, editorStyle)
        // Before the layout, never after: a `RenderedEquationSpan` reports its size from the
        // renderer it is holding, and one hydrated afterwards would be measured as its own LaTeX
        // source and drawn as a formula into the gap that left.
        hydrateEquations(text, colors)
        val widthPx = (widthDp * density).roundToInt().coerceAtLeast(1)
        return StaticLayout.Builder.obtain(text, 0, text.length, basePaint(colors), widthPx)
            .setLineSpacing(0f, LINE_SPACING_MULTIPLIER)
            .setIncludePad(true)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()
    }

    /**
     * Builds a renderer for every inline equation, the way `OutlineEditText.hydrateEquations` does.
     *
     * Sequential rather than parallel: an export is not a frame, and RaTeX's font loader is shared.
     * A formula that fails to parse keeps its null renderer and draws as its own source, which is
     * [com.vivenotes.richtext.RenderedEquationSpan]'s own fallback.
     */
    private suspend fun hydrateEquations(text: Spannable, colors: PdfCanvasColors) {
        text.getSpans(0, text.length, EquationSpan::class.java).forEach { span ->
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            if (start < 0 || end <= start) return@forEach
            val sp = SpannableCodec.fontSizeIn(text, start, end, EditorDefaults.FALLBACK_FONT_SIZE)
                ?: text.getSpans(start, end, AbsoluteSizeSpan::class.java).lastOrNull()?.size
                ?: EditorDefaults.FALLBACK_FONT_SIZE
            val sizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                sp.toFloat(),
                metrics,
            )
            val renderer = runCatching {
                createEquationRenderer(context, span.latex, sizePx, colors.textArgb)
            }.getOrNull()
            span.show(renderer)
        }
    }

    private suspend fun measureTable(
        table: Outline.Table,
        editorStyle: EditorStyle,
        colors: PdfCanvasColors,
    ): PdfTableGrid {
        val cells = mutableMapOf<String, StaticLayout>()
        val heights = FloatArray(table.rows.size)
        table.rows.forEachIndexed { rowIndex, row ->
            var tallest = row.minHeight
            row.cells.forEachIndexed { columnIndex, cell ->
                if (table.inkOnly) return@forEachIndexed
                val columnWidth = table.columns.getOrElse(columnIndex) { 0f }
                val layout = layoutFor(
                    blocks = cell.blocks,
                    widthDp = (columnWidth - CELL_PADDING_DP * 2).coerceAtLeast(1f),
                    editorStyle = editorStyle,
                    colors = colors,
                )
                cells[cell.id] = layout
                tallest = maxOf(tallest, layout.height / density + CELL_PADDING_DP * 2)
            }
            heights[rowIndex] = tallest
        }
        return PdfTableGrid(
            // The grid sits inside the gutter the handles live in — `TableContainer`'s two leading
            // strips — so a table's stored corner is not where its first rule is drawn.
            leftDp = table.x + TABLE_GUTTER_DP,
            topDp = table.y + TABLE_GUTTER_DP,
            rowHeightsDp = heights,
            cells = cells,
        )
    }

    private fun measureHeader(title: String, createdAt: Long, widthDp: Float): PdfPageHeader {
        val titlePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TITLE_SP, metrics)
            typeface = Typeface.SANS_SERIF
        }
        val subtitlePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, SUBTITLE_SP, metrics)
            typeface = Typeface.SANS_SERIF
        }
        val titleHeight = -titlePaint.fontMetrics.top + titlePaint.fontMetrics.bottom
        val subtitleHeight = -subtitlePaint.fontMetrics.top + subtitlePaint.fontMetrics.bottom
        val titleBaseline = -titlePaint.fontMetrics.top / density
        val ruleTop = titleHeight / density + TITLE_RULE_GAP_DP
        val subtitleBaseline = ruleTop + HEADER_RULE_HEIGHT_DP + SUBTITLE_GAP_DP +
            -subtitlePaint.fontMetrics.top / density
        val bottom = ruleTop + HEADER_RULE_HEIGHT_DP + SUBTITLE_GAP_DP + subtitleHeight / density
        return PdfPageHeader(
            title = title.ifEmpty { UNTITLED },
            subtitle = formatCreated(createdAt),
            leftDp = 0f,
            topDp = 0f,
            widthDp = minOf(widthDp.coerceAtLeast(1f), TITLE_MAX_WIDTH_DP),
            heightDp = bottom,
            ruleWidthDp = minOf(widthDp.coerceAtLeast(1f), HEADER_RULE_MAX_WIDTH_DP),
            ruleTopDp = ruleTop,
            titleBaselineDp = titleBaseline,
            subtitleBaselineDp = subtitleBaseline,
        )
    }

    companion object {
        /** `EditorPane`'s `editorStyle`, in the dp it computes those pixel values from. */
        const val INDENT_STEP_DP = 28f
        const val LIST_GAP_DP = 34f
        const val BULLET_RADIUS_DP = 3f

        /** `OutlineContainer`: a reserved grip strip above the editor and 6 dp of padding round it. */
        const val GRIP_HEIGHT_DP = 18f
        const val TEXT_PADDING_DP = 6f

        /** `NoteEditor.setLineSpacing(0f, 1.25f)`. */
        const val LINE_SPACING_MULTIPLIER = 1.25f

        /** `TableContainer`'s own two. */
        const val TABLE_GUTTER_DP = 16f
        const val CELL_PADDING_DP = 6f

        /** `EquationLayer.BASE_FONT_DP` — the size a formula's metrics are laid out at. */
        const val EQUATION_BASE_DP = 22f

        /** `PageHeader`'s insets and type, read off the composable. */
        /** Where a *bound* page's band sits, which is where the canvas draws it. */
        const val HEADER_INSET_DP = 40f
        const val HEADER_TOP_DP = 24f

        /** Air between the band and the first thing written under it, on a tiled export. */
        const val HEADER_CONTENT_GAP_DP = 24f
        const val TITLE_SP = 26f
        const val SUBTITLE_SP = 12.5f
        const val TITLE_RULE_GAP_DP = 2f
        const val HEADER_RULE_HEIGHT_DP = 1f
        const val SUBTITLE_GAP_DP = 6f
        const val TITLE_MAX_WIDTH_DP = 900f
        const val HEADER_RULE_MAX_WIDTH_DP = 420f
        const val UNTITLED = "Untitled page"

        /** 300 dpi over the 160 dp per inch a page is measured in. */
        const val PRINT_PIXELS_PER_DP = 300f / PageStyle.DP_PER_INCH
        const val MAX_IMAGE_EDGE = 3000

        /** The id the header is tiled under. Not an outline id; nothing else can collide with it. */
        const val HEADER_ITEM_ID = " header"
    }
}

/**
 * The page's created-at line, in the format the canvas header uses.
 *
 * A copy of `EditorPane.formatCreated` rather than a shared function, and deliberately: that one is
 * private to a composable file whose only other job is drawing, and the two are the same sentence
 * only because they describe the same page. `SimpleDateFormat` is not thread-safe and the exporter
 * runs off the main thread, so these are built per call rather than held.
 */
internal fun formatCreated(timestamp: Long): String {
    if (timestamp == 0L) return ""
    val date = java.util.Date(timestamp)
    val day = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", java.util.Locale.getDefault())
    val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
    return "${day.format(date)}    ${time.format(date)}"
}
