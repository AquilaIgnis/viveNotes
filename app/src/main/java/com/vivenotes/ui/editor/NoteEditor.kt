package com.vivenotes.ui.editor

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import com.vivenotes.keyboard.KeyBindings
import com.vivenotes.keyboard.ShortcutAction
import com.vivenotes.data.EditorDefaults
import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.richtext.EditorStyle
import com.vivenotes.richtext.OutlineEditText
import com.vivenotes.richtext.SelectionState
import com.vivenotes.richtext.VideoThumbnails
import com.vivenotes.ui.theme.LocalCanvasColors

/**
 * Thumbnails for the editor's link previews, or null while they are switched off.
 *
 * A composition local rather than a parameter, which is this file's exception to threading stores by
 * hand: every writing surface needs it, and a table cell is three composables deep inside
 * `TableContainer`, so passing it down would put a store nothing in between uses into four
 * signatures. The same shape of dependency `LocalCanvasColors` is.
 *
 * Static, so it costs no recomposition to read; `NotesApp` provides it, and provides null when the
 * Settings toggle is off.
 */
val LocalVideoThumbnails = staticCompositionLocalOf<VideoThumbnails?> { null }

/**
 * The keyboard shortcuts in force, for every writing surface, a table cell included — a composition
 * local for the reason [LocalVideoThumbnails] is. `NotesApp` provides the user's.
 */
val LocalKeyBindings = staticCompositionLocalOf { KeyBindings.Default }

/** Runs a workspace command a text box key was given; see `OutlineEditText.onWorkspaceShortcut`. */
val LocalWorkspaceShortcuts = staticCompositionLocalOf<((ShortcutAction) -> Boolean)?> { null }

/**
 * One writing surface, hosted through [AndroidView] — the one place the Compose shell hands off to a
 * View.
 *
 * Extracted from `OutlineContainer` when tables arrived, because a table cell is the same thing: a
 * box that holds blocks. Two copies would be two places for the base text size, the input type or
 * the default marks to drift apart, and the first symptom is text in a table rendering half a point
 * off the text beside it.
 *
 * Everything here is base styling — what a character with no span of its own looks like. Fixed
 * rather than taken from the current default, because changing it would restyle writing already on
 * the page; [EditorDefaults] reaches only text with nothing of its own to inherit.
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
     * Where Tab goes, for an editor that is a table cell. Returns whether
     * it moved; null, and false, both leave Tab as the indent it is in a text container.
     */
    onTabNavigate: ((forward: Boolean) -> Boolean)? = null,
    /** Handed the view once, so a caller can hold it for focus requests. */
    onViewCreated: (OutlineEditText) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val canvas = LocalCanvasColors.current
    val thumbnails = LocalVideoThumbnails.current
    val keyBindings = LocalKeyBindings.current
    val workspaceShortcuts = LocalWorkspaceShortcuts.current
    val hostContext = LocalContext.current
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val readAloud = rememberNonTalkBackServiceOn()
    var spoken by remember { mutableStateOf("") }

    // The editor's text again, on a Compose node around it, for every accessibility service but
    // TalkBack. While a service is on, Compose reports the holder it wraps each AndroidView in as
    // not visible to the user, which keeps TalkBack from focusing the holder instead of the editor;
    // Select to Speak stops there, so it read every part of the page except the text boxes. The
    // holder also lays the editor out at its unzoomed size, while this node's bounds go through the
    // zoom like the rest of the page. Withheld from TalkBack, which reaches the editor and would
    // read the text twice.
    //
    // A box around the view rather than a modifier on it: an AndroidView's own node is replaced by
    // the view in the accessibility tree, taking whatever was set on it along. Min constraints
    // pass through, so the view still fills a cell held open to its row's height.
    Box(
        modifier = modifier.then(
            if (readAloud) {
                Modifier.semantics { if (spoken.isNotBlank()) text = AnnotatedString(spoken) }
            } else {
                Modifier
            },
        ),
        propagateMinConstraints = true,
    ) {
        AndroidView(
            factory = { context ->
                OutlineEditText(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    background = null
                    setPadding(0, 0, 0, 0)
                    // Fixed, never the current default: this is what every character with no span
                    // of its own renders as, so changing it would restyle old writing.
                    setTextSize(
                        TypedValue.COMPLEX_UNIT_SP,
                        EditorDefaults.FALLBACK_FONT_SIZE.toFloat(),
                    )
                    setLineSpacing(0f, 1.25f)
                    typeface = Typeface.SANS_SERIF
                    // Named here because a Typeface cannot be mapped back to an id, and the ribbon
                    // has to be able to say what unmarked text is written in.
                    baseFontFamily = EditorDefaults.FALLBACK_FONT_FAMILY
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    isVerticalScrollBarEnabled = false
                    maxLines = Int.MAX_VALUE
                    // Qualified because the composable's parameters of the same name would
                    // otherwise shadow the view's properties inside apply.
                    this@apply.editorStyle = editorStyle
                    this@apply.onBlocksChanged = { blocks -> onBlocksChanged(blocks) }
                    this@apply.onSelectionStateChanged = { state -> onSelectionChanged(state) }
                    this@apply.onMarkArmed = { mark -> onMarkArmed(mark) }
                    setOnFocusChangeListener { view, hasFocus ->
                        if (hasFocus) onFocused(view as OutlineEditText) else onBlurred()
                    }
                    // Before the blocks go in, so it hears them: setBlocks keeps the editor's own
                    // watcher, and with it onBlocksChanged, quiet.
                    doAfterTextChanged { spoken = it?.toString().orEmpty() }
                    setBlocks(initialBlocks)
                    onViewCreated(this)
                }
            },
            update = { editor ->
                editor.editorStyle = editorStyle
                // Here rather than in the factory, unlike the callbacks above: this one closes over
                // the *grid*, and a column inserted beside the cell must not leave Tab walking the
                // table as it was when the editor was made.
                editor.onTabNavigate = onTabNavigate
                editor.setTextColor(canvas.text.toArgb())
                editor.setLinkTextColor(linkColor)
                editor.linkColor = linkColor
                editor.equationColor = canvas.text.toArgb()
                editor.setHintTextColor(canvas.secondaryText.toArgb())
                editor.defaultMarks = defaults.asEditorMarks()
                editor.videoThumbnails = thumbnails
                editor.keyBindings = keyBindings
                editor.onWorkspaceShortcut = workspaceShortcuts
                editor.onOpenVideo = { url -> hostContext.openExternally(url) }
                editor.onOpenLink = { url -> hostContext.openExternally(url) }
                // Applied to the view rather than as a Compose height constraint: constraining the
                // wrapper only grows the box around the editor, leaving the text area itself — and
                // its touch target — at the height of its content.
                editor.minimumHeight = minHeightPx
            },
        )
    }
}

