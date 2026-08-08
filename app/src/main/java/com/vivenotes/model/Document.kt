package com.vivenotes.model

import com.vivenotes.model.ink.LineType
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.ShapeSegment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The canonical note document. This — not [android.text.Spannable] — is the source of truth.
 *
 * Keeping the document as portable data is what lets export, search indexing and (later) sync
 * and the MCP server operate on formatted content without depending on Android. The editor
 * converts to and from spans at its own boundary; see `richtext/SpannableCodec.kt`.
 */
@Serializable
data class PageDoc(
    val schema: Int = CURRENT_SCHEMA,
    val outlines: List<Outline> = emptyList(),
    val style: PageStyle = PageStyle(),
) {
    companion object {
        /**
         * 2 — outline coordinates are measured from the page's top-left corner.
         *
         * Schema 1 measured `y` from below the title header, which meant an outline and the sheet
         * drawn behind it disagreed about where the page began. See [PageDoc.migrated].
         */
        const val CURRENT_SCHEMA = 2

        /** A page starts with one full-width outline holding a single empty paragraph. */
        fun empty(): PageDoc = PageDoc(outlines = listOf(Outline.Text.empty(y = PageStyle.TITLE_BAND_DP)))
    }
}

/**
 * Brings a stored document up to [PageDoc.CURRENT_SCHEMA].
 *
 * Schema 1 laid outlines out in a box that sat *below* the title header, so an outline's `y` was
 * measured from a different origin than the sheet, the ruling and the margin guides it was drawn
 * against. Schema 2 shares one origin, which costs every schema-1 outline a shift down by the band
 * the title used to occupy — a page whose title was hidden already started at the top and must not
 * move.
 *
 * Kept a pure function on the model so it is covered by JVM tests, and applied on every load, so it
 * has to be idempotent: the schema stamp is what makes it so.
 */
fun PageDoc.migrated(): PageDoc {
    if (schema >= PageDoc.CURRENT_SCHEMA) return this
    val shift = if (style.hideTitle) 0f else PageStyle.TITLE_BAND_DP
    return copy(
        schema = PageDoc.CURRENT_SCHEMA,
        outlines = if (shift == 0f) outlines else outlines.map { it.shiftedDown(shift) },
    )
}

private fun Outline.shiftedDown(dy: Float): Outline = when (this) {
    is Outline.Text -> copy(y = y + dy)
    is Outline.Image -> copy(y = y + dy)
    is Outline.Ink -> copy(y = y + dy)
    // Unreachable in practice — shapes and tables postdate schema 2 — but exhaustive rather than
    // `else`, so that a variant added later fails here at compile time instead of silently not
    // being migrated.
    is Outline.Shape -> translated(0f, dy)
    is Outline.Table -> translated(0f, dy)
}

/**
 * How a page is presented: its ruling, colour, paper bounds and whether it shows a title.
 *
 * These belong to the document rather than to preferences, because they are properties of the page
 * itself — squared or dotted paper stays that way when it reaches another device, and an exporter
 * needs the setting to reproduce the page. Contrast `data/EditorDefaults.kt`, which describes how
 * the user likes to write and so must *not* travel with any one document.
 *
 * Every field has a default, so a page written before the View tab existed decodes to the same
 * appearance it already had.
 */
