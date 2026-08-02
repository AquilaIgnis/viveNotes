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
    val ArrowBack: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_arrow_back)
    val ArrowDropDown: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_arrow_drop_down)
    val Article: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_article)
    val Book: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_book)
    val Brush: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_brush)
    val Check: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_check)
    val Close: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_close)
    val ContentCopy: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_content_copy)
    val ContentCut: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_content_cut)
    val ContentPaste: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_content_paste)
    val Delete: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_delete)
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
    val Fullscreen: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_fullscreen)
    val Info: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_info)
    val KeyboardDoubleArrowLeft: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_keyboard_double_arrow_left)
    val KeyboardDoubleArrowRight: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_keyboard_double_arrow_right)
    val Menu: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_menu)
    val MenuOpen: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_menu_open)
    val NoteAdd: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_note_add)
    val Redo: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_redo)
    val Remove: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_remove)
    val Sort: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_sort)
    val Title: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_title)
    val TouchApp: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_touch_app)
    val Undo: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_undo)
    val VerticalSplit: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_vertical_split)
    val WbSunny: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_wb_sunny)
    val ZoomIn: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_zoom_in)
    val ZoomOut: ImageVector @Composable get() = symbol(R.drawable.ms_rounded_zoom_out)
}

@Composable
private fun symbol(@DrawableRes resourceId: Int): ImageVector = ImageVector.vectorResource(resourceId)
