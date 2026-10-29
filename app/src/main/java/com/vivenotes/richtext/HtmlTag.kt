package com.vivenotes.richtext

internal data class HtmlTag(
    val name: String,
    val attributes: Map<String, String>,
    val end: Int,
    val closing: Boolean = false,
    val selfClosing: Boolean = false,
)

/** A bounded, quote-aware tag reader shared by text and canvas paste. */
internal fun htmlTag(html: String, at: Int): HtmlTag? {
    var index = at + 1
    val closing = html.getOrNull(index) == '/'
    if (closing) index++
    if (html.getOrNull(index)?.isLetter() != true) return null
    val start = index
    while (index < html.length && !html[index].isWhitespace() && html[index] !in ">/") index++
    val name = html.substring(start, index).lowercase()
    val attributes = mutableMapOf<String, String>()
    var selfClosing = false
    while (index < html.length) {
        while (index < html.length && html[index].isWhitespace()) index++
        if (index >= html.length) break
        if (html[index] == '>') { index++; break }
        if (html[index] == '/') { selfClosing = true; index++; continue }
        val attributeStart = index
        while (index < html.length && !html[index].isWhitespace() && html[index] !in "=>/") index++
        val attribute = html.substring(attributeStart, index).lowercase()
        while (index < html.length && html[index].isWhitespace()) index++
        var value = ""
        if (html.getOrNull(index) == '=') {
            index++
            while (index < html.length && html[index].isWhitespace()) index++
            if (html.getOrNull(index) == '"' || html.getOrNull(index) == '\'') {
                val quote = html[index++]
                val close = html.indexOf(quote, index).let { if (it < 0) html.length else it }
                value = html.substring(index, close)
                index = (close + 1).coerceAtMost(html.length)
            } else {
                val valueStart = index
                while (index < html.length && !html[index].isWhitespace() && html[index] != '>') index++
                value = html.substring(valueStart, index)
            }
        }
        if (attribute.isNotEmpty()) attributes.getOrPut(attribute) { decodeEntities(value) }
        if (attributeStart == index) index++
    }
    return HtmlTag(name, attributes, index, closing, selfClosing)
}