@Serializable
data class PageStyle(
    val ruleLines: RuleLines = RuleLines.GridMedium,
    val paper: PaperSize = PaperSize.Auto,
    val orientation: Orientation = Orientation.Portrait,
    /** Only meaningful for [PaperSize.Custom], and measured as though the page were portrait. */
    val customPaper: PaperDimensions? = null,
    val margins: PrintMargins = PrintMargins(),
    /** Page background. Null follows the app's canvas colours, which track the theme. */
    val backgroundArgb: Int? = null,
    val hideTitle: Boolean = false,
) {
    /** The sheet in inches, portrait-relative, or null when the page is unbounded. */
    val paperInches: PaperDimensions?
        get() = when (paper) {
            PaperSize.Auto -> null
            // A custom size with nothing entered yet is a page in the middle of being set up, not
            // an unbounded one; falling back keeps it a sheet the moment it is chosen.
            PaperSize.Custom -> customPaper ?: PaperDimensions.DEFAULT
            else -> PaperDimensions(paper.widthInches, paper.heightInches)
        }

    /**
     * The page's bounds in dp, or null on [PaperSize.Auto] — the canvas is then unbounded, which
     * is the free-form default rather than a missing value.
     */
    val pageSizeDp: Pair<Float, Float>?
        get() {
            val size = paperInches ?: return null
            val w = size.widthInches * DP_PER_INCH
            val h = size.heightInches * DP_PER_INCH
            return if (orientation == Orientation.Landscape) h to w else w to h
        }

    companion object {
        /**
         * Android's dp is defined so that 160 of them measure an inch, so a page laid out this way
         * is physically the size it claims at 100% zoom. That is what makes the View tab's "100%"
         * mean the same thing here as it does in OneNote.
         */
        const val DP_PER_INCH = 160f

        /**
         * The band at the top of the page that the title and its date occupy.
         *
         * A document constant rather than a layout detail, because outline coordinates start at the
         * page's top-left corner: where content begins on a titled page has to mean the same number
         * on every device, and [PageDoc.migrated] measures a schema-1 page's shift by it.
         *
         * Measured off the rendered header at font scale 1. The header is drawn over this band
         * rather than clipped to it, so a larger font scale makes it encroach on the content area
         * without moving the origin — a moving origin is the thing schema 2 exists to stop.
         */
        const val TITLE_BAND_DP = 94f
    }
}

/** A sheet in inches. Portrait-relative; [PageStyle.orientation] decides which way it is turned. */
@Serializable
data class PaperDimensions(val widthInches: Float, val heightInches: Float) {
    companion object {
        /** Where a custom size starts when there is nothing to seed it from. */
        val DEFAULT = PaperDimensions(PaperSize.A4.widthInches, PaperSize.A4.heightInches)

        /** What a page can be set to by hand. Anything outside this is a typo, not a page. */
        const val MIN_INCHES = 1f
        const val MAX_INCHES = 48f
    }
}

/**
 * Page margins for printing and export, in inches.
 *
 * Stored with the page because they describe the sheet, not the reader. Nothing consumes them yet —
 * there is no print or PDF path (feature J3) — so the Paper Size panel draws them as guides on the
 * page while it is open, which is the only honest way to show what a setting is doing.
 */
@Serializable
data class PrintMargins(
    val topInches: Float = DEFAULT_INCHES,
    val bottomInches: Float = DEFAULT_INCHES,
    val leftInches: Float = DEFAULT_INCHES,
    val rightInches: Float = DEFAULT_INCHES,
) {
    companion object {
        const val DEFAULT_INCHES = 1f
        const val MAX_INCHES = 4f
    }
}

/**
 * Page ruling. Pattern and spacing are carried here rather than in the renderer because dp is the
 * document's unit — outline positions are stored in it — so this stays a description of the page
 * rather than of one screen. Only the enum's *name* is serialized, so the spacings can be retuned
 * without touching a stored document.
 */
@Serializable
enum class RuleLines(
    val spacingDp: Float,
    val squared: Boolean,
    val dotted: Boolean = false,
    val hexagonal: Boolean = false,
) {
    None(0f, false),
    Narrow(18f, false),
    College(22f, false),
    Standard(26f, false),
    Wide(32f, false),
    GridSmall(14f, true),
    Dotted(26f, false, dotted = true),
    /** Side length of each chemistry-friendly hexagon. */
    Hexagonal(32f, false, hexagonal = true),
    GridMedium(26f, true),
    GridLarge(38f, true),
}

@Serializable
enum class Orientation { Portrait, Landscape }

/**
 * Paper sizes offered by the View tab.
 *
 * A subset of the reference's list: the US sizes it also offers — Letter, Legal, Statement,
 * Tabloid, Postcard and Index Card — are deliberately not here. Anything they would have covered is
 * reachable through [Custom].
 */
