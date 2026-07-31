package st.unamedtba.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import st.unamedtba.data.EditorDefaults
import st.unamedtba.model.Align
import st.unamedtba.model.BlockType
import st.unamedtba.model.Mark
import st.unamedtba.richtext.ClipboardAction
import st.unamedtba.richtext.FontRegistry
import st.unamedtba.richtext.FormatCommand
import st.unamedtba.richtext.SelectionState
import st.unamedtba.ui.icons.AppIcons
import st.unamedtba.ui.icons.LocalRibbonIcons
import st.unamedtba.ui.icons.fontColorGlyph
import st.unamedtba.ui.icons.highlightGlyph

/**
 * Ribbon tabs from the reference UI. Only Home is implemented; the rest are shown because the
 * shell is part of the design, and each states plainly that it is not built rather than
 * pretending to work.
 */
enum class RibbonTab(val label: String) {
    File("File"),
    Home("Home"),
    Insert("Insert"),
    Draw("Draw"),
    History("History"),
    Review("Review"),
    View("View"),
    Help("Help"),
}

private val TEXT_COLORS = listOf(
    0xFFFFFFFF, 0xFFE6E6E6, 0xFF9A9A9A, 0xFF000000,
    0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047,
    0xFF1E88E5, 0xFF8E24AA, 0xFF00ACC1, 0xFFD81B60,
).map { it.toInt() }

private val HIGHLIGHT_COLORS = listOf(
    0x66FFEB3B, 0x6676FF03, 0x6640C4FF, 0x66FF4081,
    0x66FF9100, 0x66B388FF, 0x66FFFFFF, 0x00000000,
).map { it.toInt() }

// 15 is not a size Word or OneNote offers, but it is this editor's default, and now that the
// default persists a user who changes it could otherwise never get back to it.
private val FONT_SIZES = listOf(8, 9, 10, 11, 12, 14, 15, 16, 18, 20, 24, 28, 36, 48, 72)

private val FONT_FAMILIES = FontRegistry.families

@Composable
fun Ribbon(
    selection: SelectionState,
    activeTab: RibbonTab,
    onTabChange: (RibbonTab) -> Unit,
    onCommand: (FormatCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        TabStrip(activeTab, onTabChange)
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        when (activeTab) {
            RibbonTab.Home -> HomeTab(selection, onCommand)
            else -> PlaceholderTab(activeTab)
        }
    }
}

