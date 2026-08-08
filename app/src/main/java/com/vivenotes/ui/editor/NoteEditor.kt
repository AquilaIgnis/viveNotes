package com.vivenotes.ui.editor

import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.vivenotes.data.EditorDefaults
import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.richtext.EditorStyle
import com.vivenotes.richtext.OutlineEditText
import com.vivenotes.richtext.SelectionState
import com.vivenotes.ui.theme.LocalCanvasColors

/**
 * One writing surface, hosted through [AndroidView] — the one place the Compose shell hands off to a
 * View (AD6).
 *
 * Extracted from `OutlineContainer` when tables arrived, because a table cell is the same thing: a
 * box that holds blocks (`docs/tablePlan.md` TA2). Two copies of this configuration would be two
 * places for the base text size, the input type or the default marks to drift apart, and the first
 * symptom of that is text in a table that renders half a point off the text beside it.
 *
 * Everything here is *base* styling — what a character with no span of its own looks like. It is
 * fixed rather than taken from the current default, because changing it would restyle writing that
 * is already on the page; [EditorDefaults] reaches only text with nothing of its own to inherit.
 */
@Composable
internal fun NoteEditor(
    initialBlocks: List<Block>,
    editorStyle: EditorStyle,
    defaults: EditorDefaults,
    /** A floor for the text area, in pixels. Applied to the view, not as a layout constraint. */
    minHeightPx: Int = 0,
    onFocused: (OutlineEditText) -> Unit,
    onBlurred: () -> Unit,
    onBlocksChanged: (List<Block>) -> Unit,
    onSelectionChanged: (SelectionState) -> Unit,
    onMarkArmed: (Mark) -> Unit = {},
    /**
     * Where Tab goes, for an editor that is a table cell — `docs/tablePlan.md` TA17. Returns whether
     * it moved; null, and false, both leave Tab as the indent it is in a text container.
     */
    onTabNavigate: ((forward: Boolean) -> Boolean)? = null,
    /** Handed the view once, so a caller can hold it for focus requests. */
    onViewCreated: (OutlineEditText) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val canvas = LocalCanvasColors.current

    AndroidView(
        factory = { context ->
            OutlineEditText(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                background = null
                setPadding(0, 0, 0, 0)
                // Fixed, never the current default: this is what every character with no span of
                // its own renders as, so changing it would restyle old writing.
                setTextSize(TypedValue.COMPLEX_UNIT_SP, EditorDefaults.FALLBACK_FONT_SIZE.toFloat())
                setLineSpacing(0f, 1.25f)
                typeface = Typeface.SANS_SERIF
                // Named here because a Typeface cannot be mapped back to an id, and the ribbon has
                // to be able to say what unmarked text is written in.
                baseFontFamily = EditorDefaults.FALLBACK_FONT_FAMILY
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                isVerticalScrollBarEnabled = false
                maxLines = Int.MAX_VALUE
                // Qualified because the composable's parameters of the same name would otherwise
                // shadow the view's properties inside apply.
                this@apply.editorStyle = editorStyle
                this@apply.onBlocksChanged = { blocks -> onBlocksChanged(blocks) }
                this@apply.onSelectionStateChanged = { state -> onSelectionChanged(state) }
                this@apply.onMarkArmed = { mark -> onMarkArmed(mark) }
                setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) onFocused(view as OutlineEditText) else onBlurred()
                }
                setBlocks(initialBlocks)
                onViewCreated(this)
            }
        },
        update = { editor ->
            editor.editorStyle = editorStyle
            // Here rather than in the factory, unlike the callbacks above: this one closes over the
            // *grid*, and a column inserted beside the cell must not leave Tab walking the table as
            // it was when the editor was made.
            editor.onTabNavigate = onTabNavigate
            editor.setTextColor(canvas.text.toArgb())
            editor.equationColor = canvas.text.toArgb()
            editor.setHintTextColor(canvas.secondaryText.toArgb())
            editor.defaultMarks = defaults.asEditorMarks()
            // Applied to the view rather than as a Compose height constraint: constraining the
            // wrapper only grows the box around the editor, leaving the text area itself — and its
            // touch target — at the height of its content.
            editor.minimumHeight = minHeightPx
        },
        modifier = modifier,
    )
}

/**
 * The default expressed as marks, empty where it already matches the editor's fixed base.
 *
 * Skipping the matching case is what keeps documents clean for anyone who never changes the
 * setting: their text renders from the base and carries no font marks at all. Because that base is
 * a constant, such text also stays put forever, whatever the default becomes later.
 */
internal fun EditorDefaults.asEditorMarks(): Set<Mark> = buildSet {
    if (fontFamily != EditorDefaults.FALLBACK_FONT_FAMILY) add(Mark.FontFamily(fontFamily))
    if (fontSize != EditorDefaults.FALLBACK_FONT_SIZE) add(Mark.FontSize(fontSize))
}
