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
) {
    companion object {
        const val CURRENT_SCHEMA = 1

        /** A page starts with one full-width outline holding a single empty paragraph. */
        fun empty(): PageDoc = PageDoc(outlines = listOf(Outline.Text.empty()))
    }
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

            fun empty(): Text = Text(id = newId(), blocks = listOf(Block.empty()))
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
}

/** Convenience over [JsonDocumentCodec]; prefer a [DocumentCodec] where the format should vary. */
fun PageDoc.encode(): String = JsonDocumentCodec.encodeToString(this)

fun decodePageDoc(json: String): PageDoc = JsonDocumentCodec.decodeFromString(json)

/** Flattened plain text for search indexing and page-list previews. */
fun PageDoc.plainText(): String = outlines
    .filterIsInstance<Outline.Text>()
    .flatMap { it.blocks }
    .joinToString("\n") { it.text }