@Serializable
enum class PaperSize(val widthInches: Float, val heightInches: Float) {
    /** No bounds: the canvas grows with its content, which is how a page starts. */
    Auto(0f, 0f),
    A3(11.69f, 16.54f),
    A4(8.27f, 11.69f),
    A5(5.83f, 8.27f),
    A6(4.13f, 5.83f),
    B4(9.84f, 13.90f),
    B5(6.93f, 9.84f),
    B6(4.92f, 6.93f),
    Billfold(2.75f, 6.25f),

    /** Dimensions come from [PageStyle.customPaper] rather than from the entry itself. */
    Custom(0f, 0f),
}

/**
 * A positioned container on the page canvas. OneNote pages are free-form: content lives in
 * independently placed outlines rather than one linear flow. The UI currently renders a single
 * full-width text outline, but the model carries position from the start so free placement does
 * not require a schema migration later.
 */
@Serializable
sealed interface Outline {
    val id: String
    val x: Float
    val y: Float
    val width: Float

    @Serializable
    @SerialName("text")
    data class Text(
        override val id: String,
        override val x: Float = 0f,
        override val y: Float = 0f,
        override val width: Float = DEFAULT_WIDTH,
        /**
         * Floor for the container's height, set by dragging its bottom edge. A floor rather than a
         * fixed height so that text can never be clipped by a box the user made too small.
         */
        val minHeight: Float = 0f,
        val blocks: List<Block>,
    ) : Outline {
        companion object {
            const val DEFAULT_WIDTH = 720f

            /** Defaults to the top of the page; a titled page passes the band below its header. */
            fun empty(y: Float = 0f): Text = Text(id = newId(), y = y, blocks = listOf(Block.empty()))
        }
    }

    @Serializable
    @SerialName("image")
    data class Image(
        override val id: String,
        override val x: Float = 0f,
        override val y: Float = 0f,
        override val width: Float = Text.DEFAULT_WIDTH,
        val attachmentId: String,
        val height: Float,
    ) : Outline

    /**
     * A shape placed on the canvas — `docs/inkPlan.md` §5.4.
     *
     * **An object, not ink.** A handful of numbers rather than a blob of digitizer samples, so ID2's
     * argument for keeping strokes in their own table does not apply: a shape belongs in the
     * document, where it travels with the page, exports with it, and can be read by the MCP server
     * without a device.
     *
     * **The segments are the shape.** Geometry is stored rather than derived from [kind] and a box,
     * because every segment can be selected on its own and either of its ends dragged — once a corner
     * has moved, no box describes it any more. [kind] is therefore what the shape was *created* as:
     * it names the shape, picks its icon, and seeds the segments, but it does not constrain them
     * afterwards.
     *
     * [x], [y], [width] and [height] are the bounds the segments currently occupy, kept in sync so
     * the canvas can lay out and hit-test without walking every segment.
     *
     * [fillArgb] is null for no fill, which is a different thing from transparent black and is the
     * state a shape starts in.
     */
    @Serializable
    @SerialName("shape")
    data class Shape(
        override val id: String,
        override val x: Float = 0f,
        override val y: Float = 0f,
        override val width: Float = DEFAULT_WIDTH,
        val height: Float = DEFAULT_HEIGHT,
        val kind: ShapeKind = ShapeKind.DEFAULT,
        val segments: List<ShapeSegment> = emptyList(),
        val borderArgb: Int = 0xFF000000.toInt(),
        val borderWidth: Float = 2f,
        val lineType: LineType = LineType.Solid,
        val fillArgb: Int? = null,
    ) : Outline {

        /** Recomputed after any segment moves, so the stored bounds never drift from the geometry. */
        fun withRecomputedBounds(): Shape {
            if (segments.isEmpty()) return this
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            segments.forEach { segment ->
                val points = segment.polyline()
                for (index in points.indices step 2) {
                    minX = minOf(minX, points[index])
                    maxX = maxOf(maxX, points[index])
                    minY = minOf(minY, points[index + 1])
                    maxY = maxOf(maxY, points[index + 1])
                }
            }
            return copy(x = minX, y = minY, width = maxX - minX, height = maxY - minY)
        }

        fun translated(dx: Float, dy: Float): Shape = copy(
            x = x + dx,
            y = y + dy,
            segments = segments.map { it.translated(dx, dy) },
        )

        /**
         * Scales every segment about [anchorX], [anchorY] — a corner-handle drag, per AD7.
         *
         * The anchor is the corner opposite the one being dragged, which is what makes the far
         * corner stay put while the near one follows the finger.
         *
         * **Absolute, against the shape it is called on.** A corner drag reports where the finger
         * is now, measured from the geometry the drag started with, so this must be applied to that
         * same starting geometry once — never to the result of the previous frame, which multiplies
         * a drag's own scales together and sends the shape off the page.
         *
         * How one segment carries a scale, arcs included, is
         * [ShapeSegment.scaledAbout][com.vivenotes.model.ink.ShapeSegment.scaledAbout].
         */
        fun scaledAbout(anchorX: Float, anchorY: Float, scaleX: Float, scaleY: Float): Shape = copy(
            segments = segments.map { it.scaledAbout(anchorX, anchorY, scaleX, scaleY) },
        ).withRecomputedBounds()

        companion object {
            /** What a tap drops, matching the gesture's default box. */
            const val DEFAULT_WIDTH = 120f
            const val DEFAULT_HEIGHT = 80f
        }
    }

