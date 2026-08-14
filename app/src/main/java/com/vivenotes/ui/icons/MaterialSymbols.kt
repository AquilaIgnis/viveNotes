package com.vivenotes.ui.icons

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.vivenotes.R

/**
 * Material Symbols Rounded used by the app.
 *
 * Keeping resource lookup behind this object gives call sites the same concise shape as Compose's
 * retired `Icons` API while the actual vectors remain ordinary, shrinkable Android resources.
 */
object MaterialSymbols {
    val Add: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_add)
    val AddColumnRight: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_add_column_right)
    val AddRowBelow: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_add_row_below)
    val ArrowBack: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_arrow_back)
    val ArrowDropDown: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_arrow_drop_down)
    val ArrowSelectorTool: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_arrow_selector_tool)
    val Article: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_article)
    val Block: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_block)
    val Book: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_book)
    val Brush: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_brush)
    val Category: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_category)
    val Check: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_check)
    val Close: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_close)
    val ContentCopy: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_content_copy)
    val ContentCut: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_content_cut)
    val ContentPaste: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_content_paste)
    val Delete: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_delete)
    val DragIndicator: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_drag_indicator)
    val Edit: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_edit)
    val ExpandLess: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_expand_less)
    val ExpandMore: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_expand_more)
    val FormatAlignCenter: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_format_align_center)
    val FormatAlignLeft: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_format_align_left)
    val FormatAlignRight: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_format_align_right)
    val FormatBold: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_format_bold)
    val FormatClear: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_format_clear)
    val FormatIndentDecrease: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_format_indent_decrease)
    val FormatIndentIncrease: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_format_indent_increase)
    val FormatItalic: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_format_italic)
    val FormatStrikethrough: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_format_strikethrough)
    val FormatUnderlined: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_format_underlined)
    val Function: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_function)
    val Image: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_image)
    val Functions: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_functions)
    val History: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_history)
    val Info: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_info)
    val Keyboard: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_keyboard)
    val KeyboardDoubleArrowLeft: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_keyboard_double_arrow_left)
    val KeyboardDoubleArrowRight: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_keyboard_double_arrow_right)
    val LassoSelect: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_lasso_select)
    val LocalCafe: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_local_cafe)
    val Memory: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_memory)
    val Menu: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_menu)
    val MenuOpen: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_menu_open)
    val NoteAdd: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_note_add)
    val Redo: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_redo)
    val Remove: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_remove)
    val RestoreFromTrash: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_restore_from_trash)
    val Search: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_search)
    val Sort: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_sort)
    val Straighten: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_straighten)
    val Stylus: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_stylus)
    val StylusHighlighter: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_stylus_highlighter)
    val Table: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_table)
    val TableRows: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_table_rows)
    val TouchApp: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_touch_app)
    val Undo: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_undo)
    val ViewColumn: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_view_column)
    val ViewInArOff: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_view_in_ar_off)
    val WbSunny: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_wb_sunny)
    val ZoomIn: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_zoom_in)
    val ZoomOut: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_zoom_out)
}

@Composable
private fun symbol(@DrawableRes resourceId: Int): ImageVector = ImageVector.vectorResource(resourceId)
