package com.vivenotes.fileimport.docx

/**
 * A Word document as read: formatting already resolved through its styles, nothing laid out.
 *
 * Android-free, like the rest of the reading side, so what a document is can be pinned by a JVM test
 * and only how it looks needs a device. Lengths are twips (1/1440 inch), as in the file.
 */
internal class WordDocument(
    val sections: List<WordSection>,
    val footnotes: Map<Int, List<WordBlock>>,
    val endnotes: Map<Int, List<WordBlock>>,
    val settings: WordSettings,
)

internal class WordSettings(
    val defaultTabTwips: Int = 720,
    val evenAndOddHeaders: Boolean = false,
    /** Word's own version stamp; 15 and later put a table's edge, not its text, on the margin. */
    val compatibilityMode: Int = 15,
)

internal class WordPageGeometry(
    val widthTwips: Int,
    val heightTwips: Int,
    val topTwips: Int,
    val bottomTwips: Int,
    val leftTwips: Int,
    val rightTwips: Int,
    val headerTwips: Int,
    val footerTwips: Int,
)

internal enum class WordSectionStart { NextPage, Continuous, OddPage, EvenPage, NextColumn }

internal class WordHeaderSet(
    val default: List<WordBlock>? = null,
    val first: List<WordBlock>? = null,
    val even: List<WordBlock>? = null,
)

internal class WordSection(
    val page: WordPageGeometry,
    val columns: Int,
    val columnGapTwips: Int,
    val start: WordSectionStart,
    val titlePage: Boolean,
    val pageNumberStart: Int?,
    val pageNumberFormat: String?,
    val headers: WordHeaderSet,
    val footers: WordHeaderSet,
    val blocks: List<WordBlock>,
)

internal sealed interface WordBlock

internal enum class WordAlign { Left, Center, Right, Justify }

internal enum class WordLineRule { Auto, AtLeast, Exact }

internal enum class WordTabKind { Left, Center, Right, Decimal, Bar }

internal class WordTabStop(val positionTwips: Int, val kind: WordTabKind, val leader: Char?)

internal enum class WordBorderStyle { None, Single, Double, Dashed, Dotted, Thick, ThinThick }

internal data class WordBorder(val style: WordBorderStyle, val widthEighths: Int, val colorArgb: Int?)

internal class WordBorders(
    val top: WordBorder? = null,
    val left: WordBorder? = null,
    val bottom: WordBorder? = null,
    val right: WordBorder? = null,
    val insideHorizontal: WordBorder? = null,
    val insideVertical: WordBorder? = null,
)

internal data class WordParagraphStyle(
    val name: String = "",
    /** 0 is a top-level heading; null is body text. */
    val outlineLevel: Int? = null,
    val align: WordAlign = WordAlign.Left,
    val leftTwips: Int = 0,
    val rightTwips: Int = 0,
    /** Positive indents the first line; negative hangs it. */
    val firstLineTwips: Int = 0,
    val beforeTwips: Int = 0,
    val afterTwips: Int = 0,
    val lineValue: Int = 240,
    val lineRule: WordLineRule = WordLineRule.Auto,
    val contextualSpacing: Boolean = false,
    val keepNext: Boolean = false,
    val keepLines: Boolean = false,
    val pageBreakBefore: Boolean = false,
    val widowControl: Boolean = true,
    val shadingArgb: Int? = null,
    val borders: WordBorders? = null,
    val tabs: List<WordTabStop> = emptyList(),
    val rightToLeft: Boolean = false,
)

internal enum class WordScript { Baseline, Super, Sub }

internal data class WordRunStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val script: WordScript = WordScript.Baseline,
    /** Null is Word's "automatic": black on paper, white on something dark. */
    val colorArgb: Int? = null,
    val highlightArgb: Int? = null,
    val halfPoints: Int = 20,
    val font: String? = null,
    val smallCaps: Boolean = false,
    val spacingTwips: Int = 0,
    val scalePercent: Int = 100,
)

/** A list item's computed marker, already counted and formatted. */
internal class WordListLabel(
    val text: String,
    val style: WordRunStyle,
    val followedByTab: Boolean,
    val followedBySpace: Boolean,
    val bullet: Boolean,
    val level: Int,
)

internal class WordParagraph(
    val style: WordParagraphStyle,
    /** The paragraph mark's own run formatting, which sets an empty paragraph's height. */
    val markStyle: WordRunStyle,
    val inlines: List<WordInline>,
    val label: WordListLabel?,
    val breakBefore: WordBreakBefore = WordBreakBefore.None,
) : WordBlock

internal enum class WordBreakBefore { None, Page, Column }

/** A break with nothing after it in its paragraph: whatever comes next starts over. */
internal class WordBreak(val kind: WordBreakBefore) : WordBlock

internal sealed interface WordInline

internal class WordText(val text: String, val style: WordRunStyle, val link: String?) : WordInline

internal class WordTab(val style: WordRunStyle, val absolute: WordAlign? = null) : WordInline

internal class WordLineBreak(val style: WordRunStyle) : WordInline

internal enum class WordFieldKind { PageNumber, PageCount }

internal class WordField(val kind: WordFieldKind, val style: WordRunStyle) : WordInline

/** A superscript number in the text, and the note it refers to. */
internal class WordNoteRef(val id: Int, val endnote: Boolean, val style: WordRunStyle) : WordInline

internal class WordEquation(
    val latex: String,
    /** The same formula as bare text, for a formula the renderer cannot draw. */
    val plainText: String,
    val style: WordRunStyle,
    val display: Boolean,
) : WordInline

