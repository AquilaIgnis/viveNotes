package com.vivenotes.richtext

/** A source range whose explicit math syntax makes automatic conversion unambiguous. */
internal data class AutoEquationCandidate(
    val start: Int,
    val end: Int,
    val latex: String,
    /** The single paragraph segment that owns the rendered replacement. */
    val renderStart: Int = start,
    val renderEnd: Int = end,
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
        val close = text.findClosing(opening, sourceStart)
        if (close < 0) {
            offset += opening.open.length
            continue
        }
        val source = text.substring(sourceStart, close).trim()
        if (source.isNotEmpty()) {
            val candidateEnd = close + opening.close.length
            // Android lays text out one paragraph at a time. A ReplacementSpan crossing a newline
            // is therefore measured and drawn once per paragraph. Anchor a display block to its
            // first source line instead; the view hides the remaining source segments while the
            // preview is visible and reveals all of them as soon as the block is edited.
            val renderRange = if (opening.multiline && text.hasLineBreak(offset, candidateEnd)) {
                text.firstSourceLine(sourceStart, close)
            } else {
                offset to candidateEnd
            }
            found += AutoEquationCandidate(
                start = offset,
                end = candidateEnd,
                latex = if (opening.display) "{\\displaystyle $source}" else source,
                renderStart = renderRange.first,
                renderEnd = renderRange.second,
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

private data class Delimiter(
    val open: String,
    val close: String,
    val display: Boolean,
    /** Display delimiters may wrap a block; inline delimiters must close on their opening line. */
    val multiline: Boolean = display,
)

private fun String.findClosing(delimiter: Delimiter, start: Int): Int {
    var index = start
    while (index <= length - delimiter.close.length) {
        if (!delimiter.multiline && this[index] == '\n') return -1
        if (startsWith(delimiter.close, index) && !isEscaped(index)) {
            // A single-dollar close cannot consume the first half of a display delimiter.
            if (delimiter.close != "\$" || !startsWith("\$\$", index)) return index
        }
        index++
    }
    return -1
}

private fun String.hasLineBreak(start: Int, end: Int): Boolean {
    val lineBreak = indexOf('\n', startIndex = start)
    return lineBreak >= 0 && lineBreak < end
}

/** A newline-free piece of source on which Android can draw one block preview exactly once. */
private fun String.firstSourceLine(start: Int, end: Int): Pair<Int, Int> {
    val first = (start until end).first { !this[it].isWhitespace() }
    val lineBreak = indexOf('\n', startIndex = first).takeIf { it in (first + 1)..end }
    return first to (lineBreak ?: end)
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
