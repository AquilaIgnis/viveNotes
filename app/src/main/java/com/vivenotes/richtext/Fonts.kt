package com.vivenotes.richtext

import android.content.Context
import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import androidx.core.content.res.ResourcesCompat
import com.vivenotes.R

/**
 * A typeface the user can pick from the ribbon.
 *
 * [id] is what gets written into the document, so it must stay stable across releases: a note
 * saying "Inter" has to keep meaning Inter on another device, or after export, even where the
 * font is not installed.
 */
data class FontFamily(
    val id: String,
    val displayName: String,
    /** Bundled font resource, or null for a family provided by the platform. */
    val resId: Int?,
    val attribution: String?,
)

/**
 * Resolves font ids to typefaces.
 *
 * Bundled fonts are variable, so a single file covers every weight — which is what keeps three
 * families under 1.3 MB rather than shipping a file per weight.
 */
object FontRegistry {

    val families: List<FontFamily> = listOf(
        FontFamily("sans-serif", "System sans", null, null),
        FontFamily("serif", "System serif", null, null),
        FontFamily("monospace", "System mono", null, null),
        FontFamily("inter", "Inter", R.font.inter, "Inter — SIL Open Font License 1.1"),
        FontFamily("lora", "Lora", R.font.lora, "Lora — SIL Open Font License 1.1"),
        FontFamily(
            "jetbrains_mono",
            "JetBrains Mono",
            R.font.jetbrains_mono,
            "JetBrains Mono — SIL Open Font License 1.1",
        ),
    )

    private val byId = families.associateBy { it.id }
    private val cache = mutableMapOf<String, Typeface?>()

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun displayName(id: String): String = byId[id]?.displayName ?: id

    /**
     * Returns null for an unknown id rather than substituting a default, so a document written
     * with a font this build does not have keeps its family name instead of being silently
     * rewritten to something else on the next save.
     */
    fun typeface(id: String): Typeface? = cache.getOrPut(id) {
        val family = byId[id] ?: return@getOrPut null
        when (val resId = family.resId) {
            null -> Typeface.create(family.id, Typeface.NORMAL)
            else -> appContext?.let { runCatching { ResourcesCompat.getFont(it, resId) }.getOrNull() }
        }
    }
}

/**
 * Applies a typeface while preserving bold and italic.
 *
 * [android.text.style.TypefaceSpan] can only name platform families, so it cannot express a
 * bundled font. It would also replace the paint's typeface outright, dropping any bold or italic
 * applied by a [android.text.style.StyleSpan] over the same range. Re-deriving the style from the
 * paint keeps the two composable in either application order.
 */
class FontFamilySpan(val familyId: String, private val typeface: Typeface) : MetricAffectingSpan() {

    override fun updateDrawState(textPaint: TextPaint) = apply(textPaint)

    override fun updateMeasureState(textPaint: TextPaint) = apply(textPaint)

    private fun apply(paint: TextPaint) {
        val style = paint.typeface?.style ?: Typeface.NORMAL
        paint.typeface = Typeface.create(typeface, style)
    }
}