    /**
     * A table placed on the canvas — `docs/tablePlan.md`.
     *
     * **An object, not a block.** `docs/plan.md` §4 sketched a table as `BlockType.Table` inside a
     * text container, which predates AD7; TA1 supersedes it. The diagram says the class *implements
     * Prime Object*, and every row of that — lassoed, dragged, resized by its corners, copied,
     * deleted, undone — belongs to something placed on the page rather than to a paragraph inside
     * something else.
     *
     * **Columns are widths; rows are floors.** [columns] is one width per column, in page dp, and
     * [width] is their sum, stored so the page can lay out and hit-test without measuring text.
     * [TableRow.minHeight] is a *floor* for the same reason [Text.minHeight] is — a cell's text wraps,
     * and a stored height would eventually clip what someone wrote. So the document does not know how
     * tall a table is; only the canvas does, once it has measured (TA3). [height] is the honest
     * approximation of it: exact until some cell overflows its row.
     *
     * [headerRow] and [headerColumn] are properties of the table rather than marks on the cells they
     * style (TA8) — a bold mark would be indistinguishable from the user's own bolding, so turning the
     * header off again would have to guess what to un-bold, and an exporter needs the flag to emit a
     * `<th>` rather than a `<td>` that looks like one.
     *
     * [fillArgb] is null for no fill, which is what a table starts with and a different thing from
     * transparent black — the same distinction [Shape.fillArgb] draws.
     */
    @Serializable
    @SerialName("table")
    data class Table(
        override val id: String,
        override val x: Float = 0f,
        override val y: Float = 0f,
        /** The sum of [columns]. Stored rather than derived at every use; see [withRecomputedWidth]. */
        override val width: Float = 0f,
        val columns: List<Float> = emptyList(),
        val rows: List<TableRow> = emptyList(),
        val headerRow: Boolean = false,
        val headerColumn: Boolean = false,
        val borderArgb: Int = 0xFF000000.toInt(),
        val borderWidth: Float = 1f,
        val fillArgb: Int? = null,
        /**
         * **A ruling to write on with a stylus, not a grid of text fields** — `docs/tablePlan.md`
         * TA15, the Draw tab's table.
         *
         * The same object with the same toolkit, geometry and history; the difference is what a cell
         * *is*. Here it is empty space: no editor, no caret, nothing that consumes a touch, so the
         * pen reaches the page through it. [TableCell.blocks] stays empty for every cell, and the
         * cells exist only so that adding and removing rows and columns is one operation rather than
         * two.
         *
         * A document property rather than a preference, because it changes what the object *is* —
         * two tables on one page can differ, and an exporter has to know which is a `<table>` of text
         * and which is a drawn grid.
         *
         * Defaulted false so every table written before this existed decodes as what it was.
         */
        val inkOnly: Boolean = false,
    ) : Outline {

        val columnCount: Int get() = columns.size
        val rowCount: Int get() = rows.size
        val cellCount: Int get() = columnCount * rowCount

        /**
         * The table's height as the document can know it: the sum of its row floors.
         *
         * Exact until a cell holds more text than its row's floor, after which it is an
         * underestimate — the canvas is authoritative for anything it draws, and it hands the true
         * rectangle to the selection itself (`CanvasSelection.TableBounds`). This is what the paste
         * point is measured from, where the same approximation is already accepted for a text box.
         *
         * Always exact for an [inkOnly] table, which has no text to overflow a row.
         */
        val height: Float get() = rows.sumOf { it.minHeight.toDouble() }.toFloat()

        fun cellAt(row: Int, column: Int): TableCell? = rows.getOrNull(row)?.cells?.getOrNull(column)

        /** Every cell id, in reading order. */
        fun cellIds(): List<String> = rows.flatMap { row -> row.cells.map(TableCell::id) }

        /**
         * The cells that hold *text* — what the ViewModel keys its block map by (TA2), and empty for
         * an [inkOnly] table.
         *
         * One accessor rather than an `if (inkOnly)` at each of the eight places that seed, remove,
         * snapshot or restore cell content. Forgetting one of those is not a visible bug: it is a
         * block-map entry for a cell nobody types in, or — the expensive direction — a save that
         * blocks for ever waiting on content that will never arrive.
         */
        fun contentCellIds(): List<String> = if (inkOnly) emptyList() else cellIds()

        /** Where a cell sits, or null when it is not this table's — `row to column`. */
        fun locate(cellId: String): Pair<Int, Int>? {
            rows.forEachIndexed { rowIndex, row ->
                val column = row.cells.indexOfFirst { it.id == cellId }
                if (column >= 0) return rowIndex to column
            }
            return null
        }

        /**
         * Where Tab goes from [cellId], or null when there is nowhere further — `docs/tablePlan.md`
         * TA17.
         *
         * **Reading order, not the row alone.** The last cell of a row hands on to the first of the
         * next, which is what Tab does in every table anyone has used; stopping at the right-hand
         * edge would make the key useless on the one row where a writer most wants it. Null at the
         * very last cell, where the editor falls back to what Tab does everywhere else — this
         * deliberately does *not* grow a row, because a keystroke that silently edits the document
         * is a different promise from one that moves the caret.
         */
        fun cellAfter(cellId: String): String? = cellBeside(cellId, step = 1)

        /** Shift+Tab's destination, by the same rule read backwards. */
        fun cellBefore(cellId: String): String? = cellBeside(cellId, step = -1)

        private fun cellBeside(cellId: String, step: Int): String? {
            val ids = cellIds()
            val at = ids.indexOf(cellId)
            return if (at < 0) null else ids.getOrNull(at + step)
        }

        /** Recomputed after any column changes width, so [width] never drifts from [columns]. */
        fun withRecomputedWidth(): Table = copy(width = columns.sum())

        fun translated(dx: Float, dy: Float): Table = copy(x = x + dx, y = y + dy)

        /**
         * Scales the grid about [anchorX], [anchorY] — a corner-handle drag, per AD7.
         *
         * Columns carry the horizontal scale and row floors carry the vertical one, which is the
         * whole reason a table may keep the corner handles a text box had to decline (TA4): it has
         * two real axes of geometry rather than one wrap width. Cells re-wrap inside exactly as they
         * do when a single column is dragged, and because rows are floors, scaling down can never
         * clip.
         *
         * **Absolute, against the table it is called on** — the same contract [Shape.scaledAbout]
         * has, and it fails the same way if applied to its own result frame after frame.
         */
        fun scaledAbout(anchorX: Float, anchorY: Float, scaleX: Float, scaleY: Float): Table = copy(
            x = anchorX + (x - anchorX) * scaleX,
            y = anchorY + (y - anchorY) * scaleY,
            columns = columns.map { (it * scaleX).coerceAtLeast(MIN_COLUMN_WIDTH) },
            rows = rows.map { it.copy(minHeight = (it.minHeight * scaleY).coerceAtLeast(MIN_ROW_HEIGHT)) },
        ).withRecomputedWidth()

        companion object {
            /**
             * Caps on the grid — TA9.
             *
             * One `EditText` per cell is what buys the whole Home ribbon inside a table; it is also
             * what would make an uncapped table a way to put a thousand Android Views on one page.
             * They live here rather than in the picker because paste, undo and a document written by
             * hand all reach the same lists.
             */
            const val MAX_COLUMNS = 12
            const val MAX_ROWS = 50
            const val MAX_CELLS = 200

            const val MIN_COLUMN_WIDTH = 48f
            const val MAX_COLUMN_WIDTH = 1200f
            const val DEFAULT_COLUMN_WIDTH = 160f

            /**
             * Row floors start at a real value rather than at zero, so that a corner drag has
             * something to scale on the vertical axis from the moment a table is placed.
             */
            const val MIN_ROW_HEIGHT = 24f
            const val MAX_ROW_HEIGHT = 800f
            const val DEFAULT_ROW_HEIGHT = 42f
        }
    }

