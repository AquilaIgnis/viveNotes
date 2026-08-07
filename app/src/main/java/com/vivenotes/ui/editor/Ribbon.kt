package com.vivenotes.ui.editor

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.vivenotes.data.DrawTool
import com.vivenotes.data.EditorDefaults
import com.vivenotes.data.EraserSettings
import com.vivenotes.data.HighlighterSettings
import com.vivenotes.data.PenPreset
import com.vivenotes.data.ShapeSettings
import com.vivenotes.data.ViewSettings
import com.vivenotes.model.Align
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import com.vivenotes.model.PageStyle
import com.vivenotes.richtext.ClipboardAction
import com.vivenotes.richtext.FontRegistry
import com.vivenotes.richtext.FormatCommand
import com.vivenotes.richtext.SelectionState
import com.vivenotes.ui.ScrollingRow
import com.vivenotes.ui.icons.AppIcons
import com.vivenotes.ui.icons.LocalRibbonIcons
import com.vivenotes.ui.icons.MaterialSymbols
import com.vivenotes.ui.icons.fontColorGlyph
import com.vivenotes.ui.icons.highlightGlyph

/**
 * Ribbon tabs from the reference UI. Home, View and Draw are implemented; the rest are shown
 * because the shell is part of the design, and each states plainly that it is not built rather
 * than pretending to work.
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

/** Long enough to register as deliberate, short enough not to outlive the gesture. */
private const val CONFIRM_FLASH_MS = 650L

/** Test tags for the font controls, which have a state — a mixed selection — that shows no text. */
/** Test tag for the Text button, whose active state is the whole point of it. */
internal object HomeTags {
    const val TEXT = "home-text-mode"
}

internal object FontTags {
    const val SIZE = "font-size-combo"
    const val FAMILY = "font-family-combo"
}

@Composable
fun Ribbon(
    selection: SelectionState,
    activeTab: RibbonTab,
    onTabChange: (RibbonTab) -> Unit,
    onCommand: (FormatCommand) -> Unit,
    /** The font new text starts in, shown when there is no caret to describe instead. */
    defaults: EditorDefaults,
    /** Makes a font or size the default. A deliberate gesture, never a side effect of picking one. */
    onSetDefault: (Mark) -> Unit,
    /** The open page's appearance, which the View tab both shows and changes. */
    pageStyle: PageStyle,
    viewSettings: ViewSettings,
    view: ViewActions,
    /** The Draw tab's pens, the swatch row they share, and which tool is currently in hand. */
    pens: List<PenPreset>,
    palette: List<Int>,
    eraser: EraserSettings,
    highlighter: HighlighterSettings,
    shape: ShapeSettings,
    tool: DrawTool,
    allowFinger: Boolean,
    draw: DrawActions,
    pageOpen: Boolean,
    canUndoCanvas: Boolean = false,
    canRedoCanvas: Boolean = false,
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    showNavigationToggle: Boolean = false,
    navigationVisible: Boolean = true,
    onToggleNavigation: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        TabStrip(
            activeTab = activeTab,
            onTabChange = onTabChange,
            showBack = showBack,
            onBack = onBack,
            showNavigationToggle = showNavigationToggle,
            navigationVisible = navigationVisible,
            onToggleNavigation = onToggleNavigation,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        when (activeTab) {
            RibbonTab.Home -> HomeTab(
                selection = selection,
                defaults = defaults,
                onCommand = onCommand,
                onSetDefault = onSetDefault,
                textMode = tool == DrawTool.Text,
                // A toggle, per `docs/textBoxPlan.md` TD2: pressing it again puts the tool down
                // rather than doing nothing, and with nothing in hand a tap on bare canvas stops
                // opening containers.
                onTextMode = {
                    draw.selectTool(if (tool == DrawTool.Text) DrawTool.None else DrawTool.Text)
                },
            )
            RibbonTab.View -> ViewTab(pageStyle, viewSettings, view, pageOpen)
            RibbonTab.Draw -> DrawTab(
                pens = pens,
                palette = palette,
                eraser = eraser,
                highlighter = highlighter,
                shape = shape,
                tool = tool,
                allowFinger = allowFinger,
                actions = draw,
                pageOpen = pageOpen,
                canUndo = canUndoCanvas,
                canRedo = canRedoCanvas,
            )
            RibbonTab.Insert -> InsertTab(
                selection = selection,
                onCommand = onCommand,
                pageOpen = pageOpen,
                shape = shape,
                palette = palette,
                tool = tool,
                onSelectTool = draw.selectTool,
                onChangeShape = draw.updateShape,
                onAddColor = draw.addPaletteColor,
            )
            else -> PlaceholderTab(activeTab)
        }
    }
}

