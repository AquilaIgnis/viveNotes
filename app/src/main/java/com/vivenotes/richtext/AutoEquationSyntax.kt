package com.vivenotes.richtext

/** A source range whose explicit math syntax makes automatic conversion unambiguous. */
internal data class AutoEquationCandidate(
    val start: Int,
    val end: Int,
    val latex: String,
)

/**
 * Whether a live preview must step aside so its exact source can receive the caret.
 *
 * The rule itself is [rangeIsBeingEdited], shared with the link preview, which needs the identical
 * behaviour over a pasted URL.
 */
internal fun AutoEquationCandidate.isBeingEdited(
    editorFocused: Boolean,
    selectionStart: Int,
    selectionEnd: Int,
): Boolean = rangeIsBeingEdited(start, end, editorFocused, selectionStart, selectionEnd)

/**
 * Finds explicitly bounded LaTeX without trying to guess whether ordinary prose is mathematics.
 *
 * `$...$` and `\(...\)` are inline; `$$...$$` and `\[...\]` request display style. A whole
 * paragraph already wrapped in `{\displaystyle ...}` is also explicit enough to convert directly,
 * which supports the form commonly copied from Wikipedia.
 */
internal fun findAutoEquationCandidates(text: String): List<AutoEquationCandidate> {
    val found = mutableListOf<AutoEquationCandidate>()
    var offset = 0
    while (offset < text.length) {
        val opening = when {
            text.startsWith("\$\$", offset) && !text.isEscaped(offset) -> Delimiter("\$\$", "\$\$", true)
            text.startsWith("\\[", offset) && !text.isEscaped(offset) -> Delimiter("\\[", "\\]", true)
            text.startsWith("\\(", offset) && !text.isEscaped(offset) -> Delimiter("\\(", "\\)", false)
            text[offset] == '$' && !text.isEscaped(offset) -> Delimiter("\$", "\$", false)
            else -> null
        }
        if (opening == null) {
            offset++
            continue
        }

        val sourceStart = offset + opening.open.length
        val close = text.findClosing(opening.close, sourceStart)
        if (close < 0) {
            offset += opening.open.length
            continue
        }
        val source = text.substring(sourceStart, close).trim()
        if (source.isNotEmpty()) {
            found += AutoEquationCandidate(
                start = offset,
                end = close + opening.close.length,
                latex = if (opening.display) "{\\displaystyle $source}" else source,
            )
        }
        offset = close + opening.close.length
    }
    if (found.isNotEmpty()) return found

    // Wikipedia's copyable form has no dollar delimiters, but the wrapper itself is a strong and
    // specific signal. Require balanced braces so typing an inner limit such as `_{a}` cannot
    // convert the paragraph before its outer group has actually been closed.
    val paragraphs = text.splitParagraphRanges()
    return paragraphs.mapNotNull { range ->
        val raw = text.substring(range.first, range.last + 1)
        val leading = raw.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return@mapNotNull null
        val trailing = raw.indexOfLast { !it.isWhitespace() }
        val source = raw.substring(leading, trailing + 1)
        if (!source.startsWith("{\\displaystyle") || !source.hasBalancedBraces()) return@mapNotNull null
        AutoEquationCandidate(range.first + leading, range.first + trailing + 1, source)
    }
}

private data class Delimiter(val open: String, val close: String, val display: Boolean)

private fun String.findClosing(delimiter: String, start: Int): Int {
    var index = start
    while (index <= length - delimiter.length) {
        if (this[index] == '\n') return -1
        if (startsWith(delimiter, index) && !isEscaped(index)) {
            // A single-dollar close cannot consume the first half of a display delimiter.
            if (delimiter != "\$" || !startsWith("\$\$", index)) return index
        }
        index++
    }
    return -1
}

private fun String.isEscaped(index: Int): Boolean {
    var slashes = 0
    var cursor = index - 1
    while (cursor >= 0 && this[cursor] == '\\') {
        slashes++
        cursor--
    }
    return slashes % 2 == 1
}

private fun String.hasBalancedBraces(): Boolean {
    var depth = 0
    indices.forEach { index ->
        if (isEscaped(index)) return@forEach
        when (this[index]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth < 0) return false
            }
        }
    }
    return depth == 0
}

private fun String.splitParagraphRanges(): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var start = 0
    forEachIndexed { index, char ->
        if (char == '\n') {
            ranges += start until index
            start = index + 1
        }
    }
    ranges += start until length
    return ranges
}