@Composable
private fun TabStrip(activeTab: RibbonTab, onTabChange: (RibbonTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RibbonTab.entries.forEach { tab ->
            val active = tab == activeTab
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onTabChange(tab) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun PlaceholderTab(tab: RibbonTab) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "The ${tab.label} tab is not built yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HomeTab(
    selection: SelectionState,
    onCommand: (FormatCommand) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RibbonButton(Icons.Default.ContentPaste, "Paste") {
            onCommand(FormatCommand.Clipboard(ClipboardAction.Paste))
        }
        RibbonButton(Icons.Default.ContentCut, "Cut") {
            onCommand(FormatCommand.Clipboard(ClipboardAction.Cut))
        }
        RibbonButton(Icons.Default.ContentCopy, "Copy") {
            onCommand(FormatCommand.Clipboard(ClipboardAction.Copy))
        }

        Divider()

        // These describe the text at the caret, so text with no font mark of its own reads as the
        // editor's fixed base — which is what it is actually rendered in. Falling back to the
        // stored default instead would label old writing with a font it is not in.
        FontFamilyPicker(selection.fontFamily ?: EditorDefaults.FALLBACK_FONT_FAMILY) {
            onCommand(FormatCommand.SetMark(Mark.FontFamily(it)))
        }
        FontSizePicker(selection.fontSize ?: EditorDefaults.FALLBACK_FONT_SIZE) {
            onCommand(FormatCommand.SetMark(Mark.FontSize(it)))
        }

        Divider()

        RibbonButton(Icons.Default.FormatBold, "Bold", selection.has(Mark.Bold)) {
            onCommand(FormatCommand.ToggleMark(Mark.Bold))
        }
        RibbonButton(Icons.Default.FormatItalic, "Italic", selection.has(Mark.Italic)) {
            onCommand(FormatCommand.ToggleMark(Mark.Italic))
        }
        RibbonButton(Icons.Default.FormatUnderlined, "Underline", selection.has(Mark.Underline)) {
            onCommand(FormatCommand.ToggleMark(Mark.Underline))
        }
        RibbonButton(Icons.Default.FormatStrikethrough, "Strikethrough", selection.has(Mark.Strikethrough)) {
            onCommand(FormatCommand.ToggleMark(Mark.Strikethrough))
        }

        ColorPicker(
            glyph = ::fontColorGlyph,
            label = "Font colour",
            colors = TEXT_COLORS,
            current = selection.textColor,
            onPick = { onCommand(FormatCommand.SetMark(Mark.TextColor(it))) },
            onClear = { onCommand(FormatCommand.ClearMark(Mark.TextColor(0))) },
        )
        ColorPicker(
            glyph = ::highlightGlyph,
            label = "Highlight",
            colors = HIGHLIGHT_COLORS,
            current = selection.highlight,
            onPick = { onCommand(FormatCommand.SetMark(Mark.Highlight(it))) },
            onClear = { onCommand(FormatCommand.ClearMark(Mark.Highlight(0))) },
        )

        TwoToneRibbonButton({ it.subscript }, "Subscript", selection.has(Mark.Subscript)) {
            onCommand(FormatCommand.ToggleMark(Mark.Subscript))
        }
        TwoToneRibbonButton({ it.superscript }, "Superscript", selection.has(Mark.Superscript)) {
            onCommand(FormatCommand.ToggleMark(Mark.Superscript))
        }
        RibbonButton(Icons.Default.FormatClear, "Clear formatting") {
            onCommand(FormatCommand.ClearFormatting)
        }

        Divider()

        TwoToneRibbonButton(
            { it.bulletList },
            "Bulleted list",
            selection.blockType == BlockType.Bullet,
        ) { onCommand(FormatCommand.SetBlockType(BlockType.Bullet)) }
        TwoToneRibbonButton(
            { it.numberedList },
            "Numbered list",
            selection.blockType == BlockType.Numbered,
        ) { onCommand(FormatCommand.SetBlockType(BlockType.Numbered)) }
        TwoToneRibbonButton(
            { it.todoList },
            "To-do",
            selection.blockType == BlockType.Todo,
        ) { onCommand(FormatCommand.SetBlockType(BlockType.Todo)) }

        RibbonButton(Icons.AutoMirrored.Filled.FormatIndentDecrease, "Decrease indent") {
            onCommand(FormatCommand.Indent(-1))
        }
        RibbonButton(Icons.AutoMirrored.Filled.FormatIndentIncrease, "Increase indent") {
            onCommand(FormatCommand.Indent(1))
        }

        Divider()

        RibbonButton(
            Icons.AutoMirrored.Filled.FormatAlignLeft,
            "Align left",
            selection.align == Align.Start,
        ) { onCommand(FormatCommand.SetAlign(Align.Start)) }
        RibbonButton(
            Icons.Default.FormatAlignCenter,
            "Align centre",
            selection.align == Align.Center,
        ) { onCommand(FormatCommand.SetAlign(Align.Center)) }
        RibbonButton(
            Icons.AutoMirrored.Filled.FormatAlignRight,
            "Align right",
            selection.align == Align.End,
        ) { onCommand(FormatCommand.SetAlign(Align.End)) }

        Divider()

        StylesPicker(selection.blockType) { onCommand(FormatCommand.SetBlockType(it)) }
    }
}

@Composable
private fun RibbonButton(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    RibbonButtonSlot(active, onClick) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * A ribbon button whose icon carries its own colours.
 *
 * The glyph is picked from a pre-built set rather than passed in directly, because a two-tone
 * icon cannot be recoloured by `tint` — the pressed state needs a different neutral, which means a
 * different vector. `tint` is [Color.Unspecified] here so Compose applies no colour filter at all;
 * anything else would flatten both paths to one colour and undo the point of the icon.
 */
@Composable
private fun TwoToneRibbonButton(
    glyph: (AppIcons) -> ImageVector,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val icons = LocalRibbonIcons.current
    RibbonButtonSlot(active, onClick) {
        Icon(
            imageVector = glyph(if (active) icons.active else icons.idle),
            contentDescription = label,
            tint = Color.Unspecified,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** The pressed-state chrome shared by both button flavours. */
@Composable
private fun RibbonButtonSlot(
    active: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val background = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Box(
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .size(32.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        icon()
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .padding(horizontal = 6.dp)
            .width(1.dp)
            .height(22.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun FontFamilyPicker(current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ComboBox(
            text = FontRegistry.displayName(current),
            width = 148.dp,
        ) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FONT_FAMILIES.forEach { family ->
                DropdownMenuItem(
                    text = { Text(family.displayName) },
                    onClick = {
                        open = false
                        onPick(family.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun FontSizePicker(current: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ComboBox(text = current.toString(), width = 58.dp) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FONT_SIZES.forEach { size ->
                DropdownMenuItem(
                    text = { Text("$size") },
                    onClick = {
                        open = false
                        onPick(size)
                    },
                )
            }
        }
    }
}

@Composable
private fun ComboBox(text: String, width: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .width(width)
            .height(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Font colour and highlight, whose bar shows the colour that is currently applied.
 *
 * That bar is live selection state, so unlike the accented glyphs it cannot be pre-built — the
 * [glyph] is a builder taking the swatch, rebuilt only when the selected colour actually changes.
 * It also replaces a bar drawn under the icon: Material's `FormatColorText` and `FormatColorFill`
 * already include one in the glyph, so the old layout showed two.
 */
@Composable
private fun ColorPicker(
    glyph: (neutral: Color, swatch: Color) -> ImageVector,
    label: String,
    colors: List<Int>,
    current: Int?,
    onPick: (Int) -> Unit,
    onClear: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    // Highlights are stored semi-transparent so they read over text. The bar has nothing behind
    // it, so it is drawn opaque or the lighter shades would be all but invisible.
    val swatch = current?.takeIf { it != 0 }?.let { Color(it).copy(alpha = 1f) } ?: neutral
    val icon = remember(neutral, swatch) { glyph(neutral, swatch) }
    Box {
        RibbonButtonSlot(active = false, onClick = { open = true }) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.Unspecified,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(8.dp)) {
                colors.chunked(4).forEach { row ->
                    Row {
                        row.forEach { argb ->
                            Box(
                                modifier = Modifier
                                    .padding(3.dp)
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(argb))
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp))
                                    .clickable {
                                        open = false
                                        onPick(argb)
                                    },
                            )
                        }
                    }
                }
                DropdownMenuItem(
                    text = { Text("None") },
                    onClick = {
                        open = false
                        onClear()
                    },
                )
            }
        }
    }
}

@Composable
private fun StylesPicker(current: BlockType, onPick: (BlockType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val styles = listOf(
        BlockType.Paragraph to "Normal",
        BlockType.Heading1 to "Heading 1",
        BlockType.Heading2 to "Heading 2",
        BlockType.Heading3 to "Heading 3",
        BlockType.Quote to "Quote",
        BlockType.Code to "Code",
    )
    Box {
        Row(
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable { open = true }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = LocalRibbonIcons.current.idle.styles,
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                // Names the control, not the selection, while the selection carries no style —
                // plain text is the default state, so reading "Normal" back is noise. The menu
                // still offers "Normal" as the way to clear a heading.
                text = when (current) {
                    BlockType.Paragraph -> "Styles"
                    else -> styles.firstOrNull { it.first == current }?.second ?: "Styles"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            styles.forEach { (type, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        open = false
                        onPick(type)
                    },
                )
            }
        }
    }
}