/** Where Word last ended a page: a hint, and only ever a hint. */
internal data object WordRenderedBreak : WordInline

internal enum class WordWrap { None, TopAndBottom, Square }

/** Where a floating object sits. `from` is OOXML's `relativeFrom`, kept as the file spells it. */
internal class WordAnchor(
    val behindText: Boolean,
    val wrap: WordWrap,
    val horizontalFrom: String,
    val horizontalOffsetTwips: Int?,
    val horizontalAlign: String?,
    val verticalFrom: String,
    val verticalOffsetTwips: Int?,
    val verticalAlign: String?,
    /** Clockwise, about the box's own centre. */
    val rotationDegrees: Float = 0f,
)

/** Something drawn in a rectangle: inline if [anchor] is null, floating otherwise. */
internal sealed interface WordBoxed : WordInline {
    val widthTwips: Int
    val heightTwips: Int
    val anchor: WordAnchor?
}

internal class WordCrop(val left: Float, val top: Float, val right: Float, val bottom: Float)

internal class WordPicture(
    /** The media part, or null for a picture linked from outside, which is never fetched. */
    val part: String?,
    override val widthTwips: Int,
    override val heightTwips: Int,
    val crop: WordCrop?,
    override val anchor: WordAnchor?,
) : WordBoxed

internal enum class WordShapeForm { Rectangle, RoundedRectangle, Ellipse }

internal class WordShape(
    val blocks: List<WordBlock>,
    override val widthTwips: Int,
    override val heightTwips: Int,
    val fillArgb: Int?,
    val line: WordBorder?,
    val form: WordShapeForm,
    val insetLeftTwips: Int,
    val insetTopTwips: Int,
    val insetRightTwips: Int,
    val insetBottomTwips: Int,
    override val anchor: WordAnchor?,
    /** Where the text sits when it does not fill the box. */
    val textAlign: WordCellAlign = WordCellAlign.Top,
    /** Its own outline, in place of [form]. */
    val outline: List<WordPath> = emptyList(),
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val arrowAtStart: Boolean = false,
    val arrowAtEnd: Boolean = false,
    /** Text that keeps to its lines however narrow the box is. */
    val noWrap: Boolean = false,
    /** Painted in place of [fillArgb], which stays as the colour text is judged against. */
    val gradient: WordGradient? = null,
) : WordBoxed

/** Colours blended across a shape: along a line at [angleDegrees], or out from a point if [radial]. */
internal class WordGradient(
    val colors: IntArray,
    val positions: FloatArray,
    val angleDegrees: Float,
    val radial: Boolean,
    /** Where a radial blend starts, as shares of the box. */
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
)

/** One drawn outline, in a coordinate space of its own that stretches over the whole box. */
internal class WordPath(
    val width: Float,
    val height: Float,
    val commands: List<WordPathCommand>,
    val filled: Boolean = true,
    val stroked: Boolean = true,
)

internal sealed interface WordPathCommand

internal data class WordMoveTo(val x: Float, val y: Float) : WordPathCommand

internal data class WordLineTo(val x: Float, val y: Float) : WordPathCommand

internal data class WordCubicTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : WordPathCommand

internal data object WordClosePath : WordPathCommand

/** A child of a [WordGroup], at its place in the group's own box. */
internal class WordGroupChild(val boxed: WordBoxed, val xTwips: Int, val yTwips: Int)

internal class WordGroup(
    val children: List<WordGroupChild>,
    override val widthTwips: Int,
    override val heightTwips: Int,
    override val anchor: WordAnchor?,
) : WordBoxed

/** A chart, SmartArt or other object with no picture to show; the page keeps its place. */
internal class WordPlaceholder(
    override val widthTwips: Int,
    override val heightTwips: Int,
    override val anchor: WordAnchor?,
) : WordBoxed

internal enum class WordVerticalMerge { None, Restart, Continue }

internal enum class WordCellAlign { Top, Center, Bottom }

internal class WordCell(
    val blocks: List<WordBlock>,
    val gridSpan: Int,
    val verticalMerge: WordVerticalMerge,
    val widthTwips: Int?,
    val shadingArgb: Int?,
    val borders: WordBorders?,
    val marginLeftTwips: Int,
    val marginTopTwips: Int,
    val marginRightTwips: Int,
    val marginBottomTwips: Int,
    val align: WordCellAlign,
)

internal class WordRow(
    val cells: List<WordCell>,
    val heightTwips: Int?,
    val exactHeight: Boolean,
    val isHeader: Boolean,
    val cantSplit: Boolean,
    val gridBefore: Int,
    val gridAfter: Int,
)

internal class WordTable(
    val gridTwips: IntArray,
    val rows: List<WordRow>,
    val borders: WordBorders?,
    val indentTwips: Int,
    val align: WordAlign,
    /** Null lets the grid decide; a table can also ask for a share of the room it is in. */
    val widthTwips: Int?,
    val widthPercent: Float?,
) : WordBlock {
    /**
     * Each column's width. The grid says, and for a file written without one the first row's own
     * widths do; all zero when neither has an opinion.
     */
    fun columnTwips(columnCount: Int): List<Int> {
        val grid = List(columnCount) { gridTwips.getOrElse(it) { 0 } }
        if (grid.sum() > 0) return grid
        val first = rows.firstOrNull()?.takeIf { it.cells.size == columnCount } ?: return grid
        return first.cells.map { it.widthTwips ?: 0 }
    }
}
