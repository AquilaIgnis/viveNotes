package st.unamedtba.model

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
}

/**
 * How a page is presented: its ruling, colour, paper bounds and whether it shows a title.
 *
 * These belong to the document rather than to preferences, because they are properties of the page
 * itself — a squared page stays squared when it reaches another device, and an exporter needs them
 * to reproduce the page. Contrast `data/EditorDefaults.kt`, which describes how the user likes to
 * write and so must *not* travel with any one document.
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
 * Page ruling. Spacing is carried here rather than in the renderer because dp is already the
 * document's unit — outline positions are stored in it — so this stays a description of the page
 * rather than of one screen. Only the enum's *name* is serialized, so the spacings can be retuned
 * without touching a stored document.
 */
@Serializable
enum class RuleLines(val spacingDp: Float, val squared: Boolean) {
    None(0f, false),
    Narrow(18f, false),
    College(22f, false),
    Standard(26f, false),
    Wide(32f, false),
    GridSmall(14f, true),
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
    val text: String get() = runs.joinToString("") { it.text }

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
)

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

/** Flattened plain text for search indexing and page-list previews. */
fun PageDoc.plainText(): String = outlines
    .filterIsInstance<Outline.Text>()
    .flatMap { it.blocks }
    .joinToString("\n") { it.text }
