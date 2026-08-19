package com.vivenotes.richtext

/**
 * Whether a live preview must step aside so the exact source under it can receive the caret.
 *
 * The rule every "draw something over text that is still there" span in this editor shares —
 * [LiveEquationSpan] over `$x^2$`, [VideoEmbedSpan] over a pasted URL. It is what makes those
 * previews safe to apply automatically: the characters are never replaced, only covered, and going
 * to edit them uncovers them.
 *
 * A collapsed caret counts as inside when it sits anywhere in `start..end` **inclusive of both
 * ends**, so arrowing onto either boundary reveals the source rather than leaving the caret parked
 * against a wall it cannot see into. A selection counts when it overlaps at all.
 *
 * Only ever true for a focused editor: an unfocused one has a caret position it is not showing, and
 * hiding a preview because of it would blank cards on every container but the one being typed in.
 */
internal fun rangeIsBeingEdited(
    start: Int,
    end: Int,
    editorFocused: Boolean,
    selectionStart: Int,
    selectionEnd: Int,
): Boolean {
    if (!editorFocused) return false
    val from = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
    val to = maxOf(selectionStart, selectionEnd).coerceAtLeast(0)
    return if (from == to) from in start..end else from < end && to > start
}