    /**
     * Reserved. Stylus input is deferred (docs/inital.md), but declaring the variant now means
     * adding ink later is additive rather than a migration.
     */
    @Serializable
    @SerialName("ink")
    data class Ink(
        override val id: String,
        override val x: Float = 0f,
        override val y: Float = 0f,
        override val width: Float = Text.DEFAULT_WIDTH,
        val height: Float = 0f,
    ) : Outline
}

/**
 * One row of an [Outline.Table]: a height floor, and one cell per column.
 *
 * [minHeight] is a floor rather than a height — `docs/tablePlan.md` TA3. The row renders as tall as
 * its tallest cell needs and never shorter than this, which is the same promise the text container's
 * bottom edge makes.
 */
@Serializable
data class TableRow(
    val id: String,
    val minHeight: Float = Outline.Table.DEFAULT_ROW_HEIGHT,
    val cells: List<TableCell> = emptyList(),
)

/**
 * One cell: an id, and the blocks it holds — `docs/tablePlan.md` TA2.
 *
 * **Content-shaped like a text container and geometry-shaped like nothing.** The blocks are the same
 * type a container's are, held in the ViewModel under the same map and rendered by the same
 * `OutlineEditText`, which is what puts the entire Home ribbon inside a table for free (AD6). What a
 * cell does not have is an x, a y or a width: where it sits is decided by its row and its column,
 * and that is the whole difference between a table and three loose text boxes.
 */
