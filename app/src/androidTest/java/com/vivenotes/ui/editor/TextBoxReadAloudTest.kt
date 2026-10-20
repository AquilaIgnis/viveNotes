package com.vivenotes.ui.editor

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.data.EditorDefaults
import com.vivenotes.model.Block
import com.vivenotes.model.PageStyle
import com.vivenotes.richtext.EditorStyle
import com.vivenotes.richtext.FormatCommand
import com.vivenotes.richtext.OutlineEditText
import com.vivenotes.ui.OutlineBox
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What an accessibility service other than TalkBack finds where a text box is — Select to Speak
 * above all, which reads whatever lies under the finger.
 *
 * The editor is a platform view, and Compose reports the holder it wraps each one in as not visible
 * to the user. Select to Speak does not look past that, so the text boxes were the one thing on the
 * page it would not read. These walk the tree the way it does, through visible nodes only.
 *
 * **Needs such a service switched on**, and is skipped without one. Compose hides the holder only
 * for a service in the enabled list, which this test's own UiAutomation is not, so on a device with
 * none the editor is reached directly and there is nothing to test. TalkBack does not count: the
 * box's node is withheld from it.
 */
class TextBoxReadAloudTest {

    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** Leaves the device's services running: suppressed, they would no longer hide the holder. */
    private val automation by lazy {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }

    private lateinit var composeView: View

    @Before
    fun requireAServiceOtherThanTalkBack() {
        automation.serviceInfo
        val manager = instrumentation.targetContext.getSystemService(AccessibilityManager::class.java)
        val services = manager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
        )
        assumeTrue(
            "needs Select to Speak or another service that is not TalkBack",
            services.isNotEmpty() && !manager.isTouchExplorationEnabled,
        )
    }

    private fun setPage(zoom: Float) {
        compose.setContent {
            composeView = LocalView.current
            ViveNotesTheme {
                EditorPane(
                    title = "A page",
                    createdAt = 0L,
                    defaults = EditorDefaults(),
                    style = PageStyle(),
                    zoom = zoom,
                    onTitleChange = {},
                    outlines = listOf(OutlineBox("text", 80f, 100f, 200f)),
                    pageRevision = 0,
                    initialBlocksFor = { listOf(Block.of(TEXT)) },
                    commands = MutableSharedFlow<FormatCommand>(),
                    onBlocksChanged = { _, _ -> },
                    onSelectionChanged = {},
                    onMarkArmed = {},
                    onCreateOutline = { _, _ -> "new" },
                    textArmed = true,
                    onMoveOutline = { _, _, _ -> },
                    onResizeOutline = { _, _ -> },
                    onSetOutlineMinHeight = { _, _ -> },
                    onOutlineBlurred = {},
                    tables = emptyList(),
                    shapes = emptyList(),
                    onCanvasMeasured = { _, _ -> },
                    hasClipboard = false,
                    onPaste = {},
                    showPrintMargins = false,
                )
            }
        }
        compose.waitForIdle()
    }

    /** Every text in the window a service reaches without passing through an invisible node. */
    private fun visibleTexts(): List<Pair<String, Rect>> {
        val root = automation.rootInActiveWindow ?: return emptyList()
        val found = mutableListOf<Pair<String, Rect>>()
        fun walk(node: AccessibilityNodeInfo) {
            if (!node.isVisibleToUser) return
            node.text?.let { found += it.toString() to Rect().also(node::getBoundsInScreen) }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::walk)
        }
        walk(root)
        return found
    }

    private fun awaitText(text: String): List<Rect> {
        compose.waitUntil(TIMEOUT_MS) { visibleTexts().any { it.first == text } }
        return visibleTexts().filter { it.first == text }.map { it.second }
    }

    /** Once, too: a box read twice is what TalkBack would hear if this node were given to it. */
    @Test
    fun aTextBoxIsReachedThroughVisibleNodesAndReadOnce() {
        setPage(zoom = 1f)

        assertEquals(1, awaitText(TEXT).size)
    }

    /**
     * Zoom is a layer transform, and the holder lays the view out at its unzoomed size: at 2x, the
     * view's own bounds cover a quarter of the box, and a finger on the rest found nothing.
     */
    @Test
    fun aZoomedTextBoxIsFoundWhereItIsDrawn() {
        setPage(zoom = 2f)

        val found = awaitText(TEXT).single()
        val drawn = compose.onNodeWithTag(OutlineTags.EDITOR).fetchSemanticsNode().boundsInRoot
        val origin = IntArray(2).also(composeView::getLocationOnScreen)
        val expected = Rect(
            origin[0] + drawn.left.roundToInt(),
            origin[1] + drawn.top.roundToInt(),
            origin[0] + drawn.right.roundToInt(),
            origin[1] + drawn.bottom.roundToInt(),
        )
        val off = listOf(
            found.left - expected.left,
            found.top - expected.top,
            found.right - expected.right,
            found.bottom - expected.bottom,
        )
        assertEquals("found at $found, drawn at $expected", true, off.all { abs(it) <= 1 })
    }

    /** Typing changes what is read, or a service would go on reading the box as it was opened. */
    @Test
    fun whatIsReadFollowsTheEdits() {
        lateinit var editor: OutlineEditText
        compose.setContent {
            ViveNotesTheme {
                // Clear of the status bar, under which the system reports everything as not visible.
                Box(Modifier.padding(top = 120.dp)) {
                    NoteEditor(
                        initialBlocks = listOf(Block.of(TEXT)),
                        editorStyle = STYLE,
                        defaults = EditorDefaults(),
                        onFocused = {},
                        onBlurred = {},
                        onBlocksChanged = {},
                        onSelectionChanged = {},
                        onViewCreated = { editor = it },
                    )
                }
            }
        }
        awaitText(TEXT)

        instrumentation.runOnMainSync { editor.append(" and after") }

        assertEquals(1, awaitText("$TEXT and after").size)
    }

    private companion object {
        const val TEXT = "Notes from the lecture"
        const val TIMEOUT_MS = 5_000L
        val STYLE = EditorStyle(
            indentStepPx = 40,
            listGapPx = 40,
            bulletRadiusPx = 6,
            accentColor = 0xFF4CAF50.toInt(),
            codeBackgroundColor = 0x22FFFFFF,
            quoteColor = 0xFF4CAF50.toInt(),
        )
    }
}