/**
 * Whether an accessibility service other than TalkBack is on — Select to Speak, Switch Access,
 * Voice Access — kept current as services are switched on and off. TalkBack is told apart by the
 * touch exploration it turns on.
 *
 * The same test Compose makes before it hides an AndroidView's holder: a service in the enabled
 * list, which a test's or a tool's UiAutomation is not. Holding UiAutomation alone, the holder stays
 * visible and the editor is reached the ordinary way.
 */
@Composable
private fun rememberNonTalkBackServiceOn(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var on by remember(manager) { mutableStateOf(manager.nonTalkBackServiceOn()) }
    DisposableEffect(manager) {
        val servicesListener = AccessibilityManager.AccessibilityServicesStateChangeListener {
            on = manager.nonTalkBackServiceOn()
        }
        val touchListener = AccessibilityManager.TouchExplorationStateChangeListener {
            on = manager.nonTalkBackServiceOn()
        }
        manager?.addAccessibilityServicesStateChangeListener(context.mainExecutor, servicesListener)
        manager?.addTouchExplorationStateChangeListener(touchListener)
        onDispose {
            manager?.removeAccessibilityServicesStateChangeListener(servicesListener)
            manager?.removeTouchExplorationStateChangeListener(touchListener)
        }
    }
    return on
}

private fun AccessibilityManager?.nonTalkBackServiceOn(): Boolean =
    this != null &&
        isEnabled &&
        !isTouchExplorationEnabled &&
        getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).isNotEmpty()

/**
 * Hands a web link to the device's browser or matching app.
 *
 * `runCatching` because a device with no browser and no YouTube app resolves nothing, and a note
 * editor must not crash over a tap on a link. `NEW_TASK` because the card can be tapped from a
 * page hosted in any activity, and without it the video would open inside this task's back stack.
 */
private fun Context.openExternally(url: String) {
    runCatching {
        val destination = if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
            url
        } else {
            "https://$url"
        }
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(destination)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
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