@Serializable
data class TableCell(
    val id: String,
    val blocks: List<Block> = emptyList(),
) {
    val plainText: String get() = blocks.joinToString("\n") { it.text }

    companion object {
        fun empty(id: String): TableCell = TableCell(id = id, blocks = listOf(Block.empty()))
    }
}

/** A paragraph-level unit. Block traits map to leading-margin and alignment spans. */
@Serializable
data class Block(
    val id: String,
    val type: BlockType = BlockType.Paragraph,
    val indent: Int = 0,
    val align: Align = Align.Start,
    val runs: List<Run> = emptyList(),
    /** Only meaningful for [BlockType.Todo]. */
    val checked: Boolean? = null,
) {
    /** The block's text with formatting discarded — used for search indexing and previews. */
    val text: String get() = runs.joinToString("") { it.plainText }

    companion object {
        fun empty(): Block = Block(id = newId())

        fun of(text: String, type: BlockType = BlockType.Paragraph, indent: Int = 0): Block =
            Block(id = newId(), type = type, indent = indent, runs = listOf(Run(text)))
    }
}

@Serializable
enum class BlockType { Paragraph, Heading1, Heading2, Heading3, Bullet, Numbered, Todo, Quote, Code }

@Serializable
enum class Align { Start, Center, End }

/** A maximal stretch of text sharing an identical mark set. */
@Serializable
data class Run(
    val text: String,
    val marks: Set<Mark> = emptySet(),
) {
    /** Equations project their source, not the editor's otherwise meaningless object character. */
    val plainText: String
        get() = marks.filterIsInstance<Mark.Equation>().firstOrNull()?.latex ?: text
}

/** The single editor character occupied by an inline object such as an equation. */
const val OBJECT_REPLACEMENT_CHARACTER: Char = '\uFFFC'