@Composable
private fun TabStrip(
    activeTab: RibbonTab,
    onTabChange: (RibbonTab) -> Unit,
    showBack: Boolean,
    onBack: () -> Unit,
    showNavigationToggle: Boolean,
    navigationVisible: Boolean,
    onToggleNavigation: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        when {
            showBack -> RibbonNavigationButton(
                icon = MaterialSymbols.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
            )
            showNavigationToggle -> RibbonNavigationButton(
                // Once the panes are hidden, the changed glyph is the only visible indication
                // that the canvas can reveal them again.
                icon = if (navigationVisible) MaterialSymbols.Menu else MaterialSymbols.MenuOpen,
                contentDescription = if (navigationVisible) {
                    "Hide notebooks and pages"
                } else {
                    "Show notebooks and pages"
                },
                onClick = onToggleNavigation,
            )
        }

        ScrollingRow(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 6.dp),
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
}

@Composable
private fun RibbonNavigationButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
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
    defaults: EditorDefaults,
    onCommand: (FormatCommand) -> Unit,
    onSetDefault: (Mark) -> Unit,
    textMode: Boolean,
    onTextMode: () -> Unit,
) {
    ScrollingRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
    ) {
        // The canvas draws by default, so tapping it leaves a mark rather than opening a caret.
        // This is how you ask for text: it puts the pen down, after which a tap on bare canvas
        // starts a container exactly as it always did. A mode rather than a one-shot insert,
        // because "where does it go" is a question only a tap can answer.
        Box(Modifier.testTag(HomeTags.TEXT)) {
            TwoToneRibbonButton({ it.insertText }, "Text", textMode, onTextMode)
        }

        Divider()

        RibbonButton(MaterialSymbols.ContentPaste, "Paste") {
            onCommand(FormatCommand.Clipboard(ClipboardAction.Paste))
        }
        RibbonButton(MaterialSymbols.ContentCut, "Cut") {
            onCommand(FormatCommand.Clipboard(ClipboardAction.Cut))
        }
        RibbonButton(MaterialSymbols.ContentCopy, "Copy") {
            onCommand(FormatCommand.Clipboard(ClipboardAction.Copy))
        }

        Divider()

        // These describe the text the caret is in, not the stored default: what is shown is what
        // the next character will actually be. With nothing focused there is no text to describe,
        // so the default stands in — that is the one case where it is the honest answer.
        FontFamilyPicker(
            current = selection.fontFamily,
            default = defaults.fontFamily,
            mixed = selection.fontFamily == null && selection.hasSelection,
            onPick = { onCommand(FormatCommand.SetMark(Mark.FontFamily(it))) },
            onSetDefault = { onSetDefault(Mark.FontFamily(it)) },
        )
        FontSizePicker(
            current = selection.fontSize,
            default = defaults.fontSize,
            mixed = selection.fontSize == null && selection.hasSelection,
            onPick = { onCommand(FormatCommand.SetMark(Mark.FontSize(it))) },
            onSetDefault = { onSetDefault(Mark.FontSize(it)) },
        )

        Divider()

        RibbonButton(MaterialSymbols.FormatBold, "Bold", selection.has(Mark.Bold)) {
            onCommand(FormatCommand.ToggleMark(Mark.Bold))
        }
        RibbonButton(MaterialSymbols.FormatItalic, "Italic", selection.has(Mark.Italic)) {
            onCommand(FormatCommand.ToggleMark(Mark.Italic))
        }
        RibbonButton(MaterialSymbols.FormatUnderlined, "Underline", selection.has(Mark.Underline)) {
            onCommand(FormatCommand.ToggleMark(Mark.Underline))
        }
        RibbonButton(MaterialSymbols.FormatStrikethrough, "Strikethrough", selection.has(Mark.Strikethrough)) {
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
        RibbonButton(MaterialSymbols.FormatClear, "Clear formatting") {
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

        RibbonButton(MaterialSymbols.FormatIndentDecrease, "Decrease indent") {
            onCommand(FormatCommand.Indent(-1))
        }
        RibbonButton(MaterialSymbols.FormatIndentIncrease, "Increase indent") {
            onCommand(FormatCommand.Indent(1))
        }

        Divider()

        RibbonButton(
            MaterialSymbols.FormatAlignLeft,
            "Align left",
            selection.align == Align.Start,
        ) { onCommand(FormatCommand.SetAlign(Align.Start)) }
        RibbonButton(
            MaterialSymbols.FormatAlignCenter,
            "Align centre",
            selection.align == Align.Center,
        ) { onCommand(FormatCommand.SetAlign(Align.Center)) }
        RibbonButton(
            MaterialSymbols.FormatAlignRight,
            "Align right",
            selection.align == Align.End,
        ) { onCommand(FormatCommand.SetAlign(Align.End)) }

        Divider()

        StylesPicker(selection.blockType) { onCommand(FormatCommand.SetBlockType(it)) }
    }
}

@Composable
internal fun RibbonButton(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    /** Renders the button without wiring it up — see [RibbonCommand] for why that beats hiding it. */
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    RibbonButtonSlot(active, onClick, enabled, modifier) {
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
 * A ribbon control that spells out what it does.
 *
 * The View tab's controls are labelled in the reference where Home's are not, and for good reason:
 * "Paper" and "Paper Size" have no glyph anyone would recognise cold, while bold and italic do.
 *
 * [enabled] renders the control without wiring it up. That is the honest way to show a button whose
 * feature is not built — it holds its place in the layout and plainly does not work, rather than
 * accepting a tap and doing nothing.
 */
@Composable
internal fun RibbonCommand(
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true,
    dropdown: Boolean = false,
    icon: @Composable (active: Boolean) -> Unit,
) {
    val background = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon(active)
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
        )
        if (dropdown) {
            Icon(
                imageVector = MaterialSymbols.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

internal const val DISABLED_ALPHA = 0.42f

/**
 * A ribbon button whose icon carries its own colours.
 *
 * The glyph is picked from a pre-built set rather than passed in directly, because a two-tone
 * icon cannot be recoloured by `tint` — the pressed state needs a different neutral, which means a
 * different vector. `tint` is [Color.Unspecified] here so Compose applies no colour filter at all;
 * anything else would flatten both paths to one colour and undo the point of the icon.
 */
@Composable
internal fun TwoToneRibbonButton(
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
internal fun RibbonButtonSlot(
    active: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    val background = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Box(
        modifier = modifier
            .padding(horizontal = 1.dp)
            .size(32.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        contentAlignment = Alignment.Center,
    ) {
        icon()
    }
}

@Composable
internal fun Divider() {
    Box(
        Modifier
            .padding(horizontal = 6.dp)
            .width(1.dp)
            .height(22.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun FontFamilyPicker(
    current: String?,
    default: String,
    mixed: Boolean,
    onPick: (String) -> Unit,
    onSetDefault: (String) -> Unit,
) {
    val shown = if (mixed) null else current ?: default
    DefaultableCombo(
        text = shown?.let(FontRegistry::displayName).orEmpty(),
        width = 148.dp,
        tag = FontTags.FAMILY,
        onSetDefault = { shown?.let(onSetDefault) },
        hint = "Hold a font to type in it by default",
    ) { dismiss ->
        FONT_FAMILIES.forEach { family ->
            MenuRow(
                label = family.displayName,
                isDefault = family.id == default,
                onClick = {
                    dismiss()
                    onPick(family.id)
                },
                onLongClick = { onSetDefault(family.id) },
            )
        }
    }
}

@Composable
private fun FontSizePicker(
    current: Int?,
    default: Int,
    mixed: Boolean,
    onPick: (Int) -> Unit,
    onSetDefault: (Int) -> Unit,
) {
    // What the box reads, and so what holding it promotes: the size at the caret, or the default
    // where there is no caret to describe, or nothing at all where a selection mixes sizes.
    val shown = if (mixed) null else current ?: default
    DefaultableCombo(
        text = shown?.toString().orEmpty(),
        width = 58.dp,
        tag = FontTags.SIZE,
        onSetDefault = { shown?.let(onSetDefault) },
        hint = "Hold a size to start new text at it",
    ) { dismiss ->
        FONT_SIZES.forEach { size ->
            MenuRow(
                label = "$size",
                isDefault = size == default,
                onClick = {
                    dismiss()
                    onPick(size)
                },
                onLongClick = { onSetDefault(size) },
            )
        }
    }
}

/**
 * A menu entry that a tap picks and a hold makes the default.
 *
 * Hand-rolled rather than a [DropdownMenuItem] because that owns its own click handling: a
 * `combinedClickable` wrapped around one never sees the gesture, since the inner handler consumes
 * it first. The metrics are Material's own, so the two are indistinguishable on screen.
 *
 * Holding deliberately leaves the menu open. The tag moving onto the entry under the finger is the
 * confirmation, and closing the menu would hide the one thing that says it worked.
 */
@Composable
private fun MenuRow(
    label: String,
    isDefault: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minWidth = 112.dp, minHeight = 48.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (isDefault) {
            Spacer(Modifier.width(16.dp))
            DefaultTag()
        }
    }
}

/**
 * A combo showing [text], which holding makes the default.
 *
 * Long-press is the gesture because picking is not: choosing a size to write the next sentence in
 * used to move the default too, so a passing choice quietly changed what every later page opened in.
 * Separating them means the default only moves when the user says so.
 *
 * What is promoted is exactly [text] — the rule the whole control turns on. Blank means a selection
 * mixing several values, which has no one answer to show and none to set.
 *
 * A long press has no result of its own to see, so the border takes the accent colour for a moment
 * to say it landed — without that the gesture is indistinguishable from a mis-tap. The menu carries
 * the same fact permanently, tagging whichever entry is currently the default.
 */
@Composable
private fun DefaultableCombo(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    tag: String,
    onSetDefault: () -> Unit,
    hint: String,
    items: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(confirming) {
        if (confirming) {
            delay(CONFIRM_FLASH_MS)
            confirming = false
        }
    }
    val border by animateColorAsState(
        targetValue = if (confirming) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outline
        },
        label = "combo-border",
    )

    Box {
        ComboBox(
            text = text,
            width = width,
            modifier = Modifier.testTag(tag),
            borderColor = border,
            onClick = { open = true },
            onLongClick = {
                // Holding promotes whatever is on screen, whether that came from the caret or is
                // the default already standing in for one. Gating on there being a caret instead
                // meant the box could show a number that holding it would not set. An empty box is
                // a selection mixing values: no one number, and none shown to promote.
                if (text.isNotBlank()) {
                    confirming = true
                    onSetDefault()
                }
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // Heads the menu rather than closing it: the size list is long enough to scroll, and a
            // footer teaching a gesture nobody knows about would sit below the fold unread.
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            items { open = false }
        }
    }
}

/** Marks the entry that new text starts in, so the setting is visible rather than remembered. */
@Composable
private fun DefaultTag() {
    Text(
        text = "Default",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
internal fun ComboBox(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .padding(horizontal = 2.dp)
            .width(width)
            .height(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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
            imageVector = MaterialSymbols.ArrowDropDown,
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
                imageVector = MaterialSymbols.ArrowDropDown,
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