/** An inline formatting attribute. Each maps to one Android span; see `richtext/SpannableCodec.kt`. */
@Serializable
sealed interface Mark {
    @Serializable @SerialName("b") data object Bold : Mark

    @Serializable @SerialName("i") data object Italic : Mark

    @Serializable @SerialName("u") data object Underline : Mark

    @Serializable @SerialName("s") data object Strikethrough : Mark

    @Serializable @SerialName("sub") data object Subscript : Mark

    @Serializable @SerialName("sup") data object Superscript : Mark

    @Serializable @SerialName("color") data class TextColor(val argb: Int) : Mark

    @Serializable @SerialName("hl") data class Highlight(val argb: Int) : Mark

    @Serializable @SerialName("size") data class FontSize(val sp: Int) : Mark

    @Serializable @SerialName("font") data class FontFamily(val name: String) : Mark

    @Serializable @SerialName("link") data class Link(val href: String) : Mark

    /** Delimiter-free LaTeX source for one atomic inline equation. */
    @Serializable @SerialName("eq") data class Equation(val latex: String) : Mark
}

/**
 * Marks that are either present or absent, so the ribbon can toggle them without a value.
 * Parameterised marks (colour, size, font, link) are set rather than toggled.
 */
val TOGGLEABLE_MARKS: List<Mark> = listOf(
    Mark.Bold,
    Mark.Italic,
    Mark.Underline,
    Mark.Strikethrough,
    Mark.Subscript,
    Mark.Superscript,
)

/**
 * The script mark this one cannot share text with, or null for every other mark.
 *
 * Nothing is both raised and lowered, so applying one script replaces the other instead of layering
 * over it. Stated here rather than in the editor because it is a fact about the marks themselves —
 * an exporter or the eventual sync server has to honour it without knowing what a span is.
 */
fun Mark.opposingScript(): Mark? = when (this) {
    Mark.Subscript -> Mark.Superscript
    Mark.Superscript -> Mark.Subscript
    else -> null
}

/**
 * Time-ordered identifier. Sortable by creation time and generated on-device, so a note created
 * offline never needs a server round trip to get its identity.
 */
fun newId(): String {
    val now = System.currentTimeMillis()
    val random = java.util.UUID.randomUUID()
    val hi = (now shl 16) or ((random.mostSignificantBits ushr 48) and 0x0FFFL or 0x7000L)
    return java.util.UUID(hi, random.leastSignificantBits).toString()
}

/**
 * JSON configuration for [JsonDocumentCodec].
 *
 * `classDiscriminator` is JSON-specific; a binary format tags sealed types its own way. The
 * `@SerialName`s on [Outline] and [Mark] are format-independent and carry over unchanged.
 * `ignoreUnknownKeys` is what lets an older build open a document written by a newer one.
 */
val DocumentJson: Json = Json {
    encodeDefaults = false
    ignoreUnknownKeys = true
    classDiscriminator = "t"
    // `ignoreUnknownKeys` covers unknown *fields*; this covers unknown *values*. Without it a
    // ruling or paper size that this build does not have — written by a newer one, or dropped from
    // the list by a later one — throws, and the whole page is reported unreadable over a setting.
    // With it, the field falls back to its default and the note still opens.
    coerceInputValues = true
}

/** Convenience over [JsonDocumentCodec]; prefer a [DocumentCodec] where the format should vary. */
fun PageDoc.encode(): String = JsonDocumentCodec.encodeToString(this)

fun decodePageDoc(json: String): PageDoc = JsonDocumentCodec.decodeFromString(json)

/**
 * Flattened plain text for search indexing and page-list previews.
 *
 * Table cells are in it for the reason containers are: text someone typed on this page is text they
 * will search for, and a table is not a picture.
 */
fun PageDoc.plainText(): String = outlines
    .mapNotNull { outline ->
        when (outline) {
            is Outline.Text -> outline.blocks.joinToString("\n") { it.text }
            is Outline.Table -> outline.rows
                .flatMap { row -> row.cells.map(TableCell::plainText) }
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .ifBlank { null }
            is Outline.Image, is Outline.Ink, is Outline.Shape -> null
        }
    }
    .joinToString("\n")
